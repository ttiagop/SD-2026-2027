package tpf;

import java.io.IOException;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ChunkManager implements AutoCloseable {
    private final byte[] masterKey;
    private final ArquivoLog arquivoLog;
    private final ScheduledExecutorService scheduler;

    private Chunk currentChunk = new Chunk();
    private ScheduledFuture<?> timerTask = null;

    public ChunkManager(byte[] masterKey, ArquivoLog arquivoLog) {
        this.masterKey = masterKey;
        this.arquivoLog = arquivoLog;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chunk-timer");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void addRegisto(Registo r) {
        if (currentChunk.isEmpty()) {
            agendarSelagemPorTempo();
        }

        boolean atingiuLimite = currentChunk.add(r);
        if (atingiuLimite) {
            System.out.println("Chunk atingiu o limite de tamanho (" + Wire.CHUNK_MAX_BYTES + " bytes). A selar imediatamente...");
            selar();
        }
    }

    private synchronized void agendarSelagemPorTempo() {
        if (timerTask != null && !timerTask.isDone()) {
            timerTask.cancel(false);
        }
        timerTask = scheduler.schedule(() -> {
            synchronized (ChunkManager.this) {
                if (!currentChunk.isEmpty()) {
                    System.out.println("Tempo limite de selagem atingido (" + Wire.SELAGEM_MS + " ms). A selar chunk...");
                    selar();
                }
            }
        }, Wire.SELAGEM_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void selar() {
        if (timerTask != null) {
            timerTask.cancel(false);
            timerTask = null;
        }

        if (currentChunk.isEmpty()) {
            return;
        }

        Chunk aFechar = currentChunk;
        currentChunk = new Chunk();
        aFechar.marcarSelado();

        try {
            byte[] padded = Chunk.serialize(aFechar.getRegistos());
            ChunkCrypto.SealedResult result = ChunkCrypto.seal(padded, masterKey);
            ChunkCrypto.saveToDisk(result, Paths.get("."));

            long de = aFechar.getPrimeiroLsn();
            long ate = aFechar.getUltimoLsn();
            int tamanho = result.ciphertext().length;

            arquivoLog.registarEvento("SELADO", "chunkId=" + result.chunkId() + ",de=" + de + ",ate=" + ate + ",tamanho=" + tamanho);
            System.out.println("Chunk selado com sucesso: " + result.chunkId() + " [LSN " + de + ".." + ate + "] (" + tamanho + " bytes)");
        } catch (IOException | GeneralSecurityException e) {
            System.err.println("Erro ao selar/cifrar chunk: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public synchronized void close() {
        if (!currentChunk.isEmpty()) {
            selar();
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
