package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.Action;
import nurgling.actions.Drink;
import nurgling.actions.Equip;
import nurgling.actions.Results;
import nurgling.actions.SelectFlowerAction;
import nurgling.actions.bots.riverexplorer.Claims;
import nurgling.actions.bots.riverexplorer.GridWater;
import nurgling.actions.bots.riverexplorer.Hull;
import nurgling.actions.bots.riverexplorer.Pilot;
import nurgling.actions.bots.riverexplorer.KnownGrids;
import nurgling.actions.bots.riverexplorer.WaterAtlas;
import nurgling.actions.bots.riverexplorer.WaterCells;
import nurgling.actions.bots.riverexplorer.RiverFrontier;
import nurgling.actions.bots.riverexplorer.Voids;
import nurgling.actions.bots.worldexplorer.SeaDanger;
import nurgling.actions.bots.worldexplorer.SeaGrid;
import nurgling.conf.NRiverExplorerProp;
import nurgling.i18n.L10n;
import nurgling.pf.NHitBoxD;
import nurgling.tasks.GetCurs;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitCheckable;
import nurgling.tasks.WaitDuration;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.bots.UsingTools;

import java.util.*;

import static haven.OCache.posres;

/** River Explorer (bot id "riverexplorer"): reveals the map by boat, going up rivers. It picks targets the way Map Explorer does
 *  (the known grid that reveals the most unknown ones for the least travel) but by water - fresh water first, upstream preferred,
 *  then the coast (sea with shallows in it) to find the next river mouth; open sea is crossed, never explored, and very deep
 *  ocean (odeeper) is never entered (RiverFrontier over WaterAtlas). At sea the course keeps to the deep side of the
 *  shallows' edge. Bots running at once spread out (Claims, and the head setting). Each leg into the next grid is planned down the
 *  middle of the river on the live map (Pilot) and sailed without stopping, the way Ocean Explorer steers; reeds in the way
 *  are chopped from the boat. Ocean Explorer (WorldExplorer) is left as it is: only its vessel helpers are used. */
public class RiverExplorer implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        nurgling.widgets.bots.RiverExplorerWnd w = null;
        NRiverExplorerProp prop = null;
        try {
            NUtils.getUI().core.addTask(new WaitCheckable(NUtils.getGameUI().add((w = new nurgling.widgets.bots.RiverExplorerWnd()), UI.scale(200, 200))));
            prop = w.prop;
        } finally {
            if (w != null)
                w.destroy();
        }
        if (prop == null)
            return Results.ERROR("No config");

        Gob player = NUtils.player();
        Gob vessel = (player == null) ? null : WorldExplorer.vessel(player);
        if (vessel == null)
            return Results.ERROR(L10n.get("river.err_no_boat"));
        if (gui.mmap == null || gui.mmap.file == null)
            return Results.ERROR(L10n.get("river.err_no_map"));
        boolean ship = WorldExplorer.isShip(vessel);
        if (ship)
            gui.msg(L10n.get("river.msg_ship"));

        Voyage voyage = new Voyage(gui, prop, Hull.of(vessel, ship));
        try {
            return voyage.explore();
        } finally {
            halt(gui);
            gui.activeBotCourse = null;
            voyage.claims.release();
        }
    }

    /** Fresh water: rivers and lakes. Swamp, fen and bog water is out of scope and counts as land, for every boat. */
    static boolean freshWater(String n) {
        return n.startsWith("gfx/tiles/water") || n.equals("gfx/tiles/deep");
    }

    /** Sea the boat may sail: shallow and deep ocean. Very deep ocean (odeeper) drowns characters - never entered, it is land
     *  to every part of this bot. */
    static boolean seaWater(String n) {
        return n.startsWith("gfx/tiles/owater") || n.equals("gfx/tiles/odeep");
    }

    static final String VERY_DEEP = "gfx/tiles/odeeper";

    /** Any water this bot sails (no swamp, no very deep ocean). */
    static boolean sailable(String n) {
        return freshWater(n) || seaWater(n);
    }

    static final String REEDS = "gfx/terobjs/bushes/reeds";
    /** Beaver dams (and their doors): they block the river and can't be cleared - the boat goes round. */
    static final String DAM = "gfx/terobjs/beaverdam";

    /** One run's state - the bot menu reuses the Action instance, so nothing lives in RiverExplorer's fields. */
    private static final class Voyage {
        static final double TILE = MCache.tilesz.x;
        static final long TICK_MS = 250;
        static final double LOOKAHEAD = 100, LOOKAHEAD_SHIP = 150;
        // Recorded grids are read for at most this long per re-plan; the rest next time.
        static final long LOAD_MS = 400;
        // The course is planned again this often (new gobs, animals moving), or when the boat is this far off it.
        static final long REPLAN_MS = 3000;
        static final double OFF_COURSE = 3 * TILE;
        // Planning window: the leg's start and goal, padded by this many tiles, within the 3x3 grids around the boat.
        static final int PAD_TILES = 40;
        // How near the hop's entry the leg aims (tiles): first close in, where the water is surely the part the route goes on in
        // (a wider box can take in a bit of river cut off within the grid, which only leads back), then wider, then the whole
        // next grid.
        static final int ENTRY_NEAR_TILES = 2, ENTRY_TILES = 6;
        // A crossing after which the route turned straight back this many times is a dead end: not used again.
        static final int DEAD_END_LIMIT = 2;
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
        // Times in a row an animal may keep the boat from the middle of its own grid before that grid is given up.
        static final int OWN_GRID_DANGER_LIMIT = 3;
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
        final Claims claims = new Claims(NUtils.getDataFilePath("river_claims.nurgling.json"));
        final Coord bias;

        // Run memory, by grid id (a segment merge mid-run keeps it).
        final Set<Long> visited = new HashSet<>();
        final Set<String> failedLinks = new HashSet<>();
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
        int prevRouteSize = 0;
        final Map<String, Integer> deadEnds = new HashMap<>();
        Long startGridId = null;
        Coord leashCenter = null;
        int noWater = 0, ownGridDanger = 0;
        int startKnown = -1;
        int earlyRetries = 0;
        String abort = null;
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
        int openSeaRun = 0;
        // What show() last published, and the way on after the current leg.
        Pilot.Course shownCourse = null;
        int shownIdx = -1;
        List<Coord2d> farRoute = Collections.emptyList();

        // Filled by input(): reed gob per tile it covers, for the cutter.
        final Map<Coord, Gob> reedAt = new HashMap<>();
        // Beaver dams seen this run: grid id -> grid-local tiles under them.
        final Map<Long, Set<Coord>> dams = new HashMap<>();

        Voyage(NGameUI gui, NRiverExplorerProp prop, Hull hull) {
            this.gui = gui;
            this.prop = prop;
            this.hull = hull;
            this.bias = headOf(prop.head);
            java.util.function.Predicate<String> sea = prop.useSea ? RiverExplorer::seaWater : null;
            this.atlas = new WaterAtlas(RiverExplorer::freshWater, sea, hull.fit(), prop.useSea ? "s" : "f");
            this.danger = new SeaDanger();
            this.lookahead = hull.ship ? LOOKAHEAD_SHIP : LOOKAHEAD;
            this.map = gui.map.glob.map;
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

        enum Leg { ENTERED, MOVED, FAILED, DANGER, ABORT, NO_AXE }

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

        Results explore() throws InterruptedException {
            Gob player = NUtils.player();
            if (!NParser.checkName(NUtils.getCursorName(), "arw")) {
                gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 3, 0);
                NUtils.getUI().core.addTask(new GetCurs("arw"));
            }
            long deadline = prop.timeLimitMin > 0 ? System.currentTimeMillis() + prop.timeLimitMin * 60_000L : Long.MAX_VALUE;
            int known = 0;
            long noMapSince = 0;
            while (true) {
                String err = aboard();
                if (err != null)
                    return Results.ERROR(err);
                if (System.currentTimeMillis() > deadline) {
                    gui.msg(L10n.get("river.msg_time"));
                    break;
                }
                KnownGrids snap = KnownGrids.read(gui);
                player = NUtils.player();
                Coord sessTile = player.rc.floor(MCache.tilesz);
                Coord sessGrid = sessTile.div(MCache.cmaps);
                if (overVeryDeep(player.rc)) {
                    if (!outOfVeryDeep())
                        return Results.ERROR(L10n.get("river.err_very_deep"));
                    continue;
                }
                Long pid = (snap == null) ? null : snap.ids.get(snap.playerGrid);
                if (pid == null) {
                    // The minimap hasn't placed us, or this grid isn't recorded yet.
                    if (noMapSince == 0) noMapSince = System.currentTimeMillis();
                    if (System.currentTimeMillis() - noMapSince > NO_MAP_MS)
                        return Results.ERROR(L10n.get("river.err_no_map"));
                    pause(1000);
                    continue;
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
                    continue;
                }
                Coord local = sessTile.sub(sessGrid.mul(MCache.cmaps));
                if (local.x >= GridWater.INNER_LO / 2 && local.x < GridWater.INNER_HI / 2
                        && local.y >= GridWater.INNER_LO / 2 && local.y < GridWater.INNER_HI / 2)
                    visited.add(pid);
                Coord cell = Coord.of((int) Math.round(player.rc.x / WaterCells.CELL), (int) Math.round(player.rc.y / WaterCells.CELL)).sub(sessGrid.mul(GridWater.CELLS));
                GridWater.FromCell from = here.from(cell);
                atlas.put(pid, here.compact());
                if (from == null) {
                    if (++noWater >= NO_WATER_LIMIT)
                        return Results.ERROR(L10n.get("river.err_no_water"));
                    pause(1000);
                    continue;
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
                RiverFrontier.Ask ask = new RiverFrontier.Ask();
                ask.player = pg;
                ask.start = from;
                ask.visited = snap.coordsOf(visited);
                ask.excludedLinks = links(snap, failedLinks, coolingLinks.keySet());
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
                RiverFrontier.Choice choice = RiverFrontier.choose(new RiverFrontier.World(snap.ids.keySet(), atlas.world(snap)), ask);
                if (choice == null) {
                    if (!complete) {
                        // Grids still to read: they may hold the way on.
                        pause(500);
                        continue;
                    }
                    if (!coolingLinks.isEmpty() && earlyRetries < MAX_EARLY_RETRIES) {
                        // Give the animal time to move off before trying its way again.
                        earlyRetries++;
                        gui.msg(L10n.get("river.msg_danger_retry"));
                        halt(gui);
                        pause(DANGER_RETRY_WAIT_MS);
                        coolingLinks.clear();
                        continue;
                    }
                    gui.msg(L10n.get("river.msg_done", known, known - startKnown));
                    break;
                }
                if (choice.tier >= 0)
                    coastFound = true;
                if (!choice.grid.equals(target))
                    openSeaRun = (choice.tier < 0) ? openSeaRun + 1 : 0;
                if (!choice.grid.equals(target)) {
                    System.out.println("[RiverExplorer] best targets: " + choice.candidates);
                    target = choice.grid;
                    heading = target.sub(pg);
                    gui.msg(L10n.get("river.msg_target", known, known - startKnown,
                            Math.round(choice.cost), choice.gain, choice.fresh ? L10n.get("river.by_river") : L10n.get("river.by_sea")));
                }
                System.out.println(String.format("[RiverExplorer] grid %s, %d known (+%d), target %s cost %.0f (sea %.0f) gain %d %s depth %d, %d crossing(s), atlas %s",
                        pg, known, known - startKnown, target, choice.cost, choice.seaCost, choice.gain, choice.tier == 1 ? "fresh" : choice.tier == 0 ? "coast" : "open sea", choice.depth,
                        choice.route.size(), complete ? "complete" : "loading"));

                // The leg: into the inner square of this grid, or into the next grid of the route near where the route enters it.
                List<Coord[]> goals = new ArrayList<>();
                String link = null;
                if (choice.route.isEmpty()) {
                    Coord ul = sessGrid.mul(MCache.cmaps);
                    goals.add(new Coord[]{ul.add(GridWater.INNER_LO / 2, GridWater.INNER_LO / 2), ul.add(GridWater.INNER_HI / 2, GridWater.INNER_HI / 2)});
                } else {
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
                    if (prevLink != null && prevPid != null && prevPid.equals(nid) && choice.route.size() >= prevRouteSize) {
                        if (deadEnds.merge(prevLink, 1, Integer::sum) >= DEAD_END_LIMIT) {
                            failedLinks.add(prevLink);
                            System.out.println("[RiverExplorer] crossing " + prevLink + " leads into a dead end - not using it again");
                            prevLink = null;
                            continue;
                        }
                    }
                }
                prevLink = link;
                prevPid = pid;
                prevRouteSize = choice.route.size();
                farRoute = farRoute(choice, snap.gridOffset());
                Leg leg = sailLeg(goals, sessGrid);
                if (leg != Leg.DANGER || link != null)
                    ownGridDanger = 0;
                switch (leg) {
                    case ABORT:
                        return Results.ERROR(abort);
                    case NO_AXE:
                        return Results.ERROR(L10n.get("river.err_no_axe"));
                    case FAILED:
                        if (link == null) {
                            // The inner square can't be reached as things are: don't aim for it again.
                            visited.add(pid);
                            System.out.println("[RiverExplorer] can't reach the middle of grid " + pg + " - skipping it");
                        } else {
                            failedLinks.add(link);
                            System.out.println("[RiverExplorer] can't get into " + choice.route.get(0).grid + " here - trying another way");
                        }
                        break;
                    case DANGER:
                        if (link == null) {
                            if (++ownGridDanger >= OWN_GRID_DANGER_LIMIT) {
                                ownGridDanger = 0;
                                visited.add(pid);
                                System.out.println("[RiverExplorer] an animal keeps the boat from the middle of grid " + pg + " - skipping it");
                            } else {
                                pause(5000);
                            }
                        } else {
                            coolingLinks.put(link, System.currentTimeMillis() + DANGER_COOLDOWN_MS);
                            gui.msg(L10n.get("river.msg_danger"));
                        }
                        break;
                    default:
                        break;
                }
            }
            return Results.SUCCESS();
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

                if (course == null || now - planT > REPLAN_MS) {
                    course = null;
                    for (Coord[] g : goals) {
                        course = Pilot.plan(input(rc, g, legGrid, true));
                        if (course != null) {
                            goal = g;
                            break;
                        }
                    }
                    planT = now;
                    idx = 0;
                    if (course == null) {
                        halt(gui);
                        if (prop.avoidDanger && Pilot.plan(input(rc, goals.get(goals.size() - 1), legGrid, false)) != null)
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
                } else if (!push && lastTarget != null && now - progressT > SNAG_MS) {
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
            // The ring: where the boat reaches the target's inner square (the map records the grids round it from there).
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
        Pilot.Input input(Coord2d rc, Coord[] goal, Coord legGrid, boolean withDanger) {
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
            Coord ul = clamp(Coord.of(Math.min(t.x, goal[0].x) - PAD_TILES, Math.min(t.y, goal[0].y) - PAD_TILES), lo, hi);
            Coord br = clamp(Coord.of(Math.max(t.x + 1, goal[1].x) + PAD_TILES, Math.max(t.y + 1, goal[1].y) + PAD_TILES), lo, hi);
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
            return null;
        }

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
    private static void halt(NGameUI gui) {
        Gob player = NUtils.player();
        if (player != null && gui.map != null)
            gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 1, 0);
    }
}
