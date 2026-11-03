package nurgling.scripting;

import nurgling.NGameUI;
import nurgling.NUI;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One bot thread serving a script: the thread a Gear row in the Bot Status strip stands for.
 *
 * Every request that touches the game is executed here, in order, so ThreadLocalUI is bound to
 * the run's session and existing Java actions behave exactly as they do inside Java bots.
 * Stopping the Gear interrupts this thread; the interrupt ends {@link #serve()} and the run.
 */
public class ScriptRun {
    private static final AtomicInteger seq = new AtomicInteger();

    public final String id = "r" + seq.incrementAndGet();
    public final NUI ui;
    public final NGameUI gui;
    public final String title;

    private final LinkedBlockingQueue<Request> queue = new LinkedBlockingQueue<>();
    private final List<Runnable> cleanups = new ArrayList<>();
    volatile Thread thread;
    volatile ScriptConnection conn;
    volatile Request current;
    private volatile boolean ended = false;
    /** Result the script reported with run.finish; null means "decide from the exit". */
    public volatile Boolean reportedSuccess = null;
    /** Parameter values a managed run was started with (from the script header form or a scenario step). */
    public volatile org.json.JSONObject params = new org.json.JSONObject();

    public ScriptRun(NUI ui, String title) {
        this.ui = ui;
        this.gui = ui.gui;
        this.title = title;
    }

    public ScriptConnection connection() { return conn; }

    public boolean isEnded() { return ended; }

    public Thread thread() { return thread; }

    void submit(Request r) {
        queue.add(r);
    }

    /** Ends {@link #serve()} normally once the queued requests are done. */
    void close() {
        queue.add(Request.POISON);
    }

    /** Registers something to undo when the run ends (overlays, forms). Runs on the UI thread. */
    public void addCleanup(Runnable r) {
        synchronized (cleanups) {
            if (!ended) {
                cleanups.add(r);
                return;
            }
        }
        ScriptRuntime.onUiThread(ui, r);
    }

    public void removeCleanup(Runnable r) {
        synchronized (cleanups) {
            cleanups.remove(r);
        }
    }

    /**
     * Serves requests on the calling thread (the run's bot thread) until the connection closes
     * or the thread is interrupted. Interrupts propagate to the caller.
     */
    public void serve() throws InterruptedException {
        thread = Thread.currentThread();
        boolean normal = false;
        try {
            while (true) {
                Request r = queue.take();
                if (r == Request.POISON) break;
                ScriptConnection c = conn;
                if (c == null) continue;
                current = r;
                c.executeOrInterrupt(this, r);
                current = null;
            }
            normal = true;
        } finally {
            end(!normal);
        }
    }

    private void end(boolean stopped) {
        List<Runnable> todo;
        synchronized (cleanups) {
            ended = true;
            todo = new ArrayList<>(cleanups);
            cleanups.clear();
        }
        for (Runnable r : todo)
            ScriptRuntime.onUiThread(ui, r);
        ScriptConnection c = conn;
        if (c != null) {
            Request cur = current;
            current = null;
            List<Request> left = new ArrayList<>();
            queue.drainTo(left);
            if (cur != null) left.add(0, cur);
            c.runEnded(this, stopped, left);
        }
    }
}
