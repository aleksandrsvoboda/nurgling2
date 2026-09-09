package nurgling.guarding;

import nurgling.tools.NWoundChecker;

/** Fires when the character actually has the Swamp Fever wound itself, with damage at or above a
 *  configured threshold - not merely a wound severe enough to risk developing it. Preflight-only. */
public class WoundSeverityTrigger implements GuardTrigger {
    private final int threshold;
    private String lastReason = "";

    public WoundSeverityTrigger(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public boolean check(GuardContext ctx) {
        int damage = NWoundChecker.swampFeverDamage();
        if (damage >= threshold) {
            lastReason = "Swamp Fever damage at " + damage + " (at or above " + threshold + ")";
            return true;
        }
        return false;
    }

    @Override
    public String describe() {
        return lastReason;
    }
}
