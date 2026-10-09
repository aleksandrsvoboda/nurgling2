package nurgling.actions.bots.areamover;

import haven.Coord2d;
import haven.Gob;
import haven.Pair;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.PathFinder;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

/** Navigation and gob lookups shared by the load and unload halves of the Area Mover. */
final class AreaScan {
    /**
     * Storage the mover empties in X and fills in Y. The rest of NContext.contcaps (kiln, tables,
     * study desks, herbalist table, extraction press, jotun clam) holds things in use, not stock.
     */
    static final Set<String> STORAGE = Set.of(
            "gfx/terobjs/chest",
            "gfx/terobjs/crate",
            "gfx/terobjs/cupboard",
            "gfx/terobjs/shed",
            "gfx/terobjs/largechest",
            "gfx/terobjs/metalcabinet",
            "gfx/terobjs/strawbasket",
            "gfx/terobjs/bonechest",
            "gfx/terobjs/coffer",
            "gfx/terobjs/leatherbasket",
            "gfx/terobjs/woodbox",
            "gfx/terobjs/linencrate",
            "gfx/terobjs/stonecasket",
            "gfx/terobjs/birchbasket",
            "gfx/terobjs/wbasket",
            "gfx/terobjs/thatchbasket",
            "gfx/terobjs/map/stonekist",
            "gfx/terobjs/exquisitechest",
            "gfx/terobjs/dng/ratchest"
    );

    static final NAlias PILES = new NAlias("stockpile");

    private AreaScan() {
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

    static ArrayList<Gob> storage(Pair<Coord2d, Coord2d> rc) throws InterruptedException {
        ArrayList<Gob> result = new ArrayList<>();
        for (Gob gob : Finder.findGobs(rc, new NAlias(new ArrayList<>(STORAGE), new ArrayList<>()))) {
            if (gob.ngob.name != null && STORAGE.contains(gob.ngob.name))
                result.add(gob);
        }
        return result;
    }

    static ArrayList<Gob> piles(Pair<Coord2d, Coord2d> rc) throws InterruptedException {
        return Finder.findGobs(rc, PILES);
    }

    static String cap(Gob gob) {
        return NContext.contcaps.get(gob.ngob.name);
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

    /** True when the two areas share at least one tile. */
    static boolean overlap(NArea a, NArea b) {
        if (a.space == null || b.space == null)
            return false;
        for (Long grid : a.space.space.keySet()) {
            NArea.VArea va = a.space.space.get(grid);
            NArea.VArea vb = b.space.space.get(grid);
            if (va == null || vb == null)
                continue;
            if (va.area.ul.x < vb.area.br.x && vb.area.ul.x < va.area.br.x
                    && va.area.ul.y < vb.area.br.y && vb.area.ul.y < va.area.br.y)
                return true;
        }
        return false;
    }
}
