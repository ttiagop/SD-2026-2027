package tpf;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

public class LeaderBot {
    public static void main(String[] args) {
        String token = System.getenv("DISCORD_TOKEN");
        String guildId = System.getenv("DISCORD_GUILD_ID");

        if (token == null || token.isBlank()) {
            System.err.println("Falta DISCORD_TOKEN. Define-a no mesmo terminal onde arrancas o programa.");
            return;
        }

        if (guildId == null || guildId.isBlank()) {
            System.err.println("Falta DISCORD_GUILD_ID. Define-a no mesmo terminal onde arrancas o programa.");
            return;
        }

        System.out.println("Configuração carregada: DISCORD_TOKEN e DISCORD_GUILD_ID estão definidas.");

        try {
            JDA jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new BotListener())
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
}
