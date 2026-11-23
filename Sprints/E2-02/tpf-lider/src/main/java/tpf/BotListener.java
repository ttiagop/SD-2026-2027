package tpf;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.nio.charset.StandardCharsets;

public class BotListener extends ListenerAdapter {
    private final ArquivoLog arquivoLog;

    public BotListener(ArquivoLog arquivoLog) {
        this.arquivoLog = arquivoLog;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Ignorar bots impede que o bot responda às suas próprias mensagens em ciclo.
        if (event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }
        if (!event.isFromGuild() || event.getChannelType() != ChannelType.TEXT) {
            return;
        }
        if (!"arquivo".equals(event.getChannel().getName())) {
            return;
        }

        String texto = event.getMessage().getContentRaw();
        if (texto.isBlank()) {
            return;
        }

        String autor = event.getAuthor().getName();
        String canal = event.getChannel().getName();
        try {
            Registo r = arquivoLog.registar(autor, canal, texto);
            event.getChannel().sendMessage("Registo gravado com LSN: " + r.getLsn()).queue();
        } catch (Exception e) {
            event.getChannel().sendMessage("Erro ao gravar registo: " + e.getMessage()).queue();
        }
    }

    // A JDA chama este método quando um utilizador executa um slash command.
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        // O nome recebido não inclui a barra: /estado chega como "estado".
        String comando = event.getName();

        // Esta verificação acontece antes de tratar qualquer comando de administração.
        boolean administrativo = "config".equals(comando) || "replica-token".equals(comando)
                || "replica-remover".equals(comando);
        if (administrativo && !temPapelAdmin(event)) {
            erro(event, "Este comando exige o papel admin-arquivo.");
            return;
        }

        if ("estado".equals(comando)) {
            // reply prepara a resposta; queue envia-a ao Discord.
            event.reply("/estado recebido — consulta do arquivo ainda não implementada.")
                    .queue();
        } else if ("registar".equals(comando)) {
            // Procuramos o argumento pelo mesmo nome usado no registo do comando.
            OptionMapping argumento = event.getOption("texto");
            if (argumento == null || argumento.getAsString().isBlank()) {
                event.reply("Erro: o texto a registar não pode estar vazio.")
                        .setEphemeral(true)
                        .queue();
                return;
            }

            String texto = argumento.getAsString();
            String autor = event.getUser().getName();
            String canal = event.getChannel().getName();

            try {
                Registo r = arquivoLog.registar(autor, canal, texto);
                String resposta = "Registo gravado com LSN: " + r.getLsn();

                // O Discord limita cada mensagem a 2000 caracteres; textos maiores vão em anexo.
                if (resposta.length() > 2000) {
                    event.reply("Registo gravado com LSN: " + r.getLsn() + " (texto em anexo)")
                            .addFiles(FileUpload.fromData(texto.getBytes(StandardCharsets.UTF_8), "texto.txt"))
                            .queue();
                } else {
                    event.reply(resposta).setAllowedMentions(java.util.Collections.emptyList()).queue();
                }
            } catch (Exception e) {
                event.reply("Erro ao gravar registo: " + e.getMessage()).setEphemeral(true).queue();
            }
        } else if ("recuperar".equals(comando)) {
            OptionMapping argumentoDe = event.getOption("de");
            OptionMapping argumentoAte = event.getOption("ate");
            if (argumentoDe == null || argumentoAte == null) {
                event.reply("Erro: indica os argumentos de e ate.")
                        .setEphemeral(true)
                        .queue();
                return;
            }

            long de = argumentoDe.getAsLong();
            long ate = argumentoAte.getAsLong();
            // O Discord valida o tipo inteiro; o bot valida o significado do intervalo.
            if (de < 1 || ate < de) {
                event.reply("Erro: o intervalo deve respeitar 1 <= de <= ate.")
                        .setEphemeral(true)
                        .queue();
                return;
            }

            event.reply("/recuperar recebido: de=" + de + ", ate=" + ate + " — ainda não implementado.")
                    .queue();
        } else if ("config".equals(comando)) {
            tratarConfig(event);
        } else if ("replica-token".equals(comando)) {
            event.reply("/replica-token recebido — geração de tokens ainda não implementada.")
                    .setEphemeral(true)
                    .queue();
        } else if ("replica-remover".equals(comando)) {
            tratarReplicaRemover(event);
        } else if ("rajada".equals(comando)) {
            tratarRajada(event);
        }
    }

    private boolean temPapelAdmin(SlashCommandInteractionEvent event) {
        if (event.getMember() == null) {
            return false;
        }
        // Ter permissões de administrador do servidor não substitui este papel.
        for (Role papel : event.getMember().getRoles()) {
            if ("admin-arquivo".equals(papel.getName())) {
                return true;
            }
        }
        return false;
    }

    private void tratarConfig(SlashCommandInteractionEvent event) {
        if (!"r".equals(event.getSubcommandName())) {
            erro(event, "Usa /config r com o argumento n.");
            return;
        }
        OptionMapping argumento = event.getOption("n");
        if (argumento == null || argumento.getAsLong() < 1) {
            erro(event, "O fator de replicação n deve ser um inteiro positivo.");
            return;
        }
        long n = argumento.getAsLong();
        event.reply("/config r recebido: n=" + n + " — ainda não implementado.").queue();
    }

    private void tratarReplicaRemover(SlashCommandInteractionEvent event) {
        OptionMapping argumento = event.getOption("id");
        if (argumento == null || argumento.getAsString().isBlank()) {
            erro(event, "O identificador da réplica não pode estar vazio.");
            return;
        }
        String resposta = "/replica-remover recebido: id=" + argumento.getAsString()
                + " — ainda não implementado.";
        if (resposta.length() > 2000) {
            event.reply("/replica-remover recebido — identificador em anexo; ainda não implementado.")
                    .addFiles(FileUpload.fromData(argumento.getAsString().getBytes(StandardCharsets.UTF_8), "id.txt"))
                    .queue();
        } else {
            event.reply(resposta).setAllowedMentions(java.util.Collections.emptyList()).queue();
        }
    }

    private void tratarRajada(SlashCommandInteractionEvent event) {
        OptionMapping argumentoN = event.getOption("n");
        OptionMapping argumentoIntervalo = event.getOption("intervaloms");
        if (argumentoN == null || argumentoIntervalo == null) {
            erro(event, "Indica n e intervaloms.");
            return;
        }
        long n = argumentoN.getAsLong();
        long intervaloMs = argumentoIntervalo.getAsLong();
        if (n < 1 || intervaloMs < 0) {
            erro(event, "n deve ser positivo e intervaloms não pode ser negativo.");
            return;
        }
        event.reply("A iniciar rajada de " + n + " registos sintéticos (intervalo=" + intervaloMs + " ms)...").queue();

        String autor = event.getUser().getName();
        String canal = event.getChannel().getName();

        new Thread(() -> {
            try {
                long lsnInicial = 0;
                long lsnFinal = 0;
                for (int i = 1; i <= n; i++) {
                    String texto = "Registo sintetico #" + i + " de " + n;
                    Registo r = arquivoLog.registar("rajada:" + autor, canal, texto);
                    if (i == 1) lsnInicial = r.getLsn();
                    if (i == n) lsnFinal = r.getLsn();
                    if (intervaloMs > 0 && i < n) {
                        try {
                            Thread.sleep(intervaloMs);
                        } catch (InterruptedException ignored) { }
                    }
                }
                event.getChannel().sendMessage("Rajada concluída com sucesso: " + n + " registos gravados (LSN "
                        + lsnInicial + ".." + lsnFinal + ").").queue();
            } catch (Exception e) {
                event.getChannel().sendMessage("Erro durante a execução da rajada: " + e.getMessage()).queue();
            }
        }, "rajada-worker").start();
    }

    private void erro(SlashCommandInteractionEvent event, String mensagem) {
        event.reply("Erro: " + mensagem).setEphemeral(true).queue();
    }
}
