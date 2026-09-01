package nurgling.guarding;

import nurgling.NUtils;

/**
 * Fires when the hard-HP ceiling ({@code NUtils.getHPFraction()}, confirmed live to be hard HP
 * as a fraction of true max, not soft HP despite the name) drops below a configured threshold,
 * OR the character isn't fully healed relative to whatever that ceiling currently allows
 * (soft &lt; hard). Both conditions are bundled into this one trigger since both independently
 * meant "not okay, go home" in the check this replaces (Forager's old unconditional
 * detectThreat()); split the "not fully healed" half into its own guard type later if it ever
 * needs its own threshold/enable/outcome separate from the ceiling check.
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

        // Both fractions are live "hp" meter bar segments sharing the same denominator (true
        // max), so soft/hard = softFrac/hardFrac needs no tooltip data at all - only the chat
        // message's raw numbers below use getCurrentHP()/getMaxHP(), which can silently stay
        // stale/-1 all session if nothing ever hovers the HP bar.
        double softFrac = NUtils.getSoftHPFraction();
        if (softFrac >= 0 && hardFrac > 0 && softFrac < hardFrac) {
            int curHP = NUtils.getCurrentHP();
            int maxHP = NUtils.getMaxHP();
            lastReason = (curHP >= 0 && maxHP >= 0)
                    ? ("soft hitpoints not full (" + curHP + "/" + Math.round(hardFrac * maxHP) + ")")
                    : ("soft hitpoints not full (" + Math.round(softFrac * 100) + "% of a possible "
                        + Math.round(hardFrac * 100) + "%)");
            return true;
        }

        return false;
    }

    @Override
    public String describe() {
        return lastReason;
    }
}
