package tpf;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

public class LeaderBot {
    public static void main(String[] args) {
        java.util.Map<String, String> envFromFile = loadDotEnv();

        String token = System.getenv("DISCORD_TOKEN");
        if (token == null || token.isBlank()) {
            token = envFromFile.get("DISCORD_TOKEN");
        }

        String guildId = System.getenv("DISCORD_GUILD_ID");
        if (guildId == null || guildId.isBlank()) {
            guildId = envFromFile.get("DISCORD_GUILD_ID");
        }

        if (token == null || token.isBlank()) {
            System.err.println("Falta DISCORD_TOKEN. Define-a no ambiente ou no ficheiro .env.");
            return;
        }

        if (guildId == null || guildId.isBlank()) {
            System.err.println("Falta DISCORD_GUILD_ID. Define-a no ambiente ou no ficheiro .env.");
            return;
        }

        System.out.println("Configuração carregada: DISCORD_TOKEN e DISCORD_GUILD_ID estão definidas.");

        byte[] masterKey;
        try {
            masterKey = MasterKey.carregarOuGerar();
        } catch (Exception e) {
            System.err.println("Erro fatal na chave mestra (master.key): " + e.getMessage());
            return;
        }

        ArquivoLog arquivoLog;
        try {
            arquivoLog = new ArquivoLog();
            ChunkManager chunkManager = new ChunkManager(masterKey, arquivoLog);
            arquivoLog.setChunkManager(chunkManager);
        } catch (Exception e) {
            System.err.println("Erro ao inicializar ArquivoLog / ChunkManager: " + e.getMessage());
            return;
        }

        try {
            JDA jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new BotListener(arquivoLog))
                    .build();
            jda.awaitReady();
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                System.err.println("Não foi possível encontrar o servidor com o ID fornecido: " +guildId);
                jda.shutdown();
                return;
            }
            guild.upsertCommand("estado", "Consultar o estado do arquivo").queue();
            // true indica que o argumento "texto" é obrigatório no Discord.
            guild.upsertCommand("registar", "Registar um texto no arquivo")
                    .addOption(OptionType.STRING, "texto", "Texto a registar", true)
                    .queue();
            guild.upsertCommand("recuperar", "Recuperar um intervalo de registos")
                    .addOption(OptionType.INTEGER, "de", "Primeiro LSN do intervalo", true)
                    .addOption(OptionType.INTEGER, "ate", "Último LSN do intervalo", true)
                    .queue();
            // /config r n:3 tem comando "config", subcomando "r" e argumento "n".
            guild.upsertCommand("config", "Configurar o arquivo")
                    .addSubcommands(new SubcommandData("r", "Definir o fator de replicação")
                            .addOption(OptionType.INTEGER, "n", "Número de cópias por chunk", true))
                    .queue();
            guild.upsertCommand("replica-token", "Pedir um token de registo de réplica").queue();
            guild.upsertCommand("replica-remover", "Remover uma réplica")
                    .addOption(OptionType.STRING, "id", "Identificador da réplica", true)
                    .queue();
            guild.upsertCommand("rajada", "Injetar registos sintéticos")
                    .addOption(OptionType.INTEGER, "n", "Número de registos", true)
                    // Os nomes das opções no Discord são escritos em minúsculas.
                    .addOption(OptionType.INTEGER, "intervaloms", "Intervalo entre registos em milissegundos", true)
                    .queue();
            System.out.println("Bot pronto.");
        } catch (Exception e) {
            System.err.println("Erro ao criar o bot: " + e.getMessage());
        }
    }

    private static java.util.Map<String, String> loadDotEnv() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        java.nio.file.Path envPath = java.nio.file.Paths.get(".env");
        if (java.nio.file.Files.exists(envPath)) {
            try {
                for (String line : java.nio.file.Files.readAllLines(envPath, java.nio.charset.StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String val = line.substring(eq + 1).trim();
                        if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                            val = val.substring(1, val.length() - 1);
                        }
                        map.put(key, val);
                    }
                }
            } catch (java.io.IOException e) {
                System.err.println("Aviso: não foi possível ler o ficheiro .env: " + e.getMessage());
            }
        }
        return map;
    }
}
