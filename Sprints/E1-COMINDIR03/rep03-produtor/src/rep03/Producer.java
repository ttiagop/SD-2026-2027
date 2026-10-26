package rep03;

import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class Producer {
    public static void main(String[] args) {
        int nParticoes = 3;
        String api = "http://127.0.0.1:5001/api/v0";

        if (args.length >= 1) {
            try {
                nParticoes = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Número de partições inválido: " + args[0]);
                System.exit(1);
            }
        }
        if (args.length >= 2) {
            api = args[1];
        }

        Partitioner partitioner = new Partitioner(nParticoes);
        IpfsPubSub ps = new IpfsPubSub(api);

        System.out.println("Produtor iniciado (N=" + nParticoes + " partições, API: " + api + ").");
        System.out.println("Comandos:");
        System.out.println("  <sensor> <temperatura>        (ex: S01 22.5)");
        System.out.println("  rajada <n> <s>                 (n leituras aleatórias entre S01 e S<s>)");
        System.out.println("  rajada-quente <n> <s>          (80% de leituras de S01, 20% restantes)");
        System.out.println("  sair");

        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isBlank()) {
                    continue;
                }
                if (line.equalsIgnoreCase("sair") || line.equalsIgnoreCase("exit")) {
                    break;
                }

                String[] parts = line.split("\\s+");
                String cmd = parts[0].toLowerCase();

                if (cmd.equals("rajada") || cmd.equals("rajada-quente")) {
                    if (parts.length < 3) {
                        System.out.println("Uso: " + cmd + " <n> <s>");
                        continue;
                    }
                    int n = Integer.parseInt(parts[1]);
                    int s = Integer.parseInt(parts[2]);
                    boolean quente = cmd.equals("rajada-quente");

                    int[] contagens = new int[nParticoes];
                    long tInicio = 0;
                    long tFim = 0;

                    System.out.println("A enviar " + cmd + " de " + n + " leituras (S01..S" + String.format("%02d", s) + ")...");
                    for (int i = 0; i < n; i++) {
                        String sensorId;
                        if (quente && (ThreadLocalRandom.current().nextDouble() < 0.8 || s <= 1)) {
                            sensorId = "S01";
                        } else {
                            int minSensor = (quente && s > 1) ? 2 : 1;
                            int sensorNum = ThreadLocalRandom.current().nextInt(minSensor, s + 1);
                            sensorId = String.format("S%02d", sensorNum);
                        }

                        double temp = Math.round(ThreadLocalRandom.current().nextDouble(15.0, 30.0) * 10.0) / 10.0;
                        int k = partitioner.of(sensorId);

                        if (i == 0) {
                            tInicio = System.currentTimeMillis();
                        }
                        ps.publish(Wire.topicIn(k), Wire.in(sensorId, temp, 0L, new VectorClock(nParticoes)));
                        contagens[k]++;
                        if (i == n - 1) {
                            tFim = System.currentTimeMillis();
                        }
                    }

                    System.out.println("Envio concluído.");
                    System.out.println("Instante inicial: " + tInicio);
                    System.out.println("Instante final:   " + tFim);
                    System.out.println("Duração total:    " + (tFim - tInicio) + " ms");
                    System.out.println("Distribuição por partição:");
                    for (int p = 0; p < nParticoes; p++) {
                        System.out.println("  p" + p + ": " + contagens[p] + " leituras");
                    }
                } else if (parts.length >= 2) {
                    String sensorId = parts[0];
                    try {
                        double temp = Double.parseDouble(parts[1]);
                        int k = partitioner.of(sensorId);
                        String msg = Wire.in(sensorId, temp, 0L, new VectorClock(nParticoes));
                        ps.publish(Wire.topicIn(k), msg);
                        System.out.println("Publicado " + sensorId + " -> " + temp + " °C (-> p" + k + "): " + msg);
                    } catch (NumberFormatException e) {
                        System.out.println("Temperatura inválida: " + parts[1]);
                    }
                } else {
                    System.out.println("Comando inválido.");
                }
            }
        } catch (Exception e) {
            System.err.println("Erro no produtor: " + e.getMessage());
        }
    }
}
