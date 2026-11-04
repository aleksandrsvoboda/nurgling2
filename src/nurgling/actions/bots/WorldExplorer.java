package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.actions.bots.worldexplorer.Helm;
import nurgling.actions.bots.worldexplorer.SeaDanger;
import nurgling.actions.bots.worldexplorer.SeaGrid;
import nurgling.actions.bots.worldexplorer.SeaSearch;
import nurgling.conf.NWorldExplorerProp;
import nurgling.i18n.L10n;
import nurgling.pf.NHitBoxD;
import nurgling.tasks.GetCurs;
import nurgling.tasks.WaitCheckable;
import nurgling.tasks.WaitDuration;
import nurgling.tools.NParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static haven.OCache.posres;

/** Ocean Explorer (bot id "worldexplorer"): sails a boat along the shoreline or the deeper-water edge of the ocean, revealing
 *  the map. It never stops the boat to step: every tick it sweeps rays from the followed side (Helm), clicks well ahead along
 *  the first clear one and re-aims while the boat is still moving. Walls are grown by a clearance sized to the hull (SeaGrid),
 *  solid gobs and dangerous animals in the water are steered around, snags and islands it has sailed round are remembered. */
public class WorldExplorer implements Action {
    private static final double TILE = MCache.tilesz.x;
    private static final long TICK_MS = 250;
    // Lookahead: how far along a ray must be clear, and how far ahead the click lands.
    private static final double LOOKAHEAD = 100;
    private static final double LOOKAHEAD_SHIP = 150;
    private static final int MIN_CLEARANCE = 2;
    // Open water: how far to look for an edge to head for, and how often.
    private static final int SEEK_RADIUS = 60;
    private static final long SEEK_EVERY_MS = 2000;
    // A click is re-sent when the course turns, the boat nears the last target, or this long has passed.
    private static final long RECLICK_MS = 1500;
    // Snag: moved less than a tile in this long while sailing. Three snags close together in the window = stuck for good.
    private static final long SNAG_MS = 4000;
    private static final double SNAG_DISC = 1.5 * TILE;
    private static final int SNAG_LIMIT = 3;
    private static final long SNAG_WINDOW_MS = 30000;
    private static final double SNAG_SPREAD = 3 * TILE;
    private static final long BACK_OFF_MS = 1500;
    // Boxed in for this many ticks in a row = give up.
    private static final int BOXED_LIMIT = 20;
    // Loops: the trail is sampled this often; a loop closes passing within LOOP_CLOSE of a trail point on about the same heading.
    private static final double TRAIL_STEP = 2 * TILE;
    private static final double LOOP_MIN = 40 * TILE;
    private static final double ISLAND_MAX_LOOP = 400 * TILE;
    private static final double LOOP_CLOSE = 4 * TILE;
    private static final double HEADING_MATCH = Math.PI / 3;
    private static final int ISLAND_MAX_TILES = 20000;
    // One lap: away from the start by LAP_AWAY, then back within LAP_CLOSE of it.
    private static final double LAP_AWAY = 20 * TILE;
    private static final double LAP_CLOSE = 6 * TILE;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        nurgling.widgets.bots.WorldExplorerWnd w = null;
        NWorldExplorerProp prop = null;
        try {
            NUtils.getUI().core.addTask(new WaitCheckable(NUtils.getGameUI().add((w = new nurgling.widgets.bots.WorldExplorerWnd()), UI.scale(200, 200))));
            prop = w.prop;
        } finally {
            if (w != null)
                w.destroy();
        }
        if (prop == null)
            return Results.ERROR("No config");

        Gob player = NUtils.player();
        if (player == null)
            return Results.ERROR(L10n.get("explorer.err_no_boat"));
        Gob vessel = vessel(player);
        if (vessel == null)
            return Results.ERROR(L10n.get("explorer.err_no_boat"));

        Voyage voyage = new Voyage(gui, prop, vessel);
        try {
            return voyage.sail();
        } finally {
            halt(gui);
        }
    }

    /** One run's state - the bot menu reuses the Action instance, so nothing lives in WorldExplorer's fields. */
    private static final class Voyage {
        final NGameUI gui;
        final boolean avoidDanger, stopAfterLap;
        final Predicate<String> band;
        final SeaGrid.Tiles tiles;
        final int clearance;
        final double lookahead;
        final int radius;
        final Helm helm;
        final SeaDanger danger;

        final List<SeaGrid.Disc> snags = new ArrayList<>();
        // Recent snags (within SNAG_WINDOW_MS): where and when.
        final List<Coord2d> snagSpots = new ArrayList<>();
        final List<Long> snagTimes = new ArrayList<>();
        final Set<Coord> ignored = new HashSet<>();
        List<SeaGrid.Disc> dangerDiscs = new ArrayList<>();

        Coord2d lastTarget = null;
        double lastAngle = 0;
        long lastClickT = 0;
        Coord2d progressRc;
        long progressT;
        int boxed = 0;

        Coord seek = null;
        long seekT = 0;

        // Loops and laps
        final List<TrailPt> trail = new ArrayList<>();
        double traveled = 0, trailMark = 0;
        Coord2d prevRc;
        Coord2d lapStart = null;
        double lapHeading = 0, maxAway = 0;

        Voyage(NGameUI gui, NWorldExplorerProp prop, Gob vessel) {
            this.gui = gui;
            this.avoidDanger = prop.avoidDanger;
            this.stopAfterLap = prop.stopAfterLap;
            this.band = prop.deeper ? WorldExplorer::deeperBand : WorldExplorer::shoreBand;
            MCache map = gui.map.glob.map;
            this.tiles = tc -> {
                try {
                    return map.tilesetname(map.gettile(tc));
                } catch (Loading l) {
                    return null;
                }
            };
            this.clearance = clearance(vessel);
            this.lookahead = isShip(vessel) ? LOOKAHEAD_SHIP : LOOKAHEAD;
            this.radius = (int) Math.ceil(lookahead / TILE) + clearance + 2;
            this.helm = new Helm(Helm.edgeOnRight(prop.clockwise, prop.deeper), 0);
            this.danger = new SeaDanger();
        }

        Results sail() throws InterruptedException {
            Gob player = NUtils.player();
            if (!NParser.checkName(NUtils.getCursorName(), "arw")) {
                gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 3, 0);
                NUtils.getUI().core.addTask(new GetCurs("arw"));
            }
            Coord2d rc = player.rc;
            progressRc = prevRc = rc;
            progressT = System.currentTimeMillis();

            while (true) {
                if (gui.fv != null && gui.fv.lsrel != null && !gui.fv.lsrel.isEmpty())
                    return Results.ERROR(L10n.get("explorer.err_combat"));
                player = NUtils.player();
                if (player == null || vessel(player) == null)
                    return Results.ERROR(L10n.get("explorer.err_no_boat"));
                rc = player.rc;
                long now = System.currentTimeMillis();

                if (rc.dist(progressRc) >= TILE) {
                    progressRc = rc;
                    progressT = now;
                } else if (lastTarget != null && now - progressT > SNAG_MS) {
                    if (snagged(rc, now))
                        return Results.ERROR(L10n.get("explorer.err_stuck"));
                    continue;
                }

                if (loops(rc))
                    return Results.SUCCESS();

                List<SeaGrid.Disc> discs = new ArrayList<>(snags);
                dangerDiscs = avoidDanger ? danger.discs(gui, tiles, WorldExplorer::sailable) : new ArrayList<>();
                discs.addAll(dangerDiscs);
                SeaGrid g = SeaGrid.build(tiles, band, WorldExplorer::sailable, SeaGrid.tileOf(rc), radius,
                        solidTiles(player, rc), ignored, discs, clearance);

                Coord2d target = steer(g, rc, now);
                if (target == null) {
                    if (++boxed > BOXED_LIMIT)
                        return Results.ERROR(L10n.get("explorer.err_boxed"));
                    if (lastTarget != null) {
                        halt(gui);
                        lastTarget = null;
                    }
                } else {
                    boxed = 0;
                    sailTo(rc, target, now);
                }
                NUtils.getUI().core.addTask(new WaitDuration(TICK_MS));
            }
        }

        /** Where to click next, or null when boxed in with no way out. */
        Coord2d steer(SeaGrid g, Coord2d rc, long now) {
            double[] lens = {lookahead, lookahead / 2, lookahead / 4};
            for (double len : lens) {
                double from = g.rayStart(rc, clearance * TILE, len);
                Double a = helm.steer(g, rc, from, len, len == lookahead, () -> seekEdge(rc, now));
                if (a == null) continue;
                if (helm.following) {
                    seek = null;
                    if (lapStart == null) {
                        lapStart = rc;
                        lapHeading = a;
                    }
                }
                return rc.add(Coord2d.sc(a, len));
            }
            // Boxed in: run from an animal we are inside the ring of, else take the shortest way back to free water.
            for (SeaGrid.Disc d : dangerDiscs) {
                if (d.contains(rc)) {
                    Coord2d away = rc.sub(d.c);
                    if (away.abs() < 1) away = Coord2d.sc(helm.course + Math.PI, 1);
                    return rc.add(away.norm(4 * TILE));
                }
            }
            List<Coord> path = SeaSearch.escape(g, SeaGrid.tileOf(rc));
            if (path == null || path.isEmpty())
                return null;
            return SeaSearch.farthestInSight(g, rc, path);
        }

        /** The nearest edge tile's centre to head for, looked up again every SEEK_EVERY_MS; null when none is loaded. */
        Coord2d seekEdge(Coord2d rc, long now) {
            if (seek == null || now - seekT > SEEK_EVERY_MS) {
                seek = SeaSearch.nearestEdge(tiles, band, ignored, SeaGrid.tileOf(rc), SEEK_RADIUS);
                seekT = now;
            }
            return (seek == null) ? null : SeaGrid.centreOf(seek);
        }

        void sailTo(Coord2d rc, Coord2d target, long now) {
            double angle = rc.angle(target);
            boolean click = lastTarget == null
                    || Helm.diff(angle, lastAngle) >= Math.PI / Helm.STEPS
                    || rc.dist(lastTarget) < lookahead / 2
                    || now - lastClickT > RECLICK_MS;
            if (!click) return;
            if (lastTarget == null)
                progressT = now;   // was standing still on purpose - the snag clock starts now
            gui.map.wdgmsg("click", Coord.z, target.floor(posres), 1, 0);
            lastTarget = target;
            lastAngle = angle;
            lastClickT = now;
        }

        /** Marks the spot ahead as a snag and backs off. True when the boat keeps snagging in one place - stuck for good. */
        boolean snagged(Coord2d rc, long now) throws InterruptedException {
            int near = 0;
            for (int i = snagTimes.size() - 1; i >= 0; i--) {
                if (now - snagTimes.get(i) > SNAG_WINDOW_MS) {
                    snagTimes.remove(i);
                    snagSpots.remove(i);
                } else if (snagSpots.get(i).dist(rc) < SNAG_SPREAD) {
                    near++;
                }
            }
            if (near + 1 >= SNAG_LIMIT)
                return true;
            snagTimes.add(now);
            snagSpots.add(rc);
            System.out.println("[OceanExplorer] snagged at " + rc + ", backing off");
            // Whatever holds the boat is ahead, the way it was told to go.
            snags.add(new SeaGrid.Disc(rc.add(Coord2d.sc(lastAngle, clearance * TILE)), SNAG_DISC));
            gui.map.wdgmsg("click", Coord.z, rc.add(Coord2d.sc(lastAngle + Math.PI, 2 * TILE)).floor(posres), 1, 0);
            NUtils.getUI().core.addTask(new WaitDuration(BACK_OFF_MS));
            lastTarget = null;
            progressRc = NUtils.player() != null ? NUtils.player().rc : rc;
            progressT = System.currentTimeMillis();
            return false;
        }

        /** Keeps the trail; true when the lap the user asked for is done. A short loop round the same edge is an island: its
         *  tiles stop counting as an edge, and the boat heads for the next one. */
        boolean loops(Coord2d rc) {
            traveled += rc.dist(prevRc);
            prevRc = rc;
            if (lapStart != null) {
                maxAway = Math.max(maxAway, rc.dist(lapStart));
                if (stopAfterLap && maxAway >= LAP_AWAY && rc.dist(lapStart) <= LAP_CLOSE && Helm.diff(helm.course, lapHeading) < HEADING_MATCH) {
                    gui.msg(L10n.get("explorer.msg_lap"));
                    return true;
                }
            }
            for (TrailPt t : trail) {
                double loop = traveled - t.traveled;
                if (loop >= LOOP_MIN && rc.dist(t.rc) < LOOP_CLOSE && Helm.diff(helm.course, t.heading) < HEADING_MATCH) {
                    Coord seed = SeaSearch.nearestEdge(tiles, band, ignored, SeaGrid.tileOf(rc), radius);
                    Set<Coord> island = (seed == null) ? null : SeaSearch.island(tiles, band, seed, ISLAND_MAX_TILES);
                    if (island != null) {
                        ignored.addAll(island);
                        seek = null;
                        System.out.println("[OceanExplorer] sailed round an island of " + island.size() + " tiles, leaving it");
                    }
                    trail.clear();
                    break;
                }
            }
            trail.removeIf(t -> traveled - t.traveled > ISLAND_MAX_LOOP);
            if (traveled - trailMark >= TRAIL_STEP) {
                trail.add(new TrailPt(rc, traveled, helm.course));
                trailMark = traveled;
            }
            return false;
        }

        /** Tiles under the hitboxes of solid gobs near the boat - not the player, the boat or anyone aboard. */
        List<Coord> solidTiles(Gob player, Coord2d rc) {
            List<Coord> res = new ArrayList<>();
            Gob vessel = vessel(player);
            long vid = (vessel == null) ? -1 : vessel.id;
            double reach = (radius + 2) * TILE;
            synchronized (gui.ui.sess.glob.oc) {
                for (Gob gob : gui.ui.sess.glob.oc) {
                    if (gob.id == player.id || gob.id == vid) continue;
                    if (gob.ngob == null || gob.ngob.hitBox == null) continue;
                    if (gob.rc.dist(rc) > reach) continue;
                    Following f = gob.getattr(Following.class);
                    if (f != null && f.tgt == vid) continue;
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
                }
            }
            return res;
        }
    }

    private static final class TrailPt {
        final Coord2d rc;
        final double traveled, heading;

        TrailPt(Coord2d rc, double traveled, double heading) {
            this.rc = rc;
            this.traveled = traveled;
            this.heading = heading;
        }
    }

    /** Following the shoreline: stay in deep ocean, deep or very deep; shallow water and land are the edge. */
    static boolean shoreBand(String n) {
        return n.equals("gfx/tiles/odeep") || n.equals("gfx/tiles/odeeper");
    }

    /** Following the deeper-water edge: stay in deep or shallow ocean; very deep water is the edge. */
    static boolean deeperBand(String n) {
        return n.equals("gfx/tiles/odeep") || n.equals("gfx/tiles/owater");
    }

    /** Any water a hull floats on. */
    static boolean sailable(String n) {
        return n.equals("gfx/tiles/odeep") || n.equals("gfx/tiles/odeeper") || n.startsWith("gfx/tiles/owater")
                || n.startsWith("gfx/tiles/water") || n.equals("gfx/tiles/deep");
    }

    /** The boat the player steers, or null when not aboard one or not in the steering seat. */
    static Gob vessel(Gob player) {
        Following f = player.getattr(Following.class);
        if (f == null) return null;
        Gob v = player.glob.oc.getgob(f.tgt);
        if (v == null || v.ngob == null || v.ngob.name == null) return null;
        String n = v.ngob.name;
        if (n.endsWith("/vehicle/rowboat"))
            return "d".equals(f.xfname) ? v : null;
        if (n.endsWith("/vehicle/snekkja") || n.endsWith("/vehicle/knarr"))
            return "m0".equals(f.xfname) ? v : null;
        if (n.endsWith("/vehicle/dugout") || n.endsWith("/vehicle/coracle"))
            return v;
        return null;
    }

    static boolean isShip(Gob vessel) {
        String n = vessel.ngob.name;
        return n.endsWith("/vehicle/snekkja") || n.endsWith("/vehicle/knarr");
    }

    /** Tiles of water kept between the hull and a wall: the hull's half-length, at least MIN_CLEARANCE. */
    static int clearance(Gob vessel) {
        NHitBox hb = vessel.ngob.hitBox;
        if (hb == null) return MIN_CLEARANCE;
        double half = Math.max(Math.max(Math.abs(hb.begin.x), Math.abs(hb.begin.y)), Math.max(Math.abs(hb.end.x), Math.abs(hb.end.y)));
        return Math.max(MIN_CLEARANCE, (int) Math.ceil(half / TILE));
    }

    /** Stops the boat where it is. */
    private static void halt(NGameUI gui) {
        Gob player = NUtils.player();
        if (player != null && gui.map != null)
            gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 1, 0);
    }
}
