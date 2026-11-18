package nurgling.sessions.commander;

import haven.*;
import haven.error.FileLogger;
import nurgling.NGameUI;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Moves the visible map's camera centre away from the player ({@link MapView#ccoff}) to answer two questions: can the
 * view draw remembered ground far from its own character ({@code far}), and what does panning across it cost
 * ({@code pan}). Reports go to the chat and to the log ({@code [Commander camera]}).
 */
final class CameraProbe {
    /** When a {@code far} report is printed, in seconds after the move. */
    private static final double[] FAR_REPORTS = {1, 3, 10};

    private static volatile Far far;
    private static volatile Pan pan;

    private CameraProbe() {}

    private static final class Far {
        final NGameUI gui;
        final double t0 = Utils.rtime();
        final int fallback0;
        int next = 0;

        Far(NGameUI gui) {
            this.gui = gui;
            this.fallback0 = gui.map.ccfallback;
        }
    }

    private static final class Pan {
        final NGameUI gui;
        final Coord2d dir;
        final double dist, secs, t0 = Utils.rtime();
        final int fallback0;
        final long gc0, gcms0;
        double[] dts = new double[4096];
        int n = 0;
        double lastFrame = -1, nextHeap = 0;
        long heapMin = Long.MAX_VALUE, heapMax = 0;
        String peakStats = null;

        Pan(NGameUI gui, Coord2d dir, double dist, double secs) {
            this.gui = gui;
            this.dir = dir;
            this.dist = dist;
            this.secs = secs;
            this.fallback0 = gui.map.ccfallback;
            long[] gc = gc();
            this.gc0 = gc[0];
            this.gcms0 = gc[1];
        }

        void add(double dt) {
            if (n == dts.length)
                dts = Arrays.copyOf(dts, n * 2);
            dts[n++] = dt;
        }
    }

    static Coord2d dir(double deg) {
        double a = Math.toRadians(deg);
        return Coord2d.of(Math.cos(a), Math.sin(a));
    }

    static void far(NGameUI gui, double tiles, double deg) {
        stopPan(false);
        gui.map.ccoff = dir(deg).mul(tiles * MCache.tilesz.x);
        far = new Far(gui);
        say(gui, String.format("camera moved %.0f tiles at %.0f deg from the player; reports at +1, +3, +10 s", tiles, deg));
    }

    static void home(NGameUI gui) {
        stopPan(false);
        far = null;
        gui.map.ccoff = null;
        say(gui, "camera back on the player");
    }

    static void pan(NGameUI gui, double tiles, double secs, double deg) {
        far = null;
        stopPan(false);
        gui.map.ccoff = Coord2d.z;
        pan = new Pan(gui, dir(deg), tiles * MCache.tilesz.x, secs);
        say(gui, String.format("panning %.0f tiles out at %.0f deg and back over %.0f s: %s", tiles, deg, secs,
                               gui.map.remembered.stats()));
    }

    static void stopPan(boolean report) {
        Pan p = pan;
        pan = null;
        if (p == null)
            return;
        p.gui.map.ccoff = null;
        if (report)
            report(p);
    }

    /** One frame was drawn with {@code ui} on screen. Called on the UI loop's thread. */
    static void frame(UI ui, double now) {
        Far f = far;
        if (f != null) {
            if (f.gui.ui != ui) {
                far = null;
            } else if (f.next < FAR_REPORTS.length && now - f.t0 >= FAR_REPORTS[f.next]) {
                say(f.gui, String.format("+%.0f s: %s", FAR_REPORTS[f.next], status(f.gui, f.fallback0)));
                f.next++;
            }
        }
        Pan p = pan;
        if (p == null)
            return;
        if (p.gui.ui != ui) {
            /* The view went to another session: what is measured now is not this pan. */
            stopPan(false);
            return;
        }
        if (p.lastFrame >= 0)
            p.add(now - p.lastFrame);
        p.lastFrame = now;
        double u = (now - p.t0) / p.secs;
        if (u >= 1) {
            stopPan(true);
            return;
        }
        /* Out and back: 0 -> 1 -> 0. */
        double tri = (u < 0.5) ? (u * 2) : (2 - u * 2);
        p.gui.map.ccoff = p.dir.mul(p.dist * tri);
        if (now >= p.nextHeap) {
            p.nextHeap = now + 0.5;
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            p.heapMin = Math.min(p.heapMin, used);
            p.heapMax = Math.max(p.heapMax, used);
        }
        if (p.peakStats == null && u >= 0.5)
            p.peakStats = p.gui.map.remembered.stats();
    }

    private static void report(Pan p) {
        double[] s = Arrays.copyOf(p.dts, p.n);
        Arrays.sort(s);
        int over33 = 0, over50 = 0, over100 = 0;
        for (double d : s) {
            if (d > 0.033) over33++;
            if (d > 0.050) over50++;
            if (d > 0.100) over100++;
        }
        long[] gc = gc();
        String line = String.format("pan done: %d frames over %.0f s (%.0f fps); frame ms p50 %.1f, p95 %.1f, p99 %.1f, max %.1f; " +
                                    ">33 ms %d, >50 ms %d, >100 ms %d; heap %d-%d MB; GC %d runs, %d ms; heights not loaded %d frames; " +
                                    "at the far end: %s",
                                    s.length, p.secs, s.length / p.secs, pct(s, 0.5), pct(s, 0.95), pct(s, 0.99),
                                    (s.length == 0) ? 0 : s[s.length - 1] * 1000, over33, over50, over100,
                                    p.heapMin >> 20, p.heapMax >> 20, gc[0] - p.gc0, gc[1] - p.gcms0,
                                    p.gui.map.ccfallback - p.fallback0, p.peakStats);
        say(p.gui, line);
    }

    static String status(NGameUI gui, int fallback0) {
        MapView mv = gui.map;
        int[] fill = mv.terrainfill();
        Loading l = mv.terrain.loading();
        return String.format("live terrain %s cuts%s; heights not loaded %d frames; %s",
                             (fill == null) ? "?" : (fill[0] + "/" + fill[1]),
                             (l == null) ? "" : (" (loading: " + l.getMessage() + ")"),
                             mv.ccfallback - fallback0, mv.remembered.stats());
    }

    private static double pct(double[] sorted, double q) {
        if (sorted.length == 0)
            return 0;
        int i = (int) Math.min(sorted.length - 1, Math.floor(q * sorted.length));
        return sorted[i] * 1000;
    }

    private static long[] gc() {
        long n = 0, ms = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, b.getCollectionCount());
            ms += Math.max(0, b.getCollectionTime());
        }
        return new long[] {n, ms};
    }

    static void say(NGameUI gui, String line) {
        FileLogger.log("[Commander camera] " + line);
        gui.msg("[commander] " + line);
    }
}
