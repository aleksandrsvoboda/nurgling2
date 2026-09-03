package nurgling.guarding;

import haven.Gob;
import nurgling.conf.NAreaRad;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

/** Fires if a dangerous animal (per Options &gt; Ring Settings' configured radii) is within
 *  range of the player. Ring Settings (NConfig.Key.animalrad, read via ctx.animalRads()) is a
 *  general "draw an awareness ring around this critter" list, not a danger list - it includes
 *  plain Rat right alongside the genuinely aggressive Cave Rat, so this reads each entry's own
 *  NAreaRad.dangerous flag (a per-ring checkbox in Ring Settings, independent of visibility)
 *  rather than assuming every tracked critter is a threat. */
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
            if (!rad.dangerous) {
                continue;
            }
            // 1.25x margin over the configured danger radius - pulls the character out before
            // real danger, not after. Was temporarily widened to 2x, then 1.5x, as a stopgap
            // while NConfig.needUpdate() had a session-scoping bug that meant a Ring Settings
            // edit often didn't actually get saved (fixed - see NConfig.needUpdate()'s javadoc,
            // confirmed working live) - narrowed back down now that radius edits actually save.
            double triggerDist = rad.radius * 1.25;
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
