package nurgling.guarding;

import haven.Gob;
import nurgling.conf.NAreaRad;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

/** Fires if a dangerous animal (per each Ring Settings entry's own {@code dangerous} flag, not just visibility) is within range of the player. */
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
            // 1.25x margin over the configured danger radius - pulls the character out before real danger, not after.
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
