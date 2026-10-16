package nurgling.planning;

import haven.*;
import haven.res.gfx.terobjs.consobj.Consobj;
import nurgling.GhostAlpha;
import nurgling.NHitBox;
import nurgling.NUtils;
import nurgling.actions.bots.blueprint.BuildingNames;

import java.util.*;

import static nurgling.i18n.L10n.get;

/**
 * Saves what is already built in a rectangle of the world as a {@link PlannerPreset}: every object the
 * Build menu can place becomes a ghost at its exact position and facing, together with the visible
 * planner ghosts there, all in one layer. A construction sign counts as the building it will become.
 *
 * Only the building's resource is kept, not its sprite data: on a standing building that data is its
 * state (lit, full, open), not what was picked when it was placed, and the Blueprint builder doesn't
 * use it anyway.
 */
public final class WorldCapture
{
    private static final String SIGN = "gfx/terobjs/consobj";
    private static final String VEHICLES = "gfx/terobjs/vehicle/";
    /** World objects not worth reporting as skipped: nobody expects a tree in a building preset. */
    private static final String[] UNREPORTED = {"gfx/terobjs/trees/", "gfx/terobjs/bushes/", "gfx/terobjs/plants/"};
    /** A ghost this close to a built object of the same kind is that object, planned before it was built. */
    static final double SAME_SPOT = 1.5;
    /** How many skipped kinds the chat message names. */
    private static final int SKIPPED_SHOWN = 8;

    private WorldCapture() {}

    /** What a capture made, and how many objects of each kind it left out (by resource basename). */
    public static final class Result
    {
        public final PlannerPreset preset;
        public final Map<String, Integer> skipped;

        Result(PlannerPreset preset, Map<String, Integer> skipped)
        {
            this.preset = preset;
            this.skipped = skipped;
        }
    }

    /**
     * Captures the world rectangle [{@code min}, {@code max}) of {@code glob}, adding {@code ghosts}
     * (planner ghosts there, in world units). Throws if part of the rectangle isn't loaded, or if
     * there is nothing to save.
     */
    static Result capture(String name, Glob glob, Coord2d min, Coord2d max, List<PlannerPreset.Ghost> ghosts)
            throws PlannerPreset.CaptureException
    {
        if (!loaded(glob, min, max))
            throw new PlannerPreset.CaptureException(get("planner.preset.world_unloaded"));
        List<PlannerPreset.Ghost> objects = new ArrayList<>();
        Map<PlannerPreset.Ghost, double[]> extents = new IdentityHashMap<>();
        Map<String, Integer> skipped = new HashMap<>();
        long player = NUtils.playerID();
        synchronized (glob.oc) {
            for (Gob gob : glob.oc) {
                if (gob instanceof OCache.Virtual || gob instanceof MapView.Plob || gob.id <= 0 || gob.id == player)
                    continue;
                if (gob.getattr(GhostAlpha.class) != null || gob.ngob == null || gob.ngob.name == null)
                    continue;
                if (gob.rc.x < min.x || gob.rc.x >= max.x || gob.rc.y < min.y || gob.rc.y >= max.y)
                    continue;
                String res = gob.ngob.name;
                byte[] sdt = null;
                if (SIGN.equals(res)) {
                    ResData built = signTarget(gob);
                    if (built == null) {
                        skip(skipped, res);
                        continue;
                    }
                    res = built.res.get().name;
                    sdt = (built.sdt != null) ? built.sdt.clone().bytes() : null;
                }
                if (res.startsWith(VEHICLES) || BuildingNames.nameOf(res) == null) {
                    skip(skipped, res);
                    continue;
                }
                PlannerPreset.Ghost g = new PlannerPreset.Ghost(res, sdt, gob.rc.x, gob.rc.y, gob.a);
                NHitBox hb = (gob.ngob.hitBox != null) ? gob.ngob.hitBox : NHitBox.findCustom(res);
                objects.add(g);
                extents.put(g, PlannerPreset.extent(hb, g.x, g.y, g.angle));
            }
        }
        PlannerPreset p = build(name, objects, extents, ghosts);
        if (p == null) {
            String msg = get("planner.preset.world_empty");
            if (!skipped.isEmpty())
                msg += " " + skippedText(skipped);
            throw new PlannerPreset.CaptureException(msg);
        }
        return new Result(p, skipped);
    }

    /**
     * One layer named {@code name}: the built objects, then the ghosts that aren't one of them.
     * {@code extents} holds the objects' world bounds; ghosts use their resource's footprint. Null if
     * there is nothing at all.
     */
    static PlannerPreset build(String name, List<PlannerPreset.Ghost> objects, Map<PlannerPreset.Ghost, double[]> extents,
                               List<PlannerPreset.Ghost> ghosts)
    {
        PlannerPreset.Layer layer = new PlannerPreset.Layer(name);
        layer.ghosts.addAll(objects);
        for (PlannerPreset.Ghost g : ghosts) {
            if (!builtAt(objects, g))
                layer.ghosts.add(g);
        }
        if (layer.ghosts.isEmpty())
            return null;
        PlannerPreset p = new PlannerPreset();
        p.name = name;
        p.layers.add(layer);
        p.normalize(g -> extents.containsKey(g) ? extents.get(g) : PlannerPreset.footprint(g));
        return p;
    }

    private static boolean builtAt(List<PlannerPreset.Ghost> objects, PlannerPreset.Ghost g)
    {
        for (PlannerPreset.Ghost o : objects) {
            if (o.res.equals(g.res) && Math.hypot(o.x - g.x, o.y - g.y) <= SAME_SPOT)
                return true;
        }
        return false;
    }

    /** "palisadeseg ×38, stockpile-board ×5, ..." for the chat, most common first. */
    public static String skippedText(Map<String, Integer> skipped)
    {
        List<Map.Entry<String, Integer>> kinds = new ArrayList<>(skipped.entrySet());
        kinds.sort((a, b) -> (!a.getValue().equals(b.getValue())) ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < Math.min(SKIPPED_SHOWN, kinds.size()); i++) {
            if (i > 0)
                list.append(", ");
            list.append(kinds.get(i).getKey()).append(" ×").append(kinds.get(i).getValue());
        }
        if (kinds.size() > SKIPPED_SHOWN)
            list.append(", ").append(get("planner.preset.world_more", kinds.size() - SKIPPED_SHOWN));
        return get("planner.preset.world_skipped", list.toString());
    }

    private static void skip(Map<String, Integer> skipped, String res)
    {
        for (String prefix : UNREPORTED) {
            if (res.startsWith(prefix))
                return;
        }
        if (!res.startsWith("gfx/terobjs/"))
            return;
        skipped.merge(res.substring(res.lastIndexOf('/') + 1), 1, Integer::sum);
    }

    /** What a construction sign will become, or null while that is unknown. */
    private static ResData signTarget(Gob sign)
    {
        ResDrawable rd = sign.getattr(ResDrawable.class);
        if (rd == null || !(rd.spr instanceof Consobj))
            return null;
        ResData built = ((Consobj) rd.spr).built;
        if (built == null)
            return null;
        try {
            built.res.get();
            return built;
        } catch (Loading l) {
            return null;
        }
    }

    /** True if every map grid under the rectangle is loaded, so its objects can be there. */
    private static boolean loaded(Glob glob, Coord2d min, Coord2d max)
    {
        Coord g1 = min.floor(MCache.tilesz).div(MCache.cmaps);
        Coord g2 = max.sub(1e-6, 1e-6).floor(MCache.tilesz).div(MCache.cmaps);
        synchronized (glob.map.grids) {
            for (int x = g1.x; x <= g2.x; x++) {
                for (int y = g1.y; y <= g2.y; y++) {
                    if (glob.map.grids.get(Coord.of(x, y)) == null)
                        return false;
                }
            }
        }
        return true;
    }
}
