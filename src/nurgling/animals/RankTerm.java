package nurgling.animals;

import java.awt.Color;

/**
 * One weighted stat in an animal's rank. The arithmetic of each kind is kept operation-for-operation
 * identical to the original per-species rang() bodies, so ranks match them to the last bit.
 */
public final class RankTerm {
    public enum Kind {
        /** A quality percentage, scaled by effective quality unless "Ignore quality for % score". */
        PERCENT,
        /** A quantity counted with slope w1 up to the threshold and slope w2 above it. */
        THRESHOLD,
        /** A plain weight times the stat. */
        LINEAR
    }

    public final String id;
    public final String label;
    public final Kind kind;
    public final double w1;
    public final double threshold;
    public final double w2;
    public final Color color;

    private RankTerm(String id, String label, Kind kind, double w1, double threshold, double w2, Color color) {
        this.id = id;
        this.label = label;
        this.kind = kind;
        this.w1 = w1;
        this.threshold = threshold;
        this.w2 = w2;
        this.color = color;
    }

    public static RankTerm percent(String id, String label, double w, Color color) {
        return new RankTerm(id, label, Kind.PERCENT, w, 0, w, color);
    }

    public static RankTerm threshold(String id, String label, double w1, double threshold, double w2, Color color) {
        return new RankTerm(id, label, Kind.THRESHOLD, w1, threshold, w2, color);
    }

    public static RankTerm linear(String id, String label, double w, Color color) {
        return new RankTerm(id, label, Kind.LINEAR, w, 0, w, color);
    }

    public double value(int v, double eq, boolean ignoreQuality) {
        switch (kind) {
            case PERCENT:
                return ignoreQuality ? (w1 * v) : (eq * w1 * v / 100.);
            case THRESHOLD:
                return v * w1 + ((v > threshold) ? ((v - threshold) * (w2 - w1)) : 0);
            default:
                return w1 * v;
        }
    }

    /** True when some weight is non-zero, i.e. the user asked for this stat to count. */
    public boolean weighted() {
        return w1 != 0 || w2 != 0;
    }

    /** Human-readable form of the weights, as the settings window names them. */
    public String weightsText() {
        if (kind == Kind.THRESHOLD)
            return fmt(w1) + " up to " + fmt(threshold) + ", then " + fmt(w2);
        return fmt(w1);
    }

    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9)
            return Long.toString((long) v);
        return String.valueOf(Math.round(v * 100) / 100.);
    }
}
