package rep01;

import java.io.*;
import java.util.Random;
import java.util.Scanner;

public class PubSubLeader {
    static final String TOPIC = "rep01-temperaturas";

    public static void main(String[] args) {
        String api = args.length > 0 ? args[0] : "http://127.0.0.1:5001/api/v0";
        IpfsPubSub pubsub = new IpfsPubSub(api);                    // o intermediário: o daemon IPFS local
        try (Scanner sc = new Scanner(System.in)) {
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq();                              // continua a numeração após reinício
            Random rnd = new Random();
            System.out.println("Líder PubSub pronto (" + api + ", tópico " + TOPIC + ").");
            System.out.println("Comandos: <sensor> <temperatura> | rajada <n> | desordem | sair");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;                       // bloqueia à espera do utilizador
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;
                String[] p = line.split("\\s+");

                // ponto 7: rajada <n> — n registos do sensor SIM, enviados o mais depressa possível
                if (p.length == 2 && p[0].equalsIgnoreCase("rajada")) {
                    int n;
                    try {
                        n = Integer.parseInt(p[1]);
                    } catch (NumberFormatException e) {
                        System.out.println("Número inválido: " + p[1]);
                        continue;
                    }
                    long tPrimeiro = 0, tUltimo = 0;
                    for (int i = 0; i < n; i++) {
                        double temp = Math.round((15 + rnd.nextDouble() * 15) * 10) / 10.0;  // 15 a 30 ºC
                        SensorRecord r = SensorRecord.now(++seq, "SIM", temp);
                        file.append(r);                             // 1) escreve localmente
                        pubsub.publish(TOPIC, r.toLine());          // 2) publica no tópico (bloqueia até o daemon responder)
                        if (i == 0) tPrimeiro = System.currentTimeMillis();
                        tUltimo = System.currentTimeMillis();
                    }
                    System.out.println("Rajada de " + n + " enviada. Envio do primeiro: " + tPrimeiro
                            + " | envio do último: " + tUltimo + " | duração: " + (tUltimo - tPrimeiro) + " ms");
                    continue;
                }

                // ponto 6 (teste temporário): regista 3 registos mas publica-os pela ordem 2, 3, 1
                if (p.length == 1 && p[0].equalsIgnoreCase("desordem")) {
                    SensorRecord r1 = SensorRecord.now(++seq, "T01", 20.0);
                    SensorRecord r2 = SensorRecord.now(++seq, "T01", 21.0);
                    SensorRecord r3 = SensorRecord.now(++seq, "T01", 22.0);
                    file.append(r1);
                    file.append(r2);
                    file.append(r3);
                    for (SensorRecord r : new SensorRecord[]{r2, r3, r1}) {
                        pubsub.publish(TOPIC, r.toLine());
                        System.out.println("Publicado (fora de ordem): " + r.toLine());
                    }
                    continue;
                }

                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }
                SensorRecord r = SensorRecord.now(++seq, p[0], temp);
                file.append(r);                                     // 1) escreve localmente
                pubsub.publish(TOPIC, r.toLine());                  // 2) publica no tópico
                System.out.println("Registado e publicado: " + r.toLine());
            }
        } catch (IOException e) {
            System.out.println("IO: " + e);
        } catch (InterruptedException e) {
            System.out.println("Interrompido: " + e.getMessage());
        }
    }
}
