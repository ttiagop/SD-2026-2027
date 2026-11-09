package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * CausalCheck da COMINDIR04, adaptado aos ficheiros no-p<k>-n<i>.txt:
 * verifica V[k] = seq em todas as linhas de todos os nós (através das mudanças de líder)
 * e analisa a causalidade entre partições sobre o registo mais longo de cada partição.
 */
public class CausalCheck {
    private static class Entry {
        final int k; final SensorRecord r;
        Entry(int k, SensorRecord r) { this.k = k; this.r = r; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("Uso: CausalCheck <N> <pasta>"); System.exit(1); }
        int n = Integer.parseInt(args[0]);
        Path dir = Paths.get(args[1]);

        List<Entry> todos = new ArrayList<>();
        int violacoes = 0, linhas = 0;
        for (int k = 0; k < n; k++) {
            List<SensorRecord> maisLongo = List.of();
            for (int i = 1; i <= Wire.GRUPO; i++) {
                Path f = dir.resolve("no-p" + k + "-n" + i + ".txt");
                if (!Files.exists(f)) continue;
                List<SensorRecord> regs = new ArrayList<>();
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    SensorRecord r = SensorRecord.fromLine(line, n);
                    linhas++;
                    if (r.getVector().get(k) != r.getSeq()) {
                        violacoes++;
                        System.out.println("VIOLACAO: " + f.getFileName() + " seq=" + r.getSeq()
                                + " termo=" + r.getTerm() + " V[" + k + "]=" + r.getVector().get(k));
                    }
                    regs.add(r);
                }
                if (regs.size() > maisLongo.size()) maisLongo = regs;
            }
            for (SensorRecord r : maisLongo) todos.add(new Entry(k, r));
        }
        System.out.println("V[k] = seq: " + (violacoes == 0 ? "OK" : violacoes + " violações")
                + " em " + linhas + " linhas de todos os nós");

        int a2b = 0, b2a = 0, conc = 0, violRelogio = 0;
        for (int x = 0; x < todos.size(); x++) {
            for (int y = x + 1; y < todos.size(); y++) {
                Entry e1 = todos.get(x), e2 = todos.get(y);
                if (e1.k == e2.k) continue;
                VectorClock.Order o = e1.r.getVector().compare(e2.r.getVector());
                if (o == VectorClock.Order.BEFORE) { a2b++; if (e1.r.getLamport() >= e2.r.getLamport()) violRelogio++; }
                else if (o == VectorClock.Order.AFTER) { b2a++; if (e2.r.getLamport() >= e1.r.getLamport()) violRelogio++; }
                else if (o == VectorClock.Order.CONCURRENT) conc++;
            }
        }
        System.out.println("Pares entre partições: a->b " + a2b + ", b->a " + b2a + ", concorrentes " + conc
                + "; violações da condição de relógio: " + violRelogio);
    }
}
