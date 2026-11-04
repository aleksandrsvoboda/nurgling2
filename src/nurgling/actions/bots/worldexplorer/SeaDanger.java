package nurgling.actions.bots.worldexplorer;

import haven.Gob;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.conf.NAreaRad;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** No-go discs round the dangerous animals of Ring Settings that are in the water - a walrus or an orca, or whatever else the
 *  player marks dangerous; a bear on the beach doesn't push the boat off the shoreline it follows. The animal loop is
 *  ForagerRouteConstraints.forEachThreat's - keep the two in step. */
public final class SeaDanger {
    private final List<NAreaRad> threats = new ArrayList<>();
    private final List<NAlias> aliases = new ArrayList<>();

    /** Reads Ring Settings once, at the start of the run. */
    public SeaDanger() {
        @SuppressWarnings("unchecked")
        ArrayList<NAreaRad> rads = (ArrayList<NAreaRad>) NConfig.get(NConfig.Key.animalrad);
        if (rads == null) return;
        for (NAreaRad rad : rads) {
            if (rad.name != null && rad.isActiveThreat(false)) {
                threats.add(rad);
                aliases.add(new NAlias(rad.name));
            }
        }
    }

    /** A disc of the ring's trigger distance round every live dangerous animal standing on water. Never blocks. */
    public List<SeaGrid.Disc> discs(NGameUI gui, SeaGrid.Tiles tiles, Predicate<String> water) {
        List<SeaGrid.Disc> res = new ArrayList<>();
        if (threats.isEmpty()) return res;
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
                if (gob.ngob == null || gob.ngob.name == null) continue;
                for (int k = 0; k < threats.size(); k++) {
                    if (NParser.checkName(gob.ngob.name, aliases.get(k))) {
                        if (!NAreaRad.isDownOrDead(gob)) {
                            String under = tiles.name(SeaGrid.tileOf(gob.rc));
                            if (under != null && water.test(under))
                                res.add(new SeaGrid.Disc(gob.rc, threats.get(k).triggerDist()));
                        }
                        break;
                    }
                }
            }
        }
        return res;
    }
}
