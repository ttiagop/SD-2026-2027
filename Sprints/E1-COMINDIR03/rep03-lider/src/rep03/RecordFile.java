package rep03;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RecordFile {
    private final String filename;

    public RecordFile(String filename) {
        this.filename = filename;
    }

    public synchronized void append(SensorRecord r) throws IOException {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(filename, true), StandardCharsets.UTF_8))) {
            pw.println(r.toLine());
        }
    }

    public synchronized long lastSeq() throws IOException {
        File f = new File(filename);
        if (!f.exists() || f.length() == 0) return 0;

        String lastLine = null;
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) lastLine = line;
            }
        }
        if (lastLine == null) return 0;
        return Long.parseLong(lastLine.split(";")[0]);
    }

    // Devolve o último registo completo ou null se o ficheiro estiver vazio
    public synchronized SensorRecord last() throws IOException {
        File f = new File(filename);
        if (!f.exists() || f.length() == 0) return null;

        String lastLine = null;
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) lastLine = line;
            }
        }
        return lastLine != null ? SensorRecord.fromLine(lastLine) : null;
    }

    public synchronized SensorRecord last(int n) throws IOException {
        File f = new File(filename);
        if (!f.exists() || f.length() == 0) return null;

        String lastLine = null;
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) lastLine = line;
            }
        }
        return lastLine != null ? SensorRecord.fromLine(lastLine, n) : null;
    }

    public synchronized List<SensorRecord> range(long de, long ate) throws IOException {
        List<SensorRecord> res = new ArrayList<>();
        File f = new File(filename);
        if (!f.exists()) return res;

        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                SensorRecord r = SensorRecord.fromLine(line);
                if (r.getSeq() >= de && r.getSeq() <= ate) {
                    res.add(r);
                }
            }
        }
        return res;
    }

    public synchronized List<SensorRecord> range(long de, long ate, int n) throws IOException {
        List<SensorRecord> res = new ArrayList<>();
        File f = new File(filename);
        if (!f.exists()) return res;

        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                SensorRecord r = SensorRecord.fromLine(line, n);
                if (r.getSeq() >= de && r.getSeq() <= ate) {
                    res.add(r);
                }
            }
        }
        return res;
    }
}
