package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Scanner;

public class MulticastLeader {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        try (MulticastSocket socket = new MulticastSocket();
             Scanner sc = new Scanner(System.in)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.setTimeToLive(1);                                // o datagrama não sai da rede local
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq();                              // continua a numeração após reinício
            Random rnd = new Random();
            System.out.println("Líder pronto. Comandos: <sensor> <temperatura> | rajada <n> | desordem | sair");
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
                        byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(m, m.length, group, PORT));  // 2) propaga
                        if (i == 0) tPrimeiro = System.currentTimeMillis();
                        tUltimo = System.currentTimeMillis();
                    }
                    System.out.println("Rajada de " + n + " enviada. Envio do primeiro: " + tPrimeiro
                            + " | envio do último: " + tUltimo + " | duração: " + (tUltimo - tPrimeiro) + " ms");
                    continue;
                }

                // ponto 6 (teste temporário): regista 3 registos mas envia-os pela ordem 2, 3, 1
                if (p.length == 1 && p[0].equalsIgnoreCase("desordem")) {
                    SensorRecord r1 = SensorRecord.now(++seq, "T01", 20.0);
                    SensorRecord r2 = SensorRecord.now(++seq, "T01", 21.0);
                    SensorRecord r3 = SensorRecord.now(++seq, "T01", 22.0);
                    file.append(r1);
                    file.append(r2);
                    file.append(r3);
                    for (SensorRecord r : new SensorRecord[]{r2, r3, r1}) {
                        byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(m, m.length, group, PORT));
                        System.out.println("Enviado (fora de ordem): " + r.toLine());
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
                byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(m, m.length, group, PORT));  // 2) propaga para o grupo
                System.out.println("Registado e enviado: " + r.toLine());
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
