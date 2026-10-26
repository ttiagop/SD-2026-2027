package rep01;

import java.io.*;
import java.util.TreeMap;

public class PubSubReplica {
    static final String TOPIC = "rep01-temperaturas";

    // estado da réplica (partilhado entre a thread do subscribe e a thread que deteta o fim da rajada)
    static RecordFile file;
    static long ultimo;                                             // última página já colada no ficheiro
    static final TreeMap<Long, SensorRecord> emEspera = new TreeMap<>();  // páginas postas de lado

    // ponto 7: estatísticas da rajada (registos do sensor SIM)
    static long tPrimeiro = 0, tUltimo = 0;
    static int recebidos = 0, escritos = 0;

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        String porto = args.length > 1 ? args[1] : "5001";
        String api = "http://127.0.0.1:" + porto + "/api/v0";
        file = new RecordFile("replica-" + id + ".txt");
        try {
            ultimo = file.lastSeq();

            // ponto 7: thread auxiliar — 2 s sem registos SIM = fim da rajada
            Thread vigia = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    resumoSeRajadaTerminou();
                }
            });
            vigia.setDaemon(true);
            vigia.start();

            System.out.println("Réplica " + id + " subscrita a " + TOPIC + " via " + api);
            new IpfsPubSub(api).subscribe(TOPIC, PubSubReplica::aplicar);  // bloqueia para sempre
        } catch (IOException e) {
            System.out.println("IO: " + e);
        } catch (InterruptedException e) {
            System.out.println("Interrompido: " + e.getMessage());
        }
    }

    // mesma lógica da MulticastReplica (ponto 6), chamada por cada mensagem recebida
    static synchronized void aplicar(String line) {
        try {
            SensorRecord r;
            try {
                r = SensorRecord.fromLine(line);                    // confia que é um registo válido...
            } catch (IllegalArgumentException e) {                  // ...e se não for, rejeita e continua
                System.out.println("REJEITADO: " + line);
                return;
            }
            long seq = r.getSeq();
            boolean rajada = r.getSensorId().equals("SIM");
            if (rajada) {
                if (recebidos == 0) tPrimeiro = System.currentTimeMillis();
                tUltimo = System.currentTimeMillis();
                recebidos++;
            }

            if (seq == ultimo + 1) {                                // 1) é a página que faltava: cola-a
                file.append(r);
                ultimo = seq;
                if (rajada) escritos++; else System.out.println("Aplicado: " + r.toLine());
                while (emEspera.containsKey(ultimo + 1)) {          //    e cola as que estavam à espera dela
                    SensorRecord seguinte = emEspera.remove(ultimo + 1);
                    file.append(seguinte);
                    ultimo = seguinte.getSeq();
                    if (seguinte.getSensorId().equals("SIM")) escritos++;
                    else System.out.println("Aplicado (estava em espera): " + seguinte.toLine());
                }
            } else if (seq <= ultimo || emEspera.containsKey(seq)) {  // 2) já a tinha: ignora
                System.out.println("DUPLICADO: " + seq);
            } else {                                                // 3) falta alguma antes: põe de lado
                emEspera.put(seq, r);
                System.out.println("EM ESPERA: " + seq + " (falta o registo " + (ultimo + 1) + ")");
            }
        } catch (IOException e) {
            System.out.println("IO ao escrever no ficheiro: " + e.getMessage());
        }
    }

    static synchronized void resumoSeRajadaTerminou() {
        if (recebidos > 0 && System.currentTimeMillis() - tUltimo > 2000) {
            System.out.println("FIM DA RAJADA | receção do primeiro: " + tPrimeiro
                    + " | receção do último: " + tUltimo + " | recebidos: " + recebidos
                    + " | escritos no ficheiro: " + escritos + " | retidos em memória: " + emEspera.size());
            recebidos = 0;
            escritos = 0;
        }
    }
}
