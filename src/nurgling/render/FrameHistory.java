package nurgling.render;

import java.util.Arrays;

/** Per-session client frame cadence, including render waits and the FPS limiter. */
public class FrameHistory {
    public static final double SECONDS = 10;
    private final double[] times = new double[16384], durations = new double[times.length];
    private int start, count;
    private double previous = Double.NaN;

    public synchronized void reset(double now) {
        start = count = 0;
        previous = now;
    }

    public synchronized void record(double now) {
        if(!Double.isFinite(now)) return;
        if(Double.isNaN(previous)) { previous = now; return; }
        double dt = now - previous;
        if(dt <= 0) return;
        previous = now;
        expire(now);
        if(count == times.length) { start = (start + 1) % times.length; count--; }
        int at = (start + count++) % times.length;
        times[at] = now;
        durations[at] = dt;
    }

    private void expire(double now) {
        while(count > 0 && times[start] < now - SECONDS) {
            start = (start + 1) % times.length;
            count--;
        }
    }

    public synchronized Snapshot snapshot(double now) {
        expire(now);
        double[] t = new double[count], d = new double[count];
        for(int i = 0; i < count; i++) {
            int at = (start + i) % times.length;
            t[i] = times[at]; d[i] = durations[at];
        }
        return(new Snapshot(now, t, d));
    }

    public static class Snapshot {
        public final double now;
        public final double[] times, seconds;
        public final double fps, average, minimum, low, frameMs, p99Ms, maxMs;
        public final int slow;

        private Snapshot(double now, double[] times, double[] seconds) {
            this.now = now; this.times = times; this.seconds = seconds;
            int n = seconds.length;
            if(n == 0) {
                fps = average = minimum = low = frameMs = p99Ms = maxMs = 0;
                slow = 0;
                return;
            }
            double sum = 0, recent = 0; int nr = 0, stalls = 0;
            for(int i = 0; i < n; i++) {
                sum += seconds[i];
                if(times[i] >= now - 1) { recent += seconds[i]; nr++; }
                if(seconds[i] > .05) stalls++;
            }
            double[] sorted = seconds.clone();
            Arrays.sort(sorted);
            int worst = Math.max(1, (int)Math.ceil(n * .01));
            double worstSum = 0;
            for(int i = n - worst; i < n; i++) worstSum += sorted[i];
            fps = nr == 0 ? 0 : nr / recent;
            average = n / sum;
            minimum = 1 / sorted[n - 1];
            low = worst / worstSum;
            frameMs = seconds[n - 1] * 1000;
            p99Ms = sorted[Math.max(0, (int)Math.ceil(n * .99) - 1)] * 1000;
            maxMs = sorted[n - 1] * 1000;
            slow = stalls;
        }
    }
}
