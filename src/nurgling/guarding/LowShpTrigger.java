package nurgling.guarding;

import nurgling.NUtils;

/** Fires when soft HP (SHP) drops below a configured percentage of the hard-HP (HHP) ceiling, not true max. */
public class LowShpTrigger implements GuardTrigger {
    private final double threshold;
    private String lastReason = "";

    public LowShpTrigger(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public boolean check(GuardContext ctx) {
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
