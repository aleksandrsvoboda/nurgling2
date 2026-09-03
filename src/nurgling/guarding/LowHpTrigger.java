package nurgling.guarding;

import nurgling.NUtils;

/**
 * Fires when the hard-HP ceiling ({@code NUtils.getHPFraction()}, confirmed live to be hard HP
 * as a fraction of true max, not soft HP despite the name) drops below a configured threshold.
 * Whether soft HP is fully healed up to that ceiling is a separate, unconditional check - see
 * {@link FullShpTrigger} - since bundling it in here fired this guard any time soft HP was even
 * 1 point below the ceiling, with no way to configure or disable that half independently
 * (reported live).
 */
public class LowHpTrigger implements GuardTrigger {
    private final double threshold;
    private String lastReason = "";

    public LowHpTrigger(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public boolean check(GuardContext ctx) {
        double hardFrac = NUtils.getHPFraction();
        if (hardFrac >= 0 && hardFrac < threshold) {
            lastReason = "hard hitpoint ceiling at " + Math.round(hardFrac * 100) + "% of max (below "
                    + Math.round(threshold * 100) + "%)";
            return true;
        }
        return false;
    }

    @Override
    public String describe() {
        return lastReason;
    }
}
