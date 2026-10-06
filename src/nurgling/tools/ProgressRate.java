package nurgling.tools;

/** Estimates how an action's progress advances from the server's progress updates:
 * a smoothed rate for the time left, and an eased value between updates. Progress
 * going backwards (a new item in a batch) starts a fresh estimate. */
public class ProgressRate {
    private double lastT = -1, lastP, firstT;
    private double rate = -1, step = -1;
    private int samples;

    public void add(double t, double p) {
        if((lastT < 0) || (p < lastP - 1e-6)) {
            lastT = firstT = t;
            lastP = p;
            rate = step = -1;
            samples = 1;
            return;
        }
        double dt = t - lastT;
        if(dt <= 0) {
            lastP = p;
            return;
        }
        double r = (p - lastP) / dt;
        if(r > 0) {
            rate = (rate < 0) ? r : (rate * 0.7) + (r * 0.3);
            step = (step < 0) ? dt : (step * 0.7) + (dt * 0.3);
            samples++;
        }
        lastT = t;
        lastP = p;
    }

    /** Progress eased forward at the measured rate, but never past the expected next update. */
    public double shown(double now) {
        if((rate <= 0) || (lastT < 0))
            return lastP;
        double ahead = Math.min(now - lastT, step);
        return Math.min(1, lastP + (rate * Math.max(0, ahead)));
    }

    /** Seconds left, or a negative value while the rate is not yet reliable. */
    public double left(double now) {
        if((samples < 3) || (rate <= 0) || (now - firstT < 1))
            return -1;
        return Math.max(0, ((1 - lastP) / rate) - (now - lastT));
    }

    public static String format(double secs) {
        int s = (int)Math.ceil(secs);
        if(s < 60)
            return "~" + s + " s";
        return "~" + (s / 60) + " m " + (s % 60) + " s";
    }
}
