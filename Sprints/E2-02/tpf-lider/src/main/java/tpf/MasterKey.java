package tpf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;

public final class MasterKey {
    public static final String DEFAULT_FILE_NAME = "master.key";
    public static final int KEY_LENGTH_BYTES = 32;

    private MasterKey() { }

    public static byte[] carregarOuGerar() throws IOException {
        return carregarOuGerar(Paths.get(DEFAULT_FILE_NAME));
    }

    public static byte[] carregarOuGerar(Path caminho) throws IOException {
        if (!Files.exists(caminho)) {
            byte[] novaChave = new byte[KEY_LENGTH_BYTES];
            new SecureRandom().nextBytes(novaChave);
            Files.write(caminho, novaChave);

            // Ajustar permissões para apenas o dono ler/escrever
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                );
                Files.setPosixFilePermissions(caminho, perms);
            } catch (UnsupportedOperationException ignored) {
                // Em sistemas não-POSIX (como Windows NTFS)
                File f = caminho.toFile();
                f.setReadable(false, false);
                f.setReadable(true, true);
                f.setWritable(false, false);
                f.setWritable(true, true);
            }

            System.out.println("Nova chave mestra (" + KEY_LENGTH_BYTES + " bytes) gerada em: " + caminho.toAbsolutePath());
            return novaChave;
        }

        byte[] chaveExistente = Files.readAllBytes(caminho);
        if (chaveExistente.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("master.key invalida: esperado " + KEY_LENGTH_BYTES
                    + " bytes, mas foram encontrados " + chaveExistente.length + " bytes.");
        }

        return chaveExistente;
    }
}
