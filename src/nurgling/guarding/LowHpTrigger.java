package nurgling.guarding;

import nurgling.NUtils;

/** Fires when hard HP (HHP) drops below a configured threshold; see {@link LowShpTrigger} for soft HP. */
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
            lastReason = "hard hitpoint (HHP) ceiling at " + Math.round(hardFrac * 100) + "% of max (below "
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
