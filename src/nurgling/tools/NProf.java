package nurgling.tools;

import nurgling.NConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight timing profiler for bot actions.
 *
 * Bots spend most of their wall time blocked in {@link nurgling.NCore#addTask}, but some
 * costs (pathfinder graph construction, gob scans) are synchronous compute that never
 * touches a task at all. Measuring both, and the gap between them, is what makes it
 * possible to tell "the game hadn't done it yet" apart from "we burned CPU" apart from
 * "we waited on our own notify handshake".
 *
 * Disabled by default via {@link NConfig.Key#botprofiler}; when off, every entry point
 * short-circuits on a volatile boolean read.
 */
public class NProf {

    private static volatile boolean enabled = false;

    /** Per-label wall-time accumulator. */
    public static class Stat {
        public final AtomicLong count = new AtomicLong();
        public final AtomicLong totalNs = new AtomicLong();
        public final AtomicLong maxNs = new AtomicLong();

        void add(long ns) {
            count.incrementAndGet();
            totalNs.addAndGet(ns);
            // Racy but monotonic enough for a diagnostic max.
            long prev = maxNs.get();
            while (ns > prev && !maxNs.compareAndSet(prev, ns))
                prev = maxNs.get();
        }
    }

    private static final ConcurrentHashMap<String, Stat> stats = new ConcurrentHashMap<>();

    // --- addTask-specific counters, kept separate from labelled scopes ---
    private static final AtomicLong fastPathHits = new AtomicLong();
    private static final AtomicLong waited = new AtomicLong();
    private static final AtomicLong releasedByQueueIdle = new AtomicLong();
    private static final AtomicLong releasedByTimeout = new AtomicLong();
    private static final AtomicLong pendingNs = new AtomicLong();

    private static volatile long runStartNs = 0;
    private static volatile String runLabel = "";

    public static boolean enabled() {
        return enabled;
    }

    /** Re-reads the config key. Called when a bot starts so the flag can be toggled between runs. */
    public static void refresh() {
        Object v = NConfig.get(NConfig.Key.botprofiler);
        enabled = (v instanceof Boolean) && (Boolean) v;
    }

    public static void start(String label) {
        refresh();
        if (!enabled)
            return;
        reset();
        runLabel = label;
        runStartNs = System.nanoTime();
        System.out.println("[NProf] started: " + label);
    }

    public static void reset() {
        stats.clear();
        fastPathHits.set(0);
        waited.set(0);
        releasedByQueueIdle.set(0);
        releasedByTimeout.set(0);
        pendingNs.set(0);
        runStartNs = System.nanoTime();
    }

    // ----- labelled scopes -----

    /**
     * Times a region. close() declares no checked exception, so this is safe to use in
     * try-with-resources inside methods that throw InterruptedException.
     */
    public static class Scope implements AutoCloseable {
        private final String label;
        private final long startNs;

        Scope(String label) {
            this.label = label;
            this.startNs = enabled ? System.nanoTime() : 0;
        }

        @Override
        public void close() {
            if (enabled && startNs != 0)
                record(label, System.nanoTime() - startNs);
        }
    }

    public static Scope scope(String label) {
        return new Scope(label);
    }

    public static void record(String label, long ns) {
        if (!enabled)
            return;
        stats.computeIfAbsent(label, k -> new Stat()).add(ns);
    }

    // ----- addTask instrumentation hooks -----

    /** Anonymous NTask subclasses have an empty simple name; fall back to the superclass. */
    public static String taskName(Object task) {
        Class<?> c = task.getClass();
        String n = c.getSimpleName();
        if (n.isEmpty()) {
            Class<?> sup = c.getSuperclass();
            n = (sup != null ? sup.getSimpleName() : "NTask") + "$anon";
        }
        return n;
    }

    public static void taskFastPath(String taskName) {
        if (!enabled)
            return;
        fastPathHits.incrementAndGet();
        stats.computeIfAbsent("task:" + taskName + " [fast]", k -> new Stat()).add(0);
    }

    /** Total time the bot thread was parked on this task, from submit to wake. */
    public static void taskWaited(String taskName, long ns) {
        if (!enabled)
            return;
        waited.incrementAndGet();
        stats.computeIfAbsent("task:" + taskName, k -> new Stat()).add(ns);
    }

    /**
     * Time between the task's condition becoming true and the bot thread actually being
     * notified - i.e. time spent in NCore's pending_notify handshake. This is pure
     * client-side overhead, distinct from time spent waiting on the game.
     */
    public static void taskPending(String taskName, long ns, boolean byTimeout) {
        if (!enabled)
            return;
        pendingNs.addAndGet(ns);
        if (byTimeout)
            releasedByTimeout.incrementAndGet();
        else
            releasedByQueueIdle.incrementAndGet();
        stats.computeIfAbsent("pending:" + taskName, k -> new Stat()).add(ns);
    }

    // ----- report -----

    public static void dump() {
        if (!enabled)
            return;
        long wallNs = System.nanoTime() - runStartNs;
        double wallMs = wallNs / 1e6;

        ArrayList<Map.Entry<String, Stat>> entries = new ArrayList<>(stats.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Stat> e) -> e.getValue().totalNs.get()).reversed());

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== NProf: ").append(runLabel)
                .append(String.format(" - %.1fs wall ===%n", wallMs / 1000.0));
        sb.append(String.format("%-52s %7s %12s %9s %9s %7s%n",
                "label", "count", "total(ms)", "avg(ms)", "max(ms)", "%wall"));

        for (Map.Entry<String, Stat> e : entries) {
            Stat s = e.getValue();
            long c = s.count.get();
            double total = s.totalNs.get() / 1e6;
            sb.append(String.format("%-52s %7d %12.1f %9.1f %9.1f %6.1f%%%n",
                    truncate(e.getKey(), 52),
                    c,
                    total,
                    c > 0 ? total / c : 0.0,
                    s.maxNs.get() / 1e6,
                    wallMs > 0 ? (total / wallMs) * 100.0 : 0.0));
        }

        long w = waited.get();
        long idle = releasedByQueueIdle.get();
        long tmo = releasedByTimeout.get();
        long rel = idle + tmo;

        sb.append("\n--- addTask ---\n");
        sb.append(String.format("fast-path (0 frames)       %7d%n", fastPathHits.get()));
        sb.append(String.format("waited                     %7d%n", w));
        sb.append(String.format("  released by queueIdle    %7d (%.0f%%)%n",
                idle, rel > 0 ? idle * 100.0 / rel : 0.0));
        sb.append(String.format("  released by 2000ms TMO   %7d (%.0f%%)%n",
                tmo, rel > 0 ? tmo * 100.0 / rel : 0.0));
        sb.append(String.format("total time in pending_notify %.1f ms (%.1f%% of wall)%n",
                pendingNs.get() / 1e6,
                wallMs > 0 ? (pendingNs.get() / 1e6 / wallMs) * 100.0 : 0.0));
        sb.append("=== end NProf ===\n");

        System.out.println(sb);
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "~";
    }
}
