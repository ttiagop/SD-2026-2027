# E2-01 — Líder Discord

Java 17+ e JDA 6.7.0. Este sprint regista os comandos e envia respostas
provisórias; não grava registos, gera tokens de réplica ou implementa cifra,
replicação e recuperação. O Maven Wrapper descarrega o Maven e as dependências
na primeira execução (requer acesso à Internet).

## Configuração do Discord

1. No Developer Portal, criar uma aplicação e ativar **Message Content Intent**
   na página **Bot**. Gerar o token e guardá-lo fora do repositório.
2. Criar um servidor de teste, o canal de texto `arquivo` e o papel
   `admin-arquivo`; atribuir o papel aos membros que vão administrar o arquivo.
3. Convidar o bot com os scopes `bot` e `applications.commands` e as permissões
   **View Channels** e **Send Messages**. Para respostas com texto muito longo
   em anexo, permitir também **Attach Files** no canal.
4. Ativar o modo de programador no Discord e copiar o ID do servidor pelo
   menu de contexto do servidor (não o ID da aplicação ou do canal).

## Arranque no terminal Fish

Abrir um terminal na pasta deste README. Executar a primeira linha, colar o
token no pedido que aparece e carregar Enter. Depois executar as restantes
linhas; introduzir o ID quando for pedido.

```fish
read --silent --prompt-str "Token: " DISCORD_TOKEN
set -gx DISCORD_TOKEN "$DISCORD_TOKEN"
read --prompt-str "ID do servidor: " DISCORD_GUILD_ID
set -gx DISCORD_GUILD_ID "$DISCORD_GUILD_ID"
./mvnw compile exec:java
```

Esperar por `Bot pronto.` e manter o processo em execução. Parar com Ctrl+C.
Num terminal novo, definir novamente as variáveis. O Java lê o ambiente com
`System.getenv`; não carrega ficheiros `.env` automaticamente. O `.gitignore`
exclui `.env`, `.env.*`, `token.txt`, `discord-token.txt` e `master.key`.

## Comandos e validação

| Comando | Argumentos / regra |
| --- | --- |
| `/estado` | Sem argumentos |
| `/registar` | `texto` obrigatório, não vazio |
| `/recuperar` | Inteiros `de` e `ate`, com `1 <= de <= ate` |
| `/config r` | Inteiro `n > 0`; requer `admin-arquivo` |
| `/replica-token` | Sem argumentos; requer `admin-arquivo` |
| `/replica-remover` | `id` obrigatório, não vazio; requer `admin-arquivo` |
| `/rajada` | Inteiros `n > 0` e `intervaloms >= 0`; requer `admin-arquivo` |

No Discord, `intervaloMs` é registado como `intervaloms`, porque os nomes de
opções usam minúsculas. Tipos numéricos são verificados pelo Discord;
intervalos, valores e papéis são verificados no listener. Ser administrador
do servidor não dispensa ter o papel `admin-arquivo`.

`LeaderBot` configura a ligação e regista comandos no servidor indicado.
`BotListener` recebe execuções, valida argumentos e permissões e responde.
Também deteta texto de utilizadores em `#arquivo`, ignorando bots, webhooks,
mensagens privadas e outros canais. Não guarda o conteúdo em disco.

## Verificação manual

Confirmar os sete comandos nas sugestões. Testar respostas válidas, `/config r
n:0`, `/recuperar de:20 ate:10` e `/rajada n:0 intervaloms:100`. Confirmar que
`abc` é recusado num argumento inteiro. Executar os quatro comandos de
administração sem o papel e confirmar a recusa. Enviar texto em `#arquivo`
(deve responder) e noutro canal (deve ignorar); confirmar que não responde a
mensagens de bots.

## Formato binário do chunk

Os registos agregados num chunk aberto são serializados em binário com o seguinte formato:
1. `int` (4 bytes, Big-Endian): quantidade total de registos contidos no chunk.
2. Para cada registo:
   - `long` (8 bytes, Big-Endian): LSN do registo;
   - `long` (8 bytes, Big-Endian): instante em milissegundos (timestamp);
   - `int` (4 bytes, Big-Endian): comprimento em bytes do autor em UTF-8;
   - `byte[]`: bytes do identificador do autor;
   - `int` (4 bytes, Big-Endian): comprimento em bytes do canal em UTF-8;
   - `byte[]`: bytes do nome do canal;
   - `int` (4 bytes, Big-Endian): comprimento em bytes do texto em UTF-8;
   - `byte[]`: bytes do texto da mensagem.
3. Preenchimento (padding): bytes `0x00` adicionados no final do payload até perfazer um múltiplo exato de `PAD_BYTES` (4096 bytes).

## Formato do ficheiro de cabeçalho (.hdr)

Cada chunk selado e cifrado em `selados/<chunkId>.bin` tem um ficheiro de metadados correspondente em `selados/<chunkId>.hdr`, em texto simples UTF-8 com uma única linha:
```text
keyVersion;salt;nonce
```
Onde:
- `keyVersion`: inteiro representando a versão da chave (atualmente `1`);
- `salt`: 32 bytes do salt utilizado na derivação HKDF-SHA256, codificados em Base64;
- `nonce`: 12 bytes do nonce (IV) utilizado na cifra AES-256-GCM, codificados em Base64.

