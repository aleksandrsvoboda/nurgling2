package nurgling.guarding;

import haven.Gob;
import nurgling.conf.NAreaRad;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

/** Fires if a dangerous animal (per Options &gt; Ring Settings' configured radii) is within
 *  range of the player. Ring Settings (NConfig.Key.animalrad, read via ctx.animalRads()) is a
 *  general "draw an awareness ring around this critter" list, not a danger list - it includes
 *  plain Rat right alongside the genuinely aggressive Cave Rat, so plain Rat is explicitly
 *  excluded here. */
public class DangerousAnimalTrigger implements GuardTrigger {
    private String lastReason = "";

    @Override
    public boolean check(GuardContext ctx) throws InterruptedException {
        Gob player = ctx.player();
        if (player == null) {
            return false;
        }
        for (NAreaRad rad : ctx.animalRads()) {
            if (ctx.ignoreBats && rad.name.contains("bat")) {
                continue;
            }
            if (rad.name.equals("gfx/kritter/rat/rat")) {
                continue;
            }
            // Widened from the usual 1.5x margin to 2x as a stopgap: Ring Settings' configured
            // radius isn't reliably saving right now (separate, not-yet-fixed issue) - revert
            // this back to 1.5x once the underlying save bug is actually fixed.
            double triggerDist = rad.radius * 2;
            Gob animal = Finder.findGob(player.rc, new NAlias(rad.name), null, triggerDist);
            if (animal != null) {
                lastReason = "dangerous animal (" + rad.name + ") nearby";
                return true;
            }
        }
        return false;
    }

    @Override
    public String describe() {
        return lastReason;
    }
}
