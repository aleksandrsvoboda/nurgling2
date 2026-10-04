package nurgling.diagnostics;

import java.util.*;

/** Computed only when exporting a capture, never on the game/render thread. */
final class FrameStats {
    final List<MovementTrace.Row> worst;
    private final double[] times;
    final int invalid;

    FrameStats(List<MovementTrace.Row> rows) {
        List<MovementTrace.Row> frames = new ArrayList<>();
        int rejected = 0;
        for(MovementTrace.Row r : rows) {
            if(!r.type.equals("frame")) continue;
            if(Double.isFinite(r.values[19]) && r.values[19] > 0) frames.add(r);
            else rejected++;
        }
        invalid = rejected;
        frames.sort(Comparator.comparingDouble((MovementTrace.Row r) -> r.values[19]).reversed());
        times = new double[frames.size()];
        for(int i = 0; i < times.length; i++) times[i] = frames.get(i).values[19];
        worst = new ArrayList<>(frames.subList(0, Math.min(32, frames.size())));
    }

    int tailCount(double fraction) { return (int)Math.ceil(times.length * fraction); }
    double mean(int count) {
        if(count == 0) return Double.NaN;
        double sum = 0;
        for(int i = 0; i < count; i++) sum += times[i];
        return sum / count;
    }
    double low(double fraction) { return 1000 / mean(tailCount(fraction)); }
    double percentile(double fraction) {
        if(times.length == 0) return Double.NaN;
        return times[times.length - Math.max(1, (int)Math.ceil(times.length * fraction))];
    }
    int over(double ms) {
        int n = 0; for(double t : times) if(t > ms) n++; return n;
    }
    private static void value(StringBuilder out, String key, double v) {
        out.append(key).append('=').append(Double.isFinite(v) ? String.format(Locale.ROOT, "%.4f", v) : "unavailable").append('\n');
    }
    String report() {
        StringBuilder out = new StringBuilder();
        out.append("Frame cadence for this capture only (CPU submission/pacing, not GPU presentation).\n")
           .append("1%/0.1% low FPS = 1000 / mean duration of the slowest ceil(N * fraction) frames.\n")
           .append("Percentiles use nearest rank of ascending frame durations.\n")
           .append("Short captures have few tail samples; compare sample counts as well as FPS.\n")
           .append("slow-frames.csv contains up to 32 worst frames, longest first, with trace.csv columns.\n")
           .append("time_s marks the pre-draw sample, not the end of the measured frame interval.\n")
           .append("Stage timings can overlap (wait is included in all_wait); do not sum all columns.\n\n")
           .append("valid_frames=").append(times.length).append('\n')
           .append("invalid_frames=").append(invalid).append('\n');
        value(out, "average_fps", 1000 / mean(times.length));
        value(out, "low_1_percent_fps", low(.01));
        value(out, "low_0_1_percent_fps", low(.001));
        out.append("low_1_percent_samples=").append(tailCount(.01)).append('\n');
        out.append("low_0_1_percent_samples=").append(tailCount(.001)).append('\n');
        value(out, "median_frame_ms", percentile(.5));
        value(out, "p95_frame_ms", percentile(.95));
        value(out, "p99_frame_ms", percentile(.99));
        value(out, "p99_9_frame_ms", percentile(.999));
        value(out, "worst_frame_ms", times.length == 0 ? Double.NaN : times[0]);
        for(double threshold : new double[]{20, 1000.0 / 30, 50, 100})
            out.append(String.format(Locale.ROOT, "frames_over_%.3f_ms=%d\n", threshold, over(threshold)));
        return out.toString();
    }
}
