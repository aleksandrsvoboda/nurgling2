package nurgling.actions.bots.riverexplorer;

import haven.*;
import nurgling.*;
import nurgling.actions.Drink;
import nurgling.actions.Equip;
import nurgling.actions.Results;
import nurgling.actions.SelectFlowerAction;
import nurgling.actions.bots.WorldExplorer;
import nurgling.actions.bots.worldexplorer.SeaDanger;
import nurgling.actions.bots.worldexplorer.SeaGrid;
import nurgling.conf.NRiverExplorerProp;
import nurgling.i18n.L10n;
import nurgling.pf.NHitBoxD;
import nurgling.tasks.GetCurs;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitDuration;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.bots.UsingTools;

import java.util.*;

import static haven.OCache.posres;

/** River Explorer's boat: one run's sailing - the exploring loop, legs into the next grid down the middle of the river (Pilot),
 *  reeds, beaver dams, danger, very deep ocean, snags and the world's edge, voids, the course line and claims. Moved out of
 *  RiverExplorer (it was its inner class Voyage) unchanged, so other bots can sail with it: explore(Stopover) is River
 *  Explorer's loop with a hook that may stop the boat on the way, hop(tune) one pass of that loop with the target's worth
 *  chosen by the caller (RiverFrontier.Ask.value), sailInto(ul, br) one leg into a box of tiles near the boat. One instance
 *  per run - the bot menu reuses the Action instance, so nothing lives in the bot's fields. */
public final class Boat {
    /** Fresh water: rivers and lakes. Swamp, fen and bog water is out of scope and counts as land, for every boat. */
    public static boolean freshWater(String n) {
        return n.startsWith("gfx/tiles/water") || n.equals("gfx/tiles/deep");
    }

    /** Sea the boat may sail: shallow and deep ocean. Very deep ocean (odeeper) drowns characters - never entered, it is land
     *  to every part of this bot. */
    public static boolean seaWater(String n) {
        return n.startsWith("gfx/tiles/owater") || n.equals("gfx/tiles/odeep");
    }

    public static final String VERY_DEEP = "gfx/tiles/odeeper";

    /** Any water this bot sails (no swamp, no very deep ocean). */
    public static boolean sailable(String n) {
        return freshWater(n) || seaWater(n);
    }

    public static final String REEDS = "gfx/terobjs/bushes/reeds";
    /** Beaver dams (and their doors): they block the river and can't be cleared - the boat goes round. */
    public static final String DAM = "gfx/terobjs/beaverdam";

    static final double TILE = MCache.tilesz.x;
    static final long TICK_MS = 250;
    static final double LOOKAHEAD = 100, LOOKAHEAD_SHIP = 150;
    // Recorded grids are read for at most this long per re-plan; the rest next time.
    static final long LOAD_MS = 400;
    // The course is planned again this often (new gobs, animals moving), or when the boat is this far off it.
    static final long REPLAN_MS = 3000;
    static final double OFF_COURSE = 3 * TILE;
    // Planning window: the leg's start and goal, padded by this many tiles, within the 3x3 grids around the boat. When
    // nothing is found in it, the whole 3x3 is tried: the water out can run well away from the line between them (a lake
    // whose one outlet is at its far end).
    static final int PAD_TILES = 40;
    // How near the hop's entry the leg aims (tiles): first close in, where the water is surely the part the route goes on in
    // (a wider box can take in a bit of river cut off within the grid, which only leads back), then wider, then the whole
    // next grid.
    static final int ENTRY_NEAR_TILES = 2, ENTRY_TILES = 6;
    // A crossing after which the route turned straight back this many times, without anything new being revealed in
    // between, is a dead end (the boat going back and forth): skipped for FAILED_COOLDOWN_MS. Turning back after
    // entering a grid that revealed something is normal - a branch explored - and doesn't count.
    static final int DEAD_END_LIMIT = 2;
    // A crossing the boat failed to make (or a dead end) is skipped this long, not for the rest of the run: what blocked
    // it (a boat, gobs, an animal out of view) may be gone, and a crossing skipped for good can cut off everything past it.
    static final long FAILED_COOLDOWN_MS = 10 * 60_000;
    // Reeds: from this close to them the boat runs into them, aiming this many course points past the first reed point; a
    // reed is chopped once the hull is this close to it (units) - from a boat, as for a tree, you chop what you're up against.
    static final double PUSH_FROM = 1.5 * TILE;
    static final int PUSH_POINTS = 4;
    static final double CONTACT = 3;
    static final int CHOP_TRIES = 3;
    // Running into the reeds and stopped this long with none against the hull: re-plan; IDLE_STANDOFFS of those in a row
    // fail the leg (the course and the gobs disagree) instead of looping.
    static final long STALL_MS = 2000;
    static final int IDLE_STANDOFFS = 3;
    /** World edge: snags this close across the line (units), at least this far apart along it, rowing this much into it. */
    static final double EDGE_LINE = 2;
    static final double EDGE_APART = 2 * TILE;
    static final double EDGE_INTO = 0.1;
    /** How far past the hull's side the wall is taken to be (units). */
    static final double EDGE_PAST = 1;
    /** Getting out of very deep water: how many tries, and how far to look for water that isn't (tiles). */
    static final int VERY_DEEP_TRIES = 3;
    static final int VERY_DEEP_REACH = 60;
    static final String WORLD_EDGE = "gfx/tiles/nurgling/worldedge";
    // An unknown grid within VOID_REACH tiles of the boat that stays unloaded VOID_MS (while the boat's own grid is loaded)
    // doesn't exist: the world ends there.
    static final int VOID_REACH = 50;
    static final int OPEN_SEA_LIMIT = 6;
    // After entering a grid, how long to wait for the client to record the grids round it before choosing on.
    static final long RECORD_WAIT_MS = 3000;
    static final long VOID_MS = 10_000;
    // How long to wait for the minimap to place the boat in a recorded grid.
    static final long NO_MAP_MS = 30_000;
    // Clicks (WorldExplorer.sailTo's rules).
    static final long RECLICK_MS = 1500;
    static final double TURN_STEP = Math.PI / 18;
    // Snags (WorldExplorer.snagged's rules).
    static final long SNAG_MS = 4000;
    static final double SNAG_DISC = 1.5 * TILE;
    static final int SNAG_LIMIT = 3;
    static final long SNAG_WINDOW_MS = 30000;
    static final double SNAG_SPREAD = 3 * TILE;
    static final long BACK_OFF_MS = 1500;
    static final int MAX_SNAG_DISCS = 40;
    // A snag disc is forgotten after this long: whatever held the boat (another boat, an animal) may have moved, and one disc
    // can close a 2-tile river.
    static final long SNAG_DISC_MS = 2 * 60_000;
    // Times in a row the boat may find no water it fits under it (wedged after a back-off) before the run stops.
    static final int NO_WATER_LIMIT = 10;
    // Danger: how long a crossing an animal blocked is skipped, and how often all of them are retried early when nothing else is left.
    static final long DANGER_COOLDOWN_MS = 5 * 60_000;
    static final int MAX_EARLY_RETRIES = 10;
    static final long DANGER_RETRY_WAIT_MS = 30_000;
    // Drinking between chops (the Map Explorer walker's numbers).
    static final double DRINK_BELOW = 0.5, DRINK_TARGET = 0.9;
    static final long DRINK_RETRY_MS = 60_000;

    final NGameUI gui;
    final NRiverExplorerProp prop;
    final Hull hull;
    final WaterAtlas atlas;
    final SeaDanger danger;
    final double lookahead;
    final MCache map;
    final SeaGrid.Tiles live;
    final Claims claims;
    final Coord bias;

    // Run memory, by grid id (a segment merge mid-run keeps it).
    final Set<Long> visited = new HashSet<>();
    final Map<String, Long> failedLinks = new HashMap<>();
    final Map<String, Long> coolingLinks = new HashMap<>();
    // Reeds that wouldn't chop, as segment tiles; chops tried per reed gob.
    final Set<Coord> unchoppable = new HashSet<>();
    final Map<Long, Integer> chopTries = new HashMap<>();
    final List<SeaGrid.Disc> snags = new ArrayList<>();
    final List<Long> snagDiscTimes = new ArrayList<>();
    final List<Coord2d> snagSpots = new ArrayList<>();
    final List<Long> snagTimes = new ArrayList<>();
    final List<Double> snagAngles = new ArrayList<>();
    /** The world's invisible walls found so far (the map ends at its four sides): beyond them the live map reads as land. */
    final List<WorldEdge> edges = new ArrayList<>();

    Coord target = null, heading = null;
    // The last leg's crossing, the grid it started in and how many crossings its route had (see DEAD_END_LIMIT).
    String prevLink = null;
    Long prevPid = null;
    int prevRouteSize = 0, prevKnown = -1;
    final Map<String, Integer> deadEnds = new HashMap<>();
    Long startGridId = null;
    Coord leashCenter = null;
    int noWater = 0;
    int startKnown = -1;
    int earlyRetries = 0;
    String abort = null;
    // The exploring loop's own state (was explore()'s locals), and why the run stopped.
    int known = 0;
    long noMapSince = 0;
    String error = null;
    /** Asked every tick of a leg whether to stop here (null: never). */
    Stopover stopover = null;
    /** When set, the target messages River Explorer shows in the game are left out (the console lines stay). */
    public boolean quiet = false;
    long lastFailedDrinkMs = 0;

    // Click and progress state.
    Coord2d lastTarget = null;
    double lastAngle = 0;
    long lastClickT = 0;
    Coord2d progressRc;
    long progressT;

    // Grids past the world's edge (Voids), for the current segment, and how long each unknown grid near the boat has stayed
    // unloaded while in loading range; the last snapshot, for checks during a leg.
    Voids voids = null;
    long voidsSeg = 0;
    final Map<Coord, Long> unloadedSince = new HashMap<>();
    KnownGrids lastSnap = null;
    // Set once a coast or river is in reach: from then on open sea is only a short look round (OPEN_SEA_LIMIT targets in a
    // row) when nothing else is left - land just out of view - never a voyage across the ocean.
    boolean coastFound = false;
    Long recordedPid = null;
    int openSeaRun = 0;
    // What show() last published, and the way on after the current leg.
    Pilot.Course shownCourse = null;
    int shownIdx = -1;
    List<Coord2d> farRoute = Collections.emptyList();

    // Filled by input(): reed gob per tile it covers, for the cutter.
    final Map<Coord, Gob> reedAt = new HashMap<>();
    // Beaver dams seen this run: grid id -> grid-local tiles under them.
    final Map<Long, Set<Coord>> dams = new HashMap<>();

    public Boat(NGameUI gui, NRiverExplorerProp prop, Hull hull) {
        this(gui, prop, hull, new Claims(NUtils.getDataFilePath("river_claims.nurgling.json")), new SeaDanger(), gui.map.glob.map);
    }

    /** For tests: no game, no claims, no danger, no live map. */
    Boat(NRiverExplorerProp prop, Hull hull) {
        this(null, prop, hull, null, null, null);
    }

    private Boat(NGameUI gui, NRiverExplorerProp prop, Hull hull, Claims claims, SeaDanger danger, MCache map) {
        this.gui = gui;
        this.prop = prop;
        this.hull = hull;
        this.claims = claims;
        this.bias = headOf(prop.head);
        java.util.function.Predicate<String> sea = prop.useSea ? Boat::seaWater : null;
        this.atlas = new WaterAtlas(Boat::freshWater, sea, hull.fit(), prop.useSea ? "s" : "f");
        this.danger = danger;
        this.lookahead = hull.ship ? LOOKAHEAD_SHIP : LOOKAHEAD;
        this.map = map;
        this.live = tc -> {
            for (WorldEdge e : edges)
                if (e.beyond(tc))
                    return WORLD_EDGE;
            try {
                return map.tilesetname(map.gettile(tc));
            } catch (Loading l) {
                return null;
            }
        };
    }

    /** The direction the head setting asks for, in grid steps (north is -y), or null for any. */
    static Coord headOf(String head) {
        if (head == null) return null;
        switch (head) {
            case "N": return Coord.of(0, -1);
            case "E": return Coord.of(1, 0);
            case "S": return Coord.of(0, 1);
            case "W": return Coord.of(-1, 0);
            default: return null;
        }
    }

    /** How a leg ended: in the goal, out of the 3x3 grids it was planned in, no way in, an animal in the way, the run can't
     *  go on (error()), no axe for the reeds. */
    public enum Leg { ENTERED, MOVED, FAILED, DANGER, ABORT, NO_AXE }

    /** An invisible wall where the world ends: a line across x (vertical) or y, blocked on side's side. */
    static final class WorldEdge {
        final boolean vertical;
        final double at;
        final int side;

        WorldEdge(boolean vertical, double at, int side) {
            this.vertical = vertical;
            this.at = at;
            this.side = side;
        }

        /** Whether the tile's middle lies past the wall. */
        boolean beyond(Coord tc) {
            double c = vertical ? (tc.x + 0.5) * TILE : (tc.y + 0.5) * TILE;
            return (c - at) * side > 0;
        }

        /** Whether the whole grid (session grid coords) lies past the wall. */
        boolean beyondGrid(Coord sessGrid) {
            Coord ul = sessGrid.mul(MCache.cmaps), br = ul.add(MCache.cmaps.x - 1, MCache.cmaps.y - 1);
            return beyond(ul) && beyond(br) && beyond(Coord.of(ul.x, br.y)) && beyond(Coord.of(br.x, ul.y));
        }

        boolean same(WorldEdge o) {
            return vertical == o.vertical && side == o.side && Math.abs(at - o.at) < 2 * TILE;
        }

        @Override
        public String toString() {
            return (vertical ? "x" : "y") + (side > 0 ? " > " : " < ") + Math.round(at);
        }
    }

    /** Two snags a few tiles apart with the boat's middle on one exact line (x or y the same to an edge's width), both
     *  while rowing toward the same side of it: the hull rubbing along the world's invisible wall. Null otherwise. */
    WorldEdge edgeOf(Coord2d rc, double angle) {
        for (int i = 0; i < snagSpots.size(); i++) {
            Coord2d o = snagSpots.get(i);
            double a = snagAngles.get(i);
            for (boolean vertical : new boolean[] {false, true}) {
                double across = vertical ? rc.x - o.x : rc.y - o.y;
                double along = vertical ? rc.y - o.y : rc.x - o.x;
                double into = vertical ? Math.cos(angle) + Math.cos(a) : Math.sin(angle) + Math.sin(a);
                if (Math.abs(across) > EDGE_LINE || Math.abs(along) < EDGE_APART || Math.abs(into) < EDGE_INTO)
                    continue;
                int side = (into > 0) ? 1 : -1;
                double line = (vertical ? (rc.x + o.x) : (rc.y + o.y)) / 2;
                return new WorldEdge(vertical, line + side * (hull.halfBeam + EDGE_PAST), side);
            }
        }
        return null;
    }

    /** Gets the run going: the plain cursor, as River Explorer starts. Call once before hop() or sailInto(). */
    public void begin() throws InterruptedException {
        Gob player = NUtils.player();
        if (!NParser.checkName(NUtils.getCursorName(), "arw")) {
            gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 3, 0);
            NUtils.getUI().core.addTask(new GetCurs("arw"));
        }
    }

    /** River Explorer's run. */
    public Results explore() throws InterruptedException {
        return explore(null);
    }

    /** River Explorer's run, with a stopover the sailing asks about every tick (null: none - River Explorer itself). */
    public Results explore(Stopover stopover) throws InterruptedException {
        this.stopover = stopover;
        begin();
        long deadline = prop.timeLimitMin > 0 ? System.currentTimeMillis() + prop.timeLimitMin * 60_000L : Long.MAX_VALUE;
        while (true) {
            String err = aboard();
            if (err != null)
                return Results.ERROR(err);
            if (System.currentTimeMillis() > deadline) {
                gui.msg(L10n.get("river.msg_time"));
                break;
            }
            Hop h = hop(null);
            if (h == Hop.ERROR)
                return Results.ERROR(error);
            if (h == Hop.DONE) {
                gui.msg(L10n.get("river.msg_done", known, known - startKnown));
                break;
            }
        }
        return Results.SUCCESS();
    }

    /** What one pass of the exploring loop came to. AGAIN: waited or skipped something, go round again; SAILED: a leg was
     *  sailed (or tried); DONE: nothing left to reach by water; ERROR: the run can't go on (error()). */
    public enum Hop { AGAIN, SAILED, DONE, ERROR }

    /** One pass of River Explorer's loop: reads the map, chooses the target and sails one leg toward it. tune (or null) may
     *  change the planner's question before the choice - its worth (Ask.value), the grids it may not use. */
    public Hop hop(java.util.function.Consumer<RiverFrontier.Ask> tune) throws InterruptedException {
        String err = aboard();
        if (err != null)
            return fail(err);
        KnownGrids snap = KnownGrids.read(gui);
        Gob player = NUtils.player();
        Coord sessTile = player.rc.floor(MCache.tilesz);
        Coord sessGrid = sessTile.div(MCache.cmaps);
        if (overVeryDeep(player.rc)) {
            if (!outOfVeryDeep())
                return fail(L10n.get("river.err_very_deep"));
            return Hop.AGAIN;
        }
        Long pid = (snap == null) ? null : snap.ids.get(snap.playerGrid);
        if (pid == null) {
            // The minimap hasn't placed us, or this grid isn't recorded yet.
            if (noMapSince == 0) noMapSince = System.currentTimeMillis();
            if (System.currentTimeMillis() - noMapSince > NO_MAP_MS)
                return fail(L10n.get("river.err_no_map"));
            pause(1000);
            return Hop.AGAIN;
        }
        noMapSince = 0;
        Coord pg = snap.playerGrid;
        lastSnap = snap;
        if (voids == null || snap.loc.seg.id != voidsSeg) {
            voids = new Voids(NUtils.getDataFilePath("river_voids.nurgling.json"), snap.loc.seg.id);
            voidsSeg = snap.loc.seg.id;
            unloadedSince.clear();
        }
        checkVoids(player.rc);
        boolean complete = atlas.load(gui.mmap.file, snap, LOAD_MS);
        // Dams close a river for good: the grids they stand in are worked out again with them as land, so the route
        // goes round them instead of finding out at the dam each time.
        for (long id : noteDams(snap)) {
            if (id == pid) continue;
            Coord seg = snap.coords.get(id);
            GridWater g = (seg == null) ? null : atlas.live(map, seg.sub(snap.gridOffset()), dams.get(id));
            if (g != null)
                atlas.put(id, g.compact());
        }
        GridWater here = atlas.live(map, sessGrid, dams.get(pid));
        if (here == null) {
            pause(500);
            return Hop.AGAIN;
        }
        // In this grid: the client records the 3x3 block round it as soon as all nine are loaded. Give it a moment the
        // first time, so the choice below sees what entering revealed.
        if (!pid.equals(recordedPid)) {
            recordedPid = pid;
            if (!neighboursKnown(snap)) {
                waitRecorded(pid);
                return Hop.AGAIN;
            }
        }
        // Done with this grid only once the grids round it really are recorded: a grid left before they loaded stays
        // a target.
        if (neighboursKnown(snap))
            visited.add(pid);
        Coord cell = Coord.of((int) Math.round(player.rc.x / WaterCells.CELL), (int) Math.round(player.rc.y / WaterCells.CELL)).sub(sessGrid.mul(GridWater.CELLS));
        GridWater.FromCell from = here.from(cell);
        atlas.put(pid, here.compact());
        if (from == null) {
            if (++noWater >= NO_WATER_LIMIT)
                return fail(L10n.get("river.err_no_water"));
            pause(1000);
            return Hop.AGAIN;
        }
        noWater = 0;

        if (startGridId == null) {
            startGridId = pid;
            startKnown = snap.ids.size();
        }
        if (snap.ids.size() > known)
            earlyRetries = 0;
        known = snap.ids.size();

        long now = System.currentTimeMillis();
        coolingLinks.values().removeIf(until -> until <= now);
        failedLinks.values().removeIf(until -> until <= now);
        RiverFrontier.Ask ask = new RiverFrontier.Ask();
        ask.player = pg;
        ask.start = from;
        ask.visited = snap.coordsOf(visited);
        ask.excludedLinks = links(snap, failedLinks.keySet(), coolingLinks.keySet());
        ask.heading = heading;
        // Kept from the last time the start grid was in this segment, so a segment change doesn't drop the leash.
        Coord lc = snap.coords.get(startGridId);
        if (lc != null)
            leashCenter = lc;
        ask.leashCenter = leashCenter;
        ask.leashGrids = prop.leashGrids;
        ask.hold = target;
        ask.bias = bias;
        ask.openSea = !coastFound || openSeaRun < OPEN_SEA_LIMIT;
        Long targetId = (target == null) ? null : snap.ids.get(target);
        ask.claimed = snap.coordsOf(claims.exchange(targetId, now));
        Coord off = snap.gridOffset();
        ask.beyond = c -> {
            if (voids.contains(c)) return true;
            for (WorldEdge e : edges)
                if (e.beyondGrid(c.sub(off))) return true;
            return false;
        };
        if (tune != null)
            tune.accept(ask);
        RiverFrontier.Choice choice = RiverFrontier.choose(new RiverFrontier.World(snap.ids.keySet(), atlas.world(snap)), ask);
        if (choice == null) {
            if (!complete) {
                // Grids still to read: they may hold the way on.
                pause(500);
                return Hop.AGAIN;
            }
            if ((!coolingLinks.isEmpty() || !failedLinks.isEmpty()) && earlyRetries < MAX_EARLY_RETRIES) {
                // Only skipped crossings left: try them again before calling it done - for an animal, after giving it
                // time to move off.
                earlyRetries++;
                System.out.println("[RiverExplorer] nothing else left - retrying " + coolingLinks.size() + " animal-blocked and "
                        + failedLinks.size() + " failed crossing(s)");
                if (!coolingLinks.isEmpty()) {
                    gui.msg(L10n.get("river.msg_danger_retry"));
                    halt(gui);
                    pause(DANGER_RETRY_WAIT_MS);
                }
                coolingLinks.clear();
                failedLinks.clear();
                deadEnds.clear();
                return Hop.AGAIN;
            }
            return Hop.DONE;
        }
        if (choice.tier >= 0)
            coastFound = true;
        if (!choice.grid.equals(target))
            openSeaRun = (choice.tier < 0) ? openSeaRun + 1 : 0;
        if (!choice.grid.equals(target)) {
            System.out.println("[RiverExplorer] best targets: " + choice.candidates);
            target = choice.grid;
            heading = target.sub(pg);
            if (!quiet)
                gui.msg(L10n.get("river.msg_target", known, known - startKnown,
                        Math.round(choice.cost), choice.gain, choice.fresh ? L10n.get("river.by_river") : L10n.get("river.by_sea")));
        }
        System.out.println(String.format("[RiverExplorer] grid %s, %d known (+%d), target %s cost %.0f (sea %.0f) gain %d %s depth %d, %d crossing(s), atlas %s",
                pg, known, known - startKnown, target, choice.cost, choice.seaCost, choice.gain, choice.tier == 1 ? "fresh" : choice.tier == 0 ? "coast" : "open sea", choice.depth,
                choice.route.size(), complete ? "complete" : "loading"));

        // The leg: into the next grid of the route, near where the route enters it. (The boat's own grid is never a
        // target: being in it is what reveals the grids round it.)
        if (choice.route.isEmpty()) {
            visited.add(pid);
            return Hop.AGAIN;
        }
        List<Coord[]> goals = new ArrayList<>();
        String link;
        {
            RiverFrontier.Hop hop = choice.route.get(0);
            Coord nextSess = hop.grid.sub(snap.gridOffset());
            Coord gul = nextSess.mul(MCache.cmaps), gbr = gul.add(MCache.cmaps);
            Coord entry = gul.add(hop.cell.div(2));
            goals.add(new Coord[]{clamp(entry.sub(ENTRY_NEAR_TILES, ENTRY_NEAR_TILES), gul, gbr), clamp(entry.add(ENTRY_NEAR_TILES + 1, ENTRY_NEAR_TILES + 1), gul, gbr)});
            goals.add(new Coord[]{clamp(entry.sub(ENTRY_TILES, ENTRY_TILES), gul, gbr), clamp(entry.add(ENTRY_TILES + 1, ENTRY_TILES + 1), gul, gbr)});
            goals.add(new Coord[]{gul, gbr});
            Long nid = snap.ids.get(hop.grid);
            link = pid + ">" + nid;
            // Turned straight back to where the last crossing came from, with no fewer crossings to go: that crossing
            // led into a dead end (water cut off within the grid). Twice, and it is not used again.
            if (prevLink != null && prevPid != null && prevPid.equals(nid) && choice.route.size() >= prevRouteSize && known == prevKnown) {
                if (deadEnds.merge(prevLink, 1, Integer::sum) >= DEAD_END_LIMIT) {
                    failedLinks.put(prevLink, System.currentTimeMillis() + FAILED_COOLDOWN_MS);
                    System.out.println("[RiverExplorer] crossing " + prevLink + " leads into a dead end - skipping it for a while");
                    prevLink = null;
                    return Hop.AGAIN;
                }
            }
        }
        prevLink = link;
        prevPid = pid;
        prevRouteSize = choice.route.size();
        prevKnown = known;
        farRoute = farRoute(choice, snap.gridOffset());
        Leg leg = sailLeg(goals, sessGrid);
        switch (leg) {
            case ABORT:
                return fail(abort);
            case NO_AXE:
                return fail(L10n.get("river.err_no_axe"));
            case FAILED:
                failedLinks.put(link, System.currentTimeMillis() + FAILED_COOLDOWN_MS);
                System.out.println("[RiverExplorer] can't get into " + choice.route.get(0).grid + " here - trying another way");
                break;
            case DANGER:
                coolingLinks.put(link, System.currentTimeMillis() + DANGER_COOLDOWN_MS);
                gui.msg(L10n.get("river.msg_danger"));
                break;
            default:
                break;
        }
        return Hop.SAILED;
    }

    private Hop fail(String err) {
        error = err;
        return Hop.ERROR;
    }

    /** Why the run stopped (Hop.ERROR, Leg.ABORT, Leg.NO_AXE), or null. */
    public String error() {
        return error;
    }


    /** Crossings not to use, as RiverFrontier links between segment grid coords. */
    static Set<String> links(KnownGrids snap, Set<String> failed, Set<String> cooling) {
        Set<String> res = new HashSet<>();
        for (Set<String> s : Arrays.asList(failed, cooling)) {
            for (String l : s) {
                String[] ab = l.split(">");
                Coord a = snap.coords.get(Long.parseLong(ab[0])), b = snap.coords.get(Long.parseLong(ab[1]));
                if (a != null && b != null)
                    res.add(RiverFrontier.link(a, b));
            }
        }
        return res;
    }

    static Coord clamp(Coord c, Coord ul, Coord br) {
        return Coord.of(Math.max(ul.x, Math.min(br.x, c.x)), Math.max(ul.y, Math.min(br.y, c.y)));
    }

    /** Why the run can't go on (combat, off the boat), or null. */
    String aboard() {
        if (gui.fv != null && gui.fv.lsrel != null && !gui.fv.lsrel.isEmpty())
            return L10n.get("river.err_combat");
        Gob player = NUtils.player();
        if (player == null || WorldExplorer.vessel(player) == null)
            return L10n.get("river.err_no_boat");
        return null;
    }

    /** Sails into one of the goals (session tiles [ul, br), tried in order) without stopping, chopping reeds on the way.
     *  Ends when the boat is in the goal - not on crossing a grid edge on the way: a river winding along an edge crosses it
     *  back and forth, and a bit of it on the far side can be cut off within that grid, so deciding again there turned the
     *  boat back and round forever. It only ends early if the boat has left the 3x3 grids the course is planned in. */
    Leg sailLeg(List<Coord[]> goals, Coord legGrid) throws InterruptedException {
        Pilot.Course course = null;
        Coord[] goal = null;
        long planT = 0;
        int idx = 0;
        int idleStandoffs = 0;
        lastTarget = null;
        Gob player = NUtils.player();
        progressRc = player.rc;
        progressT = System.currentTimeMillis();
        while (true) {
            abort = aboard();
            if (abort != null)
                return Leg.ABORT;
            player = NUtils.player();
            Coord2d rc = player.rc;
            Coord tile = rc.floor(MCache.tilesz);
            if (goal != null && tile.x >= goal[0].x && tile.y >= goal[0].y && tile.x < goal[1].x && tile.y < goal[1].y)
                return Leg.ENTERED;
            Coord off = tile.div(MCache.cmaps).sub(legGrid);
            if (Math.abs(off.x) > 1 || Math.abs(off.y) > 1)
                return Leg.MOVED;
            long now = System.currentTimeMillis();
            for (int i = snags.size() - 1; i >= 0; i--) {
                if (now - snagDiscTimes.get(i) > SNAG_DISC_MS) {
                    snags.remove(i);
                    snagDiscTimes.remove(i);
                }
            }
            // A stopover (none for River Explorer itself): the course is planned again afterwards, from wherever the boat is.
            if (stopover != null && stopFor(tile, rc)) {
                idleStandoffs = 0;
                course = null;
                continue;
            }

            if (course == null || now - planT > REPLAN_MS) {
                course = null;
                // Goals in order, each in the small window and then the whole 3x3: a wider goal found in the small window
                // can be a bit of the next grid cut off from the way on (a lake's tip over the grid line).
                for (Coord[] g : goals) {
                    for (int pass = 0; pass < 2 && course == null; pass++)
                        course = Pilot.plan(input(rc, g, legGrid, true, pass == 1));
                    if (course != null) {
                        goal = g;
                        break;
                    }
                }
                planT = now;
                idx = 0;
                if (course == null) {
                    halt(gui);
                    if (prop.avoidDanger && Pilot.plan(input(rc, goals.get(goals.size() - 1), legGrid, false, true)) != null)
                        return Leg.DANGER;
                    return Leg.FAILED;
                }
            }
            idx = course.nearest(rc, idx, 80);
            if (rc.dist(course.pts.get(idx)) > OFF_COURSE) {
                course = null;
                continue;
            }
            show(course, idx);
            checkVoids(rc);
            int r = course.firstReed(idx);
            int last = course.pts.size() - 1;
            // Reeds are chopped as trees are: right up against them. A reed the hull touches while the way on needs reeds
            // gone is chopped at once.
            Gob against = (r >= 0) ? reedAgainstHull(player) : null;
            if (against != null) {
                halt(gui);
                waitStill();
                idleStandoffs = 0;
                Leg cut = cutReed(against);
                if (cut != null)
                    return cut;
                lastTarget = null;
                progressRc = NUtils.player().rc;
                progressT = System.currentTimeMillis();
                course = null;
                continue;
            }
            // Near the reeds the boat runs straight into them (the hull stops where it touches one); farther off it
            // sails the course up to the last point before them.
            Set<Coord> blocking = (r >= 0) ? course.blockingReeds(r, reedAt.keySet()) : Collections.emptySet();
            boolean push = r >= 0 && (rc.dist(course.pts.get(Math.max(idx, r - 1))) < PUSH_FROM || near(blocking, rc, hull.halfLength + PUSH_FROM));
            int limit = (r < 0) ? last : push ? Math.min(last, Math.max(idx, r) + PUSH_POINTS) : Math.max(idx, r - 1);
            sailTo(rc, push ? course.pts.get(limit) : aim(course, rc, idx, limit), now);

            if (rc.dist(progressRc) >= TILE) {
                progressRc = rc;
                progressT = now;
            } else if (push && lastTarget != null && now - progressT > STALL_MS) {
                // Stopped short of the reeds with none against the hull (a bank or the course and the gobs disagree).
                if (++idleStandoffs >= IDLE_STANDOFFS) {
                    halt(gui);
                    return Leg.FAILED;
                }
                lastTarget = null;
                progressRc = rc;
                progressT = now;
                course = null;
                continue;
            } else if (!push && stuck(now)) {
                if (snagged(rc, now)) {
                    halt(gui);
                    return Leg.FAILED;
                }
                course = null;
                continue;
            }
            NUtils.getUI().core.addTask(new WaitDuration(TICK_MS));
        }
    }

    /** Asks the stopover about the tile under the boat. When it is due - and no dangerous animal's disc covers the boat (the
     *  stop waits for a safer moment) - halts, waits until the boat is still and lets the stopover work. True when it stopped. */
    boolean stopFor(Coord tile, Coord2d rc) throws InterruptedException {
        MiniMap.Location loc = (gui.mmap != null) ? gui.mmap.sessloc : null;
        String name = live.name(tile);
        if (loc == null || name == null || !stopover.due(tile.add(loc.tc), name))
            return false;
        if (prop.avoidDanger) {
            for (SeaGrid.Disc d : danger.discs(gui, live, n -> true))
                if (d.contains(rc)) return false;
        }
        halt(gui);
        waitStill();
        stopover.stop(gui);
        resume(NUtils.player().rc, System.currentTimeMillis());
        return true;
    }

    /** After a stop on the way: clicks and progress start afresh, as at the start of a leg, so the time stopped isn't taken for
     *  a snag; and the last crossing no longer counts for dead ends (turning back after a stop is not a dead end). */
    void resume(Coord2d rc, long now) {
        lastTarget = null;
        progressRc = rc;
        progressT = now;
        prevLink = null;
    }

    /** Rowing (a click is out) and no tile of progress for SNAG_MS: held fast. */
    boolean stuck(long now) {
        return lastTarget != null && now - progressT > SNAG_MS;
    }

    /** One leg into the box of segment tiles [segUL, segBR), which must lie in the 3x3 grids round the boat: sailed as River
     *  Explorer sails into the next grid - reeds, danger, snags and all - and the stopover still asked on the way. */
    public Leg sailInto(Coord segUL, Coord segBR) throws InterruptedException {
        MiniMap.Location loc = (gui.mmap != null) ? gui.mmap.sessloc : null;
        Gob player = NUtils.player();
        if (loc == null || player == null)
            return Leg.FAILED;
        Coord legGrid = player.rc.floor(MCache.tilesz).div(MCache.cmaps);
        farRoute = Collections.emptyList();
        Leg leg = sailLeg(Collections.singletonList(new Coord[]{segUL.sub(loc.tc), segBR.sub(loc.tc)}), legGrid);
        prevLink = null;
        if (leg == Leg.ABORT)
            error = abort;
        else if (leg == Leg.NO_AXE)
            error = L10n.get("river.err_no_axe");
        return leg;
    }

    /** Sets the stopover sailInto() and hop() ask on the way (null: none). explore(stopover) sets it itself. */
    public void stopover(Stopover s) {
        stopover = s;
    }

    /** What sailing costs from the boat (tiles, as River Explorer counts them) to each tile of the 3x3 grids round it, over the
     *  loaded map: the hull has to fit; land, unloaded tiles and solid gobs are walls, reeds are water when they get chopped. */
    public TileCosts tileCosts() {
        Gob player = NUtils.player();
        MiniMap.Location loc = (gui.mmap != null) ? gui.mmap.sessloc : null;
        if (player == null || loc == null)
            return null;
        Coord2d rc = player.rc;
        Coord t = rc.floor(MCache.tilesz), g = t.div(MCache.cmaps);
        Pilot.Input in = input(rc, new Coord[]{t, t.add(1, 1)}, g, false, true);
        WaterCells c = WaterCells.build(in.tiles, in.ul, in.wt, in.ht, in.fresh, in.sea, in.solid, in.fit + WaterCells.CELL);
        double fit = in.fit;
        Coord s = c.cellOf(rc);
        // The boat may be wedged against a bank: the fit cells round it are where it sets off from.
        List<Integer> src = new ArrayList<>();
        for (int dy = -Pilot.START_FREE; dy <= Pilot.START_FREE; dy++)
            for (int dx = -Pilot.START_FREE; dx <= Pilot.START_FREE; dx++)
                if (c.clear(s.x + dx, s.y + dy) >= fit)
                    src.add(c.index(s.x + dx, s.y + dy));
        float[] cost = new float[in.wt * in.ht];
        Arrays.fill(cost, Float.POSITIVE_INFINITY);
        if (!src.isEmpty()) {
            int[] sources = new int[src.size()];
            double[] start = new double[src.size()];
            for (int k = 0; k < sources.length; k++) {
                sources[k] = src.get(k);
                Coord p = Coord.of(sources[k] % c.w, sources[k] / c.w);
                start[k] = Math.hypot(p.x - s.x, p.y - s.y) * CellSearch.ORTH;
            }
            CellSearch.Result r = CellSearch.run(c.w, c.h, new CellSearch.Graph() {
                public boolean passable(int i) {
                    return c.clear(i) >= fit;
                }

                public double step(int to, double len) {
                    return c.cost(to % c.w, to / c.w, len);
                }
            }, sources, start, null);
            for (int i = 0; i < r.cost.length; i++) {
                double v = r.cost[i];
                if (Double.isInfinite(v)) continue;
                int tile = ((i / c.w) >> 1) * in.wt + ((i % c.w) >> 1);
                if (v < cost[tile]) cost[tile] = (float) v;
            }
        }
        return new TileCosts(in.ul.add(loc.tc), in.wt, in.ht, cost);
    }

    /** Sailing costs over a box of segment tiles (tileCosts). */
    public static final class TileCosts {
        /** The box: segment tiles [ul, ul + (w, h)). */
        public final Coord ul;
        public final int w, h;
        private final float[] cost;

        TileCosts(Coord ul, int w, int h, float[] cost) {
            this.ul = ul;
            this.w = w;
            this.h = h;
            this.cost = cost;
        }

        /** Tiles of sailing to the segment tile, POSITIVE_INFINITY when the boat can't get there (or it is outside the box). */
        public double cost(Coord segTile) {
            int x = segTile.x - ul.x, y = segTile.y - ul.y;
            if (x < 0 || y < 0 || x >= w || y >= h) return Double.POSITIVE_INFINITY;
            return cost[y * w + x];
        }
    }

    /** The hull the run plans for. */
    public Hull hull() {
        return hull;
    }

    /** The water the boat sails, as WaterAtlas reads it: fresh water, and the sea (null when the sea isn't used). */
    public java.util.function.Predicate<String> fresh() {
        return atlas.fresh();
    }

    public java.util.function.Predicate<String> sea() {
        return atlas.sea();
    }

    /** Ends the run: stops the boat, clears the course line and drops the claim (RiverExplorer.run's finally). */
    public void close() {
        halt(gui);
        gui.activeBotCourse = null;
        claims.release();
    }

    /** The boat the player steers, or null when not aboard one or not in the steering seat. */
    public static Gob vessel(Gob player) {
        return WorldExplorer.vessel(player);
    }

    /** A snekkja or a knarr. */
    public static boolean isShip(Gob vessel) {
        return WorldExplorer.isShip(vessel);
    }

    /** Whether a reed among these tiles has its middle within d of p. */
    boolean near(Set<Coord> tiles, Coord2d p, double d) {
        for (Coord tc : tiles) {
            Gob g = reedAt.get(tc);
            if (g != null && g.rc.dist(p) <= d)
                return true;
        }
        return false;
    }

    /** A reed the boat's hull is touching (within CONTACT), the nearest if several; null if none. */
    Gob reedAgainstHull(Gob player) {
        Gob vessel = WorldExplorer.vessel(player);
        if (vessel == null) return null;
        NHitBoxD[] hull = NHitBoxD.solid(vessel);
        if (hull.length == 0) return null;
        Gob best = null;
        double bd = CONTACT;
        for (Gob reed : new HashSet<>(reedAt.values())) {
            for (NHitBoxD rb : NHitBoxD.solid(reed)) {
                for (NHitBoxD hb : hull) {
                    double d = boxDist(hb, rb);
                    if (d <= bd) {
                        bd = d;
                        best = reed;
                    }
                }
            }
        }
        return best;
    }

    /** Gap between two boxes (0 when they overlap). */
    static double boxDist(NHitBoxD a, NHitBoxD b) {
        if (a.intersects(b, true)) return 0;
        double d = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                d = Math.min(d, segDist(a.c[i], b.c[j], b.c[(j + 1) % 4]));
                d = Math.min(d, segDist(b.c[i], a.c[j], a.c[(j + 1) % 4]));
            }
        }
        return d;
    }

    static double segDist(Coord2d p, Coord2d a, Coord2d b) {
        Coord2d ab = b.sub(a);
        double l2 = ab.x * ab.x + ab.y * ab.y;
        double t = (l2 <= 0) ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / l2));
        return p.dist(a.add(ab.mul(t)));
    }

    /** Whether the grids round the player's grid are all recorded (or known not to exist). */
    boolean neighboursKnown(KnownGrids snap) {
        for (Coord d : RiverFrontier.NEIGHBOURS) {
            Coord g = snap.playerGrid.add(d);
            if (!snap.ids.containsKey(g) && (voids == null || !voids.contains(g)))
                return false;
        }
        return true;
    }

    /** Waits (the boat sails on meanwhile) until the client has recorded the grids round grid pid, at most RECORD_WAIT_MS,
     *  or the boat has left it. */
    void waitRecorded(long pid) throws InterruptedException {
        long until = System.currentTimeMillis() + RECORD_WAIT_MS;
        while (System.currentTimeMillis() < until) {
            pause(250);
            KnownGrids snap = KnownGrids.read(gui);
            if (snap == null) return;
            Long now = snap.ids.get(snap.playerGrid);
            if (now == null || now != pid || neighboursKnown(snap)) return;
        }
    }

    /** Unknown grids round the boat that stay unloaded in loading range are past the world's edge: recorded as voids. */
    void checkVoids(Coord2d rc) {
        KnownGrids snap = lastSnap;
        if (snap == null || voids == null) return;
        Coord tile = rc.floor(MCache.tilesz);
        Coord sg = tile.div(MCache.cmaps), off = snap.gridOffset();
        long now = System.currentTimeMillis();
        synchronized (map.grids) {
            if (!map.grids.containsKey(sg))
                return;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    Coord g = sg.add(dx, dy), seg = g.add(off);
                    if (snap.ids.containsKey(seg) || voids.contains(seg)) continue;
                    Coord ul = g.mul(MCache.cmaps), br = ul.add(MCache.cmaps.x - 1, MCache.cmaps.y - 1);
                    int ex = Math.max(0, Math.max(ul.x - tile.x, tile.x - br.x)), ey = Math.max(0, Math.max(ul.y - tile.y, tile.y - br.y));
                    if (Math.max(ex, ey) > VOID_REACH || map.grids.containsKey(g)) {
                        unloadedSince.remove(seg);
                        continue;
                    }
                    long since = unloadedSince.computeIfAbsent(seg, k -> now);
                    if (now - since >= VOID_MS) {
                        voids.add(seg);
                        unloadedSince.remove(seg);
                        System.out.println("[RiverExplorer] grid " + seg + " never loads - past the world's edge, not going for it again");
                    }
                }
            }
        }
    }

    /** Shows the course from point idx on, and the way on after it (farRoute), in the world and on the minimap. */
    void show(Pilot.Course course, int idx) {
        if (course == shownCourse && idx == shownIdx)
            return;
        shownCourse = course;
        shownIdx = idx;
        gui.activeBotCourse = new NGameUI.BotCourse(course.pts.subList(Math.min(idx + 1, course.pts.size()), course.pts.size()), farRoute);
    }

    /** The way on after this leg, for show(): the later crossings of the route and the target's middle, session coords. */
    static List<Coord2d> farRoute(RiverFrontier.Choice choice, Coord gridOffset) {
        List<Coord2d> res = new ArrayList<>();
        for (int i = 1; i < choice.route.size(); i++) {
            RiverFrontier.Hop h = choice.route.get(i);
            Coord tile = h.grid.sub(gridOffset).mul(MCache.cmaps).add(h.cell.div(2));
            res.add(Coord2d.of(tile).add(0.5, 0.5).mul(MCache.tilesz));
        }
        // The ring: where the boat arrives in the target grid (entering it is what reveals the grids round it).
        Coord ul = choice.grid.sub(gridOffset).mul(MCache.cmaps);
        if (choice.arrival != null)
            res.add(Coord2d.of(ul).mul(MCache.tilesz).add(Coord2d.of(choice.arrival).mul(WaterCells.CELL)));
        else
            res.add(Coord2d.of(ul.add(MCache.cmaps.div(2))).mul(MCache.tilesz));
        return res;
    }

    /** The farthest course point up to `limit` within the lookahead that the hull reaches in a straight line. */
    Coord2d aim(Pilot.Course course, Coord2d rc, int idx, int limit) {
        Coord2d best = course.pts.get(Math.min(idx + 1, limit));
        for (int k = idx; k <= limit; k++) {
            Coord2d p = course.pts.get(k);
            if (rc.dist(p) > lookahead) break;
            if (course.clearLine(rc, p))
                best = p;
        }
        return best;
    }

    /** The planner's view of the loaded map around the leg. withDanger false leaves the animals out (to tell "an animal
     *  blocks the way" from "there is no way"). */
    /** Pilot input for a leg into goal: the window is the start and goal padded by PAD_TILES, or the whole 3x3 round legGrid. */
    Pilot.Input input(Coord2d rc, Coord[] goal, Coord legGrid, boolean withDanger, boolean whole) {
        Pilot.Input in = new Pilot.Input();
        in.tiles = live;
        in.fresh = atlas.fresh();
        in.sea = atlas.sea();
        in.fit = hull.fit();
        in.start = rc;
        in.goalUL = goal[0];
        in.goalBR = goal[1];
        Coord t = rc.floor(MCache.tilesz);
        Coord lo = legGrid.sub(1, 1).mul(MCache.cmaps), hi = legGrid.add(2, 2).mul(MCache.cmaps);
        Coord ul = whole ? lo : clamp(Coord.of(Math.min(t.x, goal[0].x) - PAD_TILES, Math.min(t.y, goal[0].y) - PAD_TILES), lo, hi);
        Coord br = whole ? hi : clamp(Coord.of(Math.max(t.x + 1, goal[1].x) + PAD_TILES, Math.max(t.y + 1, goal[1].y) + PAD_TILES), lo, hi);
        in.ul = ul;
        in.wt = br.x - ul.x;
        in.ht = br.y - ul.y;
        gobTiles(rc, ul, br, in);
        in.discs = new ArrayList<>(snags);
        if (withDanger && prop.avoidDanger)
            in.discs.addAll(danger.discs(gui, live, n -> true));
        return in;
    }

    /** Tiles under solid gobs and under reeds in the window - not the player, the boat or anyone aboard. Reeds that
     *  wouldn't chop, and all reeds when chopping is off, count as solid. */
    void gobTiles(Coord2d rc, Coord ul, Coord br, Pilot.Input in) {
        reedAt.clear();
        Gob player = NUtils.player();
        Gob vessel = WorldExplorer.vessel(player);
        long vid = (vessel == null) ? -1 : vessel.id;
        KnownGrids snap = KnownGrids.read(gui);
        Coord segOff = (snap == null) ? null : snap.loc.tc;
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
                if (gob.id == player.id || gob.id == vid) continue;
                if (gob.ngob == null || gob.ngob.hitBox == null) continue;
                Coord gt = gob.rc.floor(MCache.tilesz);
                if (gt.x < ul.x - 2 || gt.y < ul.y - 2 || gt.x > br.x + 2 || gt.y > br.y + 2) continue;
                Following f = gob.getattr(Following.class);
                if (f != null && f.tgt == vid) continue;
                boolean reed = REEDS.equals(gob.ngob.name);
                for (Coord tc : covered(gob)) {
                    if (reed && prop.chopReeds && (segOff == null || !unchoppable.contains(tc.add(segOff)))) {
                        in.reeds.add(tc);
                        reedAt.put(tc, gob);
                    } else {
                        in.solid.add(tc);
                    }
                }
            }
        }
    }

    /** Records the tiles under the beaver dams in sight; returns the ids of the grids where a dam tile is new. */
    Set<Long> noteDams(KnownGrids snap) {
        Set<Long> changed = new HashSet<>();
        List<Gob> found = new ArrayList<>();
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc)
                if (gob.ngob != null && gob.ngob.name != null && gob.ngob.name.startsWith(DAM))
                    found.add(gob);
        }
        for (Gob gob : found) {
            int added = 0;
            for (Coord tc : covered(gob)) {
                Coord sg = tc.div(MCache.cmaps);
                Long id = snap.ids.get(sg.add(snap.gridOffset()));
                if (id == null) continue;
                if (dams.computeIfAbsent(id, k -> new HashSet<>()).add(tc.sub(sg.mul(MCache.cmaps)))) {
                    changed.add(id);
                    added++;
                }
            }
            if (added > 0)
                System.out.println("[RiverExplorer] beaver dam at " + gob.rc + ": " + added + " tile(s) closed");
        }
        return changed;
    }

    /** Tiles a solid gob's hitboxes cover (as WorldExplorer.solidTiles works them out). */
    static List<Coord> covered(Gob gob) {
        List<Coord> res = new ArrayList<>();
        for (NHitBoxD box : NHitBoxD.solid(gob)) {
            Coord ul = SeaGrid.tileOf(box.getCircumscribedUL());
            Coord br = SeaGrid.tileOf(box.getCircumscribedBR());
            for (int y = ul.y; y <= br.y; y++) {
                for (int x = ul.x; x <= br.x; x++) {
                    Coord tc = Coord.of(x, y);
                    Coord2d tul = tc.mul(MCache.tilesz);
                    if (box.intersects(new NHitBoxD(tul, tul.add(MCache.tilesz)), false))
                        res.add(tc);
                }
            }
        }
        return res;
    }

    /** Chops a reed the hull is against. Null to carry on (re-plan), NO_AXE to stop. */
    Leg cutReed(Gob reed) throws InterruptedException {
        int tries = chopTries.merge(reed.id, 1, Integer::sum);
        if (tries > CHOP_TRIES) {
            KnownGrids snap = KnownGrids.read(gui);
            if (snap != null) {
                for (Coord tc : covered(reed))
                    unchoppable.add(tc.add(snap.loc.tc));
            }
            gui.msg(L10n.get("river.msg_unchoppable"));
            return null;
        }
        if (!new Equip(axes()).run(gui).IsSuccess())
            return Leg.NO_AXE;
        drinkIfLow();
        // Run into it: the hull stops where it touches the reed, close enough to chop it whatever the boat's angle.
        gui.map.wdgmsg("click", Coord.z, reed.rc.floor(posres), 1, 0);
        waitStill();
        System.out.println("[RiverExplorer] chopping reeds at " + reed.rc + " (try " + tries + ")");
        // On reeds the menu option is "Clear" (not "Chop", as on trees).
        if (!new SelectFlowerAction("Clear", reed).run(gui).IsSuccess())
            return null;
        NUtils.getUI().core.addTask(new ChopWait(gui, reed.id));
        dropReeds();
        return null;
    }

    /** Drops every Reeds item in the inventory: clearing puts them there, and over a long river they fill it. */
    void dropReeds() throws InterruptedException {
        for (int pass = 0; pass < DROP_PASSES; pass++) {
            // The last reeds can arrive a moment after the clearing ends.
            NUtils.getUI().core.addTask(new WaitDuration(DROP_SETTLE_MS));
            ArrayList<WItem> items = gui.getInventory().getItems(REED_ITEM);
            if (items.isEmpty())
                return;
            for (WItem item : items) {
                // The server ignores drops sent faster than the shared drop budget allows.
                while (!NUtils.dropSlotReady())
                    NUtils.getUI().core.addTask(new WaitDuration(50));
                NUtils.drop(item);
            }
        }
    }

    static final NAlias REED_ITEM = new NAlias("Reeds");
    static final int DROP_PASSES = 3;
    static final long DROP_SETTLE_MS = 300;

    NAlias axes() {
        if (prop.axe != null)
            return new NAlias(prop.axe);
        List<String> names = new ArrayList<>();
        for (UsingTools.Tool t : UsingTools.Tools.axes)
            names.add(t.name);
        return new NAlias(names.toArray(new String[0]));
    }

    /** With QoL Auto-drink on, drinks back up once stamina is low (the Map Explorer walker's checkStamina). */
    void drinkIfLow() throws InterruptedException {
        if (!(Boolean) NConfig.get(NConfig.Key.autoDrink)) return;
        double stamina = NUtils.getStamina();
        if (stamina < 0 || stamina >= DRINK_BELOW) return;
        if (gui.drinkMeter != null && gui.drinkMeter.getTotalDrinkable() <= 0) return;
        if (System.currentTimeMillis() - lastFailedDrinkMs < DRINK_RETRY_MS) return;
        if (!new Drink(DRINK_TARGET, false).run(gui).IsSuccess())
            lastFailedDrinkMs = System.currentTimeMillis();
    }

    void sailTo(Coord2d rc, Coord2d to, long now) {
        if (intoVeryDeep(rc, to)) {
            System.out.println("[RiverExplorer] the way to " + to + " runs into very deep water - not going");
            if (lastTarget != null) {
                halt(gui);
                lastTarget = null;
            }
            return;
        }
        double angle = rc.angle(to);
        boolean click = lastTarget == null
                || angleDiff(angle, lastAngle) >= TURN_STEP
                || rc.dist(lastTarget) < lookahead / 2
                || lastTarget.dist(to) > TILE
                || now - lastClickT > RECLICK_MS;
        if (!click) return;
        if (lastTarget == null)
            progressT = now;
        gui.map.wdgmsg("click", Coord.z, to.floor(posres), 1, 0);
        lastTarget = to;
        lastAngle = angle;
        lastClickT = now;
    }

    /** Whether any of the hull round rc (its middle and 8 points at its half-beam) is over very deep ocean. */
    boolean overVeryDeep(Coord2d rc) {
        for (int k = -1; k < 8; k++) {
            Coord2d q = (k < 0) ? rc : rc.add(Coord2d.sc(k * Math.PI / 4, hull.halfBeam + 1));
            if (VERY_DEEP.equals(live.name(q.floor(MCache.tilesz))))
                return true;
        }
        return false;
    }

    /** The boat got over very deep ocean: rows straight for the nearest water that isn't, a little past its edge, and
     *  waits until the hull is clear. False when there is none in reach or the boat doesn't get out. */
    boolean outOfVeryDeep() throws InterruptedException {
        for (int attempt = 0; attempt < VERY_DEEP_TRIES; attempt++) {
            Coord2d rc = NUtils.player().rc;
            Coord start = rc.floor(MCache.tilesz);
            Coord out = null;
            Map<Coord, Boolean> seen = new HashMap<>();
            ArrayDeque<Coord> queue = new ArrayDeque<>();
            queue.add(start);
            seen.put(start, true);
            while (!queue.isEmpty() && out == null) {
                Coord c = queue.poll();
                for (Coord o : Coord.uecw) {
                    Coord n = c.add(o);
                    if (seen.containsKey(n) || Math.abs(n.x - start.x) > VERY_DEEP_REACH || Math.abs(n.y - start.y) > VERY_DEEP_REACH)
                        continue;
                    seen.put(n, true);
                    String name = live.name(n);
                    if (name == null)
                        continue;
                    if (sailable(name)) {
                        out = n;
                        break;
                    }
                    if (VERY_DEEP.equals(name))
                        queue.add(n);
                }
            }
            if (out == null) {
                System.out.println("[RiverExplorer] over very deep water with no way out in reach");
                return false;
            }
            Coord2d mid = SeaGrid.centreOf(out);
            Coord2d to = mid.add(mid.sub(rc).norm(hull.halfLength + 2 * TILE));
            System.out.println("[RiverExplorer] over very deep water at " + rc + " - getting out toward " + to);
            gui.msg(L10n.get("river.msg_very_deep"));
            gui.map.wdgmsg("click", Coord.z, to.floor(posres), 1, 0);
            lastTarget = null;
            for (int k = 0; k < 40; k++) {
                NUtils.getUI().core.addTask(new WaitDuration(250));
                if (!overVeryDeep(NUtils.player().rc)) {
                    halt(gui);
                    return true;
                }
            }
        }
        return !overVeryDeep(NUtils.player().rc);
    }

    /** Whether the boat's hull, rowing from rc straight to to, would get over very deep ocean. Very deep water the boat is
     *  already over (it drifted in) doesn't count until it is out: the way out is always allowed. */
    boolean intoVeryDeep(Coord2d rc, Coord2d to) {
        double len = rc.dist(to);
        Coord2d side = (len < 0.01) ? Coord2d.z : to.sub(rc).norm(hull.halfBeam + 1).rot(Math.PI / 2);
        boolean leaving = true;
        for (double d = 0; ; d += TILE / 2) {
            boolean end = d >= len;
            Coord2d c = end ? to : rc.add(to.sub(rc).mul(d / len));
            boolean deep = false;
            for (Coord2d q : new Coord2d[] {c, c.add(side), c.sub(side)}) {
                if (VERY_DEEP.equals(live.name(q.floor(MCache.tilesz)))) {
                    deep = true;
                    break;
                }
            }
            if (!deep)
                leaving = false;
            else if (!leaving)
                return true;
            if (end)
                return deep;
        }
    }

    /** Marks the spot ahead as a snag and backs off; true when the boat keeps snagging in one place. */
    boolean snagged(Coord2d rc, long now) throws InterruptedException {
        int near = 0;
        for (int i = snagTimes.size() - 1; i >= 0; i--) {
            if (now - snagTimes.get(i) > SNAG_WINDOW_MS) {
                snagTimes.remove(i);
                snagSpots.remove(i);
                snagAngles.remove(i);
            } else if (snagSpots.get(i).dist(rc) < SNAG_SPREAD) {
                near++;
            }
        }
        WorldEdge edge = edgeOf(rc, lastAngle);
        if (edge != null && edges.stream().noneMatch(edge::same)) {
            edges.add(edge);
            System.out.println("[RiverExplorer] the world ends here (" + edge + ") - nothing past it is explored");
        }
        snagTimes.add(now);
        snagSpots.add(rc);
        snagAngles.add(lastAngle);
        System.out.println("[RiverExplorer] snagged at " + rc + ", backing off");
        snags.add(new SeaGrid.Disc(rc.add(Coord2d.sc(lastAngle, hull.halfLength + TILE / 2)), SNAG_DISC));
        snagDiscTimes.add(now);
        while (snags.size() > MAX_SNAG_DISCS) {
            snags.remove(0);
            snagDiscTimes.remove(0);
        }
        if (near + 1 >= SNAG_LIMIT)
            return true;
        gui.map.wdgmsg("click", Coord.z, rc.add(Coord2d.sc(lastAngle + Math.PI, 2 * TILE)).floor(posres), 1, 0);
        NUtils.getUI().core.addTask(new WaitDuration(BACK_OFF_MS));
        lastTarget = null;
        progressRc = NUtils.player() != null ? NUtils.player().rc : rc;
        progressT = System.currentTimeMillis();
        return false;
    }

    /** Waits until the boat has stopped (at least ~0.5 s, at most 3 s). */
    void waitStill() throws InterruptedException {
        Coord2d prev = NUtils.player().rc;
        for (int k = 0; k < 20; k++) {
            NUtils.getUI().core.addTask(new WaitDuration(150));
            Gob p = NUtils.player();
            if (p == null) return;
            // Not before a click has had time to set the boat going.
            if (k >= 2 && p.rc.dist(prev) < 0.1) return;
            prev = p.rc;
        }
    }

    void pause(long ms) throws InterruptedException {
        NUtils.getUI().core.addTask(new WaitDuration(ms));
    }

    static double angleDiff(double a, double b) {
        double d = (a - b) % (2 * Math.PI);
        if (d < -Math.PI) d += 2 * Math.PI;
        if (d > Math.PI) d -= 2 * Math.PI;
        return Math.abs(d);
    }

    /** Waits for a chop to end: the reed gone, the chop stopped with it still there, or nothing started. Its own deadlines - a
     *  finite NTask times out by killing the bot. */
    private static final class ChopWait extends NTask {
        static final long NEVER_STARTED_MS = 8000, STOPPED_MS = 1500, MAX_MS = 180_000;
        final NGameUI gui;
        final long id;
        final long t0 = System.currentTimeMillis();
        boolean started = false;
        long idleSince = 0;

        ChopWait(NGameUI gui, long id) {
            this.gui = gui;
            this.id = id;
        }

        @Override
        public boolean check() {
            if (Finder.findGob(id) == null) return true;
            long now = System.currentTimeMillis();
            Gob player = NUtils.player();
            String pose = (player == null) ? null : player.pose();
            boolean busy = gui.prog != null || (pose != null && pose.contains("chop"));
            if (busy) {
                started = true;
                idleSince = 0;
            } else if (started) {
                if (idleSince == 0) idleSince = now;
                if (now - idleSince > STOPPED_MS) return true;
            } else if (now - t0 > NEVER_STARTED_MS) {
                return true;
            }
            return now - t0 > MAX_MS;
        }
    }

    /** Stops the boat where it is. */
    public static void halt(NGameUI gui) {
        Gob player = NUtils.player();
        if (player != null && gui.map != null)
            gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 1, 0);
    }
}
