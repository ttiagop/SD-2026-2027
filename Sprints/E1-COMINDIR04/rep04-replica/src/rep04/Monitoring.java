package rep04;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Monitores e ficheiro de eventos de um participante. */
public final class Monitoring implements AutoCloseable {
    private final Map<Integer, HeartbeatMonitor> monitors = new LinkedHashMap<>();
    private final Path file;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat-check"); t.setDaemon(true); return t;
    });

    public Monitoring(String id, int[] partitions) {
        file = Paths.get("monitor-" + id + ".txt");
        for (int k : partitions) monitors.put(k,
                new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, this::log));
        timer.scheduleAtFixedRate(() -> {
            for (HeartbeatMonitor monitor : monitors.values()) {
                try { monitor.check(); } catch (Exception e) { System.err.println("Monitor: " + e); }
            }
        }, Wire.VERIFICACAO_MS, Wire.VERIFICACAO_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void log(String event) {
        String line = System.currentTimeMillis() + ";" + event;
        System.out.println(line);
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void received(int k, Batch.Header h) {
        String range = h.count == 0 ? "vazio" : (h.lastSeq - h.count + 1) + ".." + h.lastSeq;
        received(k, h, range);
    }

    public void received(int k, Batch batch) {
        String range = batch.records.isEmpty() ? "vazio"
                : batch.records.get(0).getSeq() + ".." + batch.records.get(batch.records.size() - 1).getSeq();
        received(k, batch.header(), range);
    }

    private void received(int k, Batch.Header h, String range) {
        // O produtor só conhece o cabeçalho. Réplicas/processador registam o intervalo real do payload.
        // onHeartbeat filtra apenas o sinal de vida; lotes antigos ainda podem conter dados em falta.
        monitors.get(k).onHeartbeat(h.inc, h.hb);
        log("RECEBIDO;p" + k + ";inc=" + h.inc + ";hb=" + h.hb + ";ultimoSeq=" + h.lastSeq
                + ";n=" + h.count + ";seq=" + range + ";endereco=" + h.address + ";porto=" + h.port);
    }

    public void warn(int k, String description) {
        if (monitors.get(k).state() == HeartbeatMonitor.State.SUSPEITO)
            System.out.println("AVISO: líder p" + k + " suspeito; " + description + " pode perder-se");
    }

    public void subscribeHeaders(IpfsPubSub ps) {
        for (int k : monitors.keySet()) {
            Thread t = new Thread(() -> {
                try {
                    ps.subscribe(Wire.topicData(k), msg -> {
                        try { received(k, Batch.header(msg)); }
                        catch (Exception e) { System.err.println("Cabeçalho rejeitado: " + e.getMessage()); }
                    });
                } catch (Exception e) { System.err.println("Monitor p" + k + ": " + e.getMessage()); }
            }, "monitor-p" + k);
            t.setDaemon(true); t.start();
        }
    }

    public static int[] all(int n) { return java.util.stream.IntStream.range(0, n).toArray(); }
    public void close() { timer.shutdownNow(); }
}
