# Resultados medidos — COMINDIR04

Todos os processos foram executados nesta máquina, com IPFS/Kubo real. Os dados brutos e as ações foram preservados localmente; o relatório público contém apenas resultados agregados. Não foram usados valores do simulador HTML para preencher as tabelas.

## Ensaios 8(b) e 8(c), Δ = 5000 ms

A primeira tabela agrega uma execução de 8(b) e três de 8(c). Contagens de mensagens/lotes são médias por execução; médias de tamanho, bytes, intervalo e latência são ponderadas pelas respetivas amostras. Latência inclui a primeira receção de cada registo em cada participante. As proporções de vazios separam a execução em rajada e o minuto parado.

| Métrica | Medição |
| --- | --- |
| Lotes por partição, média p0 / p1 / p2 | 8.50 / 4.75 / 8.50 |
| Registos por lote: média / máximo | 11.54 / 129 |
| Lotes vazios: rajada / parado | 50.00% / 100.00% |
| Intervalo heartbeat: média / máximo (ms) | 4989.00 / 5019 |
| Latência de lote: média / máximo (ms) | 3408.85 / 5034 |
| Deteção: mínimo / média / máximo (s) | 14.36 / 15.15 / 15.32 |
| Falsas suspeitas | 0 |
| Mensagens COMINDIR03 equivalentes / COMINDIR04, média por execução | 260.00 / 21.75 |
| Bytes/registo: REG equivalente / LOTE não vazio | 54.71 / 37.55 |

A contagem COMINDIR03 é o equivalente de uma publicação por registo persistido nestas execuções; não é uma execução separada da ficha 3. Os bytes incluem o payload UTF-8 e a parte do cabeçalho do lote, sem overhead HTTP/IPFS. Os lotes vazios contam nas mensagens, mas não têm registos pelos quais dividir bytes.

## Variação de Δ — ensaio 8(e)

| Δ (ms) | Lotes/min/partição durante o minuto parado | Vazios: rajada / parado | Latência média na rajada (ms) | Deteção média (s) | Falsas suspeitas |
| --- | --- | --- | --- | --- | --- |
| 1000 | 60.00 / 60.00 / 60.00 | 55.56% / 100.00% | 549.40 | 3.55 | 0 |
| 5000 | 12.00 / 12.00 / 12.00 | 50.00% / 100.00% | 4401.45 | 15.15 | 0 |
| 10000 | 6.00 / 6.00 / 6.00 | 50.00% / 100.00% | 9443.22 | 29.77 | 0 |

## Deteção de cada falha, por participante

| Ensaio | Papel do participante | Partição | Deteção (ms) |
| --- | --- | --- | --- |
| 8a-figura | processador | p0 | 15846 |
| 8a-figura | produtor | p0 | 15936 |
| 8a-figura | replica1 | p0 | 15909 |
| 8a-figura | replica3 | p0 | 15868 |
| 8c-falha-5000-1 | processador | p1 | 15134 |
| 8c-falha-5000-1 | produtor | p1 | 15118 |
| 8c-falha-5000-1 | replica1 | p1 | 15323 |
| 8c-falha-5000-1 | replica2 | p1 | 15208 |
| 8c-falha-5000-2 | processador | p1 | 15136 |
| 8c-falha-5000-2 | produtor | p1 | 15250 |
| 8c-falha-5000-2 | replica1 | p1 | 15306 |
| 8c-falha-5000-2 | replica2 | p1 | 15218 |
| 8c-falha-5000-3 | processador | p1 | 15295 |
| 8c-falha-5000-3 | produtor | p1 | 15294 |
| 8c-falha-5000-3 | replica1 | p1 | 14358 |
| 8c-falha-5000-3 | replica2 | p1 | 15190 |
| 8e-falha-1000 | processador | p1 | 3484 |
| 8e-falha-1000 | produtor | p1 | 3466 |
| 8e-falha-1000 | replica1 | p1 | 3634 |
| 8e-falha-1000 | replica2 | p1 | 3616 |
| 8e-falha-10000 | processador | p1 | 30207 |
| 8e-falha-10000 | produtor | p1 | 30259 |
| 8e-falha-10000 | replica1 | p1 | 29305 |
| 8e-falha-10000 | replica2 | p1 | 29301 |

## Integridade e limites de medição

Ensaios normais com divergência de ficheiros ou condição de relógio não validada: nenhum.

As leituras enviadas enquanto um líder está terminado podem perder-se antes de receber seq. A igualdade entre ficheiros não prova que essas leituras chegaram. No ensaio com STOP/CONT, o processo e a subscrição continuam existentes; o destino efetivo das leituras pode ser verificado nos ficheiros do líder. A latência de lote começa no instante atribuído pelo líder ao registo; não inclui o tempo em que o IN aguarda antes de ser consumido pelo líder suspenso.

Os intervalos de deteção previstos para Δ=5000 ms são ]10,16] segundos, ignorando atrasos de rede. A amostra de deteção inclui os participantes que acompanham a partição e a verificação discreta a cada segundo. Suspeitas sem retoma posterior são classificações limitadas à janela observada.

## Erros deliberados — ensaio 9

As variantes foram compiladas em pastas temporárias, isoladas do código entregue. O ensaio da corrida acrescenta 250 ms fora do bloqueio para ampliar a janela e tornar o erro observável; esta pausa não existe na implementação correta.

| Erro | Observação medida | Verificadores / conclusão |
| --- | --- | --- |
| sem-vazios | Suspeitas=12; falsas confirmadas por retoma=0; HB_PERDIDO=0; recuperação=0; inconsistências de lote=0; erros de consola=0; réplicas iguais=True | CausalCheck=OK; O líder vivo deixa de sinalizar vida sem leituras; surgem suspeitas por silêncio. |
| relogios-hb | Suspeitas=0; falsas confirmadas por retoma=0; HB_PERDIDO=0; recuperação=6; inconsistências de lote=0; erros de consola=1; réplicas iguais=True | CausalCheck=OK sem registos (não prova progresso); O próprio invariante do líder pode impedir o append; verificar consola, não apenas ficheiros vazios. |
| seq-fora-lock | Suspeitas=0; falsas confirmadas por retoma=0; HB_PERDIDO=0; recuperação=4; inconsistências de lote=3; erros de consola=0; réplicas iguais=True | CausalCheck=OK; O cabeçalho pode anunciar registos recebidos depois de fechar a lista; a recuperação pode antecipar o próximo lote. |
| seq-zero-perdas | Suspeitas=8; falsas confirmadas por retoma=8; HB_PERDIDO=16; recuperação=0; inconsistências de lote=0; erros de consola=33; réplicas iguais=False | CausalCheck=OK; ultimoSeq=0 perde a informação da cauda; cabeçalhos incompatíveis com n são rejeitados por Batch. |
| fator1 | Suspeitas=6; falsas confirmadas por retoma=4; HB_PERDIDO=0; recuperação=0; inconsistências de lote=0; erros de consola=0; réplicas iguais=True | CausalCheck=OK; Timeout igual ao período torna o detetor mais sensível a atrasos; a pausa de 20 s provoca falsa suspeita. |

Consultar localmente `medicoes.json` e os relatórios por ensaio para os valores completos, incluindo erros de consola e resultados negativos. Um verificador que não encontra linhas inválidas não prova progresso nem ausência de leituras perdidas.

## Leituras enviadas durante falhas e suspensão

| Ensaio | Leituras enviadas | Leituras persistidas (exclui alarmes) | Diferença |
| --- | --- | --- | --- |
| 8c-falha-5000-1 | 300 | 228 | 72 |
| 8c-falha-5000-2 | 300 | 214 | 86 |
| 8c-falha-5000-3 | 300 | 221 | 79 |
| 8e-falha-1000 | 300 | 232 | 68 |
| 8e-falha-10000 | 300 | 228 | 72 |
| 8d-pausa-5000 | 5 | 5 | 0 |

## Reflexão sobre os resultados

Nas execuções de 8(b/c), as publicações passaram de 260.00 REG equivalentes para 21.75 lotes por execução, em média. O payload médio por registo passou de 54.71 para 37.55 bytes. Esta comparação inclui o custo dos heartbeats nas mensagens; aplicações muito pouco ativas podem emitir mais mensagens com heartbeats do que com publicação só por leitura.

A estimativa Δ/2 pressupõe chegadas aproximadamente uniformes ao longo do ciclo. Uma rajada curta, iniciada numa fase particular entre heartbeats, não satisfaz essa hipótese e pode apresentar uma média diferente. Alarmes podem esperar até um período na origem e outro no destino, além de rede e processamento. Para alertas urgentes seria preferível desacoplar o heartbeat do envio de dados; essa alteração não foi implementada nesta ficha.

Aumentar o período, mantendo fator=3, aumenta o timeout e atrasa a suspeita. Reduzir o fator acelera a deteção mas deixa menos margem para atrasos e pausas. A pausa STOP/CONT mantém o líder existente e a mesma incarnação: as suspeitas seguidas de retoma são falsas. Sem limite para atrasos de rede e velocidade dos processos, o silêncio não permite garantir exatidão.

A Figura 1 demonstrou a utilidade de ultimoSeq num lote vazio: o registo final já existia no disco, mas nenhum registo posterior iria revelar a lacuna. O heartbeat vazio basta para anunciar essa cauda. Heartbeats não atualizam L nem V porque esses relógios representam os registos numerados; fazê-lo quebra V[k]=seq.

Detetar a falha não elege um sucessor nem conserva leituras enviadas durante a indisponibilidade. Evitar essas perdas exigiria confirmação e retenção/reenvio; substituir o líder sem duas autoridades a atribuir seq exigiria coordenação, eleição e uma regra de exclusão/quórum. Esses mecanismos ficam fora do âmbito desta ficha.

As medições offline comparam carimbos físicos de processos na mesma máquina. Em máquinas diferentes, diferenças de relógio contaminariam latência e deteção. O detetor online usa diferenças locais de System.nanoTime(), pelo que não depende da sincronização dos relógios entre máquinas.
