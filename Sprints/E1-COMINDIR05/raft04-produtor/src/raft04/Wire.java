package raft04;

import java.nio.file.Files;
import java.nio.file.Paths;

public final class Wire {
    public static final int T_TCP_MS = 5000;
    public static final int PORTO_BASE = 7000;
    public static final double LIMIAR_ALARME = 28.0;

    public static final long PERIODO_HB_MS = Long.getLong("raft04.periodo", 2000L);
    public static final int FATOR_TIMEOUT = Integer.getInteger("raft04.fator", 3);
    public static final int VERIFICACAO_MS = 200;
    public static final long ELEICAO_MIN_MS = Long.getLong("raft04.eleicaoMin", 3 * PERIODO_HB_MS);
    public static final long ELEICAO_MAX_MS = Long.getLong("raft04.eleicaoMax", 6 * PERIODO_HB_MS);
    public static final int GRUPO = 3;
    static {
        if (PERIODO_HB_MS <= 0 || FATOR_TIMEOUT <= 0 || ELEICAO_MIN_MS <= 0 || ELEICAO_MAX_MS < ELEICAO_MIN_MS)
            throw new IllegalArgumentException("Parâmetros de temporização inválidos");
    }

    private Wire() { }

    public static String topicIn(int k)       { return "raft04-in-p" + k; }
    public static String topicData(int k)     { return "raft04-p" + k; }
    public static String topicElection(int k) { return "raft04-eleicao-p" + k; }

    // porto TCP de recuperação do nó i (1..3) da partição k
    public static int nodePort(int k, int i)  { return PORTO_BASE + 10 * k + i; }

    // partição de rede simulada: enquanto existir o ficheiro isolar-p<k>-n<i>, o nó está isolado
    public static boolean isolated(int k, int i) {
        return Files.exists(Paths.get("isolar-p" + k + "-n" + i));
    }

    // IN;<sensor>;<temperatura>;<L>;<V>
    public static String in(String sensor, double temp, long l, VectorClock v) {
        return "IN;" + sensor + ";" + temp + ";" + l + ";" + v.toString();
    }

    public static String type(String msg) {
        String m = msg.trim();
        int i = m.indexOf(';');
        if (i <= 0) throw new IllegalArgumentException("Mensagem sem tipo: " + msg);
        return m.substring(0, i);
    }

    public static String body(String msg) {
        String m = msg.trim();
        return m.substring(m.indexOf(';') + 1);
    }
}
