package rep03;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class CausalCheck {
    private static class Entry {
        final int k;
        final SensorRecord r;
        Entry(int k, SensorRecord r) { this.k = k; this.r = r; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: CausalCheck <N> <pasta>");
            System.exit(1);
        }

        int n = Integer.parseInt(args[0]);
        File dir = new File(args[1]);

        List<Entry> todos = new ArrayList<>();
        boolean violacaoSeq = false;

        for (int k = 0; k < n; k++) {
            File f = new File(dir, "lider-p" + k + ".txt");
            if (!f.exists()) continue;

            try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    SensorRecord r = SensorRecord.fromLine(line, n);
                    if (r.getVector().get(k) != r.getSeq()) {
                        System.err.println("VIOLAÇÃO: ficheiro lider-p" + k + ".txt seq=" + r.getSeq() + " != V[" + k + "]=" + r.getVector().get(k));
                        violacaoSeq = true;
                    }
                    todos.add(new Entry(k, r));
                }
            }
        }

        if (!violacaoSeq) {
            System.out.println("Validação V[k] == seq: OK em todas as linhas.");
        }

        int causaisA2B = 0, causaisB2A = 0, concorrentes = 0;
        int violacoesRelogio = 0;
        int concorrentesLDistinto = 0;

        for (int i = 0; i < todos.size(); i++) {
            for (int j = i + 1; j < todos.size(); j++) {
                Entry e1 = todos.get(i);
                Entry e2 = todos.get(j);

                if (e1.k == e2.k) continue;

                VectorClock.Order o = e1.r.getVector().compare(e2.r.getVector());
                if (o == VectorClock.Order.BEFORE) {
                    causaisA2B++;
                    if (e1.r.getLamport() >= e2.r.getLamport()) violacoesRelogio++;
                } else if (o == VectorClock.Order.AFTER) {
                    causaisB2A++;
                    if (e2.r.getLamport() >= e1.r.getLamport()) violacoesRelogio++;
                } else if (o == VectorClock.Order.CONCURRENT) {
                    concorrentes++;
                    if (e1.r.getLamport() != e2.r.getLamport()) {
                        concorrentesLDistinto++;
                    }
                }
            }
        }

        int totalPares = causaisA2B + causaisB2A + concorrentes;
        double propLDistinto = concorrentes > 0 ? (double) concorrentesLDistinto / concorrentes * 100.0 : 0.0;

        System.out.println("\n--- RELATÓRIO DE CAUSALIDADE ---");
        System.out.println("Total de pares entre partições distintas: " + totalPares);
        System.out.println("Pares a -> b: " + causaisA2B);
        System.out.println("Pares b -> a: " + causaisB2A);
        System.out.println("Pares Concorrentes: " + concorrentes);
        System.out.println("Violações da Condição de Relógio (a -> b com L(a) >= L(b)): " + violacoesRelogio);
        System.out.printf(Locale.US, "Pares concorrentes com L distinto: %d (%.2f%% dos concorrentes)\n",
                concorrentesLDistinto, propLDistinto);

        todos.sort((a, b) -> {
            int compL = Long.compare(a.r.getLamport(), b.r.getLamport());
            if (compL != 0) return compL;
            return Integer.compare(a.k, b.k);
        });

        File outVista = new File(dir, "vista-lamport.txt");
        try (PrintWriter pw = new PrintWriter(new FileWriter(outVista, StandardCharsets.UTF_8))) {
            for (Entry e : todos) {
                pw.println("p" + e.k + " " + e.r.toLine());
            }
        }
        System.out.println("Ficheiro vista-lamport.txt gerado com sucesso.");
    }
}