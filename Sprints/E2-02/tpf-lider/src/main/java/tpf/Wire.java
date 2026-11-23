package tpf;

public final class Wire {
    public static final int CHUNK_MAX_BYTES = getInt("tpf.chunk_max_bytes", "tpf.chunkMaxBytes", 65536);
    public static final int PAD_BYTES = getInt("tpf.pad_bytes", "tpf.padBytes", 4096);
    public static final long SELAGEM_MS = getLong("tpf.selagem_ms", "tpf.selagemMs", 5000L);

    private Wire() { }

    private static int getInt(String key1, String key2, int defaultValue) {
        String val = System.getProperty(key1);
        if (val == null || val.isBlank()) {
            val = System.getProperty(key2);
        }
        if (val != null && !val.isBlank()) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) { }
        }
        return defaultValue;
    }

    private static long getLong(String key1, String key2, long defaultValue) {
        String val = System.getProperty(key1);
        if (val == null || val.isBlank()) {
            val = System.getProperty(key2);
        }
        if (val != null && !val.isBlank()) {
            try {
                return Long.parseLong(val.trim());
            } catch (NumberFormatException ignored) { }
        }
        return defaultValue;
    }
}
