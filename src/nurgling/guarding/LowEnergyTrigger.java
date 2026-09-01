package nurgling.guarding;

import nurgling.NUtils;

/** Fires when the character's energy fraction (0-1) drops below a configured threshold - the
 *  same signal Validator.java gates every other bot's pre-flight check on. */
public class LowEnergyTrigger implements GuardTrigger {
    private final double threshold;
    private double lastEnergy = -1;

    public LowEnergyTrigger(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public boolean check(GuardContext ctx) {
        double energy = NUtils.getEnergy();
        lastEnergy = energy;
        return energy >= 0 && energy < threshold;
    }

    @Override
    public String describe() {
        return "energy at " + Math.round(lastEnergy * 100) + "% (below " + Math.round(threshold * 100) + "%)";
    }
}
