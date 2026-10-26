package rep03;

import java.util.HashSet;
import java.util.Set;

public class AlarmProcessor {
    private static long lp = 0;
    private static VectorClock vp;
    private static final Object lock = new Object();
    private static final Set<String> processados = new HashSet<>();

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

        for (int k = 0; k < n; k++) {
            final int partId = k;
            Thread t = new Thread(() -> {
                try {
                    ps.subscribe(Wire.topicData(partId), msg -> {
                        try {
                            if (!Wire.type(msg).equals("REG")) return;

                            String[] parts = Wire.regParts(msg);
                            SensorRecord r = SensorRecord.fromLine(parts[2], n);

                            // Ignora registos de alarmes
                            if (r.getSensor().startsWith("ALARM-")) return;

                            String deduplicador = partId + "#" + r.getSeq();
                            long snapshotLp;
                            VectorClock snapshotVp;

                            synchronized (lock) {
                                // Ignora registos duplicados (mesma partição e seq)
                                if (processados.contains(deduplicador)) return;
                                processados.add(deduplicador);

                                // Atualiza Lp e Vp com o máximo de cada registo recebido
                                lp = Math.max(lp, r.getLamport());
                                vp.merge(r.getVector());

                                snapshotLp = lp;
                                snapshotVp = vp.copy();
                            }

                            // Verifica limiar de alarme
                            if (r.getTemp() >= Wire.LIMIAR_ALARME) {
                                String alarmSensor = "ALARM-" + r.getSensor();
                                int targetPart = partitioner.of(alarmSensor);

                                String inMsg = Wire.in(alarmSensor, r.getTemp(), snapshotLp, snapshotVp);
                                ps.publish(Wire.topicIn(targetPart), inMsg);

                                System.out.println("ALARME: " + r.getSensor() + " p" + partId + "#" + r.getSeq() + " → p" + targetPart);
                            }
                        } catch (Exception e) {
                            System.err.println("Erro no processamento da mensagem: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    System.err.println("Subscrição terminada na partição " + partId + ": " + e.getMessage());
                }
            });
            t.setDaemon(true);
            t.start();
        }

        System.out.println("AlarmProcessor em execução para N=" + n);
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) { }
    }
}