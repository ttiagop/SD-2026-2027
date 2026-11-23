package tpf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Chunk {
    private final List<Registo> registos = new ArrayList<>();
    private int estimatedPayloadSize = 4; // 4 bytes para o número total de registos (int)
    private boolean selado = false;
    private long primeiroRegistoInstante = 0;

    public Chunk() { }

    public synchronized boolean add(Registo r) {
        if (selado) {
            throw new IllegalStateException("Não é possível adicionar registos a um chunk já selado.");
        }
        if (registos.isEmpty()) {
            primeiroRegistoInstante = System.currentTimeMillis();
        }

        registos.add(r);

        // Estima o tamanho: lsn(8) + instante(8) + autorLen(4) + autorBytes + canalLen(4) + canalBytes + textoLen(4) + textoBytes
        int recordBytes = 8 + 8 + 4 + r.getAutor().getBytes(StandardCharsets.UTF_8).length
                + 4 + r.getCanal().getBytes(StandardCharsets.UTF_8).length
                + 4 + r.getTexto().getBytes(StandardCharsets.UTF_8).length;
        estimatedPayloadSize += recordBytes;

        return estimatedPayloadSize >= Wire.CHUNK_MAX_BYTES;
    }

    public synchronized boolean isEmpty() {
        return registos.isEmpty();
    }

    public synchronized int size() {
        return registos.size();
    }

    public synchronized boolean isSelado() {
        return selado;
    }

    public synchronized void marcarSelado() {
        this.selado = true;
    }

    public synchronized long getPrimeiroRegistoInstante() {
        return primeiroRegistoInstante;
    }

    public synchronized List<Registo> getRegistos() {
        return Collections.unmodifiableList(new ArrayList<>(registos));
    }

    public synchronized long getPrimeiroLsn() {
        if (registos.isEmpty()) return 0;
        return registos.get(0).getLsn();
    }

    public synchronized long getUltimoLsn() {
        if (registos.isEmpty()) return 0;
        return registos.get(registos.size() - 1).getLsn();
    }

    // Serialização com padding até múltiplo de PAD_BYTES
    public static byte[] serialize(List<Registo> lista) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(lista.size());
            for (Registo r : lista) {
                dos.writeLong(r.getLsn());
                dos.writeLong(r.getInstante());

                byte[] autorBytes = r.getAutor().getBytes(StandardCharsets.UTF_8);
                dos.writeInt(autorBytes.length);
                dos.write(autorBytes);

                byte[] canalBytes = r.getCanal().getBytes(StandardCharsets.UTF_8);
                dos.writeInt(canalBytes.length);
                dos.write(canalBytes);

                byte[] textoBytes = r.getTexto().getBytes(StandardCharsets.UTF_8);
                dos.writeInt(textoBytes.length);
                dos.write(textoBytes);
            }
            dos.flush();
        }

        byte[] raw = baos.toByteArray();
        int padBlock = Wire.PAD_BYTES;
        int rem = raw.length % padBlock;
        int paddedLen = (rem == 0) ? raw.length : raw.length + (padBlock - rem);

        byte[] padded = new byte[paddedLen];
        System.arraycopy(raw, 0, padded, 0, raw.length);
        return padded;
    }

    // Desserialização: lê N registos e ignora o padding restante
    public static List<Registo> deserialize(byte[] paddedBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(paddedBytes);
        try (DataInputStream dis = new DataInputStream(bais)) {
            int count = dis.readInt();
            List<Registo> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long lsn = dis.readLong();
                long instante = dis.readLong();

                int autorLen = dis.readInt();
                byte[] autorB = new byte[autorLen];
                dis.readFully(autorB);
                String autor = new String(autorB, StandardCharsets.UTF_8);

                int canalLen = dis.readInt();
                byte[] canalB = new byte[canalLen];
                dis.readFully(canalB);
                String canal = new String(canalB, StandardCharsets.UTF_8);

                int textoLen = dis.readInt();
                byte[] textoB = new byte[textoLen];
                dis.readFully(textoB);
                String texto = new String(textoB, StandardCharsets.UTF_8);

                out.add(new Registo(lsn, instante, autor, canal, texto));
            }
            return Collections.unmodifiableList(out);
        }
    }
}
