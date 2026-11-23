package tpf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ChunkCryptoTest {

    private byte[] masterKey;
    private byte[] samplePlaintext;

    @BeforeEach
    void setUp() throws Exception {
        masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);

        List<Registo> registos = List.of(
                new Registo(1, 1000L, "Alice", "arquivo", "Primeira mensagem de teste"),
                new Registo(2, 2000L, "Bob", "arquivo", "Segunda mensagem com caracteres especiais: çãõ;123"),
                new Registo(3, 3000L, "Charlie", "arquivo", "Terceira mensagem longa...")
        );

        samplePlaintext = Chunk.serialize(registos);
    }

    @Test
    @DisplayName("Cenário 1: Cifrar e decifrar com sucesso (roundtrip)")
    void testCifrarEDecifrarComSucesso() throws Exception {
        ChunkCrypto.SealedResult sealed = ChunkCrypto.seal(samplePlaintext, masterKey);

        assertNotNull(sealed.chunkId());
        assertEquals(64, sealed.chunkId().length(), "O chunkId deve ter 64 caracteres hexadecimais (SHA-256)");
        assertEquals(1, sealed.keyVersion());
        assertEquals(32, sealed.salt().length);
        assertEquals(12, sealed.nonce().length);

        byte[] decrypted = ChunkCrypto.open(
                sealed.chunkId(),
                sealed.ciphertext(),
                sealed.keyVersion(),
                sealed.salt(),
                sealed.nonce(),
                masterKey
        );

        assertArrayEquals(samplePlaintext, decrypted, "Os bytes decifrados devem ser idênticos ao original com padding");

        List<Registo> reconstruidos = Chunk.deserialize(decrypted);
        assertEquals(3, reconstruidos.size());
        assertEquals("Alice", reconstruidos.get(0).getAutor());
        assertEquals("Segunda mensagem com caracteres especiais: çãõ;123", reconstruidos.get(1).getTexto());
        assertEquals(3, reconstruidos.get(2).getLsn());
    }

    @Test
    @DisplayName("Cenário 2: Byte alterado no texto cifrado deve falhar com exceção")
    void testByteAlteradoNoTextoCifrado() throws Exception {
        ChunkCrypto.SealedResult sealed = ChunkCrypto.seal(samplePlaintext, masterKey);

        byte[] adulterado = Arrays.copyOf(sealed.ciphertext(), sealed.ciphertext().length);
        adulterado[0] ^= 0xFF; // Inverte os bits do primeiro byte

        assertThrows(Exception.class, () -> {
            ChunkCrypto.open(
                    sealed.chunkId(),
                    adulterado,
                    sealed.keyVersion(),
                    sealed.salt(),
                    sealed.nonce(),
                    masterKey
            );
        }, "Um byte adulterado no ciphertext deve fazer o open falhar");
    }

    @Test
    @DisplayName("Cenário 3: Salt diferente deve falhar com exceção")
    void testSaltDiferente() throws Exception {
        ChunkCrypto.SealedResult sealed = ChunkCrypto.seal(samplePlaintext, masterKey);

        byte[] saltErrado = Arrays.copyOf(sealed.salt(), sealed.salt().length);
        saltErrado[0] ^= 0xFF;

        assertThrows(Exception.class, () -> {
            ChunkCrypto.open(
                    sealed.chunkId(),
                    sealed.ciphertext(),
                    sealed.keyVersion(),
                    saltErrado,
                    sealed.nonce(),
                    masterKey
            );
        }, "Salt diferente deriva DEK diferente e o GCM deve falhar com exceção");
    }

    @Test
    @DisplayName("Cenário 4: Nonce diferente deve falhar com exceção")
    void testNonceDiferente() throws Exception {
        ChunkCrypto.SealedResult sealed = ChunkCrypto.seal(samplePlaintext, masterKey);

        byte[] nonceErrado = Arrays.copyOf(sealed.nonce(), sealed.nonce().length);
        nonceErrado[0] ^= 0xFF;

        assertThrows(Exception.class, () -> {
            ChunkCrypto.open(
                    sealed.chunkId(),
                    sealed.ciphertext(),
                    sealed.keyVersion(),
                    sealed.salt(),
                    nonceErrado,
                    masterKey
            );
        }, "Nonce diferente altera o AAD e os parâmetros GCM, devendo falhar com exceção");
    }

    @Test
    @DisplayName("Cenário 5: KeyVersion diferente deve falhar com exceção")
    void testKeyVersionDiferente() throws Exception {
        ChunkCrypto.SealedResult sealed = ChunkCrypto.seal(samplePlaintext, masterKey);

        int keyVersionErrada = 2; // O sistema suporta apenas versão 1

        assertThrows(Exception.class, () -> {
            ChunkCrypto.open(
                    sealed.chunkId(),
                    sealed.ciphertext(),
                    keyVersionErrada,
                    sealed.salt(),
                    sealed.nonce(),
                    masterKey
            );
        }, "KeyVersion diferente de 1 deve ser rejeitada de imediato com exceção");
    }

    @Test
    @DisplayName("Cenário 6: MasterKey diferente deve falhar com exceção")
    void testMasterKeyDiferente() throws Exception {
        ChunkCrypto.SealedResult sealed = ChunkCrypto.seal(samplePlaintext, masterKey);

        byte[] outraMasterKey = new byte[32];
        new SecureRandom().nextBytes(outraMasterKey);

        assertThrows(Exception.class, () -> {
            ChunkCrypto.open(
                    sealed.chunkId(),
                    sealed.ciphertext(),
                    sealed.keyVersion(),
                    sealed.salt(),
                    sealed.nonce(),
                    outraMasterKey
            );
        }, "Uma chave mestra diferente deve falhar a autenticação GCM com exceção");
    }
}
