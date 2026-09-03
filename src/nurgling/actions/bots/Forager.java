package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.conf.NDiscordNotification;
import nurgling.conf.NForagerProp;
import nurgling.guarding.*;
import nurgling.navigation.ChunkNavManager;
import nurgling.navigation.ChunkPath;
import nurgling.routes.*;
import nurgling.tools.AreaStock;
import nurgling.tools.Finder;
import nurgling.tools.MilestoneRegistry;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class Forager implements Action {

    private HashSet<Long> processedGobs = new HashSet<>();
    private String presetName = null;

    // Set by the guard watcher just before it interrupts the bot thread, so run() can tell
    // "the watcher stopped me on purpose" apart from a genuine external cancel (e.g. the user
    // clicking the bot's stop button) - which must still propagate as a real interrupt.
    private volatile boolean threatStopTriggered = false;

    // The Guard whose trigger fired, set by the guard watcher (see startGuardWatcher) just
    // before it interrupts the bot thread. Its outcome is performed by the bot thread itself
    // after it's been interrupted - not by the watcher thread. See startGuardWatcher's javadoc
    // for why.
    private volatile Guard pendingGuard = null;

    // Resolved once near the top of run() from prop.guardingProfiles/currentGuardingProfile -
    // see resolveGuardingProfile(). Read by effectiveWaterMode() and used to build both the
    // pre-flight and in-flight guard lists.
    private GuardingProfile guardingProfile = null;

    // Set once run() has resolved it, so performGobAction() can persist a confirmed flower-menu
    // action back onto the live ForagerAction without needing it threaded through every method
    // signature in between.
    private NForagerProp forageProp = null;

    // Per-run baseline for Maintain (see ForagerAction.maintainQuantity): how many of each
    // Maintain-configured action's item are already sitting in its assigned Put area, resolved
    // once by resolveMaintainAreaStock() near the top of run(). findNearestActionableGob adds
    // the live carried-inventory count on top of this baseline on every scan - see its javadoc.
    // Keyed by sourceItemName (not the ForagerAction object itself) so a mid-run "Edit Pattern"
    // save - which replaces the ForagerAction instance in preset.actions in place - can't orphan
    // this baseline under a now-unreachable old key and silently reset it to 0.
    private Map<String, Integer> maintainAreaStock = new HashMap<>();

    // Route-geometry limits (max distance from route, detour-chain caps, cliff avoidance,
    // exclusion zones) configured per-route in Forager Settings - resolved once run() knows
    // path is valid, read by findNearestActionableGob and the detour-chaining loops. See
    // nurgling.actions.bots.forager.ForagerRouteConstraints.
    private nurgling.actions.bots.forager.ForagerRouteConstraints routeConstraints;

    public Forager() {
        // Default constructor - will show UI
    }

    public Forager(Map<String, Object> settings) {
        // Constructor for scenario usage - uses preset from settings
        if (settings != null && settings.containsKey("presetName")) {
            this.presetName = (String) settings.get("presetName");
        }
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NForagerProp prop = null;
        NForagerProp.PresetData preset = null;

        if (presetName != null) {
            // Scenario mode: load preset directly without UI
            prop = NForagerProp.get(NUtils.getUI().sessInfo);
            if (prop == null) {
                return Results.ERROR("Cannot load forager properties");
            }

            preset = prop.presets.get(presetName);
            if (preset == null) {
                return Results.ERROR("Preset not found: " + presetName);
            }

            // Load path if not already loaded
            if (preset.foragerPath == null && !preset.pathFile.isEmpty()) {
                try {
                    preset.foragerPath = ForagerPath.load(preset.pathFile);
                } catch (Exception e) {
                    return Results.ERROR("Failed to load path: " + e.getMessage());
                }
            }
        } else {
            // Interactive mode: show UI
            nurgling.widgets.bots.Forager w = null;
            try {
                NUtils.getUI().core.addTask(new nurgling.tasks.WaitCheckable(
                    NUtils.getGameUI().add((w = new nurgling.widgets.bots.Forager()), UI.scale(200, 200))
                ));
                if (w.cancelled)
                    return Results.FAIL();
                prop = w.prop;
            } catch (InterruptedException e) {
                throw e;
            } finally {
                if (w != null)
                    w.destroy();
            }

            if (prop == null) {
                return Results.ERROR("No configuration");
            }

            preset = prop.presets.get(prop.currentPreset);
        }

        if (preset == null || preset.foragerPath == null) {
            return Results.ERROR("No path configured");
        }

        forageProp = prop;

        // Actions are now edited as an independently-selected Actions Profile (Forager Settings
        // > Presets picks which one this preset runs with), not the preset's own (now-legacy)
        // `actions` field - overwrite it so every downstream helper that already reads
        // preset.actions keeps working unchanged. A defensive copy, not the live reference:
        // ForagerPickupContainer.load() hands out this same List<ForagerAction> to the settings
        // UI, which structurally mutates it in place (add/remove/drag) - aliasing it directly
        // here would let a concurrent Settings edit to the same Actions Profile race this bot's
        // own iteration over the list mid-run. preset.actionsProfileName falls back to the
        // prop-level currentActionsProfile only defensively - every preset is migrated to carry
        // its own selection on load (see NForagerProp's deserializing constructor), so this
        // should always be non-null by the time a real preset reaches here.
        String actionsProfileName = preset.actionsProfileName != null ? preset.actionsProfileName : prop.currentActionsProfile;
        if (prop.actionsProfiles != null && actionsProfileName != null) {
            ArrayList<ForagerAction> profileActions = prop.actionsProfiles.get(actionsProfileName);
            if (profileActions != null) {
                preset.actions = new ArrayList<>(profileActions);
            }
        }

        ForagerPath path = preset.foragerPath;

        if (path.waypoints == null || path.waypoints.size() < 2) {
            return Results.ERROR("Path has fewer than 2 waypoints");
        }

        routeConstraints = new nurgling.actions.bots.forager.ForagerRouteConstraints(path);

        gui.activeBotPath = path;
        // Index of the waypoint Forager is currently heading toward - starts at 0 (the initial
        // walk to path.waypoints.get(0) below) and advances to i+1 at the top of each main-loop
        // iteration (see below), so NWaypointOverlay can color it as the "active" node and
        // everything before it as already-passed, independent of the movement-queue/Routes-
        // editor convention of always treating index 0 as active.
        gui.activeBotWaypointIndex = 0;
        gui.activeBotFailedWaypoints = new HashSet<>();
        Thread threatWatcher = null;
        try {

        guardingProfile = resolveGuardingProfile(prop, preset);

        // Pre-flight guards: checked once, synchronously, before any movement at all (not even
        // the walk to the route's start position below) - if one fires, perform its outcome
        // immediately and end the run without ever taking a step. Distinct from the in-flight
        // guards below, which run continuously for as long as the bot is active - the same
        // underlying check (e.g. energy/HP) can be configured with a different threshold/
        // reaction for each phase (see GuardingProfile).
        GuardContext preflightCtx = new GuardContext(gui, guardingProfile.ignoreBats);
        for (Guard guard : buildGuards(guardingProfile.preflightGuards)) {
            try {
                if (guard.trigger.check(preflightCtx)) {
                    gui.msg("Forager: pre-flight - " + guard.trigger.describe() + " (" + guard.outcome.id() + ")");
                    guard.outcome.perform(gui);
                    return Results.SUCCESS();
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                // Same reasoning as startGuardWatcher's identical guard: don't let one bad
                // read (e.g. a gob disappearing mid-check) kill the whole run before it even
                // starts - just skip this guard for this pre-flight pass.
            }
        }

        // Runs continuously in the background for as long as this bot is active, so an
        // animal wandering into range - or an unknown player appearing - mid-walk (not just
        // between sections/actions) still triggers the safety action immediately.
        threatWatcher = startGuardWatcher(gui, guardingProfile, Thread.currentThread());

        // Audit every Maintain-configured action's assigned "Put" area (NArea.jout) before doing
        // anything else - same one-time-per-run, travel-and-count logic MaintainStockBot's own
        // scheduler step already uses, so this run's pickup budget for that item accounts for
        // what's already stored there, not just what's carried. See findNearestActionableGob's
        // maintainQuantity check below, which reads this map.
        maintainAreaStock = resolveMaintainAreaStock(gui, preset);

        // ForagerSection geometry is expressed in world coordinates relative to sessloc,
        // which only make sense within the map segment sessloc anchors - so sections can only
        // be (re)computed correctly once actually standing on the path's segment. They were
        // already generated once, at load time (see ForagerPath's constructor calling
        // generateSections()), but that could easily have happened from an entirely different
        // segment (e.g. this preset's window was opened while indoors) - in which case that
        // first pass produced zero sections, not an error, just quietly nothing. Regenerate
        // now, before the ChunkNav-bridging fallback below, in case we're already on the right
        // segment (the common case) and don't need it at all.
        path.generateSections();
        if (path.getSectionCount() == 0) {
            // Not on the route's own segment (e.g. started indoors, or in a different building
            // entirely) - bridge over via ChunkNav straight to the route's first waypoint. The
            // path's own waypoints are stored as persistent map-segment + tile coordinates
            // (ForagerWaypoint), which only resolve to a world position while already in that
            // exact segment; ChunkNav operates on a different (live, cross-cell) coordinate
            // system entirely, keyed to each waypoint's own gridId/localTile (resolved live at
            // record time - see ForagerWaypoint.resolveGridId's own javadoc - so a route
            // recorded before that existed, or a milestone-splice anchor, won't have one).
            // Reuses the exact ChunkNav plan-by-gridId path NUtils.navigateTo() already relies
            // on for bookmark navigation, just targeting the route's own first waypoint instead
            // of a captured bookmark.
            ForagerWaypoint firstWp = path.waypoints.get(0);
            if (firstWp.gridId != -1 && firstWp.localTile != null && gui.map instanceof NMapView) {
                ChunkNavManager chunkNav = ((NMapView) gui.map).getChunkNavManager();
                if (chunkNav != null && chunkNav.isInitialized()) {
                    gui.msg("Forager: not on the route's segment - trying ChunkNav to its first waypoint");
                    ChunkPath cp = chunkNav.planToGridCoord(firstWp.gridId, firstWp.localTile);
                    if (cp != null && chunkNav.navigateWithPath(cp, null, gui).IsSuccess()) {
                        path.generateSections();
                    }
                }
            }
        }
        if (path.getSectionCount() == 0) {
            return Results.ERROR("Forager: could not resolve path waypoints from the current location " +
                    "(wrong map/segment - begin the bot from near the path, or re-record it so its " +
                    "waypoints have a ChunkNav grid to bridge from)");
        }

        // Get first waypoint to navigate to start
        MiniMap.Location sessloc = gui.mmap.sessloc;
        if(sessloc == null) {
            return Results.ERROR("Cannot get sessloc");
        }
        Coord2d startPos = path.waypoints.get(0).toWorldCoord(sessloc);
        if(startPos == null) {
            return Results.ERROR("Cannot get start position - waypoint not in current segment");
        }

        PathFinder pf = new PathFinder(startPos);
        pf.waterMode = effectiveWaterMode(gui, preset);
        pf.run(gui);

        if (runWaypointSteps(gui, path.waypoints.get(0))) {
            return Results.SUCCESS();
        }

        // Check inventory before starting
        if (isInventoryFull(gui) && !preset.onFullInventoryAction.equals("nothing")) {
            performSafetyAction(gui, preset.onFullInventoryAction);
            return Results.SUCCESS();
        }

        // Main loop through sections
        for (int i = 0; i < path.getSectionCount(); i++)
        {
            ForagerSection section = path.getSection(i);
            if (section == null) continue;

            // A section between two waypoints sharing the same milestoneHash represents a
            // spliced-in signpost link (ForagerRouteMap.spliceMilestone()), not a walkable
            // stretch - the two anchors are typically far apart or in a different area entirely,
            // so PathFinder-ing straight to sectionEnd would be wrong (or impossible). Use the
            // milestone instead of walking, then skip the rest of this section's normal
            // detour/pathfind/collect logic - there is nothing to walk or scan along the way.
            ForagerWaypoint fromWp = path.waypoints.get(i);
            ForagerWaypoint toWp = path.waypoints.get(i + 1);
            // We're now heading toward i+1 for the rest of this iteration (walk, arrival, steps,
            // gob-collection/detours all included) - see the field's own javadoc on why this is
            // set once per iteration rather than at each individual arrival point below.
            gui.activeBotWaypointIndex = i + 1;
            if (fromWp.milestoneHash != null && fromWp.milestoneHash.equals(toWp.milestoneHash)) {
                // UseMilestone.run() already reports its own failure message via Results.ERROR().
                // toWp is passed as the expected destination so it can validate we actually landed
                // near it after traveling, and teleport home instead of continuing if not.
                Results milestoneResult = new UseMilestone(fromWp.milestoneHash, toWp).run(gui);
                if (!milestoneResult.IsSuccess()) {
                    return milestoneResult;
                }
                if (runWaypointSteps(gui, toWp)) {
                    return Results.SUCCESS();
                }
                continue;
            }

            Coord2d sectionEnd = section.endPoint;
            // section.endPoint was baked in once, up front, by the single generateSections() call
            // at the top of run() - relative to whatever sessloc was current *then*. A milestone
            // crossing physically moves the player elsewhere in the segment mid-route (the
            // teleport branch above), which can shift the live sessloc/Gob.rc anchor those
            // world coordinates were computed against - so a post-milestone section's baked
            // endPoint can end up reporting a wildly wrong distance even though the target
            // waypoint itself is only a couple of tiles away (reported live: repeated "Can't
            // find path" and eventually a pathfinder crash from a huge, bogus section distance,
            // right after boarding a coracle just past a spliced milestone). Reresolve the
            // target fresh from the waypoint's durable (segment, tile) location against the
            // *current* sessloc every iteration - toWorldCoord() itself already returns null if
            // the segment genuinely doesn't match, so this only ever corrects a stale anchor,
            // never masks a real cross-segment error - and fall back to the precomputed value
            // only if that fresh resolution isn't available.
            MiniMap.Location currentSessloc = gui.mmap.sessloc;
            if (currentSessloc != null) {
                Coord2d freshEnd = toWp.toWorldCoord(currentSessloc);
                if (freshEnd != null) {
                    sectionEnd = freshEnd;
                }
            }

            // Before committing to the walk toward this section's target, check whether a
            // known actionable gob is already closer than the target itself - if so, detour
            // to collect it (and anything else nearby, via the rescan inside the collection
            // loop) before continuing toward the path. "Return to" here means back to
            // wherever the character is standing right now, since that's where the section
            // walk below still needs to depart from.
            Gob playerBeforeWalk = NUtils.player();
            if (playerBeforeWalk != null) {
                Pair<Gob, ForagerAction> nearest = findNearestActionableGob(gui, playerBeforeWalk.rc, preset.actions, SCAN_RADIUS, playerBeforeWalk.rc);
                if (nearest != null && playerBeforeWalk.rc.dist(nearest.a.rc) < playerBeforeWalk.rc.dist(sectionEnd)) {
                    collectNearbyActionableGobs(gui, preset);
                    if (isInventoryFull(gui) && !preset.onFullInventoryAction.equals("nothing")) {
                        performSafetyAction(gui, preset.onFullInventoryAction);
                        return Results.SUCCESS();
                    }
                }
            }

            // Approaching a milestone anchor is handled entirely separately from the normal
            // gob-target/tile-target logic below: gob-targeted PathFinder (which has hitbox-aware
            // approach-point logic, normally used for exactly this kind of "walk up to a solid
            // object" case) consistently failed live ("Can't find path", then the whole run
            // aborting) even once pointed at the correct, unambiguous gob (found by durable hash,
            // valid hitbox confirmed) - the exact cause wasn't pinned down, so rather than keep
            // guessing at gob-targeted pathing, this sidesteps it completely: walk to a plain
            // tile-target point a short distance from the milestone, back toward wherever the bot
            // is currently coming from, which avoids ever pathing onto/through the milestone's own
            // occupied tile or touching its hitbox at all.
            if (toWp.milestoneHash != null) {
                Gob milestoneGob = Finder.findGob(toWp.milestoneHash);
                if (milestoneGob != null && playerBeforeWalk != null) {
                    Coord2d away = playerBeforeWalk.rc.sub(milestoneGob.rc);
                    double dist = away.dist(Coord2d.z);
                    Coord2d approachPoint = (dist > 0.01)
                            ? milestoneGob.rc.add(away.mul(MILESTONE_APPROACH_DIST / dist))
                            : sectionEnd;
                    PathFinder pfApproach = new PathFinder(approachPoint);
                    pfApproach.waterMode = effectiveWaterMode(gui, preset);
                    pfApproach.run(gui);
                } else if (milestoneGob != null) {
                    PathFinder pfApproach = new PathFinder(milestoneGob.rc);
                    pfApproach.waterMode = effectiveWaterMode(gui, preset);
                    pfApproach.run(gui);
                }
                if (runWaypointSteps(gui, toWp)) {
                    return Results.SUCCESS();
                }
                continue;
            }

            // Check if there are any target objects near the section endpoint (within 1 tile = 11 units)
            Gob targetGob = findGobNear(sectionEnd, 11.0);

            // Cover the ground toward this section's target in rescanning hops first (see
            // walkSectionInHops' own javadoc) - this is what lets a gob revealed only partway
            // through a long section still get detoured to, instead of only being caught by the
            // checks immediately before departure or after arrival.
            boolean reachedApproach = walkSectionInHops(gui, preset, targetGob != null ? targetGob.rc : sectionEnd);

            if (isInventoryFull(gui) && !preset.onFullInventoryAction.equals("nothing")) {
                performSafetyAction(gui, preset.onFullInventoryAction);
                return Results.SUCCESS();
            }

            if (!reachedApproach) {
                if (!isInventoryFull(gui)) {
                    gui.msg("Forager debug: section " + i + " failed pathing en route to "
                            + (targetGob != null ? "gob" : "sectionEnd=" + sectionEnd)
                            + " - waterMode=" + effectiveWaterMode(gui, preset) + " mounted=" + CoracleBot.isPlayerInCoracle(gui));
                    gui.activeBotFailedWaypoints.add(i + 1);
                }
            } else if (targetGob != null)
            {
                // Go to the object if found within 1 tile - walkSectionInHops only guarantees
                // getting within MAX_HOP_DISTANCE, not hitbox-aware precise arrival.
                PathFinder pfGob = new PathFinder(targetGob);
                pfGob.waterMode = effectiveWaterMode(gui, preset);
                Results pfGobResult = pfGob.run(gui);
                if (!pfGobResult.IsSuccess()) {
                    gui.msg("Forager debug: section " + i + " failed pathing to gob - waterMode="
                            + pfGob.waterMode + " mounted=" + CoracleBot.isPlayerInCoracle(gui));
                    gui.activeBotFailedWaypoints.add(i + 1);
                }
            } else
            {
                // Go to the endpoint if no objects found nearby
                PathFinder pfEnd = new PathFinder(sectionEnd);
                pfEnd.waterMode = effectiveWaterMode(gui, preset);
                Results pfEndResult = pfEnd.run(gui);
                if (!pfEndResult.IsSuccess()) {
                    gui.msg("Forager debug: section " + i + " failed pathing to sectionEnd=" + sectionEnd
                            + " - waterMode=" + pfEnd.waterMode + " mounted=" + CoracleBot.isPlayerInCoracle(gui));
                    gui.activeBotFailedWaypoints.add(i + 1);
                }
            }

            // Section i's endpoint is waypoint i+1 - run any steps attached to it now, right at
            // arrival, before this section's normal pickup pass continues the route.
            if (runWaypointSteps(gui, path.waypoints.get(i + 1))) {
                return Results.SUCCESS();
            }

            // Main collection pass for this section: repeatedly grab the nearest unprocessed
            // actionable gob, rescanning after every pickup, until nothing more is found
            // nearby - then retrace back through the breadcrumb trail before moving on to the
            // next section.
            collectNearbyActionableGobs(gui, preset);

            // CHAT_NOTIFY doesn't fit the walk-to-nearest-gob model (it's a one-shot "scan
            // and notify" action, not a per-gob interaction) - handled separately.
            processChatNotifyActions(gui, section, preset.actions);

            // Check inventory after each section
            if (isInventoryFull(gui)) {
                if (!preset.onFullInventoryAction.equals("nothing")) {
                    performSafetyAction(gui, preset.onFullInventoryAction);
                    return Results.SUCCESS();
                }
            }
        }
        
        // After completing all sections, perform finish action
        performSafetyAction(gui, preset.afterFinishAction);

        return Results.SUCCESS();
        } catch (InterruptedException e) {
            // Distinguish the watcher's own interrupt (a deliberate safety stop) from a
            // genuine external cancel (e.g. the user clicking the bot's stop button), which
            // must keep propagating as a real interrupt so it stops the whole chain rather
            // than being mistaken for one.
            if (threatStopTriggered) {
                // The safety action runs here, on the bot thread, only now that it's been
                // interrupted and its own movement/pathing has stopped - not on the watcher
                // thread. Running it there instead let the bot thread keep walking/acting in
                // parallel with (and potentially cancelling) a multi-second travel-to-hearth
                // channel, which looked like the bot's "running" indicator vanishing before
                // the character actually got home.
                //
                // Once the watcher has decided the run needs to end, that decision is final -
                // the safety action itself (e.g. TravelToHearthFire's pose/grid-change waits)
                // can still be interrupted again mid-sequence by something unrelated, and
                // observed doing so: the character didn't finish teleporting home, stranded
                // wherever it happened to be when the second interrupt landed. Retry the safety
                // action itself (clearing the interrupt flag first, so the next attempt isn't
                // immediately re-interrupted by the same stale signal) rather than letting that
                // abort the response early - this is the character's actual way home, it must
                // not give up partway.
                InterruptedException last = null;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        if (pendingGuard != null) {
                            pendingGuard.outcome.perform(gui);
                        }
                        gui.msg("Forager: stopped safely after safety action");
                        return Results.SUCCESS();
                    } catch (InterruptedException retry) {
                        last = retry;
                        Thread.interrupted();
                        gui.msg("Forager: safety action interrupted mid-way, retrying (" + attempt + "/3)");
                    }
                }
                gui.error("Forager: safety action repeatedly interrupted - check the character reached home safely");
                return Results.ERROR("Safety action interrupted after 3 attempts: " +
                        (last != null ? last.getMessage() : "unknown"));
            }
            throw e;
        } finally {
            if (threatWatcher != null) {
                threatWatcher.interrupt();
            }
            gui.activeBotPath = null;
            gui.activeBotDetourTrail = null;
            gui.activeBotDetourTarget = null;
            gui.activeBotWaypointIndex = -1;
            gui.activeBotFailedWaypoints = null;
        }
    }

    // Max distance (world units) for a single PathFinder hop, and the scan radius for the
    // CHAT_NOTIFY per-section pass (which intentionally only cares about gobs right around
    // the path, not the whole visible area). PathFinder builds a bounded search grid sized
    // around its start/end points and can fail to path at all once that span gets too big for
    // the grid to grow to cover (see PathFinder.construct()'s mul<200 cap) - so any single
    // PathFinder call, including each hop of a long detour, is kept to this distance.
    private static final double MAX_HOP_DISTANCE = 250.0;

    // Scan radius (world units) for finding actionable gobs to detour towards. Finder.findGobs
    // has no internal distance cap - it just filters whatever's currently loaded in OCache,
    // which is itself bounded by the grids the server has sent the client (there's no client-
    // side render-distance limit) - so this is set far larger than a single hop without extra
    // scanning cost; it only controls how far out to look for something to walk to, not how
    // far any single walk actually is.
    private static final double SCAN_RADIUS = 100000.0;

    // How close (world units, ~11/tile) to stop when approaching a milestone anchor waypoint -
    // close enough for a reliable right-click (UseMilestone), without pathing onto/through the
    // milestone's own occupied tile or touching its hitbox.
    private static final double MILESTONE_APPROACH_DIST = 20.0;

    /**
     * The water-mode flag every PathFinder call in this class should actually use - the preset's
     * own static waterMode toggle, OR-ed with "is the player currently mounted on a coracle right
     * now." A single static per-route toggle can't represent a route that walks normally to reach
     * a coracle, crosses water while riding it, then walks normally again after dismounting -
     * setting the preset's waterMode on for the whole route to cover the water leg also forces
     * every land leg's PathFinder to treat land tiles as blocked (NPFMap only allows water tiles
     * when waterMode is set), breaking the walk to/from the coracle. Deriving it live from actual
     * mount state means the preset's own toggle is only needed for a route with no coracle step
     * at all (e.g. wading/swimming), and a coracle route just works without the user having to
     * predict which parts of their route need it.
     * <p>
     * The base toggle itself now comes from the resolved GuardingProfile (see
     * resolveGuardingProfile()) rather than the legacy preset.waterMode field, when one is
     * available - falling back to preset.waterMode only if guardingProfile somehow isn't set
     * (shouldn't happen in a normal run() call, but this is also reachable from other bots'
     * direct construction of Forager-adjacent PathFinder calls in tests/tools).
     */
    private boolean effectiveWaterMode(NGameUI gui, NForagerProp.PresetData preset) {
        boolean baseWaterMode = guardingProfile != null ? guardingProfile.waterMode : preset.waterMode;
        return baseWaterMode || CoracleBot.isPlayerInCoracle(gui);
    }

    /**
     * Finds the nearest unprocessed, constraint-passing gob matching any of the preset's PICK/
     * FLOWER_ACTION/RIGHT_CLICK actions within radius of the given position, along with which
     * action it matched. CHAT_NOTIFY is excluded - it's a one-shot "scan this area and notify"
     * action, not a per-gob walk-to-and-interact action, so it doesn't fit this "closest first"
     * model and keeps its own pass in {@link #processChatNotifyActions}.
     * <p>
     * A candidate is rejected (skipped, same as an already-processed gob - no message, not a
     * pathing failure) if it's inside a brush-painted exclusion zone, farther than the route's
     * maxDistance from leashAnchor, or - when the route has avoidCliffs on - has a cliff edge
     * somewhere in the corridor between from and the candidate (climbing is unreliable, so the
     * fix is to never attempt the crossing, not the gob's own tile - a candidate sitting on solid
     * ground past a cliff is still rejected if the walk there would cross one). For a chained
     * detour episode (collectUntilExhausted/travelWithWaypoints/returnToPathViaBreadcrumbs),
     * leashAnchor must be held fixed for the whole episode by the caller - passing the bot's own
     * constantly-drifting current position would make the leash never fire. walkSectionInHops is
     * the one deliberate exception: it has no chain/episode concept at all (see its own javadoc -
     * maxChains/maxChainDistance don't apply to it), so it intentionally passes its own current
     * position as both from and leashAnchor - "don't detour more than maxDistance off from
     * wherever I already am on the route" is the correct, self-contained check for that single-hop
     * case, not a bug.
     * <p>
     * Candidates are gathered and distance-sorted before any cliff check runs, then checked in
     * ascending-distance order, stopping at the first that clears - the common no-cliff-nearby
     * case pays for exactly one corridor check, same as before this method needed one at all;
     * only a genuinely blocked nearest candidate costs a second check against the runner-up.
     */
    private Pair<Gob, ForagerAction> findNearestActionableGob(NGameUI gui, Coord2d from, java.util.List<ForagerAction> actions, double radius, Coord2d leashAnchor) throws InterruptedException {
        MiniMap.Location sessloc = (gui.mmap != null) ? gui.mmap.sessloc : null;
        MCache map = (gui.map != null && gui.map.glob != null) ? gui.map.glob.map : null;

        List<Pair<Gob, ForagerAction>> candidates = new ArrayList<>();
        Map<Long, Double> distByGobId = new HashMap<>();
        for (ForagerAction action : actions) {
            if (action.actionType == ForagerAction.ActionType.CHAT_NOTIFY) continue;
            if (action.maintainQuantity >= 0) {
                int areaStock = (action.sourceItemName != null) ? maintainAreaStock.getOrDefault(action.sourceItemName, 0) : 0;
                int carried = (action.sourceItemResource != null) ? countByResource(gui, action.sourceItemResource) : 0;
                if (areaStock + carried >= action.maintainQuantity) {
                    continue;
                }
            }
            for (Gob gob : Finder.findGobs(from, action.toNAlias(), null, radius)) {
                if (processedGobs.contains(gob.id)) continue;
                if (routeConstraints.isGobExcluded(sessloc, gob)) continue;
                if (!routeConstraints.withinLeash(leashAnchor, gob.rc)) continue;
                candidates.add(new Pair<>(gob, action));
                distByGobId.put(gob.id, from.dist(gob.rc));
            }
        }
        candidates.sort((a, b) -> Double.compare(distByGobId.get(a.a.id), distByGobId.get(b.a.id)));

        for (Pair<Gob, ForagerAction> candidate : candidates) {
            if (map != null && routeConstraints.cliffCorridorBlocked(map, from, candidate.a.rc)) continue;
            return candidate;
        }
        return null;
    }

    /**
     * Counts inventory items whose underlying resource (e.g. "gfx/invobjs/chestnut") matches
     * resource, for Maintain. Matching by resource rather than display name/NAlias is required
     * here - a forageable item's display name can vary by growth/quality stage (e.g. "Unripe
     * Chestnut" vs "Chestnut") while its resource stays constant, so a name-based count would
     * under/over-count depending on what happened to be in the inventory at check time. Delegates
     * to AreaStock.countByResource (shared with the Put-area container count below) rather than
     * keeping its own copy of the same filter loop.
     */
    private int countByResource(NGameUI gui, String resource) throws InterruptedException {
        return AreaStock.countByResource(gui.getInventory(), resource);
    }

    /**
     * Resolves each Maintain-configured action's area-stock baseline once, at the very start of
     * a run - visiting an area's containers is real travel (see AreaStock.countItemsInAreaContainers),
     * so this can't happen inside findNearestActionableGob's tight scan loops without turning
     * every single gob check into a detour there and back. Sums across every visible area that
     * has the item configured as "Put" (NArea.jout / containOut) rather than requiring one to be
     * picked, since Forager Settings has no area-picker UI - matching multiple areas is treated
     * as "all of them count towards the target," not an error.
     * <p>
     * Which area is the item's Put area has to be resolved by display name (containOut is keyed
     * by whatever name was dragged into that area's own "out" IngredientContainer - the existing,
     * shared area-config format this feature can't unilaterally redefine), but once inside that
     * area, its containers' contents are counted by resource (countByResource) for the same
     * display-name-varies-by-growth-stage reason the carried-inventory count uses it - an entry
     * with no resolved sourceItemResource can't be reliably counted this way, so it contributes 0
     * (degrades to the carried-only check, same as findNearestActionableGob already does for it).
     * <p>
     * Keyed by sourceItemName in the returned map (not the ForagerAction object) - see the
     * maintainAreaStock field javadoc for why.
     */
    private Map<String, Integer> resolveMaintainAreaStock(NGameUI gui, NForagerProp.PresetData preset) throws InterruptedException {
        Map<String, Integer> stock = new HashMap<>();
        if (gui.map == null || gui.map.glob == null || gui.map.glob.map == null) {
            return stock;
        }
        for (ForagerAction action : preset.actions) {
            if (action.maintainQuantity < 0 || action.sourceItemName == null || action.sourceItemResource == null) continue;

            int total = 0;
            int areasChecked = 0;
            // Enumerated the same way NContext.findOutGlobal/findOuts do (the mechanism
            // TransferItems2/"Free Inventory" already relies on to find an item's Put area) -
            // gui.map.nols (every area overlay the client knows about, id > 0) filtered by
            // !isDisabled(), NOT gui.map.glob.map.areas.values() filtered by area.isVisible().
            // isVisible() only returns true once the area's own grid is already loaded into
            // MCache - i.e. only once the character is already near/inside it - which silently
            // dropped every not-yet-loaded area from consideration here, defeating the whole
            // point of traveling to check one. isDisabled() is a persisted "hidden" flag,
            // unrelated to current load state.
            for (Integer id : gui.map.nols.keySet()) {
                if (id <= 0) continue;
                NArea area = gui.map.glob.map.areas.get(id);
                if (area == null || area.isDisabled()) continue;
                if (area.containOut(action.sourceItemName)) {
                    areasChecked++;
                    total += AreaStock.countItemsInAreaContainers(gui, area, action.sourceItemResource);
                }
            }
            // Silent when no Put area is configured for this item (the common case for a
            // Maintain entry that only ever meant "cap my carried inventory") - matches
            // MaintainStockBot's own "Checking .../Found ..." messages otherwise, so a run
            // that's about to detour through one or more areas' containers isn't silent about it.
            if (areasChecked > 0) {
                gui.msg("Forager Maintain: \"" + action.sourceItemName + "\" - " + total +
                        " already stored (target " + action.maintainQuantity + ")");
            }
            stock.put(action.sourceItemName, total);
        }
        return stock;
    }

    /**
     * Repeatedly walks to the nearest unprocessed actionable gob and performs its action,
     * rescanning from the new position after every one - so a straggler sitting just out of
     * range of the original scan (e.g. right next to the gob that was just picked) still gets
     * found, instead of only being caught on some later section's scan, if ever. Continues
     * until no more matching gobs are found nearby or the inventory fills up.
     * <p>
     * Every hop away from the starting position is recorded as a breadcrumb - mirrored live
     * into {@code gui.activeBotDetourTrail} for the yellow trail overlay (see NMapView/
     * NMiniMap) - so that once the collection loop ends, {@link #returnToPathViaBreadcrumbs}
     * can retrace them back to roughly where this call started. Without that, a chain of
     * nearby-gob hops could leave the character drifting arbitrarily far from the recorded
     * path with no way back other than the next section's own walk. The trail stays displayed
     * through the return walk too - {@code returnToPathViaBreadcrumbs} consumes the same list
     * as it retraces, so it visibly shrinks point by point instead of vanishing all at once.
     * <p>
     * If the loop itself is interrupted (the safety watchdog tripping), the return-walk is
     * skipped entirely rather than attempted in a finally block - adding movement right as an
     * interrupt is trying to stop the character is exactly what broke the hearth-travel
     * safety action earlier this session (see {@link #startGuardWatcher}'s javadoc); the same
     * risk applies here.
     * <p>
     * The route's maxChains/maxChainDistance caps (see DetourChainBudget) apply to this whole
     * episode, not per-call - a single budget is created here and threaded through both the
     * outbound sweep and the return walk's own inner sweeps, so a chain that's already used up
     * its budget chasing gobs on the way out doesn't get a fresh allowance chasing more on the
     * way back. Likewise the maxDistance leash is measured from a single anchor - the player's
     * position right here, before any hop happens - held fixed for the whole episode; re-deriving
     * it from the bot's own drifting position every hop would make the leash never fire.
     */
    private void collectNearbyActionableGobs(NGameUI gui, NForagerProp.PresetData preset) throws InterruptedException {
        ArrayList<Coord2d> breadcrumbs = new ArrayList<>();
        gui.activeBotDetourTrail = breadcrumbs;
        Gob player = NUtils.player();
        Coord2d leashAnchor = player != null ? player.rc : null;
        nurgling.actions.bots.forager.DetourChainBudget budget =
                new nurgling.actions.bots.forager.DetourChainBudget(routeConstraints.maxChains(), routeConstraints.maxChainDistanceTiles());
        boolean interrupted = false;
        try {
            collectUntilExhausted(gui, preset, breadcrumbs, budget, leashAnchor);
        } catch (InterruptedException e) {
            interrupted = true;
            throw e;
        } finally {
            if (interrupted) {
                gui.activeBotDetourTrail = null;
                gui.activeBotDetourTarget = null;
            } else {
                returnToPathViaBreadcrumbs(gui, breadcrumbs, preset, budget, leashAnchor);
                gui.activeBotDetourTrail = null;
                gui.activeBotDetourTarget = null;
            }
        }
    }

    /**
     * The actual "grab everything actionable in range" loop, extracted so both the outbound
     * collection pass and the return walk ({@link #returnToPathViaBreadcrumbs}) can run the
     * exact same behavior - the return trip used to only detour for a gob that happened to be
     * closer than the very next breadcrumb, which meant anything spotted off to the side (or
     * only visible once already past its closest point) was quietly skipped for the rest of
     * that trip. Now the return walk treats every breadcrumb stop the same way the outbound
     * loop treats every path section: fully sweep the area before moving on.
     * <p>
     * Repeatedly walks to the nearest unprocessed actionable gob and performs its action,
     * rescanning from the new position after every one, until nothing more is found within
     * SCAN_RADIUS or the inventory fills up. Every hop away from the starting position is
     * appended to breadcrumbs.
     */
    private void collectUntilExhausted(NGameUI gui, NForagerProp.PresetData preset, ArrayList<Coord2d> breadcrumbs,
                                        nurgling.actions.bots.forager.DetourChainBudget budget, Coord2d leashAnchor) throws InterruptedException {
        while (true) {
            if (isInventoryFull(gui)) return;
            if (!budget.canChain()) return;

            Gob player = NUtils.player();
            if (player == null) return;

            Pair<Gob, ForagerAction> nearest = findNearestActionableGob(gui, player.rc, preset.actions, SCAN_RADIUS, leashAnchor);
            if (nearest == null) {
                gui.activeBotDetourTarget = null;
                return;
            }
            gui.activeBotDetourTarget = nearest.a.rc;

            if (player.rc.dist(nearest.a.rc) > MAX_HOP_DISTANCE) {
                // Too far for a single PathFinder call - hop towards it, opportunistically
                // detouring to anything closer found along the way. If it gives up partway
                // (a hop failed - a dead end), stop the whole pass rather than looping back
                // around to retry the same unreachable gob forever.
                if (!travelWithWaypoints(gui, preset, nearest.a.rc, breadcrumbs, budget, leashAnchor)) return;
                continue;
            }

            budget.spend(player.rc.dist(nearest.a.rc));
            breadcrumbs.add(player.rc);
            performGobAction(gui, nearest.b, nearest.a, preset);
        }
    }

    /**
     * Walks from the current position towards target in hops of at most MAX_HOP_DISTANCE,
     * instead of one long PathFinder call that risks failing outright once the span is too
     * big for its search grid to grow to cover (see the comment on MAX_HOP_DISTANCE). Before
     * each hop, rescans out to SCAN_RADIUS for an actionable gob closer than target itself and
     * detours to collect it first if found - this is what lets a long walk toward a far gob
     * still sweep up anything else it passes near, not just the original target. Every hop's
     * start position (including ones spent detouring) is appended to breadcrumbs for the
     * eventual walk back.
     * <p>
     * Returns true once the player ends up within MAX_HOP_DISTANCE of target (ready for the
     * caller to do the final approach/action), false if a hop failed before getting there or
     * the inventory filled up mid-travel - either way, a dead end the caller shouldn't retry.
     */
    private boolean travelWithWaypoints(NGameUI gui, NForagerProp.PresetData preset, Coord2d target, ArrayList<Coord2d> breadcrumbs,
                                         nurgling.actions.bots.forager.DetourChainBudget budget, Coord2d leashAnchor) throws InterruptedException {
        while (true) {
            if (isInventoryFull(gui)) return false;
            if (!budget.canChain()) return false;

            Gob player = NUtils.player();
            if (player == null) return false;

            double remaining = player.rc.dist(target);
            if (remaining <= MAX_HOP_DISTANCE) return true;

            Pair<Gob, ForagerAction> nearest = findNearestActionableGob(gui, player.rc, preset.actions, SCAN_RADIUS, leashAnchor);
            if (nearest != null && player.rc.dist(nearest.a.rc) < remaining) {
                gui.activeBotDetourTarget = nearest.a.rc;
                budget.spend(player.rc.dist(nearest.a.rc));
                breadcrumbs.add(player.rc);
                performGobAction(gui, nearest.b, nearest.a, preset);
                continue;
            }
            gui.activeBotDetourTarget = target;

            Coord2d waypoint = player.rc.add(target.sub(player.rc).norm(MAX_HOP_DISTANCE));
            budget.spend(player.rc.dist(waypoint));
            breadcrumbs.add(player.rc);
            PathFinder hop = new PathFinder(waypoint);
            hop.waterMode = effectiveWaterMode(gui, preset);
            if (!hop.run(gui).IsSuccess()) {
                unstickAtCurrentPosition(gui, preset);
                return false;
            }
        }
    }

    /**
     * Best-effort recovery from a failed hop. The computed hop waypoint (a straight-line
     * interpolation toward the target, with no obstacle awareness) can land right on or inside a
     * gob's hitbox, which can leave the character visibly wedged against it rather than cleanly
     * failing - reported live: "there is a chance the bot attempts to try and place the waypoint
     * inside a gob and then just gets stuck." Re-pathing to the character's own current tile is a
     * trivial, always-reachable no-op that flushes any pending stuck movement state. Called right
     * before giving up on the hop, not instead of giving up - the original target is still
     * genuinely unreachable this way, this only unblocks the character for whatever comes next.
     */
    private void unstickAtCurrentPosition(NGameUI gui, NForagerProp.PresetData preset) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null) return;
        PathFinder unstick = new PathFinder(player.rc);
        unstick.waterMode = effectiveWaterMode(gui, preset);
        unstick.run(gui);
    }

    /**
     * Walks the main route toward a section's own target (sectionEnd, or a gob found right at
     * it) in the same rescanning-hop style as {@link #travelWithWaypoints}, instead of one
     * uninterrupted PathFinder call straight there. A gob only becomes findable once its grid
     * has actually loaded in - on a section longer than MAX_HOP_DISTANCE, a single long walk
     * gave the pre-departure/post-arrival checks no chance to see anything that loaded in
     * partway through, so it was silently skipped for that whole section (reported live: "we
     * used to constantly be checking for gobs at any point during the walk... now we only do it
     * when we reach the waypoints").
     * <p>
     * Unlike travelWithWaypoints, this deliberately does *not* set gui.activeBotDetourTarget
     * (or track breadcrumbs) for a plain hop toward target - target here is the route's own
     * next waypoint, already rendered as the active route node with its own live player leg;
     * only an actual detour to a gob found along the way is a real detour worth its own blue
     * node, so the field is set (and cleared right after) only for that branch.
     * <p>
     * Returns true once within MAX_HOP_DISTANCE of target, ready for the caller's own precise
     * final approach; false if a hop failed or the inventory filled up mid-walk.
     */
    private boolean walkSectionInHops(NGameUI gui, NForagerProp.PresetData preset, Coord2d target) throws InterruptedException {
        while (true) {
            if (isInventoryFull(gui)) return false;

            Gob player = NUtils.player();
            if (player == null) return false;

            double remaining = player.rc.dist(target);
            if (remaining <= MAX_HOP_DISTANCE) return true;

            Pair<Gob, ForagerAction> nearest = findNearestActionableGob(gui, player.rc, preset.actions, SCAN_RADIUS, player.rc);
            if (nearest != null && player.rc.dist(nearest.a.rc) < remaining) {
                gui.activeBotDetourTarget = nearest.a.rc;
                performGobAction(gui, nearest.b, nearest.a, preset);
                gui.activeBotDetourTarget = null;
                continue;
            }

            Coord2d waypoint = player.rc.add(target.sub(player.rc).norm(MAX_HOP_DISTANCE));
            PathFinder hop = new PathFinder(waypoint);
            hop.waterMode = effectiveWaterMode(gui, preset);
            if (!hop.run(gui).IsSuccess()) {
                unstickAtCurrentPosition(gui, preset);
                return false;
            }
        }
    }

    /**
     * Retraces the breadcrumb trail back towards target, one point at a time - most recent
     * first, since that's the order the character can actually walk it back in. This mutates
     * the same list instance {@code gui.activeBotDetourTrail} is still pointing at (the caller
     * doesn't null it out until this returns), so each consumed breadcrumb visibly shrinks the
     * yellow trail overlay rather than it vanishing all at once.
     * <p>
     * Before walking to each breadcrumb, runs a full {@link #collectUntilExhausted} sweep from
     * the current position - not just a check against that one next point - so the return trip
     * gets the same treatment as every section of the outbound path: fully clear out whatever's
     * nearby before moving on, rather than only detouring for something that happens to be
     * closer than the immediate next checkpoint (which meant anything off to the side, or only
     * spotted after already passing its closest point, used to get silently skipped for the
     * rest of the trip). Detouring towards a far gob (via travelWithWaypoints, called inside
     * collectUntilExhausted) adds fresh breadcrumbs of its own, which is fine - they just
     * become the new nearest points to retrace next.
     * <p>
     * A breadcrumb is dropped from the list once its hop is attempted, whether or not the hop
     * actually succeeded - if it's unreachable there's nothing to be gained retrying it, so
     * this just moves on to the next (older) one. There's deliberately no separate direct
     * PathFinder-to-target attempt anywhere here (not even once the list empties) - a lone
     * "just try to path straight there" call was firing constantly and spamming "can't find
     * path", on top of skipping past any gobs sitting between the hops it replaced. The
     * breadcrumb chain alone is trusted to get the character home: the oldest breadcrumb (the
     * position collection started from) is itself the last one walked, so by the time the list
     * empties the character is already essentially back at target.
     * <p>
     * Unlike collectUntilExhausted, this keeps walking breadcrumbs home even once the inventory
     * is full - collectUntilExhausted itself already stops trying to grab anything more once
     * that happens, but the walk back to the recorded path still needs to finish regardless.
     */
    private void returnToPathViaBreadcrumbs(NGameUI gui, ArrayList<Coord2d> breadcrumbs, NForagerProp.PresetData preset,
                                             nurgling.actions.bots.forager.DetourChainBudget budget, Coord2d leashAnchor) throws InterruptedException {
        while (!breadcrumbs.isEmpty()) {
            collectUntilExhausted(gui, preset, breadcrumbs, budget, leashAnchor);
            if (breadcrumbs.isEmpty()) return;

            Gob player = NUtils.player();
            if (player == null) return;

            Coord2d nextStop = breadcrumbs.get(breadcrumbs.size() - 1);
            gui.activeBotDetourTarget = nextStop;
            PathFinder hop = new PathFinder(nextStop);
            hop.waterMode = effectiveWaterMode(gui, preset);
            hop.run(gui);
            breadcrumbs.remove(breadcrumbs.size() - 1);
        }
    }

    /**
     * Walks to and performs one action on a single gob - extracted from the old per-type
     * "for gob in gobs" loops so {@link #collectNearbyActionableGobs} can dispatch a single
     * gob at a time between rescans. Marks the gob processed once done.
     */
    private void performGobAction(NGameUI gui, ForagerAction action, Gob gob,
                                   NForagerProp.PresetData preset) throws InterruptedException {
        switch (action.actionType) {
            case PICK: {
                PathFinder pfPick = new PathFinder(gob);
                pfPick.waterMode = effectiveWaterMode(gui, preset);
                pfPick.run(gui);
                new SelectFlowerAction("Pick", gob).run(gui);
                NUtils.getUI().core.addTask(new nurgling.tasks.WaitGobRemoval(gob.id));
                processedGobs.add(gob.id);
                break;
            }
            case FLOWER_ACTION: {
                PathFinder pfFlower = new PathFinder(gob);
                pfFlower.waterMode = effectiveWaterMode(gui, preset);
                pfFlower.run(gui);
                SelectFlowerAction flowerAction = new SelectFlowerAction(action.toActionNameCandidates(), gob);
                flowerAction.run(gui);
                confirmActionName(action, flowerAction.getMatchedOpt());
                NUtils.getUI().core.addTask(new nurgling.tasks.WaitPose(NUtils.player(), "gfx/borka/idle"));
                processedGobs.add(gob.id);
                break;
            }
            case RIGHT_CLICK: {
                // For objects with no flower menu at all (e.g. gates - see ChunkNavExecutor's
                // gate handling for the same rclickGob approach) - a plain right-click
                // performs the interaction directly, with no menu to select an option from.
                // There's no generic follow-up event to wait on (unlike PICK's
                // WaitGobRemoval or FLOWER_ACTION's WaitPose - a bare right-click might open
                // a window, play an animation, or do nothing visible at all depending on the
                // object), so this just gives the interaction a brief moment to register
                // before moving on.
                NUtils.setSpeed(2);
                try {
                    PathFinder pfRclick = new PathFinder(gob);
                    pfRclick.waterMode = effectiveWaterMode(gui, preset);
                    pfRclick.run(gui);
                    NUtils.rclickGob(gob);
                    NUtils.getUI().core.addTask(new nurgling.tasks.WaitTicks(30));
                } finally {
                    NUtils.setSpeed(1);
                }
                processedGobs.add(gob.id);
                break;
            }
            default:
                // CHAT_NOTIFY never reaches here - findNearestActionableGob excludes it.
                break;
        }
    }

    /**
     * Once a real flower menu confirms which of an untested "Pick X"/"Take X" candidate list
     * (see ForagerPickupContainer.actionNameCandidates) was actually correct, narrows this action
     * down to just that one string and persists it - so this pickup entry never needs to re-try
     * the whole guess list again, on this run or any future one. No-op if there was only ever one
     * candidate to begin with (matched already equals actionName - nothing to narrow) or nothing
     * matched (matched is null).
     */
    private void confirmActionName(ForagerAction action, String matched) {
        if (matched == null || matched.equals(action.actionName) || forageProp == null) {
            return;
        }
        action.actionName = matched;
        NForagerProp.set(forageProp);
    }

    /**
     * CHAT_NOTIFY is a one-shot "scan this section and notify if found" action, not a
     * per-gob walk-to-and-interact action - it doesn't fit collectNearbyActionableGobs'
     * nearest-first model at all, so it keeps its own simple per-section scan, same as before.
     */
    private void processChatNotifyActions(NGameUI gui, ForagerSection section,
                                           java.util.List<ForagerAction> actions) throws InterruptedException {
        for (ForagerAction action : actions) {
            if (action.actionType != ForagerAction.ActionType.CHAT_NOTIFY) continue;

            ArrayList<Gob> gobs = Finder.findGobs(section.getCenterPoint(), action.toNAlias(), null, MAX_HOP_DISTANCE);
            gobs.removeIf(gob -> processedGobs.contains(gob.id));
            if (gobs.isEmpty()) continue;

            String message = String.format("Found %d %s objects!", gobs.size(), action.targetObjectPattern);

            if (action.notifyTarget == ForagerAction.NotifyTarget.DISCORD) {
                NDiscordNotification discordSettings = NDiscordNotification.get("general");
                if (discordSettings != null && discordSettings.webhookUrl != null && !discordSettings.webhookUrl.isEmpty()) {
                    gui.msgToDiscord(discordSettings, message);
                }
            } else if (action.notifyTarget == ForagerAction.NotifyTarget.CHAT) {
                if (action.chatChannelName != null && !action.chatChannelName.isEmpty()) {
                    ChatUI.Channel targetChannel = findChatChannelByName(gui, action.chatChannelName);
                    if (targetChannel != null && targetChannel instanceof ChatUI.EntryChannel) {
                        ((ChatUI.EntryChannel) targetChannel).send(message);
                    }
                }
            }

            for (Gob gob : gobs) {
                processedGobs.add(gob.id);
            }

            // Pause for 5 minutes (18000 frames at 60fps)
            NUtils.getUI().core.addTask(new nurgling.tasks.WaitTicks(18000));

            // Signal to stop the bot after pause
            throw new InterruptedException("CHAT_NOTIFY action triggered - stopping bot");
        }
    }

    private boolean isInventoryFull(NGameUI gui) throws InterruptedException
    {

        if (gui.vhand != null) {
            return true;
        }

        if (gui.getInventory() != null) {
            return gui.getInventory().getFreeSpace() <= 4;
        }

        return false;
    }
    
    
    /** Runs a waypoint's attached scheduler-style steps (Ctrl+right-click on the Routes map to
     *  edit) in full, before the caller continues the route as normal. On failure, dispatches
     *  wp.onStepsFailAction via performSafetyAction() the same way onFullInventoryAction/
     *  afterFinishAction already are - "nothing" logs and lets the run continue, "logout"/
     *  "travel hearth" end it. Returns true if the caller should return Results.SUCCESS()
     *  immediately (a terminal fail-action already fired), false to keep going. */
    private boolean runWaypointSteps(NGameUI gui, ForagerWaypoint wp) throws InterruptedException {
        if (wp.steps == null || wp.steps.isEmpty()) {
            return false;
        }
        Results stepsResult = ScenarioRunner.runSteps(gui, wp.steps);
        if (!stepsResult.IsSuccess()) {
            String failAction = wp.onStepsFailAction != null ? wp.onStepsFailAction : "nothing";
            gui.msg("Forager: waypoint steps failed (" + failAction + ")");
            if (!failAction.equals("nothing")) {
                performSafetyAction(gui, failAction);
                return true;
            }
        }
        return false;
    }

    /** "nothing"/"logout"/"travel hearth" dispatch, still used by the non-Guard action strings
     *  (onFullInventoryAction, afterFinishAction, waypoint onStepsFailAction) that predate the
     *  Guard system and aren't configured per-guard. Delegates "logout"/"travel hearth" to
     *  GuardOutcome instead of keeping a second, independent copy of that same dispatch (they
     *  used to be duplicated verbatim, including the coracle-dismount-before-hearth workaround -
     *  a fix applied to only one copy would have silently left the other stale). */
    private void performSafetyAction(NGameUI gui, String action) throws InterruptedException {
        if (!"nothing".equals(action)) {
            GuardOutcome.fromId(action).perform(gui);
        }
    }
    
    /** Resolves the preset's own selected GuardingProfile (Forager Settings > Presets), falling
     *  back to the prop-level currentGuardingProfile only defensively (same reasoning as the
     *  actionsProfileName fallback above - every preset is migrated to carry its own selection
     *  on load, so this is a belt-and-suspenders case, not the normal path). Falls back to a
     *  fresh GuardingProfile.withDefaults() rather than erroring - a Forager run should never be
     *  blocked from starting just because its guarding config is missing or stale. */
    private GuardingProfile resolveGuardingProfile(NForagerProp prop, NForagerProp.PresetData preset) {
        if (prop.guardingProfiles == null || prop.guardingProfiles.isEmpty()) {
            return GuardingProfile.withDefaults();
        }
        String name = preset.guardingProfileName != null ? preset.guardingProfileName : prop.currentGuardingProfile;
        GuardingProfile p = prop.guardingProfiles.get(name);
        if (p != null) {
            return p;
        }
        return prop.guardingProfiles.values().iterator().next();
    }

    private List<Guard> buildGuards(List<GuardEntry> entries) {
        List<Guard> guards = new ArrayList<>();
        for (GuardEntry entry : entries) {
            Guard guard = entry.toGuard();
            if (guard != null) {
                guards.add(guard);
            }
        }
        return guards;
    }

    /**
     * Starts a background thread that polls the selected GuardingProfile's in-flight guards
     * (unknown players, dangerous animals, low energy/HP, stuck detection - see GuardRegistry)
     * for as long as the bot is running, independent of whatever the bot thread is doing at the
     * time - including mid-walk to a single distant gob, which the bot's own inline checks
     * (only run between sections/actions) would otherwise miss entirely. On a guard firing it
     * only records which one and interrupts the bot thread - it does not perform the outcome
     * itself.
     * <p>
     * The outcome runs later, on the bot thread, only once it's actually been interrupted and
     * stopped moving/pathing - not on this watcher thread. Running it here instead would let
     * the bot thread keep walking/acting in parallel with (and potentially cancelling) a
     * multi-second travel-to-hearth channel, which looked like the bot's "running" indicator
     * vanishing before the character actually got home (see run()'s InterruptedException catch
     * block, which retries the outcome up to 3 times for exactly this reason).
     */
    private Thread startGuardWatcher(NGameUI gui, GuardingProfile profile, Thread botThread) {
        List<Guard> guards = buildGuards(profile.inflightGuards);
        GuardContext ctx = new GuardContext(gui, profile.ignoreBats);
        // BotExecutor is documented as "the ONLY place that calls ThreadLocalUI.set/clear" -
        // this ad-hoc watcher thread is a real exception to that, and without its own binding,
        // NConfig.get() calls made from it (e.g. GuardContext.animalRads(), reading the user's
        // Ring Settings) fall back to the global config instead of this bot's own session/
        // profile config - wrong data in a multi-session client. Capture the CALLING thread's
        // bound NUI (this method only ever runs from the already-bound bot thread) and bind it
        // on the watcher thread too, mirroring BotExecutor.runAsync's own pattern exactly.
        NUI boundUI = NUtils.getUI();
        Thread watcher = new Thread(() -> {
            if (boundUI != null) {
                nurgling.sessions.ThreadLocalUI.set(boundUI);
            }
            try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    for (Guard guard : guards) {
                        if (guard.trigger.check(ctx)) {
                            gui.msg("Forager: " + guard.trigger.describe() + " (" + guard.outcome.id() + ")");
                            pendingGuard = guard;
                            threatStopTriggered = true;
                            botThread.interrupt();
                            return;
                        }
                    }
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    // Don't let one bad read (e.g. a gob disappearing mid-check) kill the
                    // watcher for the rest of the bot's run.
                }
            }
            } finally {
                if (boundUI != null) {
                    nurgling.sessions.ThreadLocalUI.clear();
                }
            }
        }, "ForagerGuardWatcher");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    private Gob findGobNear(Coord2d pos, double radius) {
        synchronized (NUtils.getGameUI().ui.sess.glob.oc) {
            for (Gob gob : NUtils.getGameUI().ui.sess.glob.oc) {
                if (!(gob instanceof OCache.Virtual || gob.attr.isEmpty() || gob.getClass().getName().contains("GlobEffector"))) {
                    if (gob.id != NUtils.playerID() && gob.rc.dist(pos) <= radius && !(gob instanceof MapView.Plob) && gob.id > 0) {
                        return gob;
                    }
                }
            }
        }
        return null;
    }
    
    private ChatUI.Channel findChatChannelByName(NGameUI gui, String channelName) {
        if (gui.chat == null) return null;
        
        for (Widget w = gui.chat.child; w != null; w = w.next) {
            if (w instanceof ChatUI.Channel) {
                ChatUI.Channel chan = (ChatUI.Channel) w;
                if (chan.name().equalsIgnoreCase(channelName)) {
                    return chan;
                }
            }
        }
        return null;
    }
}
