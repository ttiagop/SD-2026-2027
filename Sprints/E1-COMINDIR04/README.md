# COMINDIR04 — Heartbeats e envio em lote

Continuação da COMINDIR03, em quatro projetos independentes, pacote `rep04`.
Enunciado: https://github.com/cunhaestgv/SD-2026-2027/issues/17

Medições realizadas com IPFS real: [resultados e reflexão](resultados/20261101-232254/RESULTADOS.md).
Os ficheiros brutos ficam localmente no arquivo `dados-brutos.tar.gz` da mesma
pasta; o Git publica apenas o relatório agregado, sem os registos de execução.
Os cenários 8(d) e fator=1 foram repetidos após apertar a condição inicial:
todos os participantes têm de observar cada partição antes da suspensão.
As execuções preliminares foram conservadas em `_exploratorios` no arquivo;
as tabelas usam as repetições corrigidas nas pastas principais.

## Preparação e execução

Requer JDK 11 ou superior e um daemon IPFS/Kubo com PubSub disponível na
API HTTP. Os valores por omissão usam `http://127.0.0.1:5001/api/v0`.
Todos os processos devem correr na mesma máquina. A simulação local descrita
abaixo serve para testar o código; não substitui os ensaios com IPFS.

Neste PC foi instalado o binário oficial Kubo 0.39.0 em `~/.local/bin/ipfs`,
com SHA-256 conferido contra os metadados do lançamento oficial. O repositório
`~/.ipfs` usa o perfil `test` (rede local, sem bootstrap público) e API na porta
5001. Para arrancar o daemon num terminal:

```bash
ipfs daemon --enable-pubsub-experiment
```

A versão foi fixada para manter o comportamento PubSub das fichas anteriores.
Não foi criado um serviço de arranque automático.

Na pasta `Sprints/E1-COMINDIR04`:

```bash
bash executar.sh compilar
```

Abrir um terminal por processo, pela ordem seguinte:

```bash
# 1. Réplicas (cada linha num terminal)
bash executar.sh replica 1 0,1
bash executar.sh replica 2 1,2
bash executar.sh replica 3 0,2

# 2. Líderes (cada linha num terminal)
bash executar.sh lider 0 3
bash executar.sh lider 1 3
bash executar.sh lider 2 3

# 3. Processador
bash executar.sh processador 3

# 4. Produtor
bash executar.sh produtor 3
```

Os ficheiros ficam em `ensaios/manual`. Para separar ensaios, definir, em
todos os terminais, `REP04_ENSAIO` com o caminho absoluto da nova pasta.
Não misturar ficheiros de ensaios diferentes nem executar dois líderes da
mesma partição. Não é necessário alterar a configuração do IntelliJ ou VS Code:
cada projeto compila separadamente, pois contém as suas cópias das classes comuns.

Para outra API, o produtor/processador recebem a URL como segundo argumento;
o líder recebe `<k> <N> [endereço anunciado] [URL da API]`; a réplica recebe
`<id> <partições> [portoApi] [N=3]`. Os portos TCP são `7000 + k`.

Comandos no produtor:

```text
S01 21.0
S04 22.5
rajada 300 10
rajada 300 10 10
rajada-quente 300 10 10
sair
```

`rajada <n> <s> [intervaloMs]` envia n leituras distribuídas por s sensores.
O terceiro número opcional introduz uma espera entre leituras. O formato
original `rajada 300 10` continua válido e não introduz espera deliberada.
Para medir também uma rajada com intervalo de 10 ms, usar `rajada 300 10 10`.

## Código

- `Batch`: codificação e validação do cabeçalho e das linhas `LOTE`.
- `HeartbeatMonitor`: estados e temporização, conforme o código fornecido na ficha.
- `Monitoring`: um monitor por partição, verificação periódica e ficheiro de eventos.
- `PartitionLeader`: atribui seq/L/V e escreve imediatamente em disco; fecha
  o lote sob o mesmo bloqueio e publica fora dele. Heartbeat no arranque e
  depois a cada período, mesmo sem leituras. Uma publicação falhada não é repetida.
- `PartitionReplica`: aplica em sequência, ignora duplicados e recupera lacunas
  por TCP, incluindo a cauda indicada por `ultimoSeq` num lote vazio.
- `AlarmProcessor`: trata os registos de cada lote com as regras da COMINDIR03;
  avisa quando o líder de destino está suspeito, mas publica o alarme.
- `Producer`: interpreta apenas o cabeçalho dos lotes; avisa quando o destino
  está suspeito, mas envia a leitura na mesma.
- `HeartbeatCheck`: relatório offline dos heartbeats, suspeitas, latências e bytes.
- `CausalCheck` e `PartitionCheck`: verificadores herdados da COMINDIR03.

Um lote antigo pode não renovar o sinal de vida, mas os seus registos continuam
a ser processados: o filtro do detetor não descarta dados.

## Relatórios e testes

Depois de terminar os processos do ensaio:

```bash
bash executar.sh heartbeat-check 3 . falhas.txt
bash executar.sh causal-check 3 .
bash executar.sh partition-check 3 .
```

Se não houver `falhas.txt`, omitir esse argumento. O formato é `p<k>;instante`,
com instante Unix em milissegundos. Para provocar uma falha, no terminal em
que são geridos os processos, substituir o PID e a pasta do ensaio:

```bash
printf 'p0;%s\n' "$(date +%s%3N)" >> ensaios/manual/falhas.txt
kill -9 PID_DO_LIDER_P0
```

Para a suspensão temporária de um líder, usar `kill -STOP PID` e, após 20 s,
`kill -CONT PID`. A suspensão conserva a incarnação, ao contrário do reinício.
O `HeartbeatCheck` mostra os tempos em ms; dividir por 1000 para as tabelas em s.

O relatório classifica uma retoma com a mesma incarnação como falsa suspeita.
Sem retoma posterior observada, a classificação de falha real é uma inferência
limitada à janela registada. `falhas.txt` permite associar os instantes reais
provocados às suspeitas e calcular o tempo de deteção. Suspeitas anteriores
ao primeiro heartbeat ficam indeterminadas. Uma latência por registo e
participante usa a primeira receção do lote que o transporta; registos obtidos
apenas por TCP não são tratados como receções de lote. Os bytes medem o payload
UTF-8, sem overhead HTTP/IPFS; os cabeçalhos de lotes vazios são reportados
separadamente. O endereço anunciado nos eventos permite comparar com `REG`.

Teste automatizado com API PubSub simulada e processos Java reais:

```bash
python3 verificar.py
```

Com o daemon IPFS local já em execução, repetir a integração com a API real:

```bash
python3 verificar.py --ipfs
```

Neste modo a perda é simulada na réplica com `-Ddescartar=2`; no modo sem
IPFS o intermediário simulado perde um lote completo. Ambos testam a recuperação
da cauda por TCP, mas só o segundo força `HB_PERDIDO`. O modo real também valida
os formatos multipart e multibase e as subscrições da API Kubo.

Compila os quatro projetos para Java 11 em `/tmp` e verifica o fluxo produtor →
líder → réplicas, geração de alarmes entre partições, perda de lote e recuperação
por TCP, suspeita após falha, reinício e retoma, falsa suspeita após suspensão,
igualdade dos ficheiros e execução dos verificadores. Usa temporização reduzida
e precisa de portas locais livres, incluindo 7000–7002. Os ficheiros temporários
são removidos no fim. Não são resultados experimentais de Kubo.

## Ensaios que devem ser medidos com IPFS

Para executar automaticamente os ensaios 8(a–e) e as cinco variantes erradas
do ponto 9, com o daemon local em execução:

```bash
python3 ensaios.py
```

As durações da ficha são reais, pelo que a execução demora vários minutos.
As variantes erradas são compiladas em pastas temporárias; os ficheiros Java
entregues não são alterados. Cada cenário guarda ações, consolas, dados e
relatórios numa subpasta de `resultados/<execução>`. Para preencher as tabelas:

```bash
python3 resumir_ensaios.py resultados/PASTA_DA_EXECUCAO
```

O comando produz `RESULTADOS.md` e `medicoes.json`. Só o relatório agregado é
publicado. O arquivo local `dados-brutos.tar.gz` preserva os ficheiros brutos;
pode ser extraído na pasta da execução para repetir relatórios e conferir
medições. Noutro computador, executar os ensaios para obter novos dados locais.

Os ensaios oficiais e as tabelas estão na secção 4.2/4.3 do enunciado:

1. Reproduzir a Figura 1, incluindo registo persistido que não chegou a ser publicado.
2. Rajada de 300 leituras/10 sensores e um minuto sem leituras.
3. Falhar p1 e reiniciar 30 s depois, três execuções.
4. Suspender p2 por 20 s, enviando leituras durante a pausa.
5. Repetir a comparação com períodos 1000, 5000 e 10000 ms.
6. Introduzir cada erro deliberado da ficha isoladamente e repor o código correto.

Para mudar o período, definir `REP04_PERIODO=1000` (ou 5000/10000) em todos os
terminais antes de executar os comandos. Para o ensaio com fator 1, definir
`REP04_FATOR=1` nos participantes. As variáveis tornam-se propriedades Java
`rep04.periodo` e `rep04.fator`; a verificação continua a cada 1000 ms.

Registar os resultados reais, sem preencher com valores teóricos:

| Métrica | Medição |
| --- | --- |
| Lotes publicados p0 / p1 / p2 | |
| Registos por lote: média / máximo | |
| Proporção vazia: rajada / parado | |
| Intervalo heartbeat: média / máximo (ms) | |
| Latência: média / máximo (ms) | |
| Deteção: mínimo / média / máximo (s) | |
| Falsas suspeitas | |
| Mensagens COMINDIR03 / COMINDIR04 | |
| Bytes por registo REG / LOTE | |

| Δ (ms) | Lotes/min/partição | Vazios | Latência média (ms) | Deteção (s) | Falsas suspeitas |
| --- | --- | --- | --- | --- | --- |
| 1000 | | | | | |
| 5000 | | | | | |
| 10000 | | | | | |

| Erro deliberado | Onde surgiu | Verificador que detetou | Conclusão |
| --- | --- | --- | --- |
| Publicar só lotes não vazios | | | |
| Incrementar L e V[k] no heartbeat | | | |
| Ler ultimoSeq fora da secção sincronizada | | | |
| ultimoSeq=0 e perder um lote em dez | | | |
| Participantes com fator=1 | | | |
