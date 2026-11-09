package raft04;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AlarmProcessor {
    private static long lp = 0;
    private static VectorClock vp;
    private static final Object lock = new Object();
    private static final Set<String> processados = new HashSet<>();     // pares (seq, termo) já tratados
    private static final Map<Integer, Long> maiorTermo = new HashMap<>();

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: AlarmProcessor <N> [api]");
            System.exit(1);
        }
        int n = Integer.parseInt(args[0]);
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";

        vp = new VectorClock(n);
        Partitioner partitioner = new Partitioner(n);
        IpfsPubSub ps = new IpfsPubSub(api);
        Monitoring monitoring = new Monitoring("processador", Monitoring.all(n));
        Runtime.getRuntime().addShutdownHook(new Thread(monitoring::close));

        for (int k = 0; k < n; k++) {
            final int partId = k;
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        ps.subscribe(Wire.topicData(partId), msg -> tratarLote(msg, partId, n, partitioner, ps, monitoring));
                    } catch (Exception e) {
                        System.err.println("Subscrição de p" + partId + " interrompida: " + e.getMessage());
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            });
            t.setDaemon(true);
            t.start();
        }
        System.out.println("AlarmProcessor em execução para N=" + n);
        try { Thread.currentThread().join(); } catch (InterruptedException ignored) { }
    }

    private static void tratarLote(String msg, int partId, int n, Partitioner partitioner, IpfsPubSub ps,
                                   Monitoring monitoring) {
        try {
            Batch batch = Batch.decode(msg, n);
            synchronized (lock) {
                // só lotes de termo maior ou igual ao maior termo já observado na partição
                long maior = maiorTermo.getOrDefault(partId, 0L);
                if (batch.term < maior) {
                    monitoring.log("IGNORADO;p" + partId + ";termo=" + batch.term + ";maior=" + maior);
                    return;
                }
                maiorTermo.put(partId, batch.term);
            }
            String range = batch.records.isEmpty() ? "vazio"
                    : batch.records.get(0).getSeq() + ".." + batch.records.get(batch.records.size() - 1).getSeq();
            monitoring.received(partId, batch.header(), range);

            for (SensorRecord r : batch.records) {
                if (r.getSensor().startsWith("ALARM-")) continue;
                long snapshotLp;
                VectorClock snapshotVp;
                synchronized (lock) {
                    // cada registo é identificado pelo par (seq, termo)
                    if (!processados.add(partId + "#" + r.getSeq() + "#" + r.getTerm())) continue;
                    lp = Math.max(lp, r.getLamport());
                    vp.merge(r.getVector());
                    snapshotLp = lp;
                    snapshotVp = vp.copy();
                }
                if (r.getTemp() >= Wire.LIMIAR_ALARME) {
                    String alarmSensor = "ALARM-" + r.getSensor();
                    int destino = partitioner.of(alarmSensor);
                    if (monitoring.suspect(destino))
                        monitoring.log("AVISO;p" + destino + ";partição p" + destino
                                + " sem líder conhecido; alarme " + alarmSensor + " pode perder-se");
                    ps.publish(Wire.topicIn(destino), Wire.in(alarmSensor, r.getTemp(), snapshotLp, snapshotVp));
                    System.out.println("ALARME: " + r.getSensor() + " p" + partId + "#" + r.getSeq()
                            + "(termo " + r.getTerm() + ") -> p" + destino);
                }
            }
        } catch (Exception e) {
            System.err.println("Erro no processamento do lote: " + e.getMessage());
        }
    }
}
