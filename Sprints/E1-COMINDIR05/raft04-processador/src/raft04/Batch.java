package raft04;

import java.util.ArrayList;
import java.util.List;

// LOTE;<termo>;<lider>;<endereço>;<porto>;<hb>;<ultimoSeq>;<ultimoTermo>;<n>
// seguido de n linhas seq;termo;sensor;temperatura;instante;L;V
public final class Batch {
    public final long term;
    public final int leader;
    public final String address;
    public final int port;
    public final long hb, lastSeq, lastTerm;
    public final List<SensorRecord> records;

    public Batch(long term, int leader, String address, int port, long hb, long lastSeq, long lastTerm,
                 List<SensorRecord> records) {
        new Header(term, leader, address, port, hb, lastSeq, lastTerm, records.size());
        this.term = term;
        this.leader = leader;
        this.address = address;
        this.port = port;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
        this.records = List.copyOf(records);
    }

    public String encode() {
        StringBuilder text = new StringBuilder("LOTE;" + term + ";" + leader + ";" + address + ";" + port
                + ";" + hb + ";" + lastSeq + ";" + lastTerm + ";" + records.size());
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
        return new Batch(h.term, h.leader, h.address, h.port, h.hb, h.lastSeq, h.lastTerm, records);
    }

    // o produtor só interpreta o cabeçalho; não desserializa os registos
    public static Header header(String msg) {
        int end = msg.indexOf('\n');
        String[] f = (end < 0 ? msg : msg.substring(0, end)).trim().split(";", -1);
        if (f.length != 9 || !f[0].equals("LOTE")) throw new IllegalArgumentException("Cabeçalho LOTE inválido");
        return new Header(Long.parseLong(f[1]), Integer.parseInt(f[2]), f[3], Integer.parseInt(f[4]),
                Long.parseLong(f[5]), Long.parseLong(f[6]), Long.parseLong(f[7]), Integer.parseInt(f[8]));
    }

    public Header header() {
        return new Header(term, leader, address, port, hb, lastSeq, lastTerm, records.size());
    }

    public static final class Header {
        public final long term, hb, lastSeq, lastTerm;
        public final int leader, port, count;
        public final String address;

        Header(long term, int leader, String address, int port, long hb, long lastSeq, long lastTerm, int count) {
            if (address.isBlank() || address.contains(";") || address.contains("\n") || address.contains("\r")
                    || term < 1 || leader < 1 || port < 1 || port > 65535 || hb < 1 || lastSeq < 0
                    || lastTerm < 0 || lastTerm > term || count < 0 || count > lastSeq)
                throw new IllegalArgumentException("Valores inválidos no cabeçalho LOTE");
            this.term = term; this.leader = leader; this.address = address; this.port = port;
            this.hb = hb; this.lastSeq = lastSeq; this.lastTerm = lastTerm; this.count = count;
        }
    }
}
