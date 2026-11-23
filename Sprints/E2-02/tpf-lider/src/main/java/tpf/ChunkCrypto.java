package tpf;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class ChunkCrypto {
    public static final int KEY_VERSION = 1;
    public static final int SALT_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BITS = 128;
    public static final String HKDF_INFO = "tpf-chunk-v1";
    public static final String AAD_PREFIX = "tpf-v1";
    public static final String SELADOS_DIR = "selados";

    private ChunkCrypto() { }

    public record SealedResult(String chunkId, byte[] ciphertext, int keyVersion, byte[] salt, byte[] nonce) { }

    // Derivação de chave DEK via HKDF-SHA256 (RFC 5869)
    public static byte[] deriveDek(byte[] masterKey, byte[] salt) throws GeneralSecurityException {
        // 1. Extração: PRK = HMAC-SHA256(chave=salt, dados=masterKey)
        Mac hmacExtract = Mac.getInstance("HmacSHA256");
        hmacExtract.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmacExtract.doFinal(masterKey);

        // 2. Expansão de um único bloco (32 bytes): DEK = HMAC-SHA256(chave=PRK, dados=info || 0x01)
        Mac hmacExpand = Mac.getInstance("HmacSHA256");
        hmacExpand.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] infoBytes = HKDF_INFO.getBytes(StandardCharsets.UTF_8);
        byte[] expandInput = new byte[infoBytes.length + 1];
        System.arraycopy(infoBytes, 0, expandInput, 0, infoBytes.length);
        expandInput[infoBytes.length] = 0x01;

        return hmacExpand.doFinal(expandInput);
    }

    // Constrói os bytes do AAD: "tpf-v1" + keyVersion (4 bytes) + salt + nonce
    public static byte[] buildAad(int keyVersion, byte[] salt, byte[] nonce) {
        byte[] prefix = AAD_PREFIX.getBytes(StandardCharsets.UTF_8);
        byte[] aad = new byte[prefix.length + 4 + salt.length + nonce.length];
        ByteBuffer bb = ByteBuffer.wrap(aad);
        bb.put(prefix);
        bb.putInt(keyVersion);
        bb.put(salt);
        bb.put(nonce);
        return aad;
    }

    // Calcula o SHA-256 em hexadecimal
    public static String computeSha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 não disponível: " + e.getMessage(), e);
        }
    }

    // Cifra o chunk com padding
    public static SealedResult seal(byte[] plaintextPadded, byte[] masterKey) throws GeneralSecurityException {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(salt);
        random.nextBytes(nonce);

        byte[] dek = deriveDek(masterKey, salt);
        byte[] aad = buildAad(KEY_VERSION, salt, nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad);
        byte[] ciphertext = cipher.doFinal(plaintextPadded);

        String chunkId = computeSha256Hex(ciphertext);
        return new SealedResult(chunkId, ciphertext, KEY_VERSION, salt, nonce);
    }

    // Abre e decifra um chunk verificando: 1. Hash vs ID, 2. Versão da chave, 3. Autenticação GCM
    public static byte[] open(String expectedChunkId, byte[] ciphertext, int keyVersion, byte[] salt, byte[] nonce, byte[] masterKey)
            throws GeneralSecurityException {
        // 1. Verificação do hash contra o chunkId
        String calculatedId = computeSha256Hex(ciphertext);
        if (!calculatedId.equalsIgnoreCase(expectedChunkId)) {
            throw new SecurityException("Falha de integridade: hash calculado (" + calculatedId
                    + ") difere do chunkId esperado (" + expectedChunkId + ").");
        }

        // 2. Verificação da versão da chave
        if (keyVersion != KEY_VERSION) {
            throw new SecurityException("Versão da chave não suportada: " + keyVersion);
        }

        // 3. Autenticação e decifração GCM
        byte[] dek = deriveDek(masterKey, salt);
        byte[] aad = buildAad(keyVersion, salt, nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad);

        return cipher.doFinal(ciphertext);
    }

    // Grava selados/<chunkId>.bin e selados/<chunkId>.hdr
    public static void saveToDisk(SealedResult result, Path baseDir) throws IOException {
        Path selados = baseDir.resolve(SELADOS_DIR);
        if (!Files.exists(selados)) {
            Files.createDirectories(selados);
        }

        Path binFile = selados.resolve(result.chunkId() + ".bin");
        Path hdrFile = selados.resolve(result.chunkId() + ".hdr");

        Files.write(binFile, result.ciphertext());

        // Header: keyVersion;salt;nonce em Base64
        String saltB64 = Base64.getEncoder().encodeToString(result.salt());
        String nonceB64 = Base64.getEncoder().encodeToString(result.nonce());
        String hdrLine = result.keyVersion() + ";" + saltB64 + ";" + nonceB64 + System.lineSeparator();
        Files.writeString(hdrFile, hdrLine, StandardCharsets.UTF_8);
    }
}
