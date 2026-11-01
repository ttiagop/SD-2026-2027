#!/usr/bin/env python3
"""Compila em Java 11 e testa os processos com uma API PubSub local simulada.

Não substitui os ensaios com IPFS/Kubo. Não altera ficheiros dos outros sprints.
"""
import base64
import http.server
import json
import pathlib
import queue
import subprocess
import tempfile
import threading
import time
import urllib.parse
import urllib.request
import sys

BASE = pathlib.Path(__file__).resolve().parent


def encoded(value):
    return "u" + base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")


def decoded(value):
    return base64.urlsafe_b64decode(value[1:] + "=" * (-len(value[1:]) % 4)).decode()


class Broker(http.server.BaseHTTPRequestHandler):
    topics = {}
    lock = threading.Lock()
    drops = set()

    def log_message(self, *args):
        pass

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        topic = decoded(urllib.parse.parse_qs(url.query)["arg"][0])
        if url.path.endswith("/sub"):
            messages = queue.Queue()
            with self.lock:
                self.topics.setdefault(topic, []).append(messages)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.flush()
            try:
                while True:
                    message = messages.get()
                    self.wfile.write((json.dumps({"data": encoded(message)}, separators=(",", ":")) + "\n").encode())
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                with self.lock:
                    self.topics[topic].remove(messages)
        else:
            body = self.rfile.read(int(self.headers["Content-Length"]))
            message = body.split(b"\r\n\r\n", 1)[1].rsplit(b"\r\n--", 1)[0].decode()
            with self.lock:
                if topic in self.drops:
                    self.drops.remove(topic)
                else:
                    for messages in self.topics.get(topic, []):
                        messages.put(message)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"{}")


def wait_for(predicate, description, seconds=12):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(0.02)
    raise AssertionError("Timeout: " + description)


def main():
    real_ipfs = "--ipfs" in sys.argv
    with tempfile.TemporaryDirectory(prefix="rep04-check-") as folder:
        root = pathlib.Path(folder)
        classes = {}
        for project in sorted(BASE.glob("rep04-*")):
            output = root / "classes" / project.name
            output.mkdir(parents=True)
            subprocess.run(["javac", "--release", "11", "-d", str(output),
                            *map(str, sorted((project / "src/rep04").glob("*.java")))], check=True)
            classes[project.name] = output
        print("OK: quatro projetos compilados para Java 11", flush=True)

        server = None
        if real_ipfs:
            port = 5001
        else:
            server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Broker)
            server.daemon_threads = True
            threading.Thread(target=server.serve_forever, daemon=True).start()
            port = server.server_port
        api = f"http://127.0.0.1:{port}/api/v0"
        processes, handles = [], []
        work = root / "ensaio"
        work.mkdir()

        def start(project, main_class, *args):
            log = open(root / f"{main_class}-{len(processes)}.log", "w")
            handles.append(log)
            proc = subprocess.Popen(["java", "-Drep04.periodo=500", "-Drep04.fator=3", "-Ddescartar=2",
                                     "-cp", str(classes[project]), "rep04." + main_class,
                                     *map(str, args)], cwd=work, stdin=subprocess.PIPE,
                                    stdout=log, stderr=subprocess.STDOUT, text=True)
            processes.append(proc)
            return proc

        def text(name):
            p = work / name
            return p.read_text() if p.exists() else ""

        def count(name):
            return len(text(name).splitlines())

        def publish(topic, message):
            if real_ipfs:
                boundary = "rep04-test-boundary"
                body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"msg\""
                        f"\r\nContent-Type: application/octet-stream\r\n\r\n{message}\r\n--{boundary}--\r\n")
                req = urllib.request.Request(api + "/pubsub/pub?arg=" + encoded(topic),
                                             data=body.encode(), headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
                with urllib.request.urlopen(req, timeout=5) as response:
                    assert response.status == 200
                return
            with Broker.lock:
                for q in Broker.topics.get(topic, []):
                    q.put(message)

        try:
            for i, partitions in ((1, "0,1"), (2, "1,2"), (3, "0,2")):
                start("rep04-replica", "PartitionReplica", i, partitions, port)
            leaders = [start("rep04-lider", "PartitionLeader", k, 3, "127.0.0.1", api) for k in range(3)]
            start("rep04-processador", "AlarmProcessor", 3, api)
            producer = start("rep04-produtor", "Producer", 3, api)
            if not real_ipfs:
                wait_for(lambda: all(len(Broker.topics.get(f"rep04-p{k}", [])) == 4
                                     and Broker.topics.get(f"rep04-in-p{k}") for k in range(3)), "subscrições")
            watched = {"replica1": (0, 1), "replica2": (1, 2), "replica3": (0, 2),
                       "processador": (0, 1, 2), "produtor": (0, 1, 2)}
            wait_for(lambda: all(f"RECEBIDO;p{k};" in text(f"monitor-{id}.txt")
                                 for id, partitions in watched.items() for k in partitions), "heartbeat vazio em todas as partições")
            producer.stdin.write("S01 29.0\n")
            producer.stdin.flush()
            wait_for(lambda: "ALARM-S01" in text("lider-p1.txt"), "alarme entre partições")
            wait_for(lambda: "ALARM-S01" in text("replica-2-p1.txt"), "alarme aplicado")
            print("OK: produtor, três líderes, três réplicas e processador de alarmes", flush=True)

            # Perde-se um lote inteiro; o heartbeat seguinte deve recuperar também a cauda.
            previous = count("replica-1-p0.txt")
            if not real_ipfs:
                with Broker.lock:
                    Broker.drops.add("rep04-p0")
            publish("rep04-in-p0", "IN;S01;21.4;0;0,0,0")
            wait_for(lambda: count("lider-p0.txt") > previous, "persistência antes da publicação")
            wait_for(lambda: count("replica-1-p0.txt") == count("lider-p0.txt")
                     and (real_ipfs or "HB_PERDIDO" in text("monitor-replica1.txt"))
                     and "RECUPERACAO" in text("monitor-replica1.txt"), "recuperação pelo lote vazio")
            print("OK: perda simulada e recuperação TCP pelo ultimoSeq" if real_ipfs else
                  "OK: lote perdido, HB_PERDIDO e recuperação TCP pelo ultimoSeq", flush=True)

            failure = int(time.time() * 1000)
            leaders[0].kill()
            leaders[0].wait(timeout=5)
            (work / "falhas.txt").write_text(f"p0;{failure}\n")
            wait_for(lambda: all("SUSPEITA;p0" in text(f"monitor-{id}.txt")
                                 for id in ("replica1", "replica3", "processador", "produtor")), "suspeita de falha")
            leaders[0] = start("rep04-lider", "PartitionLeader", 0, 3, "127.0.0.1", api)
            wait_for(lambda: "REINICIO;p0" in text("monitor-replica1.txt")
                     and "RETOMA;p0" in text("monitor-replica1.txt"), "reinício e retoma")
            print("OK: falha, suspeita nos quatro participantes, reinício e retoma", flush=True)

            # Suspensão do processo real provoca suspeita e retoma na mesma incarnação.
            import os
            import signal
            os.kill(leaders[2].pid, signal.SIGSTOP)
            wait_for(lambda: "SUSPEITA;p2" in text("monitor-replica2.txt"), "falsa suspeita")
            os.kill(leaders[2].pid, signal.SIGCONT)
            wait_for(lambda: "RETOMA;p2" in text("monitor-replica2.txt"), "retoma sem reinício")
            print("OK: pausa e falsa suspeita na mesma incarnação", flush=True)

            producer.stdin.write("sair\n")
            producer.stdin.flush()
            producer.wait(timeout=8)
            for proc in processes:
                if proc.poll() is None:
                    proc.terminate()
            for proc in processes:
                proc.wait(timeout=8)
            for k in range(3):
                for replica, ps in ((1, (0, 1)), (2, (1, 2)), (3, (0, 2))):
                    if k in ps:
                        assert text(f"lider-p{k}.txt") == text(f"replica-{replica}-p{k}.txt")
            check = subprocess.run(["java", "-cp", str(classes["rep04-replica"]),
                                    "rep04.HeartbeatCheck", "3", str(work), str(work / "falhas.txt")],
                                   capture_output=True, text=True, check=True)
            assert "FALSA SUSPEITA" in check.stdout and "DETECAO;p0" in check.stdout
            causal = subprocess.run(["java", "-cp", str(classes["rep04-replica"]),
                                     "rep04.CausalCheck", "3", str(work)],
                                    capture_output=True, text=True, check=True)
            assert "Validação V[k] == seq: OK" in causal.stdout
            assert "Violações da Condição de Relógio (a -> b com L(a) >= L(b)): 0" in causal.stdout
            print("OK: ficheiros das réplicas iguais aos líderes, HeartbeatCheck e CausalCheck", flush=True)
        except BaseException:
            for log in sorted(root.glob("*.log")):
                print(log.name + ":\n" + log.read_text()[-4000:])
            raise
        finally:
            for proc in processes:
                if proc.poll() is None:
                    proc.kill()
                proc.wait()
            for handle in handles:
                handle.close()
            if server is not None:
                server.shutdown()


if __name__ == "__main__":
    main()
