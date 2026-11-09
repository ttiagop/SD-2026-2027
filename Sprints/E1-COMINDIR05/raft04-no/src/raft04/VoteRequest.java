package raft04;

// PEDIDO_VOTO;<termo>;<candidato>;<ultimoSeq>;<ultimoTermo>
public final class VoteRequest {
    public final long term;
    public final int candidate;
    public final long lastSeq, lastTerm;

    public VoteRequest(long term, int candidate, long lastSeq, long lastTerm) {
        if (term < 1 || candidate < 1 || lastSeq < 0 || lastTerm < 0)
            throw new IllegalArgumentException("PEDIDO_VOTO inválido");
        this.term = term;
        this.candidate = candidate;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
    }

    public String encode() {
        return "PEDIDO_VOTO;" + term + ";" + candidate + ";" + lastSeq + ";" + lastTerm;
    }

    public static VoteRequest decode(String msg) {
        String[] f = msg.trim().split(";", -1);
        if (f.length != 5 || !f[0].equals("PEDIDO_VOTO")) throw new IllegalArgumentException("PEDIDO_VOTO inválido: " + msg);
        return new VoteRequest(Long.parseLong(f[1]), Integer.parseInt(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4]));
    }
}
