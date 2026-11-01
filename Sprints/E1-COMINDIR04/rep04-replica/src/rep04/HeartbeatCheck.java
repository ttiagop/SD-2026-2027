package rep04;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Análise offline dos ficheiros produzidos nos ensaios, sem bibliotecas externas. */
public final class HeartbeatCheck {
    private static final class Stats {
        long count, sum, max = Long.MIN_VALUE, min = Long.MAX_VALUE;
        void add(long value) { count++; sum += value; max = Math.max(max, value); min = Math.min(min, value); }
        String report() {
            return count == 0 ? "sem amostras" : String.format(Locale.ROOT,
                    "n=%d mínimo=%d média=%.2f máximo=%d", count, min, (double) sum / count, max);
        }
    }

    private static final class Published {
        long inc, hb, time, lastSeq, count, bytes;
        Published(String line) {
            String[] f = line.split(";", -1);
            if (f.length != 6) throw new IllegalArgumentException("Linha hb inválida: " + line);
            inc = Long.parseLong(f[0]); hb = Long.parseLong(f[1]); time = Long.parseLong(f[2]);
            lastSeq = Long.parseLong(f[3]); count = Long.parseLong(f[4]); bytes = Long.parseLong(f[5]);
        }
    }

    private static final class Event {
        long time;
        int k;
        String type;
        Map<String, String> detail = new HashMap<>();
        Event(String line) {
            String[] f = line.split(";", -1);
            if (f.length < 3) throw new IllegalArgumentException("Evento inválido: " + line);
            time = Long.parseLong(f[0]); type = f[1]; k = Integer.parseInt(f[2].substring(1));
            for (int i = 3; i < f.length; i++) {
                int eq = f[i].indexOf('=');
                if (eq > 0) detail.put(f[i].substring(0, eq), f[i].substring(eq + 1));
            }
        }
        long number(String key) { return Long.parseLong(detail.get(key)); }
    }

    private static List<String> lines(Path file) throws Exception {
        if (!Files.exists(file)) {
            System.out.println("AVISO: ficheiro ausente: " + file.getFileName());
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            if (!line.isBlank()) result.add(line);
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("Uso: HeartbeatCheck <N> <pasta> [falhas.txt]");
        int n = Integer.parseInt(args[0]);
        Path dir = Paths.get(args[1]);
        if (n < 1 || !Files.isDirectory(dir)) throw new IllegalArgumentException("N/pasta inválidos");

        Map<Integer, List<Published>> published = new TreeMap<>();
        Map<Integer, Map<Long, SensorRecord>> records = new TreeMap<>();
        Map<Integer, List<Long>> failures = new TreeMap<>();
        Map<Integer, Stats> latency = new TreeMap<>();
        for (int k = 0; k < n; k++) {
            List<Published> entries = new ArrayList<>();
            for (String line : lines(dir.resolve("hb-p" + k + ".txt"))) entries.add(new Published(line));
            entries.sort(Comparator.comparingLong(p -> p.time));
            published.put(k, entries);
            Map<Long, SensorRecord> rs = new TreeMap<>();
            for (String line : lines(dir.resolve("lider-p" + k + ".txt"))) {
                SensorRecord r = SensorRecord.fromLine(line, n); rs.put(r.getSeq(), r);
            }
            records.put(k, rs);
            latency.put(k, new Stats());
            failures.put(k, new ArrayList<>());

            Map<Long, List<Published>> incarnations = new LinkedHashMap<>();
            for (Published p : entries) incarnations.computeIfAbsent(p.inc, ignored -> new ArrayList<>()).add(p);
            System.out.println("\nPARTIÇÃO p" + k);
            for (Map.Entry<Long, List<Published>> group : incarnations.entrySet()) {
                Stats sizes = new Stats(), intervals = new Stats();
                long empty = 0, previous = -1, bytes = 0, count = 0;
                for (Published p : group.getValue()) {
                    sizes.add(p.count);
                    if (p.count == 0) empty++; else { bytes += p.bytes; count += p.count; }
                    if (previous >= 0) intervals.add(p.time - previous);
                    previous = p.time;
                }
                System.out.printf(Locale.ROOT, "inc=%d lotes=%d vazios=%d (%.2f%%) registos/lote: %s%n",
                        group.getKey(), sizes.count, empty, 100.0 * empty / sizes.count, sizes.report());
                System.out.println("Intervalo heartbeat (ms): " + intervals.report());
                System.out.printf(Locale.ROOT, "Bytes/registo em LOTE não vazio: %s%n",
                        count == 0 ? "sem amostras" : String.format(Locale.ROOT, "%.2f", (double) bytes / count));
            }
            System.out.println("Mensagens de dados COMINDIR03 (uma por registo): " + rs.size()
                    + "; COMINDIR04 (lotes publicados): " + entries.size());
        }

        if (args.length == 3) {
            for (String line : lines(Paths.get(args[2]))) {
                String[] f = line.split(";");
                if (f.length != 2 || !f[0].startsWith("p"))
                    throw new IllegalArgumentException("Falha: esperado p<k>;instante: " + line);
                int k = Integer.parseInt(f[0].substring(1));
                if (!failures.containsKey(k)) throw new IllegalArgumentException("Partição inválida em falhas.txt");
                failures.get(k).add(Long.parseLong(f[1]));
            }
            for (List<Long> times : failures.values()) Collections.sort(times);
        }

        List<Path> monitorFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "monitor-*.txt")) {
            for (Path p : stream) monitorFiles.add(p);
        }
        Collections.sort(monitorFiles);
        if (monitorFiles.isEmpty()) System.out.println("AVISO: nenhum ficheiro de monitor encontrado");
        long lost = 0, recoveries = 0, real = 0, falseSuspicions = 0;
        Stats detection = new Stats();
        Map<Integer, String> addresses = new HashMap<>();
        Map<Integer, Long> ports = new HashMap<>();

        for (Path file : monitorFiles) {
            List<Event> events = new ArrayList<>();
            for (String line : lines(file)) {
                Event e = new Event(line);
                if (e.k < 0 || e.k >= n) throw new IllegalArgumentException("Partição inválida em " + file);
                events.add(e);
            }
            // Ordenação estável preserva REINICIO/RETOMA/RECEBIDO com o mesmo milissegundo.
            events.sort(Comparator.comparingLong(e -> e.time));
            Set<String> measured = new HashSet<>(), matchedFailures = new HashSet<>();
            Map<Integer, Long> lastInc = new HashMap<>();
            Map<Integer, Stats> localLatency = new TreeMap<>();
            System.out.println("\nPARTICIPANTE " + file.getFileName());
            for (int i = 0; i < events.size(); i++) {
                Event e = events.get(i);
                if (e.type.equals("HB_PERDIDO")) lost++;
                if (e.type.equals("RECUPERACAO")) recoveries++;
                if (e.type.equals("RECEBIDO")) {
                    lastInc.merge(e.k, e.number("inc"), Math::max);
                    if (e.detail.containsKey("endereco")) {
                        addresses.put(e.k, e.detail.get("endereco")); ports.put(e.k, e.number("porto"));
                    }
                    String range = e.detail.get("seq");
                    if (range != null && !range.equals("vazio")) {
                        String[] ends = range.split("\\.\\.");
                        long first = Long.parseLong(ends[0]), last = Long.parseLong(ends[1]);
                        if (last != e.number("ultimoSeq") || last - first + 1 != e.number("n"))
                            System.out.println("INCONSISTENCIA_LOTE;p" + e.k + ";seq=" + range
                                    + ";ultimoSeq=" + e.number("ultimoSeq") + ";n=" + e.number("n"));
                        for (SensorRecord r : records.get(e.k).values()) {
                            if (r.getSeq() < first || r.getSeq() > last || !measured.add(e.k + "#" + r.getSeq())) continue;
                            long delay = e.time - r.getTs();
                            latency.get(e.k).add(delay);
                            localLatency.computeIfAbsent(e.k, ignored -> new Stats()).add(delay);
                            System.out.println("LATENCIA;p" + e.k + ";seq=" + r.getSeq() + ";ms=" + delay);
                        }
                    }
                }
                if (!e.type.equals("SUSPEITA")) continue;
                Long incarnation = lastInc.get(e.k);
                boolean resumedSame = false;
                for (int j = i + 1; j < events.size(); j++) {
                    Event next = events.get(j);
                    if (next.k != e.k) continue;
                    if (next.type.equals("RETOMA")) {
                        resumedSame = incarnation != null && next.number("inc") == incarnation;
                        break;
                    }
                }
                String classification = incarnation == null ? "INDETERMINADA (sem heartbeat inicial)"
                        : resumedSame ? "FALSA SUSPEITA" : "FALHA REAL (inferida até ao fim do registo)";
                if (incarnation != null) { if (resumedSame) falseSuspicions++; else real++; }
                Published lastPublished = null;
                for (Published p : published.get(e.k)) {
                    if (p.time <= e.time && (incarnation == null || p.inc == incarnation)) lastPublished = p;
                }
                System.out.println("p" + e.k + " SUSPEITA em " + e.time + ": " + classification
                        + "; silêncio desde último publicado (ms)="
                        + (lastPublished == null ? "desconhecido" : e.time - lastPublished.time));
                if (!resumedSame && incarnation != null) {
                    Long failure = null;
                    for (long time : failures.get(e.k))
                        if (time <= e.time && (lastPublished == null || time >= lastPublished.time)) failure = time;
                    if (failure != null && matchedFailures.add(e.k + "#" + failure)) {
                        long elapsed = e.time - failure; detection.add(elapsed);
                        System.out.println("DETECAO;p" + e.k + ";falha=" + failure + ";ms=" + elapsed);
                    }
                }
            }
            for (Map.Entry<Integer, Stats> entry : localLatency.entrySet())
                System.out.println("p" + entry.getKey() + " latência (ms): " + entry.getValue().report());
            if (args.length == 3) {
                for (int k = 0; k < n; k++) for (long time : failures.get(k))
                    if (!matchedFailures.contains(k + "#" + time))
                        System.out.println("p" + k + " falha " + time + ": sem deteção real associada neste participante");
            }
        }

        System.out.println("\nTOTAIS");
        System.out.println("Falhas reais inferidas=" + real + "; falsas suspeitas=" + falseSuspicions
                + "; HB_PERDIDO=" + lost + "; RECUPERACAO=" + recoveries);
        System.out.println("Tempo de deteção (ms): " + detection.report());
        for (int k = 0; k < n; k++) {
            System.out.println("p" + k + " latência de lote, todos os participantes (ms): " + latency.get(k).report());
            long bytes = 0;
            String address = addresses.getOrDefault(k, "127.0.0.1");
            long port = ports.getOrDefault(k, (long) Wire.PORTO_BASE + k);
            for (SensorRecord r : records.get(k).values())
                bytes += ("REG;" + address + ";" + port + ";" + r.toLine()).getBytes(StandardCharsets.UTF_8).length;
            System.out.printf(Locale.ROOT, "p%d bytes/REG equivalente (%s:%d): %s%n", k, address, port,
                    records.get(k).isEmpty() ? "sem amostras" : String.format(Locale.ROOT,
                            "%.2f", (double) bytes / records.get(k).size()));
        }
        System.out.println("Bytes: payload UTF-8, sem overhead HTTP/IPFS. Lotes vazios reportados separadamente.");
        System.out.println("Latências comparam relógios físicos: executar todos os processos na mesma máquina.");
        System.out.println("Sem retoma observada, a classificação real é provisória; falhas.txt ancora os tempos de deteção.");
    }
}
