package nurgling.diagnostics;

import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Temporary hitch probe, on with -Dnurgling.framesampler. Once a UI frame passes {@link #AFTER_MS},
 * a watchdog samples the UI thread's stack every millisecond; frames over {@link #LOG_MS} are
 * written with those stacks to diagnostics/frame-samples.log.
 */
public final class FrameSampler {
    private static final String SPEC = System.getProperty("nurgling.framesampler");
    private static final boolean ON = SPEC != null;
    /* -Dnurgling.framesampler=AFTER,LOG in ms; any other value keeps 12,20. */
    private static final double AFTER_MS = arg(0, 12), LOG_MS = arg(1, 20);
    /* Every this many frames a normal one is logged too, as a baseline. */
    private static final int BASELINE_EVERY = 50;
    private static long frameno = 0;

    /* TEMP: event counters, printed every 10 s while the sampler is on. */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> counts = new java.util.concurrent.ConcurrentHashMap<>();
    private static long nextdump = 0;

    public static void count(String key) {
        if(ON)
            counts.computeIfAbsent(key, k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    private static void dumpcounts() {
        long now = System.currentTimeMillis();
        if(now < nextdump)
            return;
        nextdump = now + 10000;
        List<Map.Entry<String, java.util.concurrent.atomic.LongAdder>> l = new ArrayList<>(counts.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        StringBuilder b = new StringBuilder("COUNTS " + now);
        for(int i = 0; i < Math.min(l.size(), 25); i++)
            b.append(" | ").append(l.get(i).getKey()).append('=').append(l.get(i).getValue().sumThenReset());
        for(int i = 25; i < l.size(); i++)
            l.get(i).getValue().reset();
        out.println(b);
        out.flush();
    }

    private static double arg(int i, double def) {
        try {
            return(Double.parseDouble(SPEC.split(",")[i]));
        } catch(RuntimeException e) {
            return(def);
        }
    }
    private static volatile Thread ui;
    private static volatile long start;
    private static final List<StackTraceElement[]> samples = new ArrayList<>();
    /* For a sample taken while the UI thread was blocked: the lock holder's name and stack. */
    private static final Map<StackTraceElement[], ThreadInfo> owners = new IdentityHashMap<>();
    private static final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
    private static PrintWriter out;

    public static void begin() {
        if(!ON)
            return;
        if(ui == null) {
            ui = Thread.currentThread();
            try {
                new File("diagnostics").mkdirs();
                out = new PrintWriter(new OutputStreamWriter(new FileOutputStream("diagnostics/frame-samples.log", true), StandardCharsets.UTF_8));
            } catch(IOException e) {
                throw(new UncheckedIOException(e));
            }
            Thread w = new Thread(FrameSampler::watch, "Frame sampler");
            w.setDaemon(true);
            w.start();
        }
        synchronized(samples) {
            samples.clear();
            owners.clear();
        }
        start = System.nanoTime();
    }

    public static void end() {
        if(!ON || ui == null)
            return;
        double ms = (System.nanoTime() - start) / 1e6;
        start = 0;
        dumpcounts();
        boolean base = (++frameno % BASELINE_EVERY) == 0;
        if((ms < LOG_MS) && !base)
            return;
        List<StackTraceElement[]> got;
        Map<StackTraceElement[], ThreadInfo> own;
        synchronized(samples) {
            got = new ArrayList<>(samples);
            own = new IdentityHashMap<>(owners);
        }
        out.printf(Locale.ROOT, "%s %d %.2f %d%n", (ms < LOG_MS) ? "NFRAME" : "FRAME", System.currentTimeMillis(), ms, got.size());
        for(StackTraceElement[] st : got) {
            StringBuilder b = new StringBuilder("S");
            for(int i = 0; i < Math.min(st.length, 40); i++)
                b.append(' ').append(st[i].getClassName()).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
            out.println(b);
            ThreadInfo o = own.get(st);
            if(o != null) {
                StringBuilder ob = new StringBuilder("O ").append(o.getThreadName().replace(' ', '_'));
                for(StackTraceElement e : o.getStackTrace())
                    ob.append(' ').append(e.getClassName()).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
                out.println(ob);
            }
        }
        out.flush();
    }

    private static void watch() {
        while(true) {
            long s = start;
            if((s != 0) && ((System.nanoTime() - s) / 1e6 > AFTER_MS)) {
                StackTraceElement[] st = ui.getStackTrace();
                ThreadInfo owner = null;
                Thread.State state = ui.getState();
                if((state == Thread.State.BLOCKED) || (state == Thread.State.WAITING) || (state == Thread.State.TIMED_WAITING)) {
                    ThreadInfo ti = mx.getThreadInfo(ui.getId(), 0);
                    if((ti != null) && (ti.getLockOwnerId() >= 0))
                        owner = mx.getThreadInfo(ti.getLockOwnerId(), 40);
                }
                if(start == s) {
                    synchronized(samples) {
                        samples.add(st);
                        if(owner != null)
                            owners.put(st, owner);
                    }
                }
            }
            try {
                Thread.sleep(1);
            } catch(InterruptedException e) {
                return;
            }
        }
    }
}
