package nurgling.colony.actions;

import haven.*;
import haven.res.gfx.terobjs.consobj.Consobj;
import nurgling.GhostAlpha;
import nurgling.NGameUI;
import nurgling.NGob;
import nurgling.NHitBox;
import nurgling.NISBox;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.PathFinder;
import nurgling.actions.RestoreResources;
import nurgling.actions.Results;
import nurgling.actions.TakeItems2;
import nurgling.actions.bots.blueprint.BuildPaginae;
import nurgling.actions.bots.blueprint.BuildRecipes;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Finder;
import nurgling.widgets.Specialisation;
import nurgling.colony.ColonyItems;
import nurgling.colony.ColonyReporter;
import nurgling.colony.ColonyStorage;
import nurgling.colony.ColonyZones;
import nurgling.tools.StackSupporter;
import haven.error.FileLogger;
import nurgling.colony.SpawnPlan;
import nurgling.NInventory;
import nurgling.NMapView;
import nurgling.navigation.ChunkNavManager;
import nurgling.navigation.ChunkPath;
import nurgling.tools.NAlias;
import nurgling.i18n.L10n;
import nurgling.pf.NHitBoxD;
import nurgling.sessions.squad.SquadExecutor;
import nurgling.sessions.squad.WorldRef;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitBuildState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static haven.OCache.posres;

/**
 * Builds a Wilderness Beacon in the Beacons area (or next to the colony's home without one), with the materials the
 * builder carries and those in the Construction Materials zones of their subtype (Clay, Stone, Board, Block), the
 * worst quality first. Nothing is taken from anywhere else. Runs on the builder's own session. A beacon or beacon sign
 * already standing near home is used instead of placing a new one, so a stopped run picks up where it was.
 *
 * <p>The placing, window reading and Build loop follow the Blueprint Builder's GhostConstruction, which is tied to
 * planner ghosts; this one is tied to a spot. Fetching follows its MaterialFetcher, without the fall-back to other
 * areas: one trip shares the free cells between what is missing, and the way back goes through chunk navigation.
 */
public final class BuildBeacon implements Action {
    public static final String BEACON = "gfx/terobjs/wildernessbeacon";
    private static final String SIGN = "gfx/terobjs/consobj";
    /** A beacon or sign this close to home is the colony's. */
    private static final double MINE = 15 * MCache.tilesz.x;
    /** "This spot": a sign or beacon this close to where it was placed is the one. */
    private static final double SPOT = 1.5 * MCache.tilesz.x;
    private static final int SEARCH_TILES = 8;
    private static final int MAX_PLACE_TRIES = 6;
    private static final long WAIT_MS = 5000;
    private static final long PLACE_MS = 10000;
    private static final long PROG_MS = 2000;
    private static final int MAX_STALLS = 3;
    /** Bulkiest first, so one trip fills the space with what is hardest to fit later. */
    private static final List<ColonyItems.Material> FETCH_ORDER = Arrays.asList(
        ColonyItems.Material.BOARD, ColonyItems.Material.BLOCK, ColonyItems.Material.STONE, ColonyItems.Material.CLAY);

    /** Back at the site: the player is this close to it. */
    private static final double NEAR = 30 * MCache.tilesz.x;

    private final WorldRef home;
    private final ColonyStorage storage;
    /** Where the beacon stands, once there is a sign or a beacon. */
    public volatile WorldRef site;
    /** Why the run failed, for the Colony panel; null when it didn't. */
    public volatile String problem;
    /** What it is doing now, for the Colony panel. */
    public volatile String step;

    public BuildBeacon(WorldRef home, ColonyStorage storage) {
        this.home = home;
        this.storage = storage;
    }

    private Results fail(String key, Object... args) {
        problem = L10n.get(key, args);
        return Results.FAIL();
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        /* A placed sign that gets nothing put in it vanishes, so the builder carries materials before it places one
         * and starts building at once. */
        Results stocked = stockUp(gui);
        if (!stocked.IsSuccess())
            return stocked;
        boolean beaconsArea = NContext.findSpecGlobal(Specialisation.SpecName.beacons.toString()) != null;
        if (!beaconsArea && !goTo(gui, home))
            return fail("colony.err.gohome");
        MiniMap.Location sl = SquadExecutor.sessloc(gui);
        Coord2d h = home.localIn(gui.map.glob.map, sl);
        if (h == null)
            return fail("colony.err.home");
        step = L10n.get("colony.step.site");
        /* A Beacons area keeps the colony's beacons (and so its newcomers' Hearth Fires) together; without one they
         * go next to home. */
        Predicate<Coord2d> mine;
        List<Coord2d> spots;
        if (beaconsArea) {
            step = L10n.get("colony.step.area");
            NArea area = new NContext(gui).goToArea(Specialisation.SpecName.beacons);
            Pair<Coord2d, Coord2d> rc = (area == null) ? null : area.getRCArea();
            if (rc == null)
                return fail("colony.err.area");
            Coord2d ul = rc.a.sub(MCache.tilesz), br = rc.b.add(MCache.tilesz);
            mine = p -> p.x >= ul.x && p.y >= ul.y && p.x <= br.x && p.y <= br.y;
            spots = null;
            areaRc = rc;
        } else {
            mine = p -> p.dist(h) <= MINE;
            spots = spots(h);
        }
        Gob there = find(gui, mine, BEACON, h);
        if (there != null) {
            site = ref(gui, there.rc);
            return Results.SUCCESS();
        }
        Gob sign = beaconSign(gui, mine, h);
        if (sign == null) {
            Results placed = place(gui, spots);
            if (!placed.IsSuccess())
                return placed;
            sign = (placedAt == null) ? null : beaconSign(gui, placedAt);
            if (sign == null)
                return fail("colony.err.nosign");
        }
        site = ref(gui, sign.rc);
        return finish(gui);
    }

    /* The Beacons area's ground, when the beacon goes there; spots in it are worked out once the button's hitbox is known. */
    private Pair<Coord2d, Coord2d> areaRc;
    /** Where this run placed its sign. */
    private Coord2d placedAt;

    private static WorldRef ref(NGameUI gui, Coord2d local) {
        return WorldRef.of(gui.map.glob.map, SquadExecutor.sessloc(gui), local);
    }

    /* ---------------------------------------------------------------- finding */

    /** The gob named {@code res} where {@code mine} says, nearest {@code to}. */
    private static Gob find(NGameUI gui, Predicate<Coord2d> mine, String res, Coord2d to) {
        Gob best = null;
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob g : gui.ui.sess.glob.oc) {
                if (g.ngob == null || !res.equals(g.ngob.name) || !mine.test(g.rc))
                    continue;
                if (best == null || g.rc.dist(to) < best.rc.dist(to))
                    best = g;
            }
        }
        return best;
    }

    private static Gob nearest(NGameUI gui, Coord2d at, String res) {
        Gob best = null;
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob g : gui.ui.sess.glob.oc) {
                if (g.ngob == null || !res.equals(g.ngob.name) || g.rc.dist(at) > SPOT)
                    continue;
                if (best == null || g.rc.dist(at) < best.rc.dist(at))
                    best = g;
            }
        }
        return best;
    }

    /** A construction sign where {@code mine} says that becomes a beacon, nearest {@code to}. */
    private static Gob beaconSign(NGameUI gui, Predicate<Coord2d> mine, Coord2d to) {
        Gob best = null;
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob g : gui.ui.sess.glob.oc) {
                if (g.ngob == null || !SIGN.equals(g.ngob.name) || !mine.test(g.rc) || !BEACON.equals(signTarget(g)))
                    continue;
                if (best == null || g.rc.dist(to) < best.rc.dist(to))
                    best = g;
            }
        }
        return best;
    }

    /** A construction sign on the spot {@code at} that becomes a beacon. */
    private static Gob beaconSign(NGameUI gui, Coord2d at) {
        Gob best = null;
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob g : gui.ui.sess.glob.oc) {
                if (g.ngob == null || !SIGN.equals(g.ngob.name) || g.rc.dist(at) > SPOT || !BEACON.equals(signTarget(g)))
                    continue;
                if (best == null || g.rc.dist(at) < best.rc.dist(at))
                    best = g;
            }
        }
        return best;
    }

    private static String signTarget(Gob sign) {
        ResDrawable rd = sign.getattr(ResDrawable.class);
        if (rd == null || !(rd.spr instanceof Consobj))
            return null;
        Consobj c = (Consobj) rd.spr;
        try {
            return (c.built != null) ? c.built.res.get().name : null;
        } catch (Loading l) {
            return null;
        }
    }

    /* ---------------------------------------------------------------- placing */

    /** Places the sign on the first spot the server takes; {@code spots} null means a free place in the Beacons area. */
    private Results place(NGameUI gui, List<Coord2d> spots) throws InterruptedException {
        step = L10n.get("colony.step.place");
        MapView.Plob plob = startPlacing(gui);
        if (plob == null)
            return fail("colony.err.nobutton");
        NHitBox hb = (plob.ngob != null && plob.ngob.hitBox != null) ? plob.ngob.hitBox : NHitBox.findCustom(BEACON);
        if (spots == null) {
            /* A tile of clear ground between beacons and Hearth Fires, then none if the area is that full. */
            spots = new ArrayList<>();
            for (double spread : new double[]{MCache.tilesz.x, 0}) {
                Coord2d p = (hb == null) ? null : Finder.getFreePlace(areaRc, hb, 0, spread);
                if (p != null && !spots.contains(p))
                    spots.add(p);
            }
            if (spots.isEmpty()) {
                cancelPlacing(gui);
                return fail("colony.err.areafull");
            }
        }
        String lastError = null;
        int tries = 0;
        for (Coord2d pos : spots) {
            if (tries >= MAX_PLACE_TRIES)
                break;
            if (blocker(gui, hb, pos) != null)
                continue;
            if (gui.map.placing == null && startPlacing(gui) == null)
                return fail("colony.err.nobutton");
            PathFinder pf = new PathFinder(NGob.getDummy(pos, 0, hb), true);
            pf.isHardMode = true;
            if (!pf.run(gui).IsSuccess())
                continue;
            tries++;
            gui.ui.dropLastError();
            gui.map.wdgmsg("place", pos.floor(posres), 0, 1, 0);
            String[] error = {null};
            long deadline = System.currentTimeMillis() + PLACE_MS;
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    String err = gui.ui.getLastError();
                    if (err != null)
                        error[0] = err;
                    return error[0] != null || beaconSign(gui, pos) != null || System.currentTimeMillis() > deadline;
                }
            });
            if (beaconSign(gui, pos) != null) {
                placedAt = pos;
                return Results.SUCCESS();
            }
            if (error[0] != null)
                lastError = error[0];
        }
        cancelPlacing(gui);
        return (lastError != null) ? fail("colony.err.place", lastError) : fail("colony.err.nospot");
    }

    /** Tile centres around home, nearest rings first. */
    private static List<Coord2d> spots(Coord2d h) {
        Coord c = h.floor(MCache.tilesz);
        List<Coord2d> out = new ArrayList<>();
        for (int r = 0; r <= SEARCH_TILES; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r)
                        continue;
                    out.add(Coord2d.of(c.add(dx, dy)).mul(MCache.tilesz).add(MCache.tilesz.div(2)));
                }
            }
        }
        return out;
    }

    private static MapView.Plob startPlacing(NGameUI gui) throws InterruptedException {
        for (MenuGrid.Pagina pag : BuildPaginae.candidates(gui, BEACON)) {
            cancelPlacing(gui);
            pag.button().use(new MenuGrid.Interaction(1, 0));
            long deadline = System.currentTimeMillis() + WAIT_MS;
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    Loader.Future<MapView.Plob> p = gui.map.placing;
                    return (p != null && p.ready()) || System.currentTimeMillis() > deadline;
                }
            });
            Loader.Future<MapView.Plob> snapshot = gui.map.placing;
            if (snapshot != null && snapshot.ready()) {
                MapView.Plob p = snapshot.get();
                if (BEACON.equals(plobRes(p)))
                    return p;
            }
        }
        cancelPlacing(gui);
        return null;
    }

    private static String plobRes(MapView.Plob p) {
        ResDrawable rd = p.getattr(ResDrawable.class);
        try {
            return (rd != null && rd.res != null) ? rd.res.get().name : null;
        } catch (Loading l) {
            return null;
        }
    }

    private static void cancelPlacing(NGameUI gui) {
        if (gui.map.placing != null)
            gui.map.uimsg("unplace");
    }

    /** A real gob whose hitbox overlaps the beacon's footprint at {@code pos}, or null. */
    private static Gob blocker(NGameUI gui, NHitBox hb, Coord2d pos) {
        if (hb == null)
            return null;
        NHitBoxD box = new NHitBoxD(hb.begin, hb.end, pos, 0);
        Gob player = NUtils.player();
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
                if (gob instanceof OCache.Virtual || gob instanceof MapView.Plob || gob.id <= 0)
                    continue;
                if (player != null && gob.id == player.id)
                    continue;
                if (gob.getattr(GhostAlpha.class) != null || gob.getattr(Following.class) != null)
                    continue;
                if (gob.ngob == null || gob.ngob.hitBox == null)
                    continue;
                if (new NHitBoxD(gob).intersects(box, false))
                    return gob;
            }
        }
        return null;
    }

    /* ---------------------------------------------------------------- building */

    private Results finish(NGameUI gui) throws InterruptedException {
        String lastProgress = null;
        int stalls = 0;
        while (true) {
            Coord2d pos = site.localIn(gui.map.glob.map, SquadExecutor.sessloc(gui));
            if (pos == null)
                return fail("colony.err.back");
            Gob sign = beaconSign(gui, pos);
            if (sign == null) {
                long deadline = System.currentTimeMillis() + WAIT_MS;
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        return nearest(gui, pos, BEACON) != null || System.currentTimeMillis() > deadline;
                    }
                });
                Gob b = nearest(gui, pos, BEACON);
                if (b != null) {
                    site = ref(gui, b.rc);
                    storage.stale();
                    return Results.SUCCESS();
                }
                return fail("colony.err.signgone");
            }
            step = L10n.get("colony.step.build");
            Window wnd = openWindow(gui, sign);
            if (wnd == null)
                return fail("colony.err.window");
            Map<ColonyItems.Material, int[]> slots = readSlots(wnd);
            if (slots == null)
                return fail("colony.err.window");
            Map<ColonyItems.Material, Integer> missing = new EnumMap<>(ColonyItems.Material.class);
            boolean carrying = false;
            for (Map.Entry<ColonyItems.Material, int[]> e : slots.entrySet()) {
                int free = e.getValue()[1];
                if (free > 0) {
                    missing.put(e.getKey(), free);
                    if (ColonyReporter.count(gui.maininv, e.getKey()) > 0)
                        carrying = true;
                }
            }
            if (!missing.isEmpty() && !carrying) {
                step = L10n.get("colony.step.fetch");
                Fetched f = fetch(gui, missing);
                if (f.full)
                    return fail("colony.err.invfull");
                if (f.empty != null)
                    return fail("colony.err.nomaterial", L10n.get("colony.mat." + f.empty.name().toLowerCase()));
                step = L10n.get("colony.step.back");
                if (!goBack(gui))
                    return fail("colony.err.back");
                continue;
            }
            StringBuilder progress = new StringBuilder();
            for (Map.Entry<ColonyItems.Material, int[]> e : slots.entrySet())
                progress.append(e.getKey()).append('=').append(e.getValue()[0]).append('/').append(e.getValue()[1]).append(';');
            ResDrawable rd = sign.getattr(ResDrawable.class);
            if (rd != null && rd.spr instanceof Consobj)
                progress.append(((Consobj) rd.spr).done);
            if (progress.toString().equals(lastProgress)) {
                if (++stalls >= MAX_STALLS)
                    return fail("colony.err.stalled");
            } else {
                stalls = 0;
                lastProgress = progress.toString();
            }
            NUtils.startBuild(wnd);
            long deadline = System.currentTimeMillis() + PROG_MS;
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return gui.prog != null || System.currentTimeMillis() > deadline;
                }
            });
            WaitBuildState wbs = new WaitBuildState();
            NUtils.addTask(wbs);
            if (wbs.getState() == WaitBuildState.State.TIMEFORDRINK || wbs.getState() == WaitBuildState.State.DANGER) {
                if (!new RestoreResources().run(gui).IsSuccess())
                    return fail("colony.err.restore");
            }
        }
    }

    /** How a fetch trip went: a material nowhere to be had, or no room for any of them; neither when it brought some. */
    private static final class Fetched {
        ColonyItems.Material empty;
        boolean full;
        boolean gained;
    }

    /**
     * One trip to the Construction Materials zones: each missing material gets a share of the free cells in proportion
     * to what it still needs (as the Blueprint Builder's MaterialFetcher does), so one trip brings some of everything.
     */
    private Fetched fetch(NGameUI gui, Map<ColonyItems.Material, Integer> missing) throws InterruptedException {
        NInventory inv = gui.getInventory();
        /* Only what can be had takes part in the share: a zone nobody can route to would hold cells back for
         * nothing, and the rest would come one at a time. */
        Map<ColonyItems.Material, Integer> reachable = new EnumMap<>(ColonyItems.Material.class);
        for (Map.Entry<ColonyItems.Material, Integer> e : missing.entrySet())
            if (ColonyZones.SUBTYPE.containsKey(e.getKey())
                    && NContext.findSpecGlobal(Specialisation.SpecName.buildMaterials.toString(), ColonyZones.SUBTYPE.get(e.getKey())) != null)
                reachable.put(e.getKey(), e.getValue());
        Map<ColonyItems.Material, Integer> plan = plan(inv, inv.getFreeSpace(), reachable);
        for (ColonyItems.Material m : missing.keySet())
            if (ColonyZones.SUBTYPE.containsKey(m) && !reachable.containsKey(m))
                plan.put(m, missing.get(m));
        NContext cnt = new NContext(gui);
        Fetched out = new Fetched();
        boolean gained = false, roomForAny = false;
        for (ColonyItems.Material m : FETCH_ORDER) {
            Integer want = plan.get(m);
            if (want == null || want <= 0)
                continue;
            String sub = ColonyZones.SUBTYPE.get(m);
            if (NContext.findSpecGlobal(Specialisation.SpecName.buildMaterials.toString(), sub) == null) {
                log(m, missing.get(m), want, "no " + sub + " zone it can route to");
                if (out.empty == null)
                    out.empty = m;
                continue;
            }
            if (inv.getNumberFreeCoord(BuildRecipes.shapeOf(sub)) <= 0) {
                log(m, missing.get(m), want, "no room for one");
                continue;
            }
            roomForAny = true;
            int before = ColonyReporter.count(gui.maininv, m);
            TakeItems2 t = new TakeItems2(cnt, before + want, Specialisation.SpecName.buildMaterials,
                                          sub, NInventory.QualityType.Low);
            t.exactMatch = true;
            Results r = t.takeAny(new NAlias(new ArrayList<>(ColonyItems.names(m)), new ArrayList<>()), gui);
            int got = ColonyReporter.count(gui.maininv, m) - before;
            log(m, missing.get(m), want, "got " + got + (r.IsSuccess() ? "" : " (zone has no storage it could reach)"));
            if (got > 0)
                gained = true;
            else if (out.empty == null)
                out.empty = m;
        }
        out.gained = gained;
        if (gained) {
            out.empty = null;
        } else if (!roomForAny && out.empty == null) {
            out.full = true;
        }
        storage.stale();
        return out;
    }

    private static void log(ColonyItems.Material m, int missing, int trip, String what) {
        FileLogger.log("[Colony spawn] fetch " + m + ": missing " + missing + ", this trip " + trip + ": " + what);
    }

    /**
     * How many of each to bring this trip. Everything that is missing when it fits; otherwise the free cells are shared
     * in proportion to the cells each material still needs, as the Blueprint Builder's MaterialFetcher does, but
     * counting stacks: with stacking on, clay and stone pack a whole stack into one cell, so ten of them take two
     * cells, not ten.
     */
    static Map<ColonyItems.Material, Integer> plan(NInventory inv, int free, Map<ColonyItems.Material, Integer> missing) {
        boolean stacking = inv.bundle != null && inv.bundle.a;
        Map<ColonyItems.Material, int[]> per = new EnumMap<>(ColonyItems.Material.class);
        double total = 0;
        for (Map.Entry<ColonyItems.Material, Integer> e : missing.entrySet()) {
            if (e.getValue() <= 0 || !ColonyZones.SUBTYPE.containsKey(e.getKey()))
                continue;
            int area = cells(e.getKey());
            int stack = stacking ? stackSize(inv, e.getKey()) : 1;
            per.put(e.getKey(), new int[] {area, stack});
            total += cellsFor(e.getValue(), area, stack);
        }
        Map<ColonyItems.Material, Integer> plan = new EnumMap<>(ColonyItems.Material.class);
        for (Map.Entry<ColonyItems.Material, int[]> e : per.entrySet()) {
            int want = missing.get(e.getKey());
            int area = e.getValue()[0], stack = e.getValue()[1];
            if (total <= free) {
                plan.put(e.getKey(), want);
                continue;
            }
            double share = free * cellsFor(want, area, stack) / total;
            int places = Math.max(1, (int) Math.floor(share / area));
            plan.put(e.getKey(), Math.min(want, places * stack));
        }
        return plan;
    }

    private static int cellsFor(int n, int area, int stack) {
        return ((n + stack - 1) / stack) * area;
    }

    /** Items of m per cell in the player's inventory: a full stack for stacking materials, else 1. */
    private static int stackSize(NInventory inv, ColonyItems.Material m) {
        for (String name : ColonyItems.names(m))
            if (StackSupporter.isStackable(inv, name))
                return Math.max(1, StackSupporter.getFullStackSize(name));
        return 1;
    }

    private static int cells(ColonyItems.Material m) {
        Coord s = BuildRecipes.shapeOf(ColonyZones.SUBTYPE.get(m));
        return Math.max(1, s.x * s.y);
    }

    /**
     * Unless it already carries some of every material the beacon needs, one trip to the Construction Materials
     * zones for what is missing. Fails when it then still carries nothing to start the sign with.
     */
    private Results stockUp(NGameUI gui) throws InterruptedException {
        Map<ColonyItems.Material, Integer> missing = new EnumMap<>(ColonyItems.Material.class);
        boolean all = true, any = false;
        for (Map.Entry<ColonyItems.Material, Integer> e : SpawnPlan.BEACON.entrySet()) {
            int have = ColonyReporter.count(gui.maininv, e.getKey());
            if (have > 0)
                any = true;
            else
                all = false;
            if (have < e.getValue())
                missing.put(e.getKey(), e.getValue() - have);
        }
        if (all || missing.isEmpty())
            return Results.SUCCESS();
        step = L10n.get("colony.step.fetch");
        Fetched f = fetch(gui, missing);
        if (any || f.gained)
            return Results.SUCCESS();
        if (f.full)
            return fail("colony.err.invfull");
        ColonyItems.Material m = (f.empty != null) ? f.empty : missing.keySet().iterator().next();
        return fail("colony.err.nomaterial", L10n.get("colony.mat." + m.name().toLowerCase()));
    }

    /**
     * Back to the site after a trip: through the Beacons area when there is one, else by chunk navigation to the
     * site's tile (the trip may have ended in a house or another grid, where the sign is not loaded).
     */
    private boolean goBack(NGameUI gui) throws InterruptedException {
        if (near(gui, site))
            return true;
        if (NContext.findSpecGlobal(Specialisation.SpecName.beacons.toString()) != null) {
            new NContext(gui).goToArea(Specialisation.SpecName.beacons);
            return near(gui, site);
        }
        return goTo(gui, site);
    }

    /** Walks by chunk navigation until {@code to} is near (it may be in another grid, or out of the house). */
    private static boolean goTo(NGameUI gui, WorldRef to) throws InterruptedException {
        if (near(gui, to))
            return true;
        ChunkNavManager nav = (gui.map instanceof NMapView) ? ((NMapView) gui.map).getChunkNavManager() : null;
        if (nav != null && nav.isInitialized()) {
            ChunkPath path = nav.planToGridCoord(to.gridId, to.off.floor(MCache.tilesz));
            if (path != null)
                nav.navigateWithPath(path, null, gui);
        }
        return near(gui, to);
    }

    private static boolean near(NGameUI gui, WorldRef to) {
        Coord2d at = to.localIn(gui.map.glob.map, SquadExecutor.sessloc(gui));
        Gob pl = gui.map.player();
        return at != null && pl != null && at.dist(pl.rc) < NEAR;
    }

    private static Window openWindow(NGameUI gui, Gob sign) throws InterruptedException {
        Window wnd = constructionWindow(gui);
        if (wnd != null)
            return wnd;
        PathFinder pf = new PathFinder(sign);
        pf.isHardMode = true;
        if (!pf.run(gui).IsSuccess())
            return null;
        NUtils.rclickGob(sign);
        long deadline = System.currentTimeMillis() + WAIT_MS;
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return constructionWindow(gui) != null || System.currentTimeMillis() > deadline;
            }
        });
        return constructionWindow(gui);
    }

    /** An open window with a "Build" button and material boxes. */
    private static Window constructionWindow(NGameUI gui) {
        for (Widget w = gui.lchild; w != null; w = w.prev) {
            if (!(w instanceof Window))
                continue;
            boolean build = false;
            for (Widget c = w.lchild; c != null; c = c.prev)
                if (c instanceof Button && ((Button) c).text != null && "Build".equals(((Button) c).text.text))
                    build = true;
            if (build && !boxes(w).isEmpty())
                return (Window) w;
        }
        return null;
    }

    private static List<NISBox> boxes(Widget root) {
        List<NISBox> out = new ArrayList<>();
        for (Widget c = root.child; c != null; c = c.next) {
            if (c instanceof NISBox)
                out.add((NISBox) c);
            else
                out.addAll(boxes(c));
        }
        return out;
    }

    /** Material to {in the sign, still needed}; null while unreadable or when a box asks for something unknown. */
    private static Map<ColonyItems.Material, int[]> readSlots(Window wnd) throws InterruptedException {
        Map<ColonyItems.Material, int[]> out = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + WAIT_MS;
        boolean[] ok = {false};
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                out.clear();
                try {
                    for (NISBox box : boxes(wnd)) {
                        Resource res = box.itemres.get();
                        Resource.Tooltip tt = res.layer(Resource.tooltip);
                        ColonyItems.Material m = ColonyItems.slot((tt != null) ? tt.t : null);
                        if (m == null)
                            return true;
                        out.put(m, new int[]{box.calcCount(), box.calcFreeSpace()});
                    }
                    ok[0] = true;
                    return true;
                } catch (Loading l) {
                    return System.currentTimeMillis() > deadline;
                }
            }
        });
        return ok[0] ? out : null;
    }
}
