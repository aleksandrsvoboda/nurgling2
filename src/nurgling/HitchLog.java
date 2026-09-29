package nurgling;

import haven.Config;
import haven.UILoop;
import haven.render.Environment;
import haven.render.gl.GLEnvironment;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logs every frame that takes much longer than the recent average, with a
 * breakdown of where the time went: UI thread phases, GL thread work, shader
 * programs linked/finished, and GC time. Lines go to stdout and hitches.log in
 * the working directory. Disable with haven.hitchlog=false.
 */
public class HitchLog {
    public static final Config.Variable<Boolean> enabled = Config.Variable.propb("haven.hitchlog", true);
    private static final double MIN_HITCH = 0.025;
    private static final double AVG_FACTOR = 2.5;

    /* Fed from any thread; drained once per frame by the UI thread. */
    private static final AtomicLong treewaitns = new AtomicLong(), gobwaitns = new AtomicLong(), gobworkns = new AtomicLong();
    private static final Object slowmon = new Object();
    private static long slowns;
    private static String slowdesc;

    /** Contended wait for the RenderTree lock (loaders adding render slots hold it). Frame-side threads only. */
    public static void treewait(long ns) {
        if (!Thread.currentThread().getName().startsWith("Loader"))
            treewaitns.addAndGet(ns);
    }

    /** One gob's ctick: wait for its monitor (held by loaders applying deltas) and the tick itself. */
    public static void gobtick(haven.Gob g, long waitns, long workns) {
        gobwaitns.addAndGet(waitns);
        gobworkns.addAndGet(workns);
        if (waitns + workns > 2_000_000) {
            synchronized (slowmon) {
                if (waitns + workns > slowns) {
                    slowns = waitns + workns;
                    String nm = (g.ngob != null) ? g.ngob.name : null;
                    slowdesc = String.format("%s#%d wait %.1f work %.1f", nm, g.id, waitns * 1e-6, workns * 1e-6);
                }
            }
        }
    }

    /* Per-thread child-time accumulator for widget tick/gtick self-time. */
    public static final ThreadLocal<long[]> wtimer = ThreadLocal.withInitial(() -> new long[1]);
    private static long slowwns;
    private static String slowwdesc;

    /** Called when a widget's (g)tick dispatch, including its children, finishes. t0 = {start ns, parent's child accumulator}. */
    public static void widgetdone(haven.Widget w, String kind, long[] acc, long[] t0) {
        long total = System.nanoTime() - t0[0];
        long self = total - acc[0];
        acc[0] = t0[1] + total;
        if (self > 2_000_000) {
            synchronized (slowmon) {
                if (self > slowwns) {
                    slowwns = self;
                    slowwdesc = String.format("%s.%s %.1f", w.getClass().getName(), kind, self * 1e-6);
                }
            }
        }
    }

    /* Watchdog: when a frame runs past WATCH_MS, dump what the frame-side
     * threads are doing right then, including the lock each waits on and
     * who holds it. Catches stalls that sampling profilers miss (blocked,
     * native, or simply unsampled threads). One dump per frame. */
    private static final long WATCH_MS = 60;
    private static volatile long framestartns = 0, frameno = -1;
    private static long dumpedframe = -1;
    private static Thread watchdog;

    public static void framestart(long no) {
        framestartns = System.nanoTime();
        frameno = no;
        if (watchdog == null && enabled.get()) {
            synchronized (HitchLog.class) {
                if (watchdog == null) {
                    watchdog = new Thread(HitchLog::watch, "Hitch watchdog");
                    watchdog.setDaemon(true);
                    watchdog.start();
                }
            }
        }
    }

    private static void watch() {
        java.lang.management.ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        while (true) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return;
            }
            long no = frameno, start = framestartns;
            if (no == dumpedframe || start == 0)
                continue;
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms < WATCH_MS)
                continue;
            dumpedframe = no;
            StringBuilder buf = new StringBuilder();
            buf.append(String.format("[stall] frame %d at %d ms:%n", no, ms));
            java.lang.management.ThreadInfo[] all = mx.dumpAllThreads(true, true);
            /* Also dump whoever holds a lock a frame-side thread is waiting on. */
            java.util.Set<Long> owners = new java.util.HashSet<>();
            for (java.lang.management.ThreadInfo ti : all) {
                if (framethread(ti.getThreadName()) && ti.getLockOwnerId() >= 0)
                    owners.add(ti.getLockOwnerId());
            }
            for (java.lang.management.ThreadInfo ti : all) {
                String nm = ti.getThreadName();
                if (!framethread(nm) && !owners.contains(ti.getThreadId()))
                    continue;
                StackTraceElement[] st = ti.getStackTrace();
                /* Skip idle pool threads: nothing of ours on the stack. */
                boolean ours = false;
                for (StackTraceElement e : st)
                    if (e.getClassName().startsWith("haven.") || e.getClassName().startsWith("nurgling.")) { ours = true; break; }
                if (!ours)
                    continue;
                buf.append(String.format("  %s %s", nm, ti.getThreadState()));
                if (ti.getLockName() != null)
                    buf.append(" on ").append(ti.getLockName());
                if (ti.getLockOwnerName() != null)
                    buf.append(" held by ").append(ti.getLockOwnerName());
                buf.append(System.lineSeparator());
                int n = 0, max = nm.equals("Haven UI thread") ? 40 : 14;
                for (StackTraceElement e : st) {
                    buf.append("      at ").append(e).append(System.lineSeparator());
                    if (++n >= max)
                        break;
                }
            }
            write(buf.toString());
        }
    }

    private static boolean framethread(String nm) {
        return nm.equals("Haven UI thread") || nm.startsWith("ForkJoinPool.common") || nm.startsWith("Loader") || nm.startsWith("AWT-EventQueue");
    }

    /* File and console output happen on a daemon thread: writing from the
     * UI thread made the logger cause hitches of its own. */
    private static final java.util.concurrent.LinkedBlockingQueue<String> outq = new java.util.concurrent.LinkedBlockingQueue<>();
    private static Thread writer;

    private static void write(String s) {
        synchronized (HitchLog.class) {
            if (writer == null) {
                writer = new Thread(HitchLog::drain, "Hitch log writer");
                writer.setDaemon(true);
                writer.start();
            }
        }
        outq.offer(s);
    }

    private static void drain() {
        try (PrintWriter w = new PrintWriter(new FileWriter("hitches.log", true))) {
            while (true) {
                String s = outq.take();
                System.out.print(s);
                w.print(s);
                if (outq.isEmpty())
                    w.flush();
            }
        } catch (IOException | InterruptedException e) {
        }
    }

    private final List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
    private double lastftime = -1, avg = 0;
    private long lastgc, lastlinked, lastfinished, lastframes;
    private double lastprep, lastproc, lastcompile, lastlink;

    public void frame(UILoop.Frame f, Environment env) {
        if (!enabled.get())
            return;
        long gc = gctime();
        GLEnvironment glenv = (env instanceof GLEnvironment) ? (GLEnvironment) env : null;
        GLEnvironment.ProgStats ps = (glenv != null) ? glenv.progstats : null;
        double treewait = treewaitns.getAndSet(0) * 1e-6, gobwait = gobwaitns.getAndSet(0) * 1e-6, gobwork = gobworkns.getAndSet(0) * 1e-6;
        String slow, sloww;
        synchronized (slowmon) {
            slow = slowdesc;
            slowdesc = null;
            slowns = 0;
            sloww = slowwdesc;
            slowwdesc = null;
            slowwns = 0;
        }
        if (lastftime >= 0) {
            double dt = f.ftime - lastftime;
            if ((avg > 0) && (dt > MIN_HITCH) && (dt > avg * AVG_FACTOR))
                log(f, dt, gc - lastgc, glenv, treewait, gobwait, gobwork, slow, sloww);
            /* Clamp so one hitch doesn't raise the baseline for the next. */
            double sample = (avg > 0) ? Math.min(dt, avg * AVG_FACTOR) : dt;
            avg = (avg == 0) ? sample : (avg * 0.95) + (sample * 0.05);
        }
        lastftime = f.ftime;
        lastgc = gc;
        if (ps != null) {
            lastlinked = ps.linked;
            lastfinished = ps.finished;
            lastframes = ps.frames;
            lastprep = ps.prepms;
            lastproc = ps.procms;
            lastcompile = ps.compilems;
            lastlink = ps.linkms;
        }
    }

    private void log(UILoop.Frame f, double dt, long gcms, GLEnvironment glenv, double treewait, double gobwait, double gobwork, String slow, String sloww) {
        GLEnvironment.ProgStats ps = (glenv != null) ? glenv.progstats : null;
        StringBuilder buf = new StringBuilder();
        buf.append(String.format("[hitch] %s frame %d: %.1f ms (avg %.1f) | ui: dwait %.1f tick %.1f draw %.1f swap %.1f",
                java.time.LocalTime.now().withNano(0), f.frameno, dt * 1000, avg * 1000,
                f.tdwait * 1000, f.ttick * 1000, f.tdraw * 1000, f.tswap * 1000));
        buf.append(String.format(" [tick: input %.1f objs %.1f gtick %.1f widgets %.1f | obj lockwait %.1f objwork %.1f (all threads) | tree lockwait %.1f%s]",
                f.tdisp * 1000, f.toc * 1000, f.tgtick * 1000, f.tutick * 1000, gobwait, gobwork, treewait,
                (slow != null) ? " | slowest " + slow : ""));
        if (sloww != null)
            buf.append(" [slowest widget ").append(sloww).append("]");
        if (ps != null) {
            buf.append(String.format(" | gl: busy %.1f prep %.1f over %d frames, progs linked +%d ready +%d pending %d (compile %.1f link %.1f, async %s)",
                    ps.procms - lastproc, ps.prepms - lastprep, ps.frames - lastframes,
                    ps.linked - lastlinked, ps.finished - lastfinished, ps.pending,
                    ps.compilems - lastcompile, ps.linkms - lastlink, glenv.parallelsc ? "on" : "off"));
        }
        buf.append(String.format(" | gc %d ms", gcms));
        write(buf.append(System.lineSeparator()).toString());
    }

    private long gctime() {
        long t = 0;
        for (GarbageCollectorMXBean gc : gcs) {
            long c = gc.getCollectionTime();
            if (c > 0)
                t += c;
        }
        return t;
    }
}
