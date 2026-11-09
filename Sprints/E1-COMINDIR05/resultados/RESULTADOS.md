# COMINDIR05 — Resultados e reflexão

Ensaios executados com o código final, todos os processos na mesma máquina (Linux, Java 21, Kubo 0.43.1 com PubSub), N = 3 partições, G = 3 nós por partição, Δ = 2000 ms, prazo de eleição em [6000, 12000] ms, `VERIFICACAO_MS` = 200 ms. Cada ensaio terminou com `RaftCheck` e `CausalCheck`; os relatórios ficam em `ensaios/<ensaio>/relatorio-raft.txt` (não publicados).

## Ensaios

**9(a) — Figura 1 em p1.** O n1 foi eleito no termo 1 (6,3 s após o arranque). Terminado 0,3 s depois de receber `S02 20.4`, que gravou com `seq 3` mas não publicou. O n3 foi eleito no termo 2 (indisponibilidade 6,1 s). `S05 23.9`, enviada durante a eleição, perdeu-se. O n3 atribuiu `seq 3` a `S02 20.6`, no termo 2. Ao regressar, o n1 adotou o termo 2 e assinalou `TRUNCADO;p1;de=3;descartados=1;termos=[1]`. Os três ficheiros coincidem no fim. Duas leituras perdidas: uma durante a eleição e uma por truncagem.

**9(b) — arranque.** 10 arranques dos nove nós por ordens aleatórias (30 eleições de arranque) e 5 com `eleicaoMin = eleicaoMax = 6000`, os três nós de cada partição lançados pelo mesmo script.

**9(c) — falha do líder de p1 durante `rajada 300 10 10`**, três repetições, regresso 30 s depois:

| Repetição | Indisponibilidade | Leituras enviadas a p1 durante a eleição | Registos truncados no antigo líder | Leituras perdidas |
|---|---|---|---|---|
| 1 | 8,6 s | 61 | 25 | 86 de 86 |
| 2 | 7,3 s | 58 | 4 | 62 de 78 |
| 3 | 7,9 s | 54 | 12 | 66 de 81 |

As leituras perdidas são exatamente as enviadas durante a eleição mais as truncadas (61 + 25, 58 + 4, 54 + 12). A rajada dura cerca de 5 s, menos do que a indisponibilidade, por isso o resto da rajada perde-se toda.

**9(d) — isolamento.** O líder n2 de p1 foi isolado 1,5 s depois do início da rajada, durante 30 s. O n1 foi eleito no termo 2 ao fim de 10,2 s. **Durante 20,0 s existiram dois nós que se julgavam líderes**: n2 no termo 1 (isolado) e n1 no termo 2. O antigo líder continuou a aceitar as leituras da rajada, porque o tópico `IN` não é afetado: atribuiu-lhes 96 registos no termo 1. Ao reparar a partição, recebeu um `LOTE` do termo 2, assinalou `DEMISSAO` e truncou os 96 (`de=16`). O processador nunca recebeu lotes do antigo líder (isolado, não publica). Viu `SUSPEITA` e depois `NOVO_LIDER termo=2`.
Segunda parte, sem rajada: o seguidor n2 isolado durante 60 s candidatou-se a cada prazo e chegou ao **termo 8**. No regresso, o seu `PEDIDO_VOTO` do termo 9 fez o líder saudável n1 (termo 2) **demitir-se**. Como o registo de n2 estava atualizado (não houve escritas), n2 foi eleito no termo 9.

**9(e) — falha de dois nós de p1.** O sobrevivente n2 candidatou-se nos termos 2, 3 e 4 sem nunca obter maioria. As 30 leituras enviadas a p1 em 30 s perderam-se todas. Ao voltar a arrancar o n1, houve líder 8,4 s depois (termo 5). O n1 regressou no termo 1, candidatou-se no termo 2, recebeu a recusa com termo 4 e adotou-o. A seguir, o n2 candidatou-se no termo 5 e ganhou com o voto de n1.

## Tabela 1 — ensaios 9(a) a 9(c)

| Métrica | Valor |
|---|---|
| Tempo até ao primeiro líder no arranque (mínimo / médio / máximo, s) | 6,7 / 8,5 / 10,8 (30 eleições, prazo aleatório) |
| Termos até ao primeiro líder: prazo aleatório / prazo fixo | 1,00 (máx. 1) / 1,13 (máx. 2) |
| Votos divididos: prazo aleatório / prazo fixo | 0 em 30 / 2 em 15 |
| Indisponibilidade após falha do líder (mínimo / médio / máximo, s) | 6,1 / 7,5 / 8,6 (9(a) e três repetições de 9(c)) |
| Leituras perdidas por falha: durante a eleição / por truncagem | 9(a): 1 / 1; 9(c): 61 / 25, 58 / 4, 54 / 12 |
| Registos truncados ao regressar o antigo líder | 1; 25; 4; 12 |
| Termos com mais de um líder | 0 em todos os ensaios com o código correto |
| `seq` divergentes na coincidência final | 0 em todos os ensaios |

`CausalCheck`: V[k] = seq em todas as linhas de todos os nós, em todos os ensaios, através das mudanças de líder.

## Tabela 2 — partições de rede e CAP

| Cenário (ensaio) | Lado maioritário aceita escritas? | Lado minoritário aceita escritas? | Escritas do lado minoritário após a reparação | Termos envolvidos | Comportamento |
|---|---|---|---|---|---|
| Falha do líder (c) | Sim, depois da eleição (7–9 s sem escritas) | — (o nó falhou) | Registos gravados e não publicados pelo antigo líder: truncados (25, 4, 12) | 1 → 2 | C: durante a eleição recusa (perde) escritas |
| Isolamento do líder (d) | Sim, após 10,2 s (novo líder no termo 2) | **Sim**: o antigo líder aceitou 96 leituras no termo 1 | Todas descartadas por truncagem | 1 e 2 em simultâneo durante 20 s | C na eleição, mas não CP estrito: sem *commit*, o lado minoritário aceitou escritas que depois desapareceram |
| Isolamento de um seguidor (d) | Sim (líder e um seguidor continuam) | Não (o seguidor não é líder) | Nenhuma | Seguidor sobe até 8; no regresso demite o líder (termo 9) | C; disponibilidade preservada no lado maioritário, mas o regresso provoca uma eleição desnecessária |
| Falha de dois nós (e) | — (não existe maioria) | Não: o sobrevivente fica candidato (termos 2–4) | — (30 de 30 leituras perdidas) | 1 → 5 | C: indisponível até haver maioria |

## Falhas introduzidas (passo 10)

Cada falha foi introduzida numa cópia do código (fora do repositório) e reposta a seguir.

| Falha introduzida | Lado onde surgiu | Detetada por RaftCheck / CausalCheck? | O que permitiu concluir |
|---|---|---|---|
| Maioria calculada como `votes.size() >= groupSize / 2` | Nenhum, com G = 3 | Não: 0 termos com dois líderes, ficheiros coincidentes | `onVote` só avalia a maioria depois de juntar um voto concedido ao do próprio (2 votos); com G = 3, 2 ≥ 1 e 2 > 1 dão o mesmo resultado. A falha é latente: com G = 4 bastariam 2 de 4 votos, e dois pares disjuntos elegeriam dois líderes no mesmo termo. |
| Termo e `votedFor` só em memória; seguidor reiniciado logo após conceder um voto | Eleitor n3 (reiniciado) e candidatos n1 e n2 | **Sim**: «termo 1: líder n1 n2 — DOIS LÍDERES» e «termo de n3 diminuiu 1 → 0»; os nós registaram 7 vezes `ERRO;dois lideres no termo 1` | O n3 votou em n1 no termo 1, reiniciou no termo 0 sem memória do voto, e votou em n2 no mesmo termo. Duas maiorias com o n3 em comum só são impossíveis se o voto for persistente. |
| Voto concedido sem `upToDate`; o nó atrasado é candidato | Seguidores n2 e n3, que tinham os registos | **Sim**: `TRUNCADO de=1 descartados=4` em n2 e n3; 4 de 5 leituras perdidas | O n1, sem nenhum registo, foi eleito no termo 4 e impôs o seu registo vazio: os registos 1–4 (termo 1), que a maioria já tinha, foram apagados. |
| Seguidor que aplica lotes de termo inferior (9(d) repetido) | Seguidor n2, após a reparação | **Sim**: n2 assinalou `NOVO_LIDER lider=n1` ainda no termo 2 e truncou duas vezes (`termos=[2]` e depois `termos=[1]`); o líder do termo 2 registou `ERRO;dois lideres no termo 2` | Ao receber o primeiro lote do antigo líder (termo 1), o seguidor apagou 3 registos válidos do termo 2 e escreveu os 7 obsoletos do termo 1. Só o lote seguinte do líder atual, 1 s depois, os repôs: a regra «ignorar termos inferiores» é o que impede um líder obsoleto de reescrever o registo. |
| Novo líder que inicializa L e V a zero | Novo líder n1 | Não diretamente: `V[k] = seq` OK, porque nenhum registo errado chegou a ser escrito; o RaftCheck mostra 2 leituras perdidas a mais | A verificação de invariante do líder recusou as leituras (`Violação de invariante: V[1]=1 != seq=3`). Sem essa verificação, o CausalCheck assinalaria V[k] ≠ seq a partir da primeira leitura do novo termo. |

## Reflexão crítica

**Eleição no arranque.** Com prazos aleatórios, nenhuma das 30 eleições de arranque teve voto dividido: o primeiro nó a expirar pede e recebe os votos (cerca de 20–100 ms) muito antes de o segundo expirar. A ordem de arranque deixou de importar, porque nenhum nó é configurado como líder. Com prazo fixo, 2 das 15 eleições tiveram voto dividido; as restantes só não o tiveram porque as JVM não arrancam exatamente ao mesmo tempo. Deve ter-se Δ ≪ `ELEICAO_MIN_MS` < `ELEICAO_MAX_MS`, com o intervalo largo o suficiente para separar os sorteios. Com `ELEICAO_MIN_MS` < Δ, os seguidores expirariam entre dois heartbeats de um líder saudável e haveria eleições contínuas.

**O termo.** Cada nó vota no máximo uma vez por termo, e o voto é persistente. Duas maiorias de 3 nós partilham pelo menos um nó, que teria de ter votado duas vezes: logo, nenhum termo tem dois líderes (0 em todos os ensaios). Isso não impede dois nós de se julgarem líderes ao mesmo tempo **em termos diferentes**: em 9(d), durante 20 s, n2 (termo 1) e n1 (termo 2). Ao reparar a partição, o termo identifica o líder obsoleto: o termo 1 < 2 leva à demissão e à truncagem. Tal como o relógio de Lamport, o termo só cresce e adota-se o máximo ao receber uma mensagem. Ao contrário dele, não ordena eventos: identifica épocas de liderança, e não avança a cada evento.

**CAP e particionamento.** Em todos os cenários da Tabela 2, o lado minoritário não consegue eleger líder, porque não reúne 2 votos. O sistema prefere C a A. Mas em 9(d) o antigo líder isolado aceitou 96 escritas que depois desapareceram. Em rigor, o sistema desta ficha **não é CP**: quem leu o ficheiro do antigo líder viu dados que nunca foram aceites. O que o tornaria CP é o *commit* por maioria (o líder só considera um registo aceite depois de uma maioria o confirmar, com `AppendEntries` e resposta). O custo seria mais latência por escrita (uma ronda até à maioria) e recusar escritas sempre que o líder não alcança a maioria. Este mecanismo está excluído desta ficha.

**Restrição de voto e truncagem.** A restrição protege os registos que **uma maioria** já tem: sem ela (falha 3), um nó vazio foi eleito e apagou 4 registos da maioria. Não protege os registos que só o líder tem. Sem *commit*, um registo gravado no ficheiro do líder e ainda não publicado (9(a): `S02 20.4`; 9(c): 4 a 25 registos) perde-se quando outro nó é eleito e reutiliza o mesmo `seq`.

**Persistência.** Sem persistir termo e voto (falha 2), um nó reiniciado votou duas vezes no mesmo termo e houve dois líderes no termo 1. Os registos do nó também têm de ser persistentes, porque a restrição de voto compara o último registo: um nó que os esquecesse pareceria vazio e recusaria (ou receberia) votos indevidamente. Na ficha anterior, a incarnação `inc` do líder fixo desempenhava um papel semelhante ao do termo: distinguia as vidas sucessivas do líder.

**Detetar, suspeitar e eleger.** Antes, uma falsa suspeita só gerava um evento. Agora provoca uma eleição: em 9(d), o seguidor isolado subiu o termo seis vezes e, no regresso, fez demitir um líder saudável (indisponibilidade desnecessária). Uma pausa longa do líder (GC, por exemplo) teria o mesmo efeito. A fase de **Pre-Vote** faria o nó perguntar primeiro se teria votos, sem incrementar o termo, e só depois candidatar-se de facto. Um nó isolado nunca obteria resposta positiva e não perturbaria o grupo ao voltar. Também está excluída.

**Limites.** As leituras enviadas a uma partição sem líder perdem-se (9(c): 54–61 por falha; 9(e): 30 de 30). Para não perder nenhuma leitura aceite sem introduzir duplicados, seria preciso: o produtor reter cada leitura com um identificador único e reenviá-la até ser confirmada por um líder; o líder confirmar só depois do *commit* por maioria; e o líder deduplicar pelo identificador, que teria de ser replicado com o registo para sobreviver a mudanças de líder. Nada disto cabe no âmbito desta ficha (retenção e reenvio pelo produtor e *commit* estão excluídos).
