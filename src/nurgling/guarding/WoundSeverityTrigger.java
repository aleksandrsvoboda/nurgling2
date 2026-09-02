package nurgling.guarding;

import nurgling.tools.NWoundChecker;

/**
 * Fires when the character's total wound damage - summed across every active wound, not just
 * one type (see NWoundChecker.totalWoundDamage()) - reaches or exceeds a configured threshold.
 * Flagged for swamp fever risk: in-game, that disease is what accumulated untreated wounds turn
 * into, so this is meant to stop a run before it ever starts on an already-badly-wounded
 * character, not to react to any single specific wound. Registered preflight-only (see
 * GuardRegistry) - an in-flight variant could be added the exact same way later if a run should
 * also bail out mid-way once wounds cross the threshold, but that's a separate decision from
 * "don't even start."
 */
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
