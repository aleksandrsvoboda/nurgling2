package nurgling.tasks;

import haven.Gob;
import haven.MCache;
import nurgling.NUtils;
import nurgling.tools.Finder;
import nurgling.tools.NParser;

public class AnimalIsDead extends NTask {
    long gobid;

    public AnimalIsDead(long gobid) {
        this.gobid = gobid;
        this.maxCounter = 500;
        this.infinite = false;
    }

    @Override
    public boolean check() {
        Gob animal = Finder.findGob(gobid);
        if (animal == null) {
            return false;
        }
        String pose = animal.pose();
        boolean progDone = NUtils.getGameUI().prog == null || NUtils.getGameUI().prog.prog < 0;

        // Don't timeout while slaughter is still in progress
        if (!progDone) {
            counter = 0;
        }

        if (pose != null && NParser.checkName(pose, "knock")) {
            res = true;
            return true;
        }
        return progDone && animal.rc.dist(NUtils.player().rc) > MCache.tilesz.len() * 2;
    }

    boolean res = false;

    public boolean getRes() {
        return res;
    }
}
