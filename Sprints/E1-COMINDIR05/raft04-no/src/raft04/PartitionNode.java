package raft04;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Nó de uma partição de dados k: seguidor, candidato ou líder.
 * Junta o PartitionLeader e a PartitionReplica da COMINDIR04.
 *
 * Locks: o monitor do nó (this) protege o registo local e o estado de líder;
 * o monitor de RaftState protege termo, voto e papel. A ordem é sempre
 * this -> RaftState (nunca o inverso: RaftState só chama event(), que usa um lock próprio).
 */
public class PartitionNode {
    private final int k, self, n;
    private final String host = "127.0.0.1";
    private final int port;
    private final Partitioner partitioner;
    private final IpfsPubSub ps;
    private final RaftState raft;

    private final Path logFile, hbFile, eventsFile, truncFile;
    private final Object eventsLock = new Object();

    // registo local (espelho do ficheiro no-p<k>-n<i>.txt): o índice i guarda o seq i+1
    private final List<SensorRecord> log = new ArrayList<>();

    // estado de líder (só usado enquanto raft.termIfLeader() != -1)
    private long seq, lamport, hb, leaderTerm = -1;
    private VectorClock vector;
    private List<SensorRecord> pendentes = new ArrayList<>();
    private ScheduledFuture<?> loteTask;

    // líder conhecido, para a recuperação por TCP
    private String leaderHost = "127.0.0.1";
    private int leaderPort;
    // termo em que o registo local já foi comparado com o do líder (-1 = ainda não)
    private long verificadoNoTermo = -1;

    private final ScheduledExecutorService verificacao = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService lotes = Executors.newSingleThreadScheduledExecutor();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: PartitionNode <k> <i> [N=3] [api]");
            System.exit(1);
        }
        int k = Integer.parseInt(args[0]);
        int i = Integer.parseInt(args[1]);
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        String api = args.length > 3 ? args[3] : "http://127.0.0.1:5001/api/v0";
        if (k < 0 || k >= n || i < 1 || i > Wire.GRUPO) throw new IllegalArgumentException("k ou i fora dos limites");
        new PartitionNode(k, i, n, api).start();
    }

    PartitionNode(int k, int self, int n, String api) throws IOException {
        this.k = k;
        this.self = self;
        this.n = n;
        this.port = Wire.nodePort(k, self);
        this.partitioner = new Partitioner(n);
        this.ps = new IpfsPubSub(api);
        this.logFile = Paths.get("no-p" + k + "-n" + self + ".txt");
        this.hbFile = Paths.get("hb-p" + k + "-n" + self + ".txt");
        this.eventsFile = Paths.get("eventos-p" + k + "-n" + self + ".txt");
        this.truncFile = Paths.get("truncados-p" + k + "-n" + self + ".txt");

        // 1. lê o ficheiro local e determina ultimoSeq e ultimoTermo
        if (Files.exists(logFile)) {
            for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                SensorRecord r = SensorRecord.fromLine(line, n);
                if (r.getSeq() != log.size() + 1) throw new IllegalStateException("Ficheiro local com lacuna em " + r.getSeq());
                log.add(r);
            }
        }
        // 2. RaftState como seguidor, com o termo gravado
        this.raft = new RaftState(k, self, Wire.GRUPO, Wire.ELEICAO_MIN_MS, Wire.ELEICAO_MAX_MS,
                Paths.get("estado-p" + k + "-n" + self + ".txt"), this::event);
    }

    private void start() {
        event("ARRANQUE;p" + k + ";n" + self + ";termo=" + raft.term() + ";ultimoSeq=" + lastSeq()
                + ";ultimoTermo=" + lastTerm());
        // 3. servidor TCP de recuperação: serve registos a qualquer nó, seja ou não líder
        thread("tcp-p" + k + "-n" + self, this::servirRecuperacao);
        // 4. subscrições (ignorando as mensagens que o próprio nó publicou)
        subscrever(Wire.topicIn(k), this::onIn);
        subscrever(Wire.topicData(k), this::onLote);
        subscrever(Wire.topicElection(k), this::onEleicao);
        // 5. thread de verificação: tick() a cada VERIFICACAO_MS
        verificacao.scheduleAtFixedRate(this::verificar, Wire.VERIFICACAO_MS, Wire.VERIFICACAO_MS, TimeUnit.MILLISECONDS);
        System.out.println("Nó n" + self + " de p" + k + " pronto (porto TCP " + port + ")");
    }

    // ------------------------------------------------------------------ eleição

    private void verificar() {
        try {
            VoteRequest pedido = null;
            synchronized (this) {
                long t = raft.tick();                          // grava o estado antes de devolver
                if (t > 0) pedido = new VoteRequest(t, self, lastSeq(), lastTerm());
            }
            if (pedido != null && !Wire.isolated(k, self)) publicar(Wire.topicElection(k), pedido.encode());
        } catch (Exception e) {
            event("ERRO;p" + k + ";verificacao: " + e.getMessage());
        }
    }

    private void onEleicao(String msg) {
        if (Wire.isolated(k, self)) return;
        String tipo = Wire.type(msg);
        if (tipo.equals("PEDIDO_VOTO")) {
            VoteRequest r = VoteRequest.decode(msg);
            if (r.candidate == self) return;
            VoteReply resposta;
            synchronized (this) {
                RaftState.Vote v = raft.onVoteRequest(r.term, r.candidate, r.lastSeq, r.lastTerm, lastSeq(), lastTerm());
                resposta = new VoteReply(v.term, self, r.candidate, v.granted);
            }
            // responde sempre, mesmo quando recusa: o termo da resposta pode demitir o candidato
            publicar(Wire.topicElection(k), resposta.encode());
        } else if (tipo.equals("VOTO")) {
            VoteReply v = VoteReply.decode(msg);
            if (v.voter == self || v.candidate != self) return;       // só os votos dirigidos a este nó
            synchronized (this) {
                if (raft.onVote(v.term, v.voter, v.candidate, v.granted)) tornarLider();
            }
        }
    }

    // ------------------------------------------------------------------ líder

    // chamado com o monitor do nó adquirido
    private void tornarLider() {
        long t = raft.termIfLeader();
        if (t < 0) return;
        seq = lastSeq();
        SensorRecord ultimo = log.isEmpty() ? null : log.get(log.size() - 1);
        lamport = ultimo == null ? 0 : ultimo.getLamport();
        vector = ultimo == null ? new VectorClock(n) : ultimo.getVector().copy();
        hb = 0;
        pendentes = new ArrayList<>();
        leaderTerm = t;
        if (loteTask != null) loteTask.cancel(false);
        loteTask = lotes.scheduleAtFixedRate(this::enviarLote, 0, Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);
        System.out.println("*** LIDER de p" + k + " no termo " + t + ": seq=" + seq + " L=" + lamport + " V=" + vector);
    }

    private void onIn(String msg) {
        if (!Wire.type(msg).equals("IN")) return;
        String[] p = Wire.body(msg).split(";");
        if (p.length != 4) { event("ERRO;p" + k + ";IN mal formado: " + msg); return; }
        String sensor = p[0];
        double temp = Double.parseDouble(p[1]);
        long msgL = Long.parseLong(p[2]);
        VectorClock msgV = VectorClock.parse(p[3], n);
        if (partitioner.of(sensor) != k) { event("ERRO;p" + k + ";IN de outra particao: " + sensor); return; }

        synchronized (this) {
            long t = raft.termIfLeader();
            if (t == -1) {
                event("IGNORADO;p" + k + ";IN " + sensor + " " + temp + ";nao e lider");
                return;
            }
            lamport = Math.max(lamport, msgL) + 1;
            vector.merge(msgV);
            vector.tick(k);
            seq++;
            if (vector.get(k) != seq)
                throw new IllegalStateException("Violação de invariante: V[" + k + "]=" + vector.get(k) + " != seq=" + seq);
            SensorRecord r = SensorRecord.now(seq, t, sensor, temp, lamport, vector.copy());
            acrescentar(r);
            pendentes.add(r);
            System.out.println("EVENTO: seq=" + seq + " termo=" + t + " L=" + lamport + " V=" + vector);
        }
    }

    private void enviarLote() {
        try {
            Batch lote;
            synchronized (this) {
                long t = raft.termIfLeader();
                if (t == -1 || t != leaderTerm) {                   // demitido: cancela o agendamento
                    if (loteTask != null) loteTask.cancel(false);
                    loteTask = null;
                    return;
                }
                List<SensorRecord> fechados = pendentes;
                pendentes = new ArrayList<>();
                hb++;
                lote = new Batch(t, self, host, port, hb, lastSeq(), lastTerm(), fechados);
            }
            if (Wire.isolated(k, self)) return;                     // partição de rede: não publica
            String msg = lote.encode();
            long instante = System.currentTimeMillis();
            publicar(Wire.topicData(k), msg);
            String linha = lote.term + ";" + lote.hb + ";" + instante + ";" + lote.lastSeq + ";" + lote.lastTerm
                    + ";" + lote.records.size() + ";" + msg.getBytes(StandardCharsets.UTF_8).length;
            Files.writeString(hbFile, linha + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            event("ERRO;p" + k + ";publicacao do lote: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ seguidor

    private void onLote(String msg) {
        if (Wire.isolated(k, self)) return;
        Batch lote = Batch.decode(msg, n);
        if (lote.leader == self) return;
        synchronized (this) {
            if (!raft.onBatch(lote.term, lote.leader)) {
                event("IGNORADO;p" + k + ";termo=" + lote.term + ";lider=n" + lote.leader + ";hb=" + lote.hb);
                return;
            }
            String intervalo = lote.records.isEmpty() ? "vazio"
                    : lote.records.get(0).getSeq() + ".." + lote.records.get(lote.records.size() - 1).getSeq();
            event("RECEBIDO;p" + k + ";termo=" + lote.term + ";lider=n" + lote.leader + ";hb=" + lote.hb
                    + ";ultimoSeq=" + lote.lastSeq + ";ultimoTermo=" + lote.lastTerm + ";n=" + lote.records.size()
                    + ";seq=" + intervalo);
            leaderHost = lote.address;
            leaderPort = lote.port;
            // primeiro lote de um líder novo: compara o registo local com o do líder antes de aplicar
            // (um antigo líder pode ter registos que nunca publicou, com o mesmo seq que o novo líder reutilizou)
            if (verificadoNoTermo != lote.term) {
                if (log.isEmpty() || resolverDivergencia(lote)) verificadoNoTermo = lote.term;
                else return;
            }
            aplicarLote(lote);
        }
    }

    // chamado com o monitor do nó adquirido
    private void aplicarLote(Batch lote) {
        for (SensorRecord r : lote.records) {
            long s = r.getSeq();
            if (s <= lastSeq()) {
                if (log.get((int) s - 1).getTerm() == r.getTerm()) continue;   // duplicado
                resolverDivergencia(lote);                                    // mesmo seq, outro termo
                return;
            }
            if (s > lastSeq() + 1 && !recuperar(lastSeq() + 1, s - 1)) return; // lacuna
            if (s == lastSeq() + 1) acrescentar(r);
        }
        // fim do lote: compara o último registo local com ultimoSeq e ultimoTermo
        if (lastSeq() < lote.lastSeq && !recuperar(lastSeq() + 1, lote.lastSeq)) return;
        boolean aMais = lastSeq() > lote.lastSeq;
        boolean termoDiferente = lote.lastSeq > 0 && log.get((int) lote.lastSeq - 1).getTerm() != lote.lastTerm;
        if (aMais || termoDiferente) resolverDivergencia(lote);
    }

    // pede ao líder os registos em falta; devolve true se ficou com todos até 'ate'
    private boolean recuperar(long de, long ate) {
        if (de > ate) return true;
        try {
            List<SensorRecord> recebidos = pedir(de, ate);
            for (SensorRecord r : recebidos) if (r.getSeq() == lastSeq() + 1) acrescentar(r);
            event("RECUPERACAO;p" + k + ";de=" + de + ";ate=" + ate + ";recebidos=" + recebidos.size());
        } catch (IOException e) {
            event("ERRO;p" + k + ";recuperacao " + de + ".." + ate + " falhou: " + e.getMessage());
        }
        return lastSeq() >= ate;
    }

    // divergência: recua por blocos de termo até encontrar um registo coincidente e trunca a partir daí;
    // devolve true se o registo local ficou coerente com o do líder
    private boolean resolverDivergencia(Batch lote) {
        if (log.isEmpty()) return true;
        try {
            long fimBloco = lastSeq();
            while (true) {
                long inicio = inicioDoBloco(fimBloco);
                List<SensorRecord> doLider = inicio <= lote.lastSeq ? pedir(inicio, lote.lastSeq) : List.of();
                boolean coincide = !doLider.isEmpty() && doLider.get(0).getSeq() == inicio
                        && doLider.get(0).getTerm() == log.get((int) inicio - 1).getTerm();
                if (coincide || inicio <= 1) {
                    truncarEAplicar(inicio, doLider);
                    return true;
                }
                fimBloco = inicio - 1;                                  // recua um bloco de termo
            }
        } catch (IOException e) {
            event("ERRO;p" + k + ";divergencia por resolver: " + e.getMessage());
            return false;
        }
    }

    // primeiro seq do bloco de termo que contém o seq indicado
    private long inicioDoBloco(long fim) {
        if (fim <= 0) return 1;
        long termo = log.get((int) fim - 1).getTerm();
        long s = fim;
        while (s > 1 && log.get((int) s - 2).getTerm() == termo) s--;
        return s;
    }

    private void truncarEAplicar(long inicio, List<SensorRecord> doLider) throws IOException {
        Map<Long, SensorRecord> lider = new HashMap<>();
        for (SensorRecord r : doLider) lider.put(r.getSeq(), r);
        // primeiro seq em que o registo local e o do líder diferem
        long divergente = inicio;
        while (divergente <= lastSeq() && lider.containsKey(divergente)
                && lider.get(divergente).getTerm() == log.get((int) divergente - 1).getTerm()) divergente++;

        List<SensorRecord> descartados = new ArrayList<>(log.subList((int) divergente - 1, log.size()));
        List<SensorRecord> novos = new ArrayList<>(log.subList(0, (int) divergente - 1));
        for (long s = divergente; lider.containsKey(s); s++) novos.add(lider.get(s));
        reescrever(novos);

        if (!descartados.isEmpty()) {
            Set<Long> termos = new TreeSet<>();
            StringBuilder linhas = new StringBuilder();
            long agora = System.currentTimeMillis();
            for (SensorRecord r : descartados) {
                termos.add(r.getTerm());
                linhas.append(agora).append(';').append(r.toLine()).append(System.lineSeparator());
            }
            Files.writeString(truncFile, linhas.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            event("TRUNCADO;p" + k + ";de=" + divergente + ";descartados=" + descartados.size() + ";termos=" + termos);
        } else if (lastSeq() >= divergente) {
            event("RECUPERACAO;p" + k + ";de=" + divergente + ";ate=" + lastSeq() + ";recebidos=" + (lastSeq() - divergente + 1));
        }
    }

    // ------------------------------------------------------------------ TCP de recuperação

    private List<SensorRecord> pedir(long de, long ate) throws IOException {
        if (Wire.isolated(k, self)) throw new IOException("no isolado");
        List<SensorRecord> res = new ArrayList<>();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(leaderHost, leaderPort), Wire.T_TCP_MS);
            s.setSoTimeout(Wire.T_TCP_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println("PEDIDO " + de + " " + ate);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("FIM")) return res;
                if (line.startsWith("ERRO")) throw new IOException(line);
                res.add(SensorRecord.fromLine(line, n));
            }
        }
        throw new IOException("ligação terminada sem FIM");
    }

    private void servirRecuperacao() {
        try (ServerSocket server = new ServerSocket(port)) {
            while (true) {
                try (Socket s = server.accept()) {
                    if (Wire.isolated(k, self)) continue;                // partição de rede: a ligação falha
                    s.setSoTimeout(Wire.T_TCP_MS);
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                    String[] t = Optional.ofNullable(in.readLine()).orElse("").trim().split("\\s+");
                    if (t.length != 3 || !t[0].equals("PEDIDO")) { out.println("ERRO Formato: PEDIDO <de> <ate>"); continue; }
                    long de = Long.parseLong(t[1]), ate = Long.parseLong(t[2]);
                    if (de < 1 || de > ate) { out.println("ERRO Intervalo inválido"); continue; }
                    List<SensorRecord> copia;
                    synchronized (this) {
                        int a = (int) Math.min(ate, lastSeq());
                        copia = de > a ? List.of() : new ArrayList<>(log.subList((int) de - 1, a));
                    }
                    for (SensorRecord r : copia) out.println(r.toLine());
                    out.println("FIM");
                } catch (Exception e) {
                    System.err.println("Pedido TCP falhou: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            event("ERRO;p" + k + ";servidor TCP no porto " + port + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ registo local e eventos

    private long lastSeq()  { return log.size(); }
    private long lastTerm() { return log.isEmpty() ? 0 : log.get(log.size() - 1).getTerm(); }

    private void acrescentar(SensorRecord r) {
        try {
            Files.writeString(logFile, r.toLine() + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.add(r);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // reescrita atómica do ficheiro local (truncagem)
    private void reescrever(List<SensorRecord> registos) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (SensorRecord r : registos) sb.append(r.toLine()).append(System.lineSeparator());
        Path tmp = logFile.resolveSibling(logFile.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, logFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.clear();
        log.addAll(registos);
    }

    // instante;evento;p<k>;detalhe
    private void event(String e) {
        String line = System.currentTimeMillis() + ";" + e;
        synchronized (eventsLock) {
            System.out.println(line);
            try {
                Files.writeString(eventsFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                System.err.println("Não foi possível escrever o evento: " + ex.getMessage());
            }
        }
    }

    private void publicar(String topico, String msg) {
        try {
            ps.publish(topico, msg);
        } catch (Exception e) {
            event("ERRO;p" + k + ";publicacao em " + topico + ": " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private void subscrever(String topico, java.util.function.Consumer<String> handler) {
        thread("sub-" + topico, () -> {
            while (true) {
                try {
                    ps.subscribe(topico, msg -> {
                        try { handler.accept(msg); }
                        catch (Exception e) { event("ERRO;p" + k + ";mensagem rejeitada em " + topico + ": " + e.getMessage()); }
                    });
                } catch (Exception e) {
                    System.err.println("Subscrição de " + topico + " interrompida: " + e.getMessage());
                }
                try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
            }
        });
    }

    private static void thread(String nome, Runnable r) {
        Thread t = new Thread(r, nome);
        t.setDaemon(false);
        t.start();
    }
}
