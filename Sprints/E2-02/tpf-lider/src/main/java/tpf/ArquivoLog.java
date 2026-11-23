package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class ArquivoLog implements AutoCloseable {
    public static final String DEFAULT_LOG_FILE = "registos.log";
    public static final String DEFAULT_EVENTOS_FILE = "eventos-lider.txt";

    private final Path logPath;
    private final Path eventosPath;
    private final FileChannel logChannel;
    private long lastLsn = 0;
    private ChunkManager chunkManager;
    private final Object eventosLock = new Object();

    public ArquivoLog() throws IOException {
        this(Paths.get(DEFAULT_LOG_FILE), Paths.get(DEFAULT_EVENTOS_FILE));
    }

    public ArquivoLog(Path logPath, Path eventosPath) throws IOException {
        this.logPath = logPath;
        this.eventosPath = eventosPath;

        // Recuperar o último LSN no arranque a partir do ficheiro
        if (Files.exists(logPath)) {
            List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (!line.isBlank()) {
                    Registo r = Registo.fromLine(line);
                    if (r.getLsn() > lastLsn) {
                        lastLsn = r.getLsn();
                    }
                }
            }
            System.out.println("ArquivoLog inicializado. Último LSN recuperado: " + lastLsn);
        } else {
            System.out.println("ArquivoLog inicializado com ficheiro novo (LSN inicial: 0).");
        }

        // Abre o FileChannel para escritas no fim do ficheiro
        this.logChannel = FileChannel.open(logPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    // Método sincronizado por onde passam todas as escritas (/registar, #arquivo, /rajada)
    public synchronized Registo registar(String autor, String canal, String texto) throws IOException {
        long lsn = ++lastLsn;
        long instante = System.currentTimeMillis();
        Registo registo = new Registo(lsn, instante, autor, canal, texto);

        // 1. Escrever no registos.log
        byte[] lineBytes = (registo.toLine() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(lineBytes);
        while (buffer.hasRemaining()) {
            logChannel.write(buffer);
        }

        // 2. FORÇAR fsync aos metadados e dados antes de responder
        logChannel.force(true);

        // 3. Registar o evento REGISTO em eventos-lider.txt (instante;evento;detalhe)
        registarEvento("REGISTO", "lsn=" + lsn + ",autor=" + autor + ",canal=" + canal);

        // 4. Encaminhar para o chunk aberto em memória
        if (chunkManager != null) {
            chunkManager.addRegisto(registo);
        }

        return registo;
    }

    public synchronized void setChunkManager(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
    }

    public void registarEvento(String evento, String detalhe) {
        synchronized (eventosLock) {
            long instante = System.currentTimeMillis();
            String linhaEvento = instante + ";" + evento + ";" + detalhe + System.lineSeparator();
            try {
                Files.writeString(eventosPath, linhaEvento, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("Erro ao gravar em " + eventosPath + ": " + e.getMessage());
            }
        }
    }

    public synchronized long getLastLsn() {
        return lastLsn;
    }

    @Override
    public synchronized void close() throws IOException {
        if (logChannel != null && logChannel.isOpen()) {
            logChannel.close();
        }
    }
}
