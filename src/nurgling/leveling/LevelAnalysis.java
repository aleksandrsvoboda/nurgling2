package nurgling.leveling;

import java.util.Arrays;

/**
 * How much ground has to move to level a set of tile corners to one flat height.
 *
 * <p>For a target height {@code T}: dig(T) = Σ max(0, h - T), fill(T) = Σ max(0, T - h), and
 * net(T) = fill - dig (positive: soil has to be brought in). These are the sums the Land Survey
 * window shows, per corner, before scaling to survey units.
 *
 * <p>Pure: the heights are sorted once with prefix sums, so every query is a binary search.
 */
public final class LevelAnalysis {
    private final float[] h;
    private final double[] pre;

    /** @param heights the ground height of every corner to level; copied */
    public LevelAnalysis(float[] heights) {
        h = heights.clone();
        Arrays.sort(h);
        pre = new double[h.length + 1];
        for(int i = 0; i < h.length; i++)
            pre[i + 1] = pre[i] + h[i];
    }

    public int count() {return(h.length);}

    public boolean empty() {return(h.length == 0);}

    public double min() {return(h[0]);}

    public double max() {return(h[h.length - 1]);}

    /** The height where everything dug exactly fills the holes. */
    public double mean() {return(pre[h.length] / h.length);}

    /** The height that moves the least ground in total. */
    public double median() {
        int n = h.length;
        return(((n & 1) == 1) ? h[n / 2] : ((h[(n / 2) - 1] + (double)h[n / 2]) / 2));
    }

    /** Number of heights below {@code t}. */
    private int below(double t) {
        int lo = 0, hi = h.length;
        while(lo < hi) {
            int m = (lo + hi) >>> 1;
            if(h[m] < t)
                lo = m + 1;
            else
                hi = m;
        }
        return(lo);
    }

    public double dig(double t) {
        int k = below(t);
        return((pre[h.length] - pre[k]) - (t * (h.length - k)));
    }

    public double fill(double t) {
        int k = below(t);
        return((t * k) - pre[k]);
    }

    public double net(double t) {return(fill(t) - dig(t));}

    /**
     * The height where the fill needs {@code n} more than the dig yields: net(T) = n. A soil
     * stockpile of n is used up exactly; a negative n leaves that much over. In height units summed
     * over corners. net rises steadily with T, so outside its range the answer is clamped to the
     * lowest or highest ground.
     */
    public double forNet(double n) {
        double lo = min(), hi = max();
        if(net(lo) >= n)
            return(lo);
        if(net(hi) <= n)
            return(hi);
        for(int i = 0; i < 60; i++) {
            double m = (lo + hi) / 2;
            if(net(m) < n)
                lo = m;
            else
                hi = m;
        }
        return((lo + hi) / 2);
    }

    /** Corners within {@code tol} of {@code t}: already level. */
    public int within(double t, double tol) {
        return(below(t + tol) - below(t - tol));
    }
}
