package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

public class MulticastReplica {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        RecordFile file = new RecordFile("replica-" + id + ".txt");
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.joinGroup(group);                                // passa a receber o que é enviado ao grupo
            socket.setSoTimeout(2000);                              // ponto 7: 2 s sem nada = fim da rajada
            System.out.println("Réplica " + id + " à escuta em " + GROUP + ":" + PORT);

            long ultimo = file.lastSeq();                           // última página já colada no ficheiro
            TreeMap<Long, SensorRecord> emEspera = new TreeMap<>(); // páginas postas de lado, ordenadas por seq

            // ponto 7: estatísticas da rajada (registos do sensor SIM)
            long tPrimeiro = 0, tUltimo = 0;
            int recebidos = 0, escritos = 0;

            byte[] buffer = new byte[1000];
            while (true) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(p);                              // bloqueia até chegar um datagrama
                } catch (SocketTimeoutException e) {                // 2 s em silêncio
                    if (recebidos > 0) {
                        System.out.println("FIM DA RAJADA | receção do primeiro: " + tPrimeiro
                                + " | receção do último: " + tUltimo + " | recebidos: " + recebidos
                                + " | escritos no ficheiro: " + escritos + " | retidos em memória: " + emEspera.size());
                        recebidos = 0;
                        escritos = 0;
                    }
                    continue;                                       // volta a esperar
                }
                String line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);

                SensorRecord r;
                try {
                    r = SensorRecord.fromLine(line);                // confia que é um registo válido...
                } catch (IllegalArgumentException e) {              // ...e se não for, rejeita e continua
                    System.out.println("REJEITADO: " + line);
                    continue;
                }
                long seq = r.getSeq();
                boolean rajada = r.getSensorId().equals("SIM");
                if (rajada) {
                    if (recebidos == 0) tPrimeiro = System.currentTimeMillis();
                    tUltimo = System.currentTimeMillis();
                    recebidos++;
                }

                if (seq == ultimo + 1) {                            // 1) é a página que faltava: cola-a
                    file.append(r);
                    ultimo = seq;
                    if (rajada) escritos++; else System.out.println("Aplicado: " + r.toLine());
                    while (emEspera.containsKey(ultimo + 1)) {      //    e cola as que estavam à espera dela
                        SensorRecord seguinte = emEspera.remove(ultimo + 1);
                        file.append(seguinte);
                        ultimo = seguinte.getSeq();
                        if (seguinte.getSensorId().equals("SIM")) escritos++;
                        else System.out.println("Aplicado (estava em espera): " + seguinte.toLine());
                    }
                } else if (seq <= ultimo || emEspera.containsKey(seq)) {  // 2) já a tinha: ignora
                    System.out.println("DUPLICADO: " + seq);
                } else {                                            // 3) falta alguma antes: põe de lado
                    emEspera.put(seq, r);
                    System.out.println("EM ESPERA: " + seq + " (falta o registo " + (ultimo + 1) + ")");
                }
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
