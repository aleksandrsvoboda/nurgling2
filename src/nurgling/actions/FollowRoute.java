package nurgling.actions;

import haven.Coord2d;
import haven.Gob;
import haven.MiniMap;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.navigation.ChunkNavManager;
import nurgling.navigation.ChunkPath;
import nurgling.routes.ForagerPath;
import nurgling.routes.ForagerSection;
import nurgling.routes.ForagerWaypoint;
import nurgling.tasks.NTask;
import nurgling.tools.Finder;

import java.util.ArrayList;

/**
 * Walks a saved route (a Forager {@link ForagerPath}) and does nothing else: no pickups, no waypoint
 * steps, no guards, no inventory handling. It joins the route at the waypoint nearest the player,
 * walks forward to the last one and stops - it never loops and never travels home. The one thing it
 * does look after is stamina, and only when the client's AutoDrink setting is on.
 *
 * Pausing is cooperative: the control's paused flag is handed to {@link PathFinder} as its abort hook,
 * so the character stops within a step rather than at the end of a whole leg, and the bot thread then
 * parks on an infinite {@link NTask} - which leaves Stop (thread interrupt) working while paused.
 */
public class FollowRoute implements Action {

    // Max world-unit distance for a single PathFinder hop - its search grid fails once the span exceeds this.
    private static final double MAX_HOP_DISTANCE = 250.0;

    // Milestone anchors can't be walked onto directly, so route legs ending at one stop this far short.
    private static final double MILESTONE_APPROACH_DIST = 20.0;

    private static final double DRINK_BELOW = 0.5;
    private static final double DRINK_TARGET = 0.9;
    private static final long DRINK_RETRY_MS = 60_000;

    private final ForagerPath path;
    private final RouteWalkControl control;

    // Spots this run's walks got stuck on (something the pathfinder can't see, e.g. a young tree) - every later walk avoids them.
    private final ArrayList<Coord2d> stallSpots = new ArrayList<>();

    private long lastFailedDrinkMs = 0;
    private boolean reportedNoWater = false;

    public FollowRoute(ForagerPath path, RouteWalkControl control) {
        this.path = path;
        this.control = control;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if (path == null || path.waypoints.size() < 2) {
            return Results.ERROR("Route Walker: the route needs at least two waypoints");
        }
        // gui.activeBotPath is a single slot shared with Forager - two writers would fight over the overlay.
        if (gui.activeBotPath != null && gui.activeBotPath != path) {
            return Results.ERROR("Route Walker: another route bot is already running");
        }

        control.status("starting");
        control.progress(0, path.waypoints.size());

        // Sections are segment-relative, so they only resolve while standing on the route's own segment.
        path.generateSections();
        if (path.getSectionCount() == 0) {
            bridgeToRoute(gui);
        }
        if (path.getSectionCount() == 0) {
            return Results.ERROR("Route Walker: could not resolve the route from here (wrong map/segment - "
                    + "start near the route, or re-record it so its waypoints have a ChunkNav grid to bridge from)");
        }

        MiniMap.Location sessloc = gui.mmap.sessloc;
        if (sessloc == null) {
            return Results.ERROR("Route Walker: no session location yet");
        }

        gui.activeBotPath = path;
        gui.activeBotWaypointIndex = -1;
        gui.activeBotFailedWaypoints = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try {
            // Join the route where the player already is, rather than hiking back to its start.
            int entry = nearestWaypoint(gui, sessloc);
            Coord2d entryPos = path.waypoints.get(entry).toWorldCoord(gui.mmap.sessloc);
            if (entryPos == null) {
                return Results.ERROR("Route Walker: the nearest waypoint isn't on this segment");
            }

            control.status("walking to the route");
            gui.activeBotWaypointIndex = entry;
            control.progress(entry + 1, path.waypoints.size());
            if (!walkTo(gui, entryPos)) {
                gui.msg("Route Walker: couldn't reach waypoint " + (entry + 1) + " - carrying on along the route");
                gui.activeBotFailedWaypoints.add(entry);
            }

            if (entry >= path.waypoints.size() - 1) {
                gui.msg("Route Walker: already at the end of the route - nothing to walk.");
                control.status("finished");
                return Results.SUCCESS();
            }

            // A gap longer than the section length is split across several sections, so the section
            // index is NOT the waypoint index - always go through section.waypointIndex.
            int firstSection = 0;
            while (firstSection < path.getSectionCount()
                    && path.getSection(firstSection).waypointIndex < entry) {
                firstSection++;
            }

            control.status("walking");
            for (int i = firstSection; i < path.getSectionCount(); i++) {
                ForagerSection section = path.getSection(i);
                if (section == null) continue;

                ForagerWaypoint fromWp = path.waypoints.get(section.waypointIndex);
                ForagerWaypoint toWp = path.waypoints.get(section.waypointIndex + 1);
                gui.activeBotWaypointIndex = section.waypointIndex + 1;
                control.progress(section.waypointIndex + 2, path.waypoints.size());

                awaitResume(gui);
                checkStamina(gui);

                // Two waypoints sharing a milestone hash are the two ends of one teleport - crossing it IS this leg.
                if (fromWp.milestoneHash != null && fromWp.milestoneHash.equals(toWp.milestoneHash)) {
                    control.status("milestone travel");
                    Results milestoneResult = new UseMilestone(fromWp.milestoneHash, toWp, null).run(gui);
                    control.status("walking");
                    if (!milestoneResult.IsSuccess()) {
                        return milestoneResult;
                    }
                    continue;
                }

                // Re-resolved against the current sessloc every iteration - a milestone crossing can
                // shift the anchor the baked endPoint was computed from.
                Coord2d sectionEnd = sectionTarget(section, toWp, gui.mmap.sessloc);

                // The far end of this leg is a milestone anchor: stop short of the signpost instead.
                if (toWp.milestoneHash != null) {
                    Gob milestone = Finder.findGob(toWp.milestoneHash);
                    Gob player = NUtils.player();
                    if (milestone != null && player != null) {
                        Coord2d away = player.rc.sub(milestone.rc);
                        double dist = away.dist(Coord2d.z);
                        if (dist > 0.01) {
                            sectionEnd = milestone.rc.add(away.mul(MILESTONE_APPROACH_DIST / dist));
                        }
                    }
                }

                if (!walkTo(gui, sectionEnd)) {
                    gui.msg("Route Walker: couldn't reach waypoint " + (section.waypointIndex + 2) + " - skipping it");
                    gui.activeBotFailedWaypoints.add(section.waypointIndex + 1);
                }
            }

            gui.msg("Route Walker: route finished.");
            control.status("finished");
            return Results.SUCCESS();
        } finally {
            gui.activeBotPath = null;
            gui.activeBotWaypointIndex = -1;
            gui.activeBotFailedWaypoints = null;
        }
    }

    /** Not on the route's segment: ChunkNav over to waypoint 0 - the only waypoint guaranteed to carry a recorded grid to aim at. */
    private void bridgeToRoute(NGameUI gui) throws InterruptedException {
        ForagerWaypoint first = path.waypoints.get(0);
        if (first.gridId == -1 || first.localTile == null || !(gui.map instanceof NMapView)) {
            return;
        }
        ChunkNavManager chunkNav = ((NMapView) gui.map).getChunkNavManager();
        if (chunkNav == null || !chunkNav.isInitialized()) {
            return;
        }
        gui.msg("Route Walker: not on the route's segment - bridging there with ChunkNav");
        control.status("navigating to the route");
        ChunkPath cp = chunkNav.planToGridCoord(first.gridId, first.localTile);
        if (cp != null && chunkNav.navigateWithPath(cp, null, gui).IsSuccess()) {
            path.generateSections();
        }
    }

    /** The waypoint nearest the player among those that resolve in this segment; waypoint 0 if none do. */
    private int nearestWaypoint(NGameUI gui, MiniMap.Location sessloc) {
        Gob player = NUtils.player();
        if (player == null) {
            return 0;
        }
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < path.waypoints.size(); i++) {
            Coord2d wc = path.waypoints.get(i).toWorldCoord(sessloc);
            if (wc == null) continue;
            double d = player.rc.dist(wc);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best < 0 ? 0 : best;
    }

    /** Where this section walks to: its gap's end waypoint re-resolved against the live sessloc, else the baked endpoint. */
    private Coord2d sectionTarget(ForagerSection section, ForagerWaypoint toWp, MiniMap.Location sessloc) {
        if (sessloc != null) {
            Coord2d fresh = toWp.toWorldCoord(sessloc);
            if (fresh != null) return fresh;
        }
        return section.endPoint;
    }

    /** Walks to target in hops of at most MAX_HOP_DISTANCE, pausing between and inside hops. False only on a genuine pathing failure. */
    private boolean walkTo(NGameUI gui, Coord2d target) throws InterruptedException {
        while (true) {
            awaitResume(gui);

            Gob player = NUtils.player();
            if (player == null) return false;

            double remaining = player.rc.dist(target);
            boolean lastHop = remaining <= MAX_HOP_DISTANCE;
            Coord2d step = lastHop ? target : player.rc.add(target.sub(player.rc).norm(MAX_HOP_DISTANCE));

            PathFinder pf = new PathFinder(step);
            pf.abort = control::isPaused;
            pf.learnedBlocks = stallSpots;
            Results result = pf.run(gui);

            // Paused mid-hop: park, then re-plan this same hop from wherever the character now stands.
            // Asking the walk itself (not the flag, which a quick resume may already have cleared)
            // keeps a pause-then-resume from being mistaken for a leg that couldn't be reached.
            if (pf.abortedByCaller) {
                awaitResume(gui);
                continue;
            }
            if (!result.IsSuccess()) return false;
            if (lastHop) return true;
        }
    }

    /** Stops the character and parks the bot thread while paused. */
    private void awaitResume(NGameUI gui) throws InterruptedException {
        if (!control.isPaused()) return;

        PathFinder.stopHere(gui);
        control.status("paused");
        gui.msg("Route Walker: paused");
        // NTask.infinite defaults to true, so this waits as long as it takes without the timeout that
        // would kill the bot - and NCore.addTask rethrows InterruptedException, so Stop still works here.
        NUtils.getUI().core.addTask(new NTask() {
            @Override
            public boolean check() {
                return !control.isPaused();
            }
        });
        control.status("walking");
        gui.msg("Route Walker: resumed");
    }

    /** Forager's stamina top-up, gated on the same AutoDrink setting so the walker adds no new switch. */
    private void checkStamina(NGameUI gui) throws InterruptedException {
        if (!(Boolean) NConfig.get(NConfig.Key.autoDrink)) return;
        double stamina = NUtils.getStamina();
        if (stamina < 0 || stamina >= DRINK_BELOW) return;
        if (gui.drinkMeter != null && gui.drinkMeter.getTotalDrinkable() <= 0) {
            if (!reportedNoWater) {
                reportedNoWater = true;
                gui.msg("Route Walker: out of water - can't drink, carrying on.");
            }
            return;
        }
        if (System.currentTimeMillis() - lastFailedDrinkMs < DRINK_RETRY_MS) return;
        control.status("drinking");
        if (!new Drink(DRINK_TARGET, false).run(gui).IsSuccess()) {
            lastFailedDrinkMs = System.currentTimeMillis();
        }
        control.status("walking");
    }
}
