package rep04;

import java.util.ArrayList;
import java.util.List;

public final class Batch {
    public final String address;
    public final int port;
    public final long inc, hb, lastSeq;
    public final List<SensorRecord> records;

    public Batch(String address, int port, long inc, long hb, long lastSeq, List<SensorRecord> records) {
        new Header(address, port, inc, hb, lastSeq, records.size());
        this.address = address;
        this.port = port;
        this.inc = inc;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.records = List.copyOf(records);
    }

    public String encode() {
        StringBuilder text = new StringBuilder("LOTE;" + address + ";" + port + ";" + inc
                + ";" + hb + ";" + lastSeq + ";" + records.size());
        for (SensorRecord r : records) text.append('\n').append(r.toLine());
        return text.toString();
    }

    public static Batch decode(String msg, int n) {
        String[] lines = msg.split("\\R", -1);
        Header h = header(msg);
        int count = lines.length;
        if (count > 1 && lines[count - 1].isEmpty()) count--;
        if (count - 1 != h.count) throw new IllegalArgumentException("Número de registos diferente do cabeçalho");
        List<SensorRecord> records = new ArrayList<>();
        for (int i = 1; i < count; i++) records.add(SensorRecord.fromLine(lines[i], n));
        return new Batch(h.address, h.port, h.inc, h.hb, h.lastSeq, records);
    }

    // O produtor só interpreta o cabeçalho; não desserializa os registos.
    public static Header header(String msg) {
        int end = msg.indexOf('\n');
        String[] f = (end < 0 ? msg : msg.substring(0, end)).trim().split(";", -1);
        if (f.length != 7 || !f[0].equals("LOTE")) throw new IllegalArgumentException("Cabeçalho LOTE inválido");
        return new Header(f[1], Integer.parseInt(f[2]), Long.parseLong(f[3]),
                Long.parseLong(f[4]), Long.parseLong(f[5]), Integer.parseInt(f[6]));
    }

    public Header header() { return new Header(address, port, inc, hb, lastSeq, records.size()); }

    public static final class Header {
        public final String address;
        public final int port, count;
        public final long inc, hb, lastSeq;
        Header(String address, int port, long inc, long hb, long lastSeq, int count) {
            if (address.isBlank() || address.contains(";") || address.contains("\n") || address.contains("\r")
                    || port < 1 || port > 65535 || inc < 0 || hb < 1 || lastSeq < 0 || count < 0 || count > lastSeq)
                throw new IllegalArgumentException("Valores inválidos no cabeçalho LOTE");
            this.address = address; this.port = port; this.inc = inc;
            this.hb = hb; this.lastSeq = lastSeq; this.count = count;
        }
    }
}
