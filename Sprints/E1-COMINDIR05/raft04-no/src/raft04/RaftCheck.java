package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * RaftCheck <N> <pasta> [falhas.txt]
 * Lê no-*, estado-*, hb-*, eventos-*, truncados-*, monitor-* e produtor.txt e escreve um relatório
 * na consola e em relatorio-raft.txt.
 */
public class RaftCheck {
    private static final StringBuilder out = new StringBuilder();

    private static void p(String s) { out.append(s).append('\n'); }

    // instante;evento;p<k>;detalhe...
    private static final class Ev {
        final long t; final int node; final String type; final String[] f;
        Ev(long t, int node, String type, String[] f) { this.t = t; this.node = node; this.type = type; this.f = f; }
        String field(String key) {
            for (String x : f) if (x.startsWith(key + "=")) return x.substring(key.length() + 1);
            return null;
        }
        long term() { String v = field("termo"); return v == null ? -1 : Long.parseLong(v); }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) { System.err.println("Uso: RaftCheck <N> <pasta> [falhas.txt]"); System.exit(1); }
        int n = Integer.parseInt(args[0]);
        Path dir = Paths.get(args[1]);
        List<String[]> falhas = new ArrayList<>();
        if (args.length > 2) {
            Path f = dir.resolve(args[2]);
            if (!Files.exists(f)) f = Paths.get(args[2]);
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!l.isBlank()) falhas.add(l.trim().split(";"));
        }
        List<String[]> produtor = new ArrayList<>();   // instante;p<k>;sensor;temperatura
        Path prod = dir.resolve("produtor.txt");
        if (Files.exists(prod))
            for (String l : Files.readAllLines(prod, StandardCharsets.UTF_8)) if (!l.isBlank()) produtor.add(l.split(";"));

        int termosComDoisLideres = 0, termoDiminuiu = 0;
        p("RELATÓRIO RaftCheck — pasta " + dir.toAbsolutePath().normalize());

        for (int kk = 0; kk < n; kk++) {
            final int k = kk;
            List<Ev> evs = new ArrayList<>();
            Map<Integer, List<Ev>> porNo = new TreeMap<>();
            for (int i = 1; i <= Wire.GRUPO; i++) {
                Path f = dir.resolve("eventos-p" + k + "-n" + i + ".txt");
                if (!Files.exists(f)) continue;
                List<Ev> lista = new ArrayList<>();
                for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String[] c = l.split(";");
                    if (c.length < 3) continue;
                    lista.add(new Ev(Long.parseLong(c[0]), i, c[1], Arrays.copyOfRange(c, 3, c.length)));
                }
                porNo.put(i, lista);
                evs.addAll(lista);
            }
            if (evs.isEmpty()) continue;
            evs.sort(Comparator.comparingLong(e -> e.t));
            p("");
            p("================ Partição p" + k + " ================");

            // ---- sequência de termos
            TreeMap<Long, List<Ev>> candidaturas = new TreeMap<>(), lideres = new TreeMap<>();
            Map<String, Integer> votos = new HashMap<>();             // termo#candidato -> votos concedidos
            for (Ev e : evs) {
                if (e.type.equals("CANDIDATO")) candidaturas.computeIfAbsent(e.term(), x -> new ArrayList<>()).add(e);
                if (e.type.equals("LIDER")) lideres.computeIfAbsent(e.term(), x -> new ArrayList<>()).add(e);
                if (e.type.equals("VOTO") && Arrays.asList(e.f).contains("concedido"))
                    votos.merge(e.term() + "#" + e.field("candidato"), 1, Integer::sum);
            }
            TreeSet<Long> termos = new TreeSet<>(candidaturas.keySet());
            termos.addAll(lideres.keySet());
            p("Termos:");
            for (long t : termos) {
                List<Ev> cs = candidaturas.getOrDefault(t, List.of());
                List<Ev> ls = lideres.getOrDefault(t, List.of());
                StringBuilder s = new StringBuilder("  termo " + t + ": candidatos ");
                for (Ev c : cs) s.append("n").append(c.node).append("(")
                        .append(1 + votos.getOrDefault(t + "#n" + c.node, 0)).append(" votos) ");
                Set<Integer> nosLider = new TreeSet<>();
                for (Ev l : ls) nosLider.add(l.node);
                // voto dividido: duas ou mais candidaturas no mesmo termo durante a mesma eleição
                boolean dividido = cs.size() > 1 && cs.get(cs.size() - 1).t - cs.get(0).t < Wire.ELEICAO_MIN_MS;
                if (nosLider.isEmpty()) s.append("-> sem líder").append(dividido ? " (voto dividido)" : " (sem maioria)");
                else {
                    s.append("-> líder ");
                    for (int x : nosLider) s.append("n").append(x).append(' ');
                    if (!cs.isEmpty()) s.append("; eleição ").append(ls.get(0).t - cs.get(0).t).append(" ms");
                }
                if (nosLider.size() > 1) { termosComDoisLideres++; s.append("  <<< DOIS LÍDERES"); }
                p(s.toString());
            }

            // ---- o termo de cada nó nunca diminui
            for (Map.Entry<Integer, List<Ev>> e : porNo.entrySet()) {
                long ultimo = -1;
                for (Ev ev : e.getValue()) {
                    // só eventos que indicam o termo do próprio nó (RECEBIDO e IGNORADO trazem o termo do lote)
                    if (!Set.of("ARRANQUE", "CANDIDATO", "TERMO", "VOTO", "LIDER", "NOVO_LIDER", "DEMISSAO").contains(ev.type)) continue;
                    long t = ev.term();
                    if (ev.type.equals("TERMO")) t = Long.parseLong(ev.f[0].split("->")[1]);
                    if (t < 0) continue;
                    if (t < ultimo) { termoDiminuiu++; p("  ERRO: termo de n" + e.getKey() + " diminuiu " + ultimo + " -> " + t); }
                    ultimo = Math.max(ultimo, t);
                }
            }

            // ---- tempo até ao primeiro líder
            long arranque = evs.stream().filter(e -> e.type.equals("ARRANQUE")).mapToLong(e -> e.t).min().orElse(evs.get(0).t);
            Optional<Ev> primeiro = evs.stream().filter(e -> e.type.equals("LIDER")).findFirst();
            p("Tempo até ao primeiro líder: " + primeiro.map(e -> fmt(e.t - arranque) + " (n" + e.node + ", termo " + e.term() + ")")
                    .orElse("nenhum líder"));

            // ---- lotes publicados (hb-p<k>-n<i>.txt): termo;hb;instante;...
            List<long[]> lotes = new ArrayList<>();                  // {instante, termo}
            for (int i = 1; i <= Wire.GRUPO; i++) {
                Path f = dir.resolve("hb-p" + k + "-n" + i + ".txt");
                if (!Files.exists(f)) continue;
                for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String[] c = l.split(";");
                    if (c.length >= 3) lotes.add(new long[]{Long.parseLong(c[2]), Long.parseLong(c[0])});
                }
            }
            lotes.sort(Comparator.comparingLong(x -> x[0]));

            // ---- indisponibilidade de cada falha anotada
            for (String[] fa : falhas) {
                if (!fa[0].equals("p" + k)) continue;
                long tf = Long.parseLong(fa[2]);
                long termoAntes = lotes.stream().filter(x -> x[0] <= tf).mapToLong(x -> x[1]).max().orElse(0);
                Optional<long[]> retoma = lotes.stream().filter(x -> x[0] > tf && x[1] > termoAntes).findFirst();
                if (retoma.isEmpty()) { p("Falha " + fa[1] + " em " + tf + ": nenhum LOTE de termo superior a " + termoAntes); continue; }
                long fim = retoma.get()[0];
                long enviadas = produtor.stream().filter(x -> x[1].equals("p" + k))
                        .filter(x -> Long.parseLong(x[0]) >= tf && Long.parseLong(x[0]) < fim).count();
                p("Falha " + fa[1] + " em " + tf + ": indisponibilidade " + fmt(fim - tf) + " (termo " + termoAntes
                        + " -> " + retoma.get()[1] + "), leituras enviadas no intervalo: " + enviadas);
            }

            // ---- registos finais dos nós
            Map<Integer, List<String>> finais = new TreeMap<>();
            for (int i = 1; i <= Wire.GRUPO; i++) {
                Path f = dir.resolve("no-p" + k + "-n" + i + ".txt");
                List<String> ls = new ArrayList<>();
                if (Files.exists(f)) for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!l.isBlank()) ls.add(l);
                finais.put(i, ls);
            }

            // ---- leituras perdidas: produtor.txt que não consta do registo final de nenhum nó
            Map<String, Integer> disponiveis = new HashMap<>();
            for (List<String> ls : finais.values()) {
                Map<String, Integer> c = new HashMap<>();
                for (String l : ls) { String[] x = l.split(";"); if (!x[2].startsWith("ALARM-")) c.merge(x[2] + ";" + x[3], 1, Integer::sum); }
                c.forEach((key, v) -> disponiveis.merge(key, v, Math::max));
            }
            List<String> perdidas = new ArrayList<>();
            for (String[] x : produtor) {
                if (!x[1].equals("p" + k)) continue;
                String key = x[2] + ";" + x[3];
                if (disponiveis.getOrDefault(key, 0) > 0) disponiveis.merge(key, -1, Integer::sum);
                else perdidas.add(x[0] + " " + x[2] + " " + x[3]);
            }
            long enviadasK = produtor.stream().filter(x -> x[1].equals("p" + k)).count();
            p("Leituras perdidas: " + perdidas.size() + " de " + enviadasK + " enviadas" + (perdidas.isEmpty() ? "" : " " + perdidas));

            // ---- truncagens
            int liderFinal = lideres.isEmpty() ? 0 : lideres.lastEntry().getValue().get(0).node;
            Set<String> doLider = new HashSet<>();
            Map<String, String> seqNoLider = new HashMap<>();
            if (liderFinal > 0) for (String l : finais.get(liderFinal)) {
                String[] x = l.split(";");
                seqNoLider.put(x[2] + ";" + x[3], x[0]);
                doLider.add(x[2] + ";" + x[3]);
            }
            for (Ev e : evs) {
                if (!e.type.equals("TRUNCADO")) continue;
                p("TRUNCADO em n" + e.node + ": de=" + e.field("de") + " descartados=" + e.field("descartados")
                        + " termos=" + e.field("termos"));
            }
            for (int i = 1; i <= Wire.GRUPO; i++) {
                Path f = dir.resolve("truncados-p" + k + "-n" + i + ".txt");
                if (!Files.exists(f)) continue;
                int total = 0, noLider = 0;
                for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (l.isBlank()) continue;
                    String[] x = l.split(";");                       // instante;seq;termo;sensor;temp;...
                    total++;
                    String key = x[3] + ";" + x[4];
                    if (doLider.contains(key) && !seqNoLider.get(key).equals(x[1])) noLider++;
                }
                p("  n" + i + ": " + total + " registos descartados, " + noLider
                        + " existem no registo final do líder n" + liderFinal + " com outro seq");
            }

            // ---- coincidência final
            int menor = finais.values().stream().mapToInt(List::size).min().orElse(0);
            List<Integer> difs = new ArrayList<>();
            for (int s = 0; s < menor; s++) {
                Set<String> v = new HashSet<>();
                for (List<String> ls : finais.values()) v.add(ls.get(s));
                if (v.size() > 1) difs.add(s + 1);
            }
            StringBuilder tam = new StringBuilder();
            finais.forEach((i, ls) -> tam.append(" n").append(i).append("=").append(ls.size()));
            p("Coincidência final (até seq " + menor + "; ultimoSeq:" + tam + "): "
                    + (difs.isEmpty() ? "os ficheiros coincidem" : difs.size() + " seq divergentes " + difs));
        }

        p("");
        p("================ Segurança da eleição ================");
        p("Termos com mais de um LIDER: " + termosComDoisLideres + (termosComDoisLideres == 0 ? " (OK)" : " (FALHA)"));
        p("Diminuições de termo num nó: " + termoDiminuiu + (termoDiminuiu == 0 ? " (OK)" : " (FALHA)"));

        System.out.print(out);
        Files.writeString(dir.resolve("relatorio-raft.txt"), out.toString(), StandardCharsets.UTF_8);
    }

    private static String fmt(long ms) { return String.format(Locale.US, "%.1f s", ms / 1000.0); }
}
