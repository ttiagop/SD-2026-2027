package raft04;

import java.util.Locale;

// linha de registo: seq;termo;sensor;temperatura;instante;L;V
public class SensorRecord {
    private final long seq;
    private final long term;
    private final String sensor;
    private final double temp;
    private final long ts;
    private final long lamport;
    private final VectorClock vector;

    public SensorRecord(long seq, long term, String sensor, double temp, long ts, long lamport, VectorClock vector) {
        this.seq = seq;
        this.term = term;
        this.sensor = sensor;
        this.temp = temp;
        this.ts = ts;
        this.lamport = lamport;
        this.vector = vector;
    }

    public static SensorRecord now(long seq, long term, String sensor, double temp, long lamport, VectorClock vector) {
        return new SensorRecord(seq, term, sensor, temp, System.currentTimeMillis(), lamport, vector);
    }

    public long getSeq() { return seq; }
    public long getTerm() { return term; }
    public String getSensor() { return sensor; }
    public double getTemp() { return temp; }
    public long getTs() { return ts; }
    public long getLamport() { return lamport; }
    public VectorClock getVector() { return vector; }

    public String toLine() {
        return String.format(Locale.US, "%d;%d;%s;%.1f;%d;%d;%s",
                seq, term, sensor, temp, ts, lamport, vector.toString());
    }

    public static SensorRecord fromLine(String line) {
        String[] parts = line.trim().split(";");
        if (parts.length != 7) throw new IllegalArgumentException("Linha mal formatada (esperados 7 campos): " + line);
        return fromLine(line, parts[6].trim().split(",").length);
    }

    public static SensorRecord fromLine(String line, int n) {
        String[] p = line.trim().split(";");
        if (p.length != 7) throw new IllegalArgumentException("Linha mal formatada (esperados 7 campos): " + line);
        return new SensorRecord(Long.parseLong(p[0]), Long.parseLong(p[1]), p[2], Double.parseDouble(p[3]),
                Long.parseLong(p[4]), Long.parseLong(p[5]), VectorClock.parse(p[6], n));
    }
}
