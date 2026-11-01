#!/usr/bin/env python3
"""Ensaios 8(a-e) e erros 9, com processos reais e IPFS/Kubo local.

Demora vários minutos: os períodos, pausas e 30 s de indisponibilidade são reais.
As variantes erradas são compiladas numa pasta temporária; o código entregue
nunca é modificado. Guarda dados brutos, ações e relatórios em resultados/.
"""
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request

BASE = Path(__file__).resolve().parent
API = 'http://127.0.0.1:5001/api/v0'
ASSIGNMENTS = {1: (0, 1), 2: (1, 2), 3: (0, 2)}


def now():
    return time.time_ns() // 1_000_000


def wait(predicate, description, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.025)
    raise RuntimeError('Timeout: ' + description)


def rows(path):
    return [line.split(';') for line in path.read_text().splitlines() if line.strip()] if path.exists() else []


def compile_projects(root, mutation=None):
    outputs = {}
    for project in sorted(BASE.glob('rep04-*')):
        target = root / project.name
        source = target / 'src/rep04'
        shutil.copytree(project / 'src/rep04', source)
        if mutation and project.name == 'rep04-lider':
            p = source / 'PartitionLeader.java'
            s = p.read_text()
            if mutation == 'sem-vazios':
                s = s.replace('List<SensorRecord> fechados = pendentes;',
                              'if (pendentes.isEmpty()) return;\n                List<SensorRecord> fechados = pendentes;')
            elif mutation == 'relogios-hb':
                s = s.replace('List<SensorRecord> fechados = pendentes;',
                              'lamport++; vector.tick(k);\n                List<SensorRecord> fechados = pendentes;')
            elif mutation == 'seq-fora-lock':
                # A pausa torna reprodutível a janela entre a troca e a leitura.
                # Não faz parte da solução correta.
                s = s.replace('Batch batch;\n            synchronized', 'Batch batch;\n            List<SensorRecord> fechados;\n            long numero;\n            synchronized')
                s = s.replace('List<SensorRecord> fechados = pendentes;', 'fechados = pendentes;')
                s = s.replace('batch = new Batch(host, porto, inc, ++hb, currentSeq, fechados);', 'numero = ++hb;')
                s = s.replace('// A rede nunca bloqueia',
                              'Thread.sleep(250);\n            batch = new Batch(host, porto, inc, numero, currentSeq, fechados);\n            // A rede nunca bloqueia')
            elif mutation == 'seq-zero-perdas':
                # Batch valida o cabeçalho, pelo que a corrupção é feita no fio.
                s = s.replace('String msg = batch.encode();',
                              'if (batch.hb % 10 == 0) return;\n            String msg = batch.encode().replaceFirst("(LOTE;[^;]+;[^;]+;[^;]+;[^;]+;)[^;]+;", "$10;");')
            p.write_text(s)
        output = target / 'bin'
        output.mkdir()
        subprocess.run(['javac', '--release', '11', '-d', str(output),
                        *map(str, sorted(source.glob('*.java')))], check=True)
        outputs[project.name] = output
    return outputs


class Trial:
    def __init__(self, root, name, classes, period=5000, factor=3):
        self.path = root / name
        self.path.mkdir()
        self.classes, self.period, self.factor = classes, period, factor
        self.processes, self.handles, self.actions, self.leaders = [], [], [], {}
        self.action('INICIO', periodo=period, fator=factor)
        self.producer = None

    def action(self, event, **data):
        self.actions.append(dict(instante=now(), evento=event, **data))
        (self.path / 'acoes.json').write_text(json.dumps(self.actions, indent=2, ensure_ascii=False) + '\n')

    def start(self, project, classname, *args):
        handle = open(self.path / f'consola-{classname}-{len(self.processes)}.log', 'w')
        self.handles.append(handle)
        proc = subprocess.Popen(['java', f'-Drep04.periodo={self.period}', f'-Drep04.fator={self.factor}',
                                 '-cp', str(self.classes[project]), 'rep04.' + classname, *map(str, args)],
                                cwd=self.path, stdin=subprocess.PIPE, stdout=handle,
                                stderr=subprocess.STDOUT, text=True)
        self.processes.append(proc)
        return proc

    def start_leader(self, k):
        self.leaders[k] = self.start('rep04-lider', 'PartitionLeader', k, 3, '127.0.0.1', API)
        self.action('ARRANQUE_LIDER', particao=k, pid=self.leaders[k].pid)

    def start_all(self, expect_ready=True):
        for i, partitions in ASSIGNMENTS.items():
            self.start('rep04-replica', 'PartitionReplica', i, ','.join(map(str, partitions)), 5001)
        for k in range(3):
            self.start_leader(k)
        self.start('rep04-processador', 'AlarmProcessor', 3, API)
        self.producer = self.start('rep04-produtor', 'Producer', 3, API)
        if expect_ready:
            watched = {**{f'replica{i}': partitions for i, partitions in ASSIGNMENTS.items()},
                       'produtor': (0, 1, 2), 'processador': (0, 1, 2)}
            wait(lambda: all(f'RECEBIDO;p{k};' in self.text(f'monitor-{p}.txt')
                             for p, partitions in watched.items() for k in partitions),
                 self.path.name + ': primeiros heartbeats', timeout=self.period / 1000 * 3 + 12)
        else:
            time.sleep(2)

    def text(self, name):
        p = self.path / name
        return p.read_text() if p.exists() else ''

    def command(self, command):
        self.action('COMANDO', comando=command)
        self.producer.stdin.write(command + '\n')
        self.producer.stdin.flush()

    def burst(self, pause=0):
        self.command('rajada 300 10' + (f' {pause}' if pause else ''))

    def burst_finished(self):
        return any('Envio concluído.' in p.read_text() for p in self.path.glob('consola-Producer-*.log'))

    def kill_leader(self, k):
        instant = now()
        self.leaders[k].kill()
        self.leaders[k].wait(timeout=5)
        with open(self.path / 'falhas.txt', 'a') as file:
            file.write(f'p{k};{instant}\n')
        self.action('FALHA', particao=k, instante_falha=instant)

    def drain(self):
        # Dois períodos permitem também publicar alarmes derivados do último lote.
        time.sleep(self.period / 1000 * 2 + 1)

    def close(self):
        self.action('FIM')
        for proc in self.processes:
            if proc.poll() is None:
                os.kill(proc.pid, signal.SIGCONT)
                proc.terminate()
        for proc in self.processes:
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        for handle in self.handles:
            handle.close()

    def reports(self):
        for classname in ('HeartbeatCheck', 'CausalCheck', 'PartitionCheck'):
            args = ['3', str(self.path)]
            if classname == 'HeartbeatCheck' and (self.path / 'falhas.txt').exists():
                args.append(str(self.path / 'falhas.txt'))
            result = subprocess.run(['java', '-cp', str(self.classes['rep04-replica']), 'rep04.' + classname, *args],
                                    capture_output=True, text=True)
            (self.path / (classname + '.txt')).write_text(result.stdout + result.stderr)
            if result.returncode:
                self.action('ERRO_VERIFICADOR', classe=classname, codigo=result.returncode)
        # A comparação completa complementa o PartitionCheck herdado (que compara seq).
        comparison = {}
        for i, partitions in ASSIGNMENTS.items():
            for k in partitions:
                comparison[f'replica{i}-p{k}'] = self.text(f'lider-p{k}.txt') == self.text(f'replica-{i}-p{k}.txt')
        (self.path / 'igualdade.json').write_text(json.dumps(comparison, indent=2) + '\n')


def run_trial(root, name, classes, operation, period=5000, factor=3, ready=True):
    print('INICIO ' + name, flush=True)
    trial = Trial(root, name, classes, period, factor)
    try:
        trial.start_all(ready)
        operation(trial)
    finally:
        trial.close()
        trial.reports()
    print('FIM ' + name, flush=True)


def figure(trial):
    # S01 e S04 pertencem a p0; a cauda é escrita após um heartbeat vazio.
    trial.command('S01 21.0')
    time.sleep(1)
    trial.command('S04 22.5')
    wait(lambda: len(rows(trial.path / 'replica-1-p0.txt')) == 2, 'primeiros dois registos')
    old = len(rows(trial.path / 'hb-p0.txt'))
    wait(lambda: len(rows(trial.path / 'hb-p0.txt')) > old
         and rows(trial.path / 'hb-p0.txt')[-1][4] == '0', 'lote vazio')
    trial.command('S01 21.4')
    wait(lambda: len(rows(trial.path / 'lider-p0.txt')) == 3, 'cauda persistida')
    trial.kill_leader(0)
    assert len(rows(trial.path / 'replica-1-p0.txt')) == 2
    wait(lambda: 'SUSPEITA;p0' in trial.text('monitor-replica1.txt'), 'suspeita')
    trial.start_leader(0)
    wait(lambda: len(rows(trial.path / 'replica-1-p0.txt')) == 3
         and len(rows(trial.path / 'replica-3-p0.txt')) == 3, 'recuperação da cauda nas duas réplicas')
    trial.drain()


def burst(trial):
    trial.burst()
    wait(trial.burst_finished, 'rajada concluída')
    trial.drain()


def idle(trial):
    trial.action('INICIO_MINUTO_PARADO')
    time.sleep(60)
    trial.action('FIM_MINUTO_PARADO')


def failure(trial):
    trial.burst(10)
    time.sleep(0.7)
    trial.kill_leader(1)
    time.sleep(30)
    trial.start_leader(1)
    wait(lambda: 'REINICIO;p1' in trial.text('monitor-replica1.txt'), 'reinício p1', timeout=30)
    trial.drain()


def pause(trial):
    trial.action('SUSPENSAO', particao=2)
    os.kill(trial.leaders[2].pid, signal.SIGSTOP)
    for i in range(5):
        trial.command(f'S03 {21 + i / 10:.1f}')
        time.sleep(0.2)
    time.sleep(19)
    os.kill(trial.leaders[2].pid, signal.SIGCONT)
    trial.action('CONTINUACAO', particao=2)
    wait(lambda: 'RETOMA;p2' in trial.text('monitor-replica2.txt'), 'retoma p2')
    trial.drain()


def fault_nonempty(trial):
    trial.command('S01 21.0')
    wait(lambda: len(rows(trial.path / 'replica-1-p0.txt')) == 1, 'lote não vazio inicial')
    time.sleep(17)


def fault_clocks(trial):
    trial.command('S01 21.0')
    trial.drain()


def fault_race(trial):
    trial.command('rajada 300 10 30')
    wait(trial.burst_finished, 'rajada concorrente')
    trial.drain()


def fault_zero(trial):
    for i in range(12):
        trial.command(f'S01 {20 + i / 10:.1f}')
        time.sleep(5.05)
    trial.drain()


def main():
    with urllib.request.urlopen(urllib.request.Request(API + '/version', data=b''), timeout=3) as response:
        version = json.load(response)['Version']
    root = BASE / 'resultados' / time.strftime('%Y%m%d-%H%M%S')
    root.mkdir(parents=True)
    (root / 'ambiente.json').write_text(json.dumps(dict(ipfs=version, maquina='mesma máquina', N=3,
        java=subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True),
        instante=now()), ensure_ascii=False, indent=2) + '\n')
    print('RESULTADOS=' + str(root), flush=True)
    with tempfile.TemporaryDirectory(prefix='rep04-ensaios-') as temporary:
        classes = compile_projects(Path(temporary) / 'correto')
        run_trial(root, '8a-figura', classes, figure)
        run_trial(root, '8b-rajada-5000', classes, burst)
        run_trial(root, '8b-parado-5000', classes, idle)
        for i in range(1, 4):
            run_trial(root, f'8c-falha-5000-{i}', classes, failure)
        run_trial(root, '8d-pausa-5000', classes, pause)
        for period in (1000, 10000):
            run_trial(root, f'8e-rajada-{period}', classes, burst, period)
            run_trial(root, f'8e-parado-{period}', classes, idle, period)
            run_trial(root, f'8e-falha-{period}', classes, failure, period)
        for mutation, operation, ready in (
                ('sem-vazios', fault_nonempty, False), ('relogios-hb', fault_clocks, True),
                ('seq-fora-lock', fault_race, True), ('seq-zero-perdas', fault_zero, True)):
            mutated = compile_projects(Path(temporary) / mutation, mutation)
            run_trial(root, '9-' + mutation, mutated, operation, ready=ready)
        run_trial(root, '9-fator1', classes, pause, factor=1)
    print('CONCLUIDO=' + str(root), flush=True)


if __name__ == '__main__':
    main()
