package raft04;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class Producer {
    private static final Path REGISTO = Paths.get("produtor.txt");

    public static void main(String[] args) {
        int nParticoes = args.length >= 1 ? Integer.parseInt(args[0]) : 3;
        String api = args.length >= 2 ? args[1] : "http://127.0.0.1:5001/api/v0";

        Partitioner partitioner = new Partitioner(nParticoes);
        IpfsPubSub ps = new IpfsPubSub(api);
        Monitoring monitoring = new Monitoring("produtor", Monitoring.all(nParticoes));
        monitoring.subscribeHeaders(ps);

        System.out.println("Produtor iniciado (N=" + nParticoes + ", API: " + api + ").");
        System.out.println("Comandos: <sensor> <temperatura> | rajada <n> <s> [intervaloMs] | sair");

        try (Monitoring ignored = monitoring; Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isBlank()) continue;
                if (line.equalsIgnoreCase("sair")) break;
                String[] parts = line.split("\\s+");
                try {
                    if (parts[0].equalsIgnoreCase("rajada")) {
                        if (parts.length < 3) { System.out.println("Uso: rajada <n> <s> [intervaloMs]"); continue; }
                        int n = Integer.parseInt(parts[1]);
                        int s = Integer.parseInt(parts[2]);
                        long intervalo = parts.length > 3 ? Long.parseLong(parts[3]) : 0;
                        if (n < 1 || s < 1 || intervalo < 0) { System.out.println("n e s positivos; intervalo >= 0"); continue; }
                        long t0 = System.currentTimeMillis();
                        for (int i = 0; i < n; i++) {
                            String sensor = String.format("S%02d", ThreadLocalRandom.current().nextInt(1, s + 1));
                            double temp = Math.round(ThreadLocalRandom.current().nextDouble(15.0, 30.0) * 10.0) / 10.0;
                            enviar(ps, monitoring, partitioner, nParticoes, sensor, temp);
                            if (intervalo > 0 && i < n - 1) Thread.sleep(intervalo);
                        }
                        System.out.println("Rajada de " + n + " enviada em " + (System.currentTimeMillis() - t0) + " ms");
                    } else if (parts.length == 2) {
                        enviar(ps, monitoring, partitioner, nParticoes, parts[0], Double.parseDouble(parts[1]));
                    } else {
                        System.out.println("Comando inválido.");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Número inválido: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Erro no produtor: " + e.getMessage());
        }
        System.exit(0);
    }

    private static void enviar(IpfsPubSub ps, Monitoring monitoring, Partitioner partitioner, int n,
                               String sensor, double temp) throws Exception {
        int k = partitioner.of(sensor);
        monitoring.warn(k, "leitura " + sensor);
        ps.publish(Wire.topicIn(k), Wire.in(sensor, temp, 0L, new VectorClock(n)));
        String linha = String.format(Locale.US, "%d;p%d;%s;%.1f", System.currentTimeMillis(), k, sensor, temp);
        Files.writeString(REGISTO, linha + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("IN " + sensor + " " + temp + " -> p" + k);
    }
}
