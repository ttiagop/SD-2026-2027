# COMINDIR05 — Eleição do líder de partição (ao estilo RAFT)

Continuação da COMINDIR04 (heartbeat e envio em lote), em três projetos independentes, pacote `raft04`.
Enunciado: https://github.com/cunhaestgv/SD-2026-2027/issues/18

Resultados medidos e reflexão: [resultados/RESULTADOS.md](resultados/RESULTADOS.md).

## Projetos e classes

| Projeto | Classes novas ou alteradas |
|---|---|
| `raft04-no` | `RaftState` (dada no enunciado), `PartitionNode` (junta `PartitionLeader` e `PartitionReplica`), `VoteRequest`, `VoteReply`, `Batch` (termo no cabeçalho e em cada registo), `Wire` (parâmetros, `topicElection`, `nodePort`, `isolated`), `SensorRecord` (campo `termo`), `RaftCheck`, `CausalCheck` (lê os ficheiros dos nós) |
| `raft04-produtor` | `Producer` (escreve `produtor.txt`, avisa partições suspeitas), `HeartbeatMonitor` (`onHeartbeat(term, leader, hb)`), `Monitoring` |
| `raft04-processador` | `AlarmProcessor` (só lotes de termo ≥ ao maior observado; registos identificados por (`seq`, termo)), `HeartbeatMonitor`, `Monitoring` |

Cada projeto contém as suas cópias das classes comuns e compila separadamente.

## Requisitos

- JDK 11 ou superior.
- Um daemon IPFS (Kubo) com PubSub ativo e API em `http://127.0.0.1:5001/api/v0`:

```text
ipfs config --json Pubsub.Enabled true
ipfs daemon
```

Todos os processos correm na mesma máquina e na mesma pasta de ensaio, para que os instantes dos ficheiros sejam comparáveis e os ficheiros `isolar-p<k>-n<i>` sejam vistos por todos.

## Compilar e executar

Linux/macOS (`bash executar.sh ...`) ou Windows PowerShell (`.\executar.ps1 ...`), na pasta `Sprints/E1-COMINDIR05`:

```text
executar compilar
executar no <k> <i>          # um terminal por nó: k = 0..2, i = 1..3 (nove nós para N = 3)
executar processador
executar produtor            # comandos: S02 20.1 | rajada <n> <s> [intervaloMs] | sair
```

Os nós podem arrancar por qualquer ordem; a primeira eleição acontece no arranque. Depois arrancam o processador e o produtor.

Os ficheiros ficam em `ensaios/manual`, ou na pasta indicada em `RAFT04_ENSAIO`. Entre ensaios, apague `estado-*` e `no-*` (ou use uma pasta nova).
Parâmetros: `RAFT04_PERIODO` (Δ, por omissão 2000 ms), `RAFT04_ELEICAO_MIN` e `RAFT04_ELEICAO_MAX` (por omissão 3Δ e 6Δ).

Falhas e partições de rede:

```text
executar matar <k> <i>       # só no executar.ps1; em bash: echo "p1;n2;$(date +%s%3N)" >> falhas.txt; kill -9 <pid>
executar isolar <k> <i>      # só no executar.ps1; em bash: touch isolar-p<k>-n<i>
executar reparar <k> <i>     # só no executar.ps1; em bash: rm isolar-p<k>-n<i>
executar raft-check [falhas.txt]
executar causal-check
```

## Ficheiros produzidos

| Ficheiro | Conteúdo |
|---|---|
| `no-p<k>-n<i>.txt` | `seq;termo;sensor;temperatura;instante;L;V` |
| `estado-p<k>-n<i>.txt` | `termo;votouEm`, reescrito de forma atómica |
| `hb-p<k>-n<i>.txt` | `termo;hb;instante;ultimoSeq;ultimoTermo;n;bytes`, uma linha por lote publicado |
| `eventos-p<k>-n<i>.txt` | `instante;evento;p<k>;detalhe` |
| `truncados-p<k>-n<i>.txt` | `instante;<linha descartada>` (extensão: permite ao `RaftCheck` saber se a leitura truncada existe no líder com outro `seq`) |
| `monitor-<id>.txt`, `produtor.txt` | eventos do detetor do processador e do produtor; uma linha por `IN` enviado |
| `relatorio-raft.txt` | relatório do `RaftCheck` |

Além dos eventos da ficha, cada nó escreve `ARRANQUE` ao iniciar, usado pelo `RaftCheck` para medir o tempo até ao primeiro líder.

## Decisões de implementação

- **Ordem dos locks:** o monitor do nó (`this` em `PartitionNode`) é sempre adquirido antes do de `RaftState`. `RaftState` nunca chama código do nó que adquira o monitor do nó (só escreve eventos, com um lock próprio).
- **Estado persistente antes das mensagens:** `tick()` e `onVoteRequest()` gravam termo e voto antes de devolver; o `PEDIDO_VOTO` e o `VOTO` só são publicados depois.
- **Registo local em memória:** o nó mantém uma cópia do seu ficheiro em memória (índice = `seq − 1`), usada para servir a recuperação por TCP e detetar divergências; a truncagem reescreve o ficheiro de forma atómica.
- **Divergência:** recua um bloco de termo de cada vez até o primeiro registo recebido do líder coincidir com o local, trunca a partir do primeiro `seq` diferente e aplica os do líder.
- **Isolamento:** um nó isolado continua a receber `IN` e a correr `tick()` (o termo sobe), mas não publica nem processa lotes e mensagens de eleição, e as suas ligações TCP falham.
