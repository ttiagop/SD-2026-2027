package rep03;

import java.util.Locale;

public class SensorRecord {
    private final long seq;
    private final String sensor;
    private final double temp;
    private final long ts;
    private final long lamport;
    private final VectorClock vector;

    public SensorRecord(long seq, String sensor, double temp, long ts, long lamport, VectorClock vector) {
        this.seq = seq;
        this.sensor = sensor;
        this.temp = temp;
        this.ts = ts;
        this.lamport = lamport;
        this.vector = vector;
    }

    public static SensorRecord now(long seq, String sensor, double temp, long lamport, VectorClock vector) {
        return new SensorRecord(seq, sensor, temp, System.currentTimeMillis(), lamport, vector);
    }

    public long getSeq() { return seq; }
    public String getSensor() { return sensor; }
    public double getTemp() { return temp; }
    public long getTs() { return ts; }
    public long getLamport() { return lamport; }
    public VectorClock getVector() { return vector; }

    public String toLine() {
        return String.format(Locale.US, "%d;%s;%.1f;%d;%d;%s",
                seq, sensor, temp, ts, lamport, vector.toString());
    }

    public static SensorRecord fromLine(String line) {
        String[] parts = line.trim().split(";");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Linha mal formatada (esperados 6 campos): " + line);
        }
        int n = parts[5].trim().split(",").length;
        return fromLine(line, n);
    }

    public static SensorRecord fromLine(String line, int n) {
        String[] parts = line.trim().split(";");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Linha mal formatada (esperados 6 campos): " + line);
        }
        long seq = Long.parseLong(parts[0]);
        String sensor = parts[1];
        double temp = Double.parseDouble(parts[2]);
        long ts = Long.parseLong(parts[3]);
        long lamport = Long.parseLong(parts[4]);
        VectorClock vector = VectorClock.parse(parts[5], n);
        return new SensorRecord(seq, sensor, temp, ts, lamport, vector);
    }
}