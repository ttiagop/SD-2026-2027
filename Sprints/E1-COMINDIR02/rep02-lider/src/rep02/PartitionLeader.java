package rep02;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PartitionLeader {
    private static long currentSeq = 0;

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

        try {
            currentSeq = file.lastSeq();
            System.out.println("Líder p" + k + " (N=" + n + ") iniciado. Último seq em disco: " + currentSeq);
        } catch (IOException e) {
            System.err.println("Erro ao ler ficheiro local: " + e.getMessage());
            System.exit(1);
        }

        // Thread de Entrada: subscreve tópicos de entrada PubSub
        Thread entrada = new Thread(() -> {
            try {
                System.out.println("A escutar no tópico de entrada: " + Wire.topicIn(k));
                ps.subscribe(Wire.topicIn(k), msg -> {
                    try {
                        String type = Wire.type(msg);
                        if (!type.equals("IN")) {
                            System.out.println("REJEITADO: " + msg);
                            return;
                        }

                        String[] parts = Wire.body(msg).split(";");
                        if (parts.length != 2) {
                            System.out.println("REJEITADO: " + msg);
                            return;
                        }

                        String sensor = parts[0];
                        double temp = Double.parseDouble(parts[1]);

                        // Validação de partição (Passo 9)
                        int targetPart = partitioner.of(sensor);
                        if (targetPart != k) {
                            System.out.println("PARTIÇÃO ERRADA: " + sensor + " pertence a p" + targetPart);
                            return;
                        }

                        SensorRecord record;
                        synchronized (file) {
                            currentSeq++;
                            record = SensorRecord.now(currentSeq, sensor, temp);
                            file.append(record);
                        }

                        System.out.println("Gravado e publicado [seq " + currentSeq + "]: " + record.toLine());
                        ps.publish(Wire.topicData(k), Wire.reg(host, porto, record));
                    } catch (Exception e) {
                        System.out.println("REJEITADO: " + msg + " (" + e.getMessage() + ")");
                    }
                });
            } catch (Exception e) {
                System.err.println("Thread de entrada terminada: " + e.getMessage());
            }
        });
        entrada.setDaemon(true);
        entrada.start();

        // Thread principal: servidor TCP de recuperação
        try (ServerSocket server = new ServerSocket(porto)) {
            System.out.println("Servidor de recuperação à escuta no porto " + porto + " (anunciado como " + host + ":" + porto + ")");
            while (true) {
                try (Socket s = server.accept();
                     BufferedReader in = new BufferedReader(
                             new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(
                             new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

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

                    long maxSeq;
                    synchronized (file) {
                        maxSeq = currentSeq;
                    }

                    ate = Math.min(ate, maxSeq);

                    List<SensorRecord> records = file.range(de, ate);
                    for (SensorRecord r : records) {
                        out.println(r.toLine());
                    }
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
