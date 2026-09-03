package nurgling.guarding;

import nurgling.NUtils;

/**
 * Fires when soft HP (SHP) drops below a configured percentage of the hard-HP (HHP) ceiling -
 * relative to the ceiling, not to true max, so a reduced ceiling (e.g. from wounds) doesn't make
 * this fire on soft HP that's actually maxed out relative to what's currently achievable. A
 * threshold of 100% (the default) reproduces this guard's original unconditional "not fully
 * healed at all" behavior; split out from {@link LowHpTrigger}, which used to bundle this
 * unconditionally alongside its own configurable HHP-%% check (reported live: fired on even a
 * single point of missing SHP with no way to loosen or disable that half independently).
 */
public class LowShpTrigger implements GuardTrigger {
    private final double threshold;
    private String lastReason = "";

    public LowShpTrigger(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public boolean check(GuardContext ctx) {
        // Both fractions are live "hp" meter bar segments sharing the same denominator (true
        // max), so soft/hard = softFrac/hardFrac needs no tooltip data at all - only the chat
        // message's raw numbers below use getCurrentHP()/getMaxHP(), which can silently stay
        // stale/-1 all session if nothing ever hovers the HP bar.
        double hardFrac = NUtils.getHPFraction();
        double softFrac = NUtils.getSoftHPFraction();
        if (softFrac >= 0 && hardFrac > 0 && softFrac < hardFrac * threshold) {
            int curHP = NUtils.getCurrentHP();
            int maxHP = NUtils.getMaxHP();
            lastReason = (curHP >= 0 && maxHP >= 0)
                    ? ("soft hitpoints (SHP) at " + curHP + "/" + Math.round(hardFrac * maxHP)
                        + " (below " + Math.round(threshold * 100) + "% of HHP ceiling)")
                    : ("soft hitpoints (SHP) at " + Math.round(softFrac * 100) + "% of max (below "
                        + Math.round(threshold * 100) + "% of a possible " + Math.round(hardFrac * 100) + "% HHP ceiling)");
            return true;
        }
        return false;
    }

    @Override
    public String describe() {
        return lastReason;
    }
}
