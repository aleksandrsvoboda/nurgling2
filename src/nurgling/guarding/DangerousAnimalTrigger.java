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
            if (!rad.isActiveThreat(ctx.ignoreBats)) {
                continue;
            }
            Gob animal = Finder.findGob(player.rc, new NAlias(rad.name), null, rad.triggerDist());
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
