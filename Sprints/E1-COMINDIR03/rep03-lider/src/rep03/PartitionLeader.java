package rep03;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PartitionLeader {
    private static long currentSeq = 0;
    private static long lamport = 0;
    private static VectorClock vector;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Uso: PartitionLeader <k> <N> [endereço] [api]");
            System.exit(1);
        }

        int k = Integer.parseInt(args[0]);
        int n = Integer.parseInt(args[1]);
        String host = args.length > 2 ? args[2] : "127.0.0.1";
        String api = args.length > 3 ? args[3] : "http://127.0.0.1:5001/api/v0";

        Partitioner partitioner = new Partitioner(n);
        IpfsPubSub ps = new IpfsPubSub(api);
        RecordFile file = new RecordFile("lider-p" + k + ".txt");
        int porto = Wire.PORTO_BASE + k;

        // Inicialização de L e V a partir da última linha gravada
        try {
            SensorRecord last = file.last();
            if (last != null) {
                currentSeq = last.getSeq();
                lamport = last.getLamport();
                vector = last.getVector().copy();
            } else {
                currentSeq = 0;
                lamport = 0;
                vector = new VectorClock(n);
            }
            System.out.println("Líder p" + k + " (N=" + n + ") iniciado. Estado: seq=" + currentSeq + " L=" + lamport + " V=" + vector);
        } catch (IOException e) {
            System.err.println("Erro ao ler ficheiro local: " + e.getMessage());
            System.exit(1);
        }

        // Thread de Entrada
        Thread entrada = new Thread(() -> {
            try {
                System.out.println("A escutar no tópico: " + Wire.topicIn(k));
                ps.subscribe(Wire.topicIn(k), msg -> {
                    try {
                        if (!Wire.type(msg).equals("IN")) {
                            System.out.println("REJEITADO: " + msg);
                            return;
                        }

                        String[] p = Wire.body(msg).split(";");
                        if (p.length != 4) {
                            System.out.println("REJEITADO: " + msg);
                            return;
                        }

                        String sensor = p[0];
                        double temp = Double.parseDouble(p[1]);
                        long msgL = Long.parseLong(p[2]);
                        VectorClock msgV = VectorClock.parse(p[3], n);

                        int targetPart = partitioner.of(sensor);
                        if (targetPart != k) {
                            System.out.println("PARTIÇÃO ERRADA: " + sensor + " pertence a p" + targetPart);
                            return;
                        }

                        SensorRecord record;
                        synchronized (file) {
                            // 1. Regra de Lamport
                            lamport = Math.max(lamport, msgL) + 1;

                            // 2. Regra Vetorial
                            vector.merge(msgV);
                            vector.tick(k);

                            // 3. Atribuição de seq
                            currentSeq++;

                            // 4. Verificação estrita
                            if (vector.get(k) != currentSeq) {
                                throw new IllegalStateException("Violação de invariante: V[" + k + "]=" + vector.get(k) + " != seq=" + currentSeq);
                            }

                            // 5. Append e registo
                            record = SensorRecord.now(currentSeq, sensor, temp, lamport, vector.copy());
                            file.append(record);

                            System.out.println("EVENTO: seq=" + currentSeq + " L=" + lamport + " V=" + vector);
                        }

                        // Publica REG com snapshot congelado
                        ps.publish(Wire.topicData(k), Wire.reg(host, porto, record));
                    } catch (Exception e) {
                        System.err.println("Erro ao processar mensagem: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                System.err.println("Thread de entrada terminada: " + e.getMessage());
            }
        });
        entrada.setDaemon(true);
        entrada.start();

        // Servidor TCP de recuperação
        try (ServerSocket server = new ServerSocket(porto)) {
            System.out.println("Servidor TCP à escuta no porto " + porto + " (anunciado como " + host + ":" + porto + ")");
            while (true) {
                try (Socket s = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

                    s.setSoTimeout(Wire.T_TCP_MS);
                    String pedido = in.readLine();
                    if (pedido == null || !pedido.startsWith("PEDIDO ")) {
                        out.println("ERRO Pedido inválido");
                        continue;
                    }

                    String[] tokens = pedido.trim().split("\\s+");
                    if (tokens.length != 3) {
                        out.println("ERRO Formato esperado: PEDIDO <de> <até>");
                        continue;
                    }

                    long de = Long.parseLong(tokens[1]);
                    long ate = Long.parseLong(tokens[2]);

                    if (de > ate || de < 1) {
                        out.println("ERRO Intervalo inválido");
                        continue;
                    }

                    List<SensorRecord> records;
                    synchronized (file) {
                        records = file.range(de, Math.min(ate, currentSeq), n);
                    }
                    for (SensorRecord r : records) out.println(r.toLine());
                    out.println("FIM");

                    System.out.println("RECUPERAÇÃO: " + de + ".." + ate + " para "
                            + s.getRemoteSocketAddress() + " (" + records.size() + " registos)");
                } catch (IOException e) {
                    System.out.println("Recuperação interrompida: " + e.getMessage());
                } catch (Exception e) {
                    System.out.println("Erro ao processar pedido: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Erro no ServerSocket no porto " + porto + ": " + e.getMessage());
        }
    }
}
