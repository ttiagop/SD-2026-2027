package rep04;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

public class PartitionReplica {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Uso: PartitionReplica <id> <partições> [portoApi] [N=3]");
            System.exit(1);
        }

        int id = Integer.parseInt(args[0]);
        String[] partitionsStr = args[1].split(",");
        String api = args.length > 2 ? ("http://127.0.0.1:" + args[2] + "/api/v0") : "http://127.0.0.1:5001/api/v0";

        // Parâmetros de simulação de falhas (-Dperda e -Ddescartar)
        Double perda = null;
        String perdaProp = System.getProperty("perda");
        if (perdaProp != null) {
            try {
                perda = Double.parseDouble(perdaProp);
            } catch (NumberFormatException ignored) { }
        }

        Long descartarSeq = null;
        String descartarProp = System.getProperty("descartar");
        if (descartarProp != null) {
            try {
                descartarSeq = Long.parseLong(descartarProp);
            } catch (NumberFormatException ignored) { }
        }

        System.out.println("Réplica " + id + " iniciada. Partições: " + Arrays.toString(partitionsStr));
        if (perda != null) {
            System.out.println("Simulação de perda ativa: prob=" + perda);
        }
        if (descartarSeq != null) {
            System.out.println("Simulação de descarte ativo: seq=" + descartarSeq);
        }

        IpfsPubSub ps = new IpfsPubSub(api);
        int n = args.length > 3 ? Integer.parseInt(args[3]) : 3;
        int[] partitions = Arrays.stream(partitionsStr).map(String::trim).mapToInt(Integer::parseInt).toArray();
        Monitoring monitoring = new Monitoring("replica" + id, partitions);
        Runtime.getRuntime().addShutdownHook(new Thread(monitoring::close));

        for (String pStr : partitionsStr) {
            int k = Integer.parseInt(pStr.trim());
            if (k < 0 || k >= n) throw new IllegalArgumentException("Partição fora de N");
            new Thread(new PartitionHandler(id, k, n, ps, perda, descartarSeq, monitoring), "replica-" + id + "-p" + k).start();
        }
    }

    private static class PartitionHandler implements Runnable {
        private final Monitoring monitoring;
        private final int replicaId;
        private final int k;
        private final int n;
        private final IpfsPubSub ps;
        private final Double probPerda;
        private Long seqADescartar;

        private final RecordFile file;
        private long lastSeq = 0;
        private final TreeMap<Long, SensorRecord> retidos = new TreeMap<>();
        private String leaderHost = "127.0.0.1";
        private int leaderPort = Wire.PORTO_BASE;

        public PartitionHandler(int replicaId, int k, int n, IpfsPubSub ps, Double probPerda, Long seqADescartar, Monitoring monitoring) {
            this.n = n;
            this.monitoring = monitoring;
            this.replicaId = replicaId;
            this.k = k;
            this.ps = ps;
            this.probPerda = probPerda;
            this.seqADescartar = seqADescartar;
            this.file = new RecordFile("replica-" + replicaId + "-p" + k + ".txt");
        }

        @Override
        public void run() {
            try {
                lastSeq = file.lastSeq();
                leaderPort = Wire.PORTO_BASE + k;
                System.out.println("[p" + k + "] Inicializado. Último seq em disco: " + lastSeq);

                ps.subscribe(Wire.topicData(k), msg -> {
                    try {
                        processMessage(msg);
                    } catch (Exception e) {
                        System.err.println("[p" + k + "] Erro ao processar mensagem: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                System.err.println("[p" + k + "] Subscrição terminada: " + e.getMessage());
            }
        }

        private synchronized void processMessage(String msg) throws IOException {
            Batch batch = Batch.decode(msg, n);
            monitoring.received(k, batch);
            leaderHost = batch.address;
            leaderPort = batch.port;
            for (SensorRecord record : batch.records) receberRegisto(record);
            if (lastSeq < batch.lastSeq) recuperarAte(batch.lastSeq);
        }

        private void receberRegisto(SensorRecord record) throws IOException {
            long seq = record.getSeq();
            // Simulação de descarte pontual (-Ddescartar=<seq>)
            if (seqADescartar != null && seq == seqADescartar) {
                System.out.println("[p" + k + "] DESCARTADO (simulação): " + seq);
                seqADescartar = null; // descarta apenas a primeira receção
                return;
            }

            // Simulação de perda probabilística (-Dperda=<p>)
            if (probPerda != null && ThreadLocalRandom.current().nextDouble() < probPerda) {
                System.out.println("[p" + k + "] DESCARTADO (simulação): " + seq);
                return;
            }

            aplicarRegisto(record);
        }

        private void aplicarRegisto(SensorRecord record) throws IOException {
            long seq = record.getSeq();

            if (seq <= lastSeq || retidos.containsKey(seq)) {
                System.out.println("[p" + k + "] DUPLICADO: " + seq);
                return;
            }

            if (seq == lastSeq + 1) {
                file.append(record);
                lastSeq = seq;
                System.out.println("[p" + k + "] Gravado [seq " + seq + "]: " + record.toLine());
                despejarRetidos();
                return;
            }

            // seq > lastSeq + 1: lacuna detectada, retém em memória
            retidos.put(seq, record);
            System.out.println("[p" + k + "] EM ESPERA: " + seq + " (retidos: " + retidos.size() + ")");

            recuperarAte(seq - 1);
        }

        private void despejarRetidos() throws IOException {
            while (!retidos.isEmpty() && retidos.firstKey() == lastSeq + 1) {
                SensorRecord r = retidos.pollFirstEntry().getValue();
                file.append(r);
                lastSeq = r.getSeq();
            }
        }

        private void recuperarAte(long ate) {
            long de = lastSeq + 1;
            if (de > ate) return;
            monitoring.log("RECUPERACAO;p" + k + ";de=" + de + ";ate=" + ate);

            System.out.println("[p" + k + "] RECUPERAÇÃO: " + de + ".." + ate + " pedida a " + leaderHost + ":" + leaderPort);

            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(leaderHost, leaderPort), Wire.T_TCP_MS);
                s.setSoTimeout(Wire.T_TCP_MS);

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(
                        new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);

                out.println("PEDIDO " + de + " " + ate);

                String line;
                while ((line = in.readLine()) != null) {
                    if (line.equals("FIM")) {
                        break;
                    }
                    if (line.startsWith("ERRO")) {
                        System.out.println("[p" + k + "] RECUPERAÇÃO FALHOU: " + line);
                        return;
                    }

                    SensorRecord r = SensorRecord.fromLine(line);
                    if (r.getSeq() == lastSeq + 1) {
                        file.append(r);
                        lastSeq = r.getSeq();
                        System.out.println("[p" + k + "] RECUPERADO: " + r.getSeq());
                    }
                }

                // Após recuperar os em falta, tenta descarregar os retidos que agora podem estar em sequência
                despejarRetidos();
            } catch (Exception e) {
                System.out.println("[p" + k + "] RECUPERAÇÃO FALHOU: " + e.getMessage());
            }
        }
    }
}
