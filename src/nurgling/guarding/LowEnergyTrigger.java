package nurgling.guarding;

import nurgling.NUtils;

/** Fires when energy fraction (0-1) drops below a configured threshold - same signal Validator.java gates other bots' pre-flight checks on. */
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
