package nurgling.guarding;

import nurgling.NUtils;

/**
 * Fires when soft HP isn't fully healed up to whatever the hard-HP ceiling currently allows
 * (soft &lt; hard) - unconditional, no threshold, since "not full" is itself the condition.
 * Split out from {@link LowHpTrigger}, which used to bundle this in unconditionally alongside
 * its own configurable hard-HP-%% check, firing on even a single point of missing soft HP with
 * no way to disable that half independently of the ceiling threshold (reported live).
 */
public class FullShpTrigger implements GuardTrigger {
    private String lastReason = "";

    @Override
    public boolean check(GuardContext ctx) {
        // Both fractions are live "hp" meter bar segments sharing the same denominator (true
        // max), so soft/hard = softFrac/hardFrac needs no tooltip data at all - only the chat
        // message's raw numbers below use getCurrentHP()/getMaxHP(), which can silently stay
        // stale/-1 all session if nothing ever hovers the HP bar.
        double hardFrac = NUtils.getHPFraction();
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
