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
    private static final boolean ON = System.getProperty("nurgling.framesampler") != null;
    private static final double AFTER_MS = 12, LOG_MS = 20;
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
        if(ms < LOG_MS)
            return;
        List<StackTraceElement[]> got;
        Map<StackTraceElement[], ThreadInfo> own;
        synchronized(samples) {
            got = new ArrayList<>(samples);
            own = new IdentityHashMap<>(owners);
        }
        out.printf(Locale.ROOT, "FRAME %d %.2f %d%n", System.currentTimeMillis(), ms, got.size());
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
                if(ui.getState() == Thread.State.BLOCKED) {
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
