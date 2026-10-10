package nurgling.actions.bots.swillpiles;

import haven.Coord2d;
import haven.Gob;
import haven.Indir;
import haven.Loading;
import haven.Pair;
import haven.Resource;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.PathFinder;
import nurgling.actions.bots.SwillItemRegistry;
import nurgling.areas.NArea;
import nurgling.tasks.NTask;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Navigation and lookups shared by the pile sweep and the trough delivery.
 * reach / walkToCenter / nearest are copied from the package-private areamover.AreaScan, and
 * waitResName from areamover.Cargo; check fixes in one copy against the other.
 */
final class SwillScan {
    static final NAlias PILES = new NAlias("stockpile");

    private SwillScan() {
    }

    /**
     * Walks to the area and returns its bounds, or null when it could not be reached. A failed
     * navigation must not reach a scan: an unloaded area looks exactly like an empty one.
     */
    static Pair<Coord2d, Coord2d> reach(NArea area, boolean ensurePresence) throws InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (NUtils.navigateToArea(area, ensurePresence)) {
                Pair<Coord2d, Coord2d> rc = area.getRCArea();
                if (rc != null)
                    return rc;
            }
        }
        return null;
    }

    /** Walks to the middle of the area. @return false when there is no path there. */
    static boolean walkToCenter(NGameUI gui, Pair<Coord2d, Coord2d> rc) throws InterruptedException {
        Coord2d center = rc.a.add(rc.b).div(2);
        if (!PathFinder.isAvailable(center))
            return false;
        return new PathFinder(center).run(gui).IsSuccess();
    }

    static Gob nearest(Collection<Gob> gobs) {
        Gob player = NUtils.player();
        Gob best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Gob gob : gobs) {
            double d = (player == null) ? 0 : gob.rc.dist(player.rc);
            if (best == null || d < bestDist) {
                best = gob;
                bestDist = d;
            }
        }
        return best;
    }

    static String waitResName(Indir<Resource> res) throws InterruptedException {
        if (res == null)
            return null;
        String[] out = {null};
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                try {
                    out[0] = res.get().name;
                } catch (Loading l) {
                    return false;
                }
                return true;
            }
        });
        return out[0];
    }

    /**
     * Alias for the swill the inventory holds right now, or null when it holds none.
     *
     * NAlias matches by substring, so the bare registry names would also catch e.g. a carried
     * "Strawberry Pie" through "Straw". The trough refuses it and FilledTrough then waits forever,
     * so every carried name that matches a key without being swill becomes an exception.
     */
    static NAlias carriedSwill(NGameUI gui) throws InterruptedException {
        Set<String> names = new LinkedHashSet<>();
        for (WItem item : gui.getInventory().getItems()) {
            String name = ((NGItem) item.item).name();
            if (name != null)
                names.add(name);
        }
        ArrayList<String> keys = new ArrayList<>();
        for (String name : names) {
            if (SwillItemRegistry.isSwillItem(name))
                keys.add(name);
        }
        if (keys.isEmpty())
            return null;
        NAlias plain = new NAlias(keys);
        ArrayList<String> exceptions = new ArrayList<>();
        for (String name : names) {
            if (!SwillItemRegistry.isSwillItem(name) && plain.matches(name))
                exceptions.add(name);
        }
        return new NAlias(keys, exceptions);
    }

    static int countSwill(NGameUI gui) throws InterruptedException {
        NAlias swill = carriedSwill(gui);
        return (swill == null) ? 0 : gui.getInventory().getItems(swill).size();
    }
}
