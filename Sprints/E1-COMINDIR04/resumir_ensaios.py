#!/usr/bin/env python3
"""Produz tabelas a partir dos dados brutos; não inventa medições ausentes."""
import json
from pathlib import Path
import re
import statistics
import sys


def rows(path):
    return [line.split(';') for line in path.read_text().splitlines() if line.strip()] if path.exists() else []


def avg(values):
    return statistics.mean(values) if values else None


def show(value):
    return 'sem amostras' if value is None else f'{value:.2f}'


def summarize(folder):
    result = dict(nome=folder.name, lotes=[0, 0, 0], registos_lote=[], intervalos=[], latencias=[],
                  detecoes=[], falsassuspeitas=0, hbperdido=0, recuperacoes=0,
                  vazios=0, byteslote=0, regstransportados=0, bytesreg=0, registos=0,
                  detalhes_detecao=[], problemas=[])
    result['suspeitas'] = 0
    result['leituras_persistidas'] = 0
    for k in range(3):
        hb = rows(folder / f'hb-p{k}.txt')
        result['lotes'][k] = len(hb)
        previous = {}
        for inc, number, time, last, size, sizebytes in hb:
            result['registos_lote'].append(int(size))
            result['vazios'] += int(size) == 0
            if int(size):
                result['byteslote'] += int(sizebytes)
                result['regstransportados'] += int(size)
            if inc in previous:
                result['intervalos'].append(int(time) - previous[inc])
            previous[inc] = int(time)
        for record in rows(folder / f'lider-p{k}.txt'):
            result['registos'] += 1
            result['leituras_persistidas'] += not record[1].startswith('ALARM-')
            result['bytesreg'] += len((f'REG;127.0.0.1;{7000 + k};' + ';'.join(record)).encode())
    report = (folder / 'HeartbeatCheck.txt').read_text()
    result['inconsistencias_lote'] = report.count('INCONSISTENCIA_LOTE;')
    for line in report.splitlines():
        if line.startswith('PARTICIPANTE '):
            participant = line.split(' ', 1)[1]
        if line.startswith('LATENCIA;'):
            result['latencias'].append(int(line.rsplit('=', 1)[1]))
        if line.startswith('DETECAO;'):
            ms = int(line.rsplit('=', 1)[1])
            result['detecoes'].append(ms)
            result['detalhes_detecao'].append(dict(participante=participant, evento=line))
        if ': FALSA SUSPEITA' in line:
            result['falsassuspeitas'] += 1
    for path in folder.glob('monitor-*.txt'):
        for r in rows(path):
            result['hbperdido'] += r[1] == 'HB_PERDIDO'
            result['recuperacoes'] += r[1] == 'RECUPERACAO'
            result['suspeitas'] += r[1] == 'SUSPEITA'
    equality = json.loads((folder / 'igualdade.json').read_text())
    result['replicas_iguais'] = all(equality.values())
    causal = (folder / 'CausalCheck.txt').read_text()
    result['causal_ok'] = 'Validação V[k] == seq: OK' in causal and bool(re.search(r'Violações da Condição de Relógio .*: 0\b', causal))
    result['erros_consola'] = []
    for path in folder.glob('consola-*.log'):
        for line in path.read_text().splitlines():
            if any(marker in line for marker in ('REJEITADO', 'Erro', 'falhou', 'Violação', 'terminada')):
                result['erros_consola'].append(dict(ficheiro=path.name, linha=line))
    return result


def metrics(samples):
    counts = [n for s in samples for n in s['registos_lote']]
    intervals = [n for s in samples for n in s['intervalos']]
    delays = [n for s in samples for n in s['latencias']]
    detects = [n / 1000 for s in samples for n in s['detecoes']]
    total_records = sum(s['registos'] for s in samples)
    transported = sum(s['regstransportados'] for s in samples)
    return dict(lotes=[avg([s['lotes'][k] for s in samples]) for k in range(3)],
                media=avg(counts), maximo=max(counts, default=0),
                vazios=100 * sum(s['vazios'] for s in samples) / len(counts) if counts else None,
                intervalo=avg(intervals), intervalo_max=max(intervals, default=0),
                latencia=avg(delays), latencia_max=max(delays, default=0),
                detecao=avg(detects), detecao_min=min(detects, default=0), detecao_max=max(detects, default=0),
                falsas=sum(s['falsassuspeitas'] for s in samples),
                mensagens03=avg([s['registos'] for s in samples]),
                mensagens04=avg([sum(s['lotes']) for s in samples]),
                bytesreg=sum(s['bytesreg'] for s in samples) / total_records if total_records else None,
                byteslote=sum(s['byteslote'] for s in samples) / transported if transported else None)


def main():
    root = Path(sys.argv[1]).resolve()
    samples = {p.name: summarize(p) for p in sorted(root.iterdir()) if p.is_dir() and (p / 'HeartbeatCheck.txt').exists()}
    (root / 'medicoes.json').write_text(json.dumps(samples, indent=2, ensure_ascii=False) + '\n')
    expected = ['8a-figura', '8b-rajada-5000', '8b-parado-5000', *[f'8c-falha-5000-{i}' for i in range(1, 4)],
                '8d-pausa-5000', *[f'8e-{s}-{p}' for p in (1000, 10000) for s in ('rajada', 'parado', 'falha')],
                *['9-' + s for s in ('sem-vazios', 'relogios-hb', 'seq-fora-lock', 'seq-zero-perdas', 'fator1')]]
    missing = [s for s in expected if s not in samples]
    if missing:
        raise RuntimeError('Ensaios em falta: ' + ', '.join(missing))
    normal = [s for name, s in samples.items() if name.startswith('8')]
    failed = [s['nome'] for s in normal if not s['replicas_iguais'] or not s['causal_ok']]
    m = metrics([samples['8b-rajada-5000'], *[samples[f'8c-falha-5000-{i}'] for i in range(1, 4)]])
    active = metrics([samples['8b-rajada-5000']])
    idle = metrics([samples['8b-parado-5000']])
    lines = ['# Resultados medidos — COMINDIR04', '',
             'Todos os processos foram executados nesta máquina, com IPFS/Kubo real. '
             'Os dados brutos e as ações foram preservados localmente; o relatório público contém apenas resultados agregados. '
             'Não foram usados valores do simulador HTML para preencher as tabelas.', '',
             '## Ensaios 8(b) e 8(c), Δ = 5000 ms', '',
             'A primeira tabela agrega uma execução de 8(b) e três de 8(c). '
             'Contagens de mensagens/lotes são médias por execução; médias de tamanho, bytes, '
             'intervalo e latência são ponderadas pelas respetivas amostras. '
             'Latência inclui a primeira receção de cada registo em cada participante. '
             'As proporções de vazios separam a execução em rajada e o minuto parado.', '',
             '| Métrica | Medição |', '| --- | --- |',
             '| Lotes por partição, média p0 / p1 / p2 | ' + ' / '.join(map(show, m['lotes'])) + ' |',
             f"| Registos por lote: média / máximo | {show(m['media'])} / {m['maximo']} |",
             f"| Lotes vazios: rajada / parado | {show(active['vazios'])}% / {show(idle['vazios'])}% |",
             f"| Intervalo heartbeat: média / máximo (ms) | {show(m['intervalo'])} / {m['intervalo_max']} |",
             f"| Latência de lote: média / máximo (ms) | {show(m['latencia'])} / {m['latencia_max']} |",
             f"| Deteção: mínimo / média / máximo (s) | {show(m['detecao_min'])} / {show(m['detecao'])} / {show(m['detecao_max'])} |",
             f"| Falsas suspeitas | {m['falsas']} |",
             f"| Mensagens COMINDIR03 equivalentes / COMINDIR04, média por execução | {show(m['mensagens03'])} / {show(m['mensagens04'])} |",
             f"| Bytes/registo: REG equivalente / LOTE não vazio | {show(m['bytesreg'])} / {show(m['byteslote'])} |", '',
             'A contagem COMINDIR03 é o equivalente de uma publicação por registo persistido '
             'nestas execuções; não é uma execução separada da ficha 3. '
             'Os bytes incluem o payload UTF-8 e a parte do cabeçalho do lote, sem overhead HTTP/IPFS. '
             'Os lotes vazios contam nas mensagens, mas não têm registos pelos quais dividir bytes.', '',
             '## Variação de Δ — ensaio 8(e)', '',
             '| Δ (ms) | Lotes/min/partição durante o minuto parado | Vazios: rajada / parado | Latência média na rajada (ms) | Deteção média (s) | Falsas suspeitas |',
             '| --- | --- | --- | --- | --- | --- |']
    for period in (1000, 5000, 10000):
        prefix = '8b' if period == 5000 else '8e'
        b = samples[f'{prefix}-rajada-{period}']
        idle_sample = samples[f'{prefix}-parado-{period}']
        c = [samples[f'8c-falha-5000-{i}'] for i in range(1, 4)] if period == 5000 else [samples[f'8e-falha-{period}']]
        a, d, f = metrics([b]), metrics([idle_sample]), metrics(c)
        actions = json.loads((root / idle_sample['nome'] / 'acoes.json').read_text())
        start = next(x['instante'] for x in actions if x['evento'] == 'INICIO_MINUTO_PARADO')
        end = next(x['instante'] for x in actions if x['evento'] == 'FIM_MINUTO_PARADO')
        rates = [sum(start <= int(r[2]) < end for r in rows(root / idle_sample['nome'] / f'hb-p{k}.txt'))
                 * 60000 / (end - start) for k in range(3)]
        lines.append(f"| {period} | {' / '.join(map(show, rates))} | {show(a['vazios'])}% / {show(d['vazios'])}% | {show(a['latencia'])} | {show(f['detecao'])} | {a['falsas'] + d['falsas'] + f['falsas']} |")
    lines += ['', '## Deteção de cada falha, por participante', '',
              '| Ensaio | Papel do participante | Partição | Deteção (ms) |', '| --- | --- | --- | --- |']
    for name, sample in samples.items():
        for item in sample['detalhes_detecao']:
            fields = item['evento'].split(';')
            role = item['participante'].removeprefix('monitor-').removesuffix('.txt')
            lines.append(f"| {name} | {role} | {fields[1]} | {fields[3].split('=')[1]} |")
    lines += ['', '## Integridade e limites de medição', '',
              'Ensaios normais com divergência de ficheiros ou condição de relógio não validada: '
              + (', '.join(failed) if failed else 'nenhum') + '.', '',
              'As leituras enviadas enquanto um líder está terminado podem perder-se antes de receber seq. '
              'A igualdade entre ficheiros não prova que essas leituras chegaram. '
              'No ensaio com STOP/CONT, o processo e a subscrição continuam existentes; '
              'o destino efetivo das leituras pode ser verificado nos ficheiros do líder. '
              'A latência de lote começa no instante atribuído pelo líder ao registo; '
              'não inclui o tempo em que o IN aguarda antes de ser consumido pelo líder suspenso.', '',
              'Os intervalos de deteção previstos para Δ=5000 ms são ]10,16] segundos, '
              'ignorando atrasos de rede. A amostra de deteção inclui os participantes que acompanham '
              'a partição e a verificação discreta a cada segundo. '
              'Suspeitas sem retoma posterior são classificações limitadas à janela observada.', '',
              '## Erros deliberados — ensaio 9', '',
              'As variantes foram compiladas em pastas temporárias, isoladas do código entregue. '
              'O ensaio da corrida acrescenta 250 ms fora do bloqueio para ampliar a janela '
              'e tornar o erro observável; esta pausa não existe na implementação correta.', '',
              '| Erro | Observação medida | Verificadores / conclusão |', '| --- | --- | --- |']
    for name, explanation in (
        ('sem-vazios', 'O líder vivo deixa de sinalizar vida sem leituras; surgem suspeitas por silêncio.'),
        ('relogios-hb', 'O próprio invariante do líder pode impedir o append; verificar consola, não apenas ficheiros vazios.'),
        ('seq-fora-lock', 'O cabeçalho pode anunciar registos recebidos depois de fechar a lista; a recuperação pode antecipar o próximo lote.'),
        ('seq-zero-perdas', 'ultimoSeq=0 perde a informação da cauda; cabeçalhos incompatíveis com n são rejeitados por Batch.'),
        ('fator1', 'Timeout igual ao período torna o detetor mais sensível a atrasos; a pausa de 20 s provoca falsa suspeita.')):
        s = samples['9-' + name]
        errors = len(s['erros_consola'])
        causal_status = ('OK sem registos (não prova progresso)' if s['registos'] == 0
                         else 'OK' if s['causal_ok'] else 'falha')
        lines.append(f"| {name} | Suspeitas={s['suspeitas']}; falsas confirmadas por retoma={s['falsassuspeitas']}; HB_PERDIDO={s['hbperdido']}; recuperação={s['recuperacoes']}; inconsistências de lote={s['inconsistencias_lote']}; erros de consola={errors}; réplicas iguais={s['replicas_iguais']} | CausalCheck={causal_status}; {explanation} |")
    lines += ['', 'Consultar localmente `medicoes.json` e os relatórios por ensaio para os valores completos, '
              'incluindo erros de consola e resultados negativos. Um verificador que não encontra '
              'linhas inválidas não prova progresso nem ausência de leituras perdidas.', '']
    lines += ['## Leituras enviadas durante falhas e suspensão', '',
              '| Ensaio | Leituras enviadas | Leituras persistidas (exclui alarmes) | Diferença |',
              '| --- | --- | --- | --- |']
    for name, sample in samples.items():
        if name.startswith(('8c-', '8e-falha-')):
            lines.append(f"| {name} | 300 | {sample['leituras_persistidas']} | {300 - sample['leituras_persistidas']} |")
    paused = samples['8d-pausa-5000']
    lines.append(f"| 8d-pausa-5000 | 5 | {paused['leituras_persistidas']} | {5 - paused['leituras_persistidas']} |")
    lines += ['', '## Reflexão sobre os resultados', '',
              f"Nas execuções de 8(b/c), as publicações passaram de {show(m['mensagens03'])} REG equivalentes "
              f"para {show(m['mensagens04'])} lotes por execução, em média. "
              f"O payload médio por registo passou de {show(m['bytesreg'])} para {show(m['byteslote'])} bytes. "
              'Esta comparação inclui o custo dos heartbeats nas mensagens; aplicações muito pouco ativas '
              'podem emitir mais mensagens com heartbeats do que com publicação só por leitura.', '',
              'A estimativa Δ/2 pressupõe chegadas aproximadamente uniformes ao longo do ciclo. '
              'Uma rajada curta, iniciada numa fase particular entre heartbeats, não satisfaz essa hipótese '
              'e pode apresentar uma média diferente. Alarmes podem esperar até um período na origem '
              'e outro no destino, além de rede e processamento. Para alertas urgentes seria preferível '
              'desacoplar o heartbeat do envio de dados; essa alteração não foi implementada nesta ficha.', '',
              'Aumentar o período, mantendo fator=3, aumenta o timeout e atrasa a suspeita. '
              'Reduzir o fator acelera a deteção mas deixa menos margem para atrasos e pausas. '
              'A pausa STOP/CONT mantém o líder existente e a mesma incarnação: as suspeitas seguidas '
              'de retoma são falsas. Sem limite para atrasos de rede e velocidade dos processos, '
              'o silêncio não permite garantir exatidão.', '',
              'A Figura 1 demonstrou a utilidade de ultimoSeq num lote vazio: o registo final já '
              'existia no disco, mas nenhum registo posterior iria revelar a lacuna. O heartbeat '
              'vazio basta para anunciar essa cauda. Heartbeats não atualizam L nem V porque '
              'esses relógios representam os registos numerados; fazê-lo quebra V[k]=seq.', '',
              'Detetar a falha não elege um sucessor nem conserva leituras enviadas durante a '
              'indisponibilidade. Evitar essas perdas exigiria confirmação e retenção/reenvio; '
              'substituir o líder sem duas autoridades a atribuir seq exigiria coordenação, eleição '
              'e uma regra de exclusão/quórum. Esses mecanismos ficam fora do âmbito desta ficha.', '',
              'As medições offline comparam carimbos físicos de processos na mesma máquina. '
              'Em máquinas diferentes, diferenças de relógio contaminariam latência e deteção. '
              'O detetor online usa diferenças locais de System.nanoTime(), pelo que não depende '
              'da sincronização dos relógios entre máquinas.', '']
    (root / 'RESULTADOS.md').write_text('\n'.join(lines))
    print(root / 'RESULTADOS.md')


if __name__ == '__main__':
    main()
