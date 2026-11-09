package raft04;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Monitores (um por partição) e ficheiro de eventos monitor-<id>.txt de um participante. */
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

    // instante;evento;p<k>;detalhe
    public synchronized void log(String event) {
        String line = System.currentTimeMillis() + ";" + event;
        System.out.println(line);
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void received(int k, Batch.Header h, String range) {
        monitors.get(k).onHeartbeat(h.term, h.leader, h.hb);
        log("RECEBIDO;p" + k + ";termo=" + h.term + ";lider=n" + h.leader + ";hb=" + h.hb
                + ";ultimoSeq=" + h.lastSeq + ";ultimoTermo=" + h.lastTerm + ";n=" + h.count + ";seq=" + range);
    }

    public void received(int k, Batch.Header h) {
        received(k, h, h.count == 0 ? "vazio" : (h.lastSeq - h.count + 1) + ".." + h.lastSeq);
    }

    public boolean suspect(int k) { return monitors.get(k).state() == HeartbeatMonitor.State.SUSPEITO; }

    // aviso antes de enviar para uma partição suspeita (a mensagem é enviada na mesma)
    public void warn(int k, String description) {
        if (suspect(k)) log("AVISO;p" + k + ";partição p" + k + " sem líder conhecido; " + description + " pode perder-se");
    }

    public void subscribeHeaders(IpfsPubSub ps) {
        for (int k : monitors.keySet()) {
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        ps.subscribe(Wire.topicData(k), msg -> {
                            try { received(k, Batch.header(msg)); }
                            catch (Exception e) { System.err.println("Cabeçalho rejeitado: " + e.getMessage()); }
                        });
                    } catch (Exception e) { System.err.println("Monitor p" + k + ": " + e.getMessage()); }
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            }, "monitor-p" + k);
            t.setDaemon(true); t.start();
        }
    }

    public static int[] all(int n) { return java.util.stream.IntStream.range(0, n).toArray(); }
    public void close() { timer.shutdownNow(); }
}
