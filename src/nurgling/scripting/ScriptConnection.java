package nurgling.scripting;

import haven.Loading;
import nurgling.NUI;
import nurgling.scripting.api.*;
import org.json.JSONObject;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One connected script.
 *
 * A reader thread parses frames and routes each request: CONNECTION ops run right there, every
 * other op is queued on its target run's bot thread. A writer thread sends responses and events,
 * so a slow script never blocks the UI thread that produces events.
 */
public class ScriptConnection {
    /** Events beyond this backlog are dropped (and counted) instead of growing memory without bound. */
    private static final int EVENT_BACKLOG = 5000;

    private final ScriptServer server;
    private final Socket sock;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final LinkedBlockingQueue<JSONObject> outq = new LinkedBlockingQueue<>();
    private final AtomicInteger droppedEvents = new AtomicInteger();
    private final Map<String, ScriptRun> runs = new ConcurrentHashMap<>();
    private final List<Subscription> subs = new CopyOnWriteArrayList<>();
    private final AtomicInteger subSeq = new AtomicInteger();
    private volatile boolean closed = false;

    volatile ScriptRun primary;
    /** Whether layer 4 (java.*) is enabled for this connection. */
    public volatile boolean javaEnabled = false;
    public volatile String clientName = "script";
    /** Layer 4 object references, per connection. */
    public final JavaRefs refs = new JavaRefs();

    ScriptConnection(ScriptServer server, Socket sock) throws IOException {
        this.server = server;
        this.sock = sock;
        this.in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
    }

    void start() {
        Thread r = new Thread(this::readLoop, "Script-Reader");
        r.setDaemon(true);
        r.start();
        Thread w = new Thread(this::writeLoop, "Script-Writer");
        w.setDaemon(true);
        w.start();
    }

    public ScriptRun primary() { return primary; }

    public Collection<ScriptRun> runs() { return runs.values(); }

    public ScriptRun run(String id) { return runs.get(id); }

    // ---------------------------------------------------------------- reading

    private void readLoop() {
        try {
            sock.setSoTimeout(5000);
            JSONObject hello = Frames.read(in);
            if (hello == null) return;
            if (!handshake(Request.parse(hello))) return;
            sock.setSoTimeout(0);
            while (!closed) {
                JSONObject f = Frames.read(in);
                if (f == null) break;
                route(Request.parse(f));
            }
        } catch (SocketTimeoutException e) {
            System.out.println("[Scripting] handshake timed out");
        } catch (IOException | org.json.JSONException e) {
            // A script that exits right after its run ended often resets the socket; that is not an error.
            boolean finished = closed || (primary != null && primary.isEnded());
            if (!finished) System.out.println("[Scripting] connection error: " + e);
        } finally {
            close();
        }
    }

    private boolean handshake(Request r) {
        if (!"hello".equals(r.op)) {
            sendErr(r.id, Errors.AUTH, "the first frame must be hello");
            return false;
        }
        JSONObject a = r.args;
        if (!server.checkToken(a.optString("token", ""))) {
            sendErr(r.id, Errors.AUTH, "bad token");
            flushAndWait();
            return false;
        }
        clientName = a.optString("client", "script");
        javaEnabled = a.optBoolean("java", false);
        String major = a.optString("api", ScriptRuntime.API_VERSION).split("\\.")[0];
        if (!major.equals(ScriptRuntime.API_VERSION.split("\\.")[0])) {
            sendErr(r.id, Errors.NOT_SUPPORTED, "SDK speaks API " + a.optString("api") + ", client speaks " + ScriptRuntime.API_VERSION);
            flushAndWait();
            return false;
        }
        ScriptRun run;
        if (a.has("run") && !a.isNull("run")) {
            run = server.claimManagedRun(a.getString("run"));
            if (run == null) {
                sendErr(r.id, Errors.NOT_FOUND, "no pending managed run with that id (was it stopped?)");
                flushAndWait();
                return false;
            }
        } else {
            NUI ui = ScriptRuntime.resolveSession(a.optString("session", null));
            if (ui == null || ui.gui == null) {
                sendErr(r.id, Errors.NOT_FOUND, "no logged-in session" + (a.has("session") ? " named '" + a.optString("session") + "'" : ""));
                flushAndWait();
                return false;
            }
            run = ScriptRuntime.startAttachedRun(ui, "py: " + clientName);
            if (run == null) {
                sendErr(r.id, Errors.NOT_SUPPORTED, "could not start a bot thread (too many running bots?)");
                flushAndWait();
                return false;
            }
        }
        attach(run);
        primary = run;
        JSONObject ok = new JSONObject();
        ok.put("api", ScriptRuntime.API_VERSION);
        ok.put("client", ScriptRuntime.clientVersion());
        ok.put("run", run.id);
        ok.put("session", ScriptRuntime.sessionInfo(run.ui));
        ok.put("java", javaEnabled);
        ok.put("params", ScriptRuntime.paramsFor(run));
        sendOk(r.id, ok);
        return true;
    }

    public void attach(ScriptRun run) {
        run.conn = this;
        runs.put(run.id, run);
    }

    private void route(Request r) {
        if (r.op == null) {
            sendErr(r.id, Errors.BAD_ARGS, "frame has no op");
            return;
        }
        OpRegistry.OpDef def = OpRegistry.get(r.op);
        if (def == null) {
            sendErr(r.id, Errors.UNKNOWN_OP, "no op '" + r.op + "' in API " + ScriptRuntime.API_VERSION);
            return;
        }
        if (def.op.unstable() && !javaEnabled) {
            sendErr(r.id, Errors.NOT_SUPPORTED, r.op + " is a layer-4 op; enable it with '# nurgling: java=true' or connect(java=True)");
            return;
        }
        if (def.op.exec() == ApiOp.Exec.CONNECTION) {
            ScriptRun run = (r.run != null) ? runs.get(r.run) : primary;
            execute(run, r);
            return;
        }
        ScriptRun run = (r.run != null) ? runs.get(r.run) : primary;
        if (run == null || run.isEnded()) {
            sendErr(r.id, Errors.STOPPED, "run " + (r.run != null ? r.run : "") + " has ended");
            return;
        }
        run.submit(r);
    }

    // ---------------------------------------------------------------- executing

    /**
     * Executes one request and sends its response. Called on the run's bot thread for BOT ops
     * and on the reader thread for CONNECTION ops. Interrupts propagate (they end the run).
     */
    void execute(ScriptRun run, Request r) {
        try {
            executeOrInterrupt(run, r);
        } catch (InterruptedException e) {
            // Only CONNECTION ops reach here; they never block. Keep the interrupt for the owner.
            Thread.currentThread().interrupt();
        }
    }

    void executeOrInterrupt(ScriptRun run, Request r) throws InterruptedException {
        OpRegistry.OpDef def = OpRegistry.get(r.op);
        OpContext ctx = new OpContext(run, this);
        try {
            Args args = new Args(r.args, ctx);
            args.checkKnown(def.op);
            Object res = invokeWithLoadingRetry(def, ctx, args);
            sendOk(r.id, Codec.toJson(res));
        } catch (ApiError e) {
            sendErr(r.id, e.type, e.getMessage());
        } catch (Loading e) {
            sendErr(r.id, Errors.INTERNAL, "game resources did not finish loading: " + e);
        } catch (RuntimeException e) {
            // A bug in an op handler must not end the run; report it to the script instead.
            System.out.println("[Scripting] op " + r.op + " failed: " + e);
            e.printStackTrace();
            sendErr(r.id, Errors.INTERNAL, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Object invokeWithLoadingRetry(OpRegistry.OpDef def, OpContext ctx, Args args) throws InterruptedException {
        int attempts = 0;
        while (true) {
            try {
                return def.invoke(ctx, args);
            } catch (Loading l) {
                // Queries are safe to repeat; actions are not, so they report the Loading instead.
                if (def.op.kind() != ApiOp.Kind.QUERY || ctx.ui == null || ++attempts > 100) throw l;
                final long tick = ctx.ui.tickId;
                ctx.waitFor(() -> ctx.ui.tickId != tick, 1.0);
            }
        }
    }

    /** Called by a run as it ends: fail what it still owed the script, tell the script, and close if it was the primary. */
    void runEnded(ScriptRun run, boolean stopped, List<Request> unanswered) {
        runs.remove(run.id);
        for (Request r : unanswered) {
            if (r != Request.POISON)
                sendErr(r.id, Errors.STOPPED, "the run was stopped");
        }
        subs.removeIf(s -> s.run == run);
        JSONObject ev = new JSONObject().put("ev", "run.ended")
                .put("data", new JSONObject().put("run", run.id).put("stopped", stopped));
        outq.add(ev);
        if (run == primary) {
            for (ScriptRun other : new ArrayList<>(runs.values())) {
                Thread t = other.thread;
                if (t != null) t.interrupt();
            }
            closeAfterFlush();
        }
    }

    // ---------------------------------------------------------------- events

    public static final class Subscription {
        public final int id;
        public final Set<String> kinds;
        public final ScriptRun run;
        public final JSONObject filter;

        Subscription(int id, Set<String> kinds, ScriptRun run, JSONObject filter) {
            this.id = id;
            this.kinds = kinds;
            this.run = run;
            this.filter = filter;
        }

        public boolean wants(String kind) {
            if (kinds.contains(kind)) return true;
            int dot = kind.indexOf('.');
            return dot > 0 && kinds.contains(kind.substring(0, dot) + ".*");
        }
    }

    public int subscribe(Set<String> kinds, ScriptRun run, JSONObject filter) {
        Subscription s = new Subscription(subSeq.incrementAndGet(), kinds, run, filter);
        subs.add(s);
        ScriptEvents.ensureInstalled();
        return s.id;
    }

    public boolean unsubscribe(int id) {
        return subs.removeIf(s -> s.id == id);
    }

    public List<Subscription> subscriptions() { return subs; }

    /** Queues an event for subscription {@code sid}. Safe to call from any thread; never blocks. */
    public void sendEvent(int sid, String kind, Object data) {
        if (closed) return;
        if (outq.size() > EVENT_BACKLOG) {
            droppedEvents.incrementAndGet();
            return;
        }
        JSONObject ev = new JSONObject().put("ev", kind).put("sub", sid).put("data", data == null ? JSONObject.NULL : data);
        int dropped = droppedEvents.getAndSet(0);
        if (dropped > 0) ev.put("dropped", dropped);
        outq.add(ev);
    }

    // ---------------------------------------------------------------- writing

    void sendOk(Object id, Object result) {
        if (id == null) return;
        outq.add(new JSONObject().put("id", id).put("ok", result == null ? JSONObject.NULL : result));
    }

    void sendErr(Object id, String type, String msg) {
        if (id == null) {
            outq.add(new JSONObject().put("ev", "error").put("data", new JSONObject().put("type", type).put("msg", String.valueOf(msg))));
            return;
        }
        outq.add(new JSONObject().put("id", id).put("err", new JSONObject().put("type", type).put("msg", String.valueOf(msg))));
    }

    private static final JSONObject CLOSE = new JSONObject();

    private void writeLoop() {
        try {
            while (true) {
                JSONObject f = outq.take();
                if (f == CLOSE) break;
                Frames.write(out, f);
            }
        } catch (InterruptedException e) {
            // The writer is a daemon thread owned by this connection; an interrupt only means shut down.
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed) System.out.println("[Scripting] write failed: " + e);
        } finally {
            closeSocket();
        }
    }

    private void flushAndWait() {
        outq.add(CLOSE);
    }

    private void closeAfterFlush() {
        closed = true;
        outq.add(CLOSE);
    }

    /** Closes the connection: ends every run it owns. */
    public void close() {
        if (!closed) {
            closed = true;
            outq.add(CLOSE);
        }
        for (ScriptRun run : new ArrayList<>(runs.values())) {
            run.close();
            // A request still executing (a long walk, a wait) is cut short, as if stopped.
            if (run.current != null && run.thread != null)
                run.thread.interrupt();
        }
        refs.clear();
        server.connectionClosed(this);
    }

    private void closeSocket() {
        try {
            sock.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    public boolean isClosed() { return closed; }
}
