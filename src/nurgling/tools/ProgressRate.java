package nurgling.tools;

/** Estimates how an action's progress advances from the server's progress updates:
 * a steady rate for the time left, and a smooth displayed value between updates.
 * Progress going backwards (a new item in a batch) starts a fresh estimate. */
public class ProgressRate {
    /* Seconds for the display to close most of a gap when an update lands ahead of it. */
    private static final double CATCHUP = 0.15;
    private double firstT = -1, firstP, lastT = -1, lastP;
    private double recent = -1, step = -1;
    private int samples;
    private double shown, shownT = -1;

    public void add(double t, double p) {
        if((lastT < 0) || (p < lastP - 1e-6)) {
            firstT = lastT = t;
            firstP = lastP = p;
            recent = step = -1;
            samples = 1;
            shown = p;
            shownT = t;
            return;
        }
        double dt = t - lastT;
        if(dt <= 0) {
            lastP = p;
            return;
        }
        double r = (p - lastP) / dt;
        if(r > 0) {
            recent = (recent < 0) ? r : (recent * 0.7) + (r * 0.3);
            step = (step < 0) ? dt : (step * 0.7) + (dt * 0.3);
            samples++;
        }
        lastT = t;
        lastP = p;
    }

    /** Progress per second: the whole item so far blended with recent updates, so uneven
     * update timing does not make it wobble. */
    private double rate() {
        if(recent <= 0)
            return(-1);
        double span = lastT - firstT;
        if((span <= 0) || (lastP <= firstP))
            return(recent);
        return((0.6 * ((lastP - firstP) / span)) + (0.4 * recent));
    }

    /** Where progress probably is now: linear at the measured rate for one expected update
     * interval, then easing towards a limit instead of stopping dead. */
    private double estimate(double now) {
        double rate = rate();
        if((rate <= 0) || (step <= 0))
            return(lastP);
        double dt = Math.max(0, now - lastT);
        double ahead = (dt <= step) ? dt : step + (step * (1 - Math.exp(-(dt - step) / step)));
        return(Math.min(1, lastP + (rate * ahead)));
    }

    /** The value to draw: follows the estimate smoothly and never moves backwards within an
     * item, so early, late or uneven updates cause neither stalls nor snap-backs. */
    public double shown(double now) {
        if(shownT < 0)
            return(lastP);
        double target = Math.max(estimate(now), lastP);
        double dt = Math.max(0, now - shownT);
        shownT = now;
        if(target > shown)
            shown += (target - shown) * (1 - Math.exp(-dt / CATCHUP));
        return(Math.min(1, shown));
    }

    /** Seconds left, or a negative value while the rate is not yet reliable. */
    public double left(double now) {
        double rate = rate();
        if((samples < 3) || (rate <= 0) || (now - firstT < 1))
            return(-1);
        return(Math.max(0, ((1 - lastP) / rate) - (now - lastT)));
    }

    public static String format(double secs) {
        int s = (int)Math.ceil(secs);
        if(s < 60)
            return("~" + s + " s");
        return("~" + (s / 60) + " m " + (s % 60) + " s");
    }
}
