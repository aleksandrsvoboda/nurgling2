package nurgling.guarding;

import nurgling.tools.NWoundChecker;

/** Fires when total wound damage (summed across every active wound) reaches a configured threshold - swamp fever risk. Preflight-only. */
public class WoundSeverityTrigger implements GuardTrigger {
    private final int threshold;
    private String lastReason = "";

    public WoundSeverityTrigger(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public boolean check(GuardContext ctx) {
        int total = NWoundChecker.totalWoundDamage();
        if (total >= threshold) {
            lastReason = "total wound damage at " + total + " (at or above " + threshold + " - swamp fever risk)";
            return true;
        }
        return false;
    }

    @Override
    public String describe() {
        return lastReason;
    }
}
