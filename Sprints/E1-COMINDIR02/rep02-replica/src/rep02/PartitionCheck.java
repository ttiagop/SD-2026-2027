package rep02;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class PartitionCheck {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: PartitionCheck <N> [pasta]");
            System.exit(1);
        }

        int n = Integer.parseInt(args[0]);
        String folderPath = args.length > 1 ? args[1] : ".";
        Path dir = Paths.get(folderPath);

        if (!Files.isDirectory(dir)) {
            System.err.println("A pasta especificada não existe: " + folderPath);
            System.exit(1);
        }

        Partitioner partitioner = new Partitioner(n);
        System.out.println("==================================================");
        System.out.println("   RELATÓRIO DE VALIDAÇÃO DE PARTIÇÕES (N=" + n + ")");
        System.out.println("   Pasta: " + dir.toAbsolutePath().normalize());
        System.out.println("==================================================\n");

        long totalRegistosSistema = 0;
        boolean tudoConforme = true;

        for (int k = 0; k < n; k++) {
            System.out.println("--- Partição " + k + " ---");
            Path liderPath = dir.resolve("lider-p" + k + ".txt");

            if (!Files.exists(liderPath)) {
                System.out.println("  [AVISO] Ficheiro do líder não encontrado: lider-p" + k + ".txt");
                continue;
            }

            List<String> liderLines;
            try {
                liderLines = Files.readAllLines(liderPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("  [ERRO] Ao ler ficheiro do líder: " + e.getMessage());
                continue;
            }

            // Filtrar linhas vazias
            List<SensorRecord> liderRecords = new ArrayList<>();
            for (String l : liderLines) {
                if (!l.isBlank()) {
                    liderRecords.add(SensorRecord.fromLine(l));
                }
            }

            int nLider = liderRecords.size();
            totalRegistosSistema += nLider;
            System.out.println("  Registos no Líder: " + nLider);

            // 1. Verificar se todos os sensores pertencem à partição k
            boolean sensoresCorretos = true;
            Set<String> sensoresDistintos = new HashSet<>();
            long esperadoSeq = 1;
            boolean sequenciaCorreta = true;

            for (SensorRecord r : liderRecords) {
                sensoresDistintos.add(r.getSensorId());
                if (partitioner.of(r.getSensorId()) != k) {
                    System.out.println("  [FALHA] Sensor " + r.getSensorId() + " (seq " + r.getSeq() + ") não pertence à partição " + k);
                    sensoresCorretos = false;
                    tudoConforme = false;
                }
                if (r.getSeq() != esperadoSeq) {
                    System.out.println("  [FALHA] Salto de sequência no líder: esperado " + esperadoSeq + ", obtido " + r.getSeq());
                    sequenciaCorreta = false;
                    tudoConforme = false;
                }
                esperadoSeq++;
            }

            if (sensoresCorretos) {
                System.out.println("  Verificação de chaves: [OK] todos os sensores pertencem a p" + k);
            }
            if (sequenciaCorreta) {
                System.out.println("  Sequência do líder:   [OK] começa em 1, sem saltos nem repetições");
            }
            System.out.println("  Sensores distintos:    " + sensoresDistintos.size() + " " + sensoresDistintos);

            // 2. Verificar réplicas que guardam a partição k
            List<Path> replicaFiles = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "replica-*-p" + k + ".txt")) {
                for (Path p : stream) {
                    replicaFiles.add(p);
                }
            } catch (IOException e) {
                System.err.println("  [ERRO] Ao listar réplicas: " + e.getMessage());
            }

            System.out.println("  Réplicas encontradas:  " + replicaFiles.size() + " (esperado R=2)");
            if (replicaFiles.size() != 2) {
                System.out.println("  [AVISO] Fator de replicação diferente de 2!");
            }

            for (Path repPath : replicaFiles) {
                String fileName = repPath.getFileName().toString();
                try {
                    List<String> repLines = Files.readAllLines(repPath, StandardCharsets.UTF_8);
                    List<SensorRecord> repRecords = new ArrayList<>();
                    for (String l : repLines) {
                        if (!l.isBlank()) {
                            repRecords.add(SensorRecord.fromLine(l));
                        }
                    }

                    int nRep = repRecords.size();
                    int emFalta = nLider - nRep;

                    // Verificar se é igual ou prefixo
                    boolean ehPrefixo = true;
                    if (nRep > nLider) {
                        ehPrefixo = false;
                    } else {
                        for (int i = 0; i < nRep; i++) {
                            if (repRecords.get(i).getSeq() != liderRecords.get(i).getSeq()) {
                                ehPrefixo = false;
                                break;
                            }
                        }
                    }

                    if (nRep == nLider && ehPrefixo) {
                        System.out.println("    " + fileName + ": IGUAL ao líder (" + nRep + " registos, 0 em falta) [OK]");
                    } else if (ehPrefixo) {
                        System.out.println("    " + fileName + ": PREFIXO do líder (" + nRep + "/" + nLider + " registos, faltam " + emFalta + ")");
                    } else {
                        System.out.println("    " + fileName + ": [DIVERGIU] registos não coincidem com o líder!");
                        tudoConforme = false;
                    }
                } catch (IOException e) {
                    System.err.println("    Erro ao ler " + fileName + ": " + e.getMessage());
                }
            }
            System.out.println();
        }

        System.out.println("==================================================");
        System.out.println("Total de registos no sistema (soma dos líderes): " + totalRegistosSistema);
        System.out.println("Estado Global: " + (tudoConforme ? "[CONFORME - SUCESSO]" : "[INCONSISTÊNCIAS DETETADAS]"));
        System.out.println("==================================================");
    }
}
