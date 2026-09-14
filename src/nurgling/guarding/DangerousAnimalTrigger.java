package nurgling.guarding;

import haven.Gob;
import haven.Homing;
import haven.Moving;
import nurgling.conf.NAreaRad;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

/** Fires if a dangerous animal (per each Ring Settings entry's own {@code dangerous} flag, not just visibility) is within range of the player, or is chasing it. */
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
            for (Gob animal : Finder.findGobs(player.rc, new NAlias(rad.name), null, rad.triggerDist())) {
                // A knocked-out or dead one lying nearby is no threat.
                if (!NAreaRad.isDownOrDead(animal)) {
                    lastReason = "dangerous animal (" + rad.name + ") nearby";
                    return true;
                }
            }
        }
        String chaser = chasingAnimal(ctx, player);
        if (chaser != null) {
            lastReason = "dangerous animal (" + chaser + ") is chasing you";
            return true;
        }
        return false;
    }

    /** Ring name of a dangerous animal homing on the player - attacking it - at any distance, else null; no point waiting for it to close to triggerDist. */
    private String chasingAnimal(GuardContext ctx, Gob player) {
        synchronized (ctx.gui.ui.sess.glob.oc) {
            for (Gob gob : ctx.gui.ui.sess.glob.oc) {
                Moving m = gob.getattr(Moving.class);
                if (!(m instanceof Homing) || ((Homing) m).tgt != player.id || gob.ngob == null || gob.ngob.name == null) {
                    continue;
                }
                for (NAreaRad rad : ctx.animalRads()) {
                    if (rad.name != null && rad.isActiveThreat(ctx.ignoreBats) && NParser.checkName(gob.ngob.name, new NAlias(rad.name))) {
                        return rad.name;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String describe() {
        return lastReason;
    }
}
