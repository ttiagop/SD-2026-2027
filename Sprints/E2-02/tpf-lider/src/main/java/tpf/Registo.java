package tpf;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

public class Registo {
    private final long lsn;
    private final long instante;
    private final String autor;
    private final String canal;
    private final String texto;

    public Registo(long lsn, long instante, String autor, String canal, String texto) {
        this.lsn = lsn;
        this.instante = instante;
        this.autor = Objects.requireNonNull(autor, "autor");
        this.canal = Objects.requireNonNull(canal, "canal");
        this.texto = Objects.requireNonNull(texto, "texto");
    }

    public static Registo now(long lsn, String autor, String canal, String texto) {
        return new Registo(lsn, System.currentTimeMillis(), autor, canal, texto);
    }

    public long getLsn() {
        return lsn;
    }

    public long getInstante() {
        return instante;
    }

    public String getAutor() {
        return autor;
    }

    public String getCanal() {
        return canal;
    }

    public String getTexto() {
        return texto;
    }

    // Formato de registo durável: lsn;instante;autor;canal;texto em Base64
    public String toLine() {
        String textoBase64 = Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
        return lsn + ";" + instante + ";" + autor + ";" + canal + ";" + textoBase64;
    }

    public static Registo fromLine(String line) {
        String[] parts = line.split(";", 5);
        if (parts.length != 5) {
            throw new IllegalArgumentException("Linha de registo mal formada: " + line);
        }
        long lsn = Long.parseLong(parts[0]);
        long instante = Long.parseLong(parts[1]);
        String autor = parts[2];
        String canal = parts[3];
        byte[] textoBytes = Base64.getDecoder().decode(parts[4]);
        String texto = new String(textoBytes, StandardCharsets.UTF_8);

        return new Registo(lsn, instante, autor, canal, texto);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Registo registo)) return false;
        return lsn == registo.lsn && instante == registo.instante &&
                autor.equals(registo.autor) && canal.equals(registo.canal) && texto.equals(registo.texto);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lsn, instante, autor, canal, texto);
    }

    @Override
    public String toString() {
        return "Registo{" +
                "lsn=" + lsn +
                ", instante=" + instante +
                ", autor='" + autor + '\'' +
                ", canal='" + canal + '\'' +
                ", texto='" + texto + '\'' +
                '}';
    }
}
