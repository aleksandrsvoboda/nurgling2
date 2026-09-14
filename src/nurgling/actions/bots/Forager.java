package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.actions.bots.forager.DetourBranchBudget;
import nurgling.actions.bots.forager.RouteLookahead;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.conf.NDiscordNotification;
import nurgling.conf.NForagerProp;
import nurgling.guarding.*;
import nurgling.navigation.ChunkNavManager;
import nurgling.navigation.ChunkPath;
import nurgling.routes.*;
import nurgling.tasks.GateDetector;
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

    // The Guard whose trigger fired, claimed atomically by the catch block below - a plain
    // boolean+reference pair here couldn't tell a genuine external cancel racing the watcher's own
    // interrupt apart from the watcher's own stop (both would just see the boolean already true).
    // compareAndSet on claim, getAndSet(null) on consume: at most one InterruptedException gets
    // attributed to a given guard firing, and any interrupt that arrives once this is empty again
    // is unambiguously external.
    private final java.util.concurrent.atomic.AtomicReference<Guard> pendingGuard = new java.util.concurrent.atomic.AtomicReference<>();

    // Resolved once near the top of run() - see resolveGuardingProfile().
    private GuardingProfile guardingProfile = null;

    // The preset's "Dangerous animal" guard while it's enabled, else null. When set, Forager's walks plan around dangerous
    // animals (avoidZones), and a route leg with no safe way around falls back to this guard's reaction.
    private Guard dangerGuard = null;
    private java.util.function.Supplier<List<PathFinder.AvoidZone>> avoidZones = null;

    // Testing aid for the [ForagerAvoid] log: what the bot thread is doing right now (read by the guard watcher), and when the run began.
    private volatile String activity = "starting";
    private long runStartMs = 0;

    // Set by walk() when a route leg found no safe way around a dangerous animal; the main loop then skips that waypoint.
    private boolean routeLegBlocked = false;

    // The in-flight guard watcher (see startGuardWatcher) - a field so an end-of-run action can stop it first.
    private Thread threatWatcher = null;

    // Spots this run's walks got stuck on (something the pathfinder can't see, e.g. a young tree) - every later walk avoids them.
    private final ArrayList<Coord2d> stallSpots = new ArrayList<>();

    // Set once run() has resolved it, so performGobAction() can persist a confirmed flower-menu action.
    private NForagerProp forageProp = null;

    // Per-run Maintain baseline (see ForagerAction.maintainQuantity), keyed by sourceItemName so an in-run Edit Pattern save can't orphan it.
    private Map<String, Integer> maintainAreaStock = new HashMap<>();

    // Route-geometry limits configured per-route in Forager Settings - see ForagerRouteConstraints.
    private nurgling.actions.bots.forager.ForagerRouteConstraints routeConstraints;

    // When checkStamina's last drink failed, so it can hold off DRINK_RETRY_MS instead of stalling every hop.
    private long lastFailedDrinkMs = 0;
    // "Out of water" is only reported once per run.
    private boolean reportedNoWater = false;

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
        // The Recent Actions panel re-runs the same instance, so per-run drink state starts fresh here.
        lastFailedDrinkMs = 0;
        reportedNoWater = false;
        stallSpots.clear();

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

        // Overwrite the legacy preset.actions with a defensive copy of its Actions Profile, so concurrent Settings edits can't race this run's iteration.
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
        // Index of the waypoint Forager is currently heading toward - lets NWaypointOverlay color it as active.
        gui.activeBotWaypointIndex = 0;
        // Concurrent set: the render thread iterates this via NWaypointOverlay while this bot
        // thread adds to it, with no other synchronization between them.
        gui.activeBotFailedWaypoints = java.util.concurrent.ConcurrentHashMap.newKeySet();
        threatWatcher = null;
        try {

        guardingProfile = resolveGuardingProfile(prop, preset);
        dangerGuard = resolveDangerGuard(guardingProfile);
        final boolean ignoreBats = guardingProfile.ignoreBats;
        avoidZones = (dangerGuard != null) ? () -> routeConstraints.dangerZones(gui, ignoreBats) : null;
        runStartMs = System.currentTimeMillis();
        System.out.println("[ForagerAvoid] avoidance " + (dangerGuard != null
                ? "ON - blocked waypoints are skipped; chase/nearby reaction: " + dangerGuard.outcome.id() + ", ignoreBats=" + ignoreBats
                : "OFF - \"Dangerous animal\" guard disabled"));

        // Pre-flight guards: checked once before any movement; in-flight guards run continuously below.
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
                // Don't let one bad read kill the run before it even starts.
            }
        }

        // Runs continuously in the background so a mid-walk threat still triggers the safety action immediately.
        threatWatcher = startGuardWatcher(gui, guardingProfile, Thread.currentThread());

        // One-time Put-area audit for Maintain's pickup budget; skipped if the preset ignores Maintain limits.
        maintainAreaStock = preset.ignoreMaintainLimits
                ? java.util.Collections.emptyMap()
                : resolveMaintainAreaStock(gui, preset);

        // Every pickup action already maintained - nothing this run could collect, so there's no
        // point walking the route at all.
        if (nothingLeftToPickUp(gui, preset)) {
            gui.msg("Forager: every item is already at its Maintain quantity - nothing to do, stopping.");
            performSafetyAction(gui, preset.afterFinishAction);
            return Results.SUCCESS();
        }

        // Sections are segment-relative, so they can only be (re)computed while standing on the path's own segment.
        path.generateSections();
        if (path.getSectionCount() == 0) {
            // Not on the route's segment - bridge over via ChunkNav to the route's first waypoint.
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

        Results startResult = walk(gui, preset, new PathFinder(startPos), true);

        // Only run this waypoint's steps if we actually reached it.
        if (startResult.IsSuccess() && runWaypointSteps(gui, path.waypoints.get(0))) {
            return Results.SUCCESS();
        }

        // Check inventory before starting
        if (isInventoryFull(gui) && !preset.onFullInventoryAction.equals("nothing")) {
            performSafetyAction(gui, preset.onFullInventoryAction);
            return Results.SUCCESS();
        }

        // Waypoint a dangerous animal blocked - the gap's remaining sub-sections all walk to that same waypoint, so they're skipped too.
        int zoneBlockedWaypoint = -1;

        // Main loop through sections
        for (int i = 0; i < path.getSectionCount(); i++)
        {
            ForagerSection section = path.getSection(i);
            if (section == null) continue;
            if (section.waypointIndex + 1 == zoneBlockedWaypoint) continue;
            routeLegBlocked = false;

            // A waypoint gap longer than ForagerPath.SECTION_LENGTH gets split across multiple
            // sections (see generateSections()), so the section-loop counter i is NOT the same
            // as the waypoint index - always go through section.waypointIndex instead.
            ForagerWaypoint fromWp = path.waypoints.get(section.waypointIndex);
            ForagerWaypoint toWp = path.waypoints.get(section.waypointIndex + 1);
            gui.activeBotWaypointIndex = section.waypointIndex + 1;
            if (fromWp.milestoneHash != null && fromWp.milestoneHash.equals(toWp.milestoneHash)) {
                // toWp validates we actually landed near the expected destination.
                activity = "milestone travel";
                Results milestoneResult = new UseMilestone(fromWp.milestoneHash, toWp, guardingProfile).run(gui);
                if (!milestoneResult.IsSuccess()) {
                    return milestoneResult;
                }
                if (runWaypointSteps(gui, toWp)) {
                    return Results.SUCCESS();
                }
                continue;
            }

            // Re-resolved against the current sessloc every iteration - see sectionTarget().
            Coord2d sectionEnd = sectionTarget(section, toWp, gui.mmap.sessloc);

            // Detour to a known actionable gob first if it's closer than this section's own target.
            Gob playerBeforeWalk = NUtils.player();
            if (playerBeforeWalk != null) {
                Pair<Gob, ForagerAction> nearest = findNearestActionableGob(gui, playerBeforeWalk.rc, preset.actions, playerBeforeWalk.rc, preset.ignoreMaintainLimits, effectiveWaterMode(gui, preset), lookaheadFrom(gui, path, i, playerBeforeWalk.rc), null);
                if (nearest != null && playerBeforeWalk.rc.dist(nearest.a.rc) < playerBeforeWalk.rc.dist(sectionEnd)) {
                    collectNearbyActionableGobs(gui, preset, path, i);
                    if (isInventoryFull(gui) && !preset.onFullInventoryAction.equals("nothing")) {
                        performSafetyAction(gui, preset.onFullInventoryAction);
                        return Results.SUCCESS();
                    }
                }
            }

            // Milestone anchors: walk to a plain tile-target point short of the milestone rather than gob-targeted PathFinder (which failed on these).
            if (toWp.milestoneHash != null) {
                Gob milestoneGob = Finder.findGob(toWp.milestoneHash);
                boolean arrivedNearMilestone = false;
                if (milestoneGob != null && playerBeforeWalk != null) {
                    Coord2d away = playerBeforeWalk.rc.sub(milestoneGob.rc);
                    double dist = away.dist(Coord2d.z);
                    Coord2d approachPoint = (dist > 0.01)
                            ? milestoneGob.rc.add(away.mul(MILESTONE_APPROACH_DIST / dist))
                            : sectionEnd;
                    arrivedNearMilestone = walk(gui, preset, new PathFinder(approachPoint), true).IsSuccess();
                } else if (milestoneGob != null) {
                    arrivedNearMilestone = walk(gui, preset, new PathFinder(milestoneGob.rc), true).IsSuccess();
                }
                if (arrivedNearMilestone && runWaypointSteps(gui, toWp)) {
                    return Results.SUCCESS();
                }
                continue;
            }

            // Check if there are any target objects near the section endpoint (within 1 tile = 11 units)
            Gob targetGob = findGobNear(sectionEnd, 11.0);

            // Cover the ground in rescanning hops first, so a gob revealed mid-section still gets detoured to.
            Gob walkStart = NUtils.player();
            RouteLookahead walkLookahead = lookaheadFrom(gui, path, i, walkStart != null ? walkStart.rc : null);
            boolean reachedApproach = walkInHops(gui, preset, targetGob != null ? targetGob.rc : sectionEnd, null, walkLookahead);

            if (isInventoryFull(gui) && !preset.onFullInventoryAction.equals("nothing")) {
                performSafetyAction(gui, preset.onFullInventoryAction);
                return Results.SUCCESS();
            }

            // Whether this section's walk actually landed at/near waypoint i+1, gating the steps call below.
            boolean arrivedAtWaypoint = reachedApproach;
            if (!reachedApproach) {
                if (!isInventoryFull(gui)) {
                    gui.msg("Forager debug: section " + i + " failed pathing en route to "
                            + (targetGob != null ? "gob" : "sectionEnd=" + sectionEnd)
                            + " - waterMode=" + effectiveWaterMode(gui, preset) + " mounted=" + CoracleBot.isPlayerInCoracle(gui));
                    gui.activeBotFailedWaypoints.add(section.waypointIndex + 1);
                }
            } else if (targetGob != null)
            {
                // walkInHops only guarantees getting within MAX_HOP_DISTANCE, not precise arrival.
                PathFinder pfGob = new PathFinder(targetGob);
                Results pfGobResult = walk(gui, preset, pfGob, true);
                arrivedAtWaypoint = pfGobResult.IsSuccess();
                if (!arrivedAtWaypoint) {
                    gui.msg("Forager debug: section " + i + " failed pathing to gob - waterMode="
                            + pfGob.waterMode + " mounted=" + CoracleBot.isPlayerInCoracle(gui));
                    gui.activeBotFailedWaypoints.add(section.waypointIndex + 1);
                }
            } else
            {
                // Go to the endpoint if no objects found nearby
                PathFinder pfEnd = new PathFinder(sectionEnd);
                Results pfEndResult = walk(gui, preset, pfEnd, true);
                arrivedAtWaypoint = pfEndResult.IsSuccess();
                if (!arrivedAtWaypoint) {
                    gui.msg("Forager debug: section " + i + " failed pathing to sectionEnd=" + sectionEnd
                            + " - waterMode=" + pfEnd.waterMode + " mounted=" + CoracleBot.isPlayerInCoracle(gui));
                    gui.activeBotFailedWaypoints.add(section.waypointIndex + 1);
                }
            }

            if (routeLegBlocked) {
                zoneBlockedWaypoint = section.waypointIndex + 1;
            }

            // Waypoint steps only run once we've actually reached the real waypoint - not on an
            // intermediate sub-section of a gap that got split across multiple sections.
            if (arrivedAtWaypoint && section.isLastInGap && runWaypointSteps(gui, toWp)) {
                return Results.SUCCESS();
            }

            // Repeatedly grab the nearest unprocessed actionable gob until nothing more is found nearby,
            // leaving anything a stop further along passes closer to for that stop.
            collectNearbyActionableGobs(gui, preset, path, i + 1);

            // One-shot scan-and-notify, handled separately from the per-gob walk-to model.
            processChatNotifyActions(gui, section, preset.actions);

            // Check inventory after each section
            if (isInventoryFull(gui)) {
                if (!preset.onFullInventoryAction.equals("nothing")) {
                    performSafetyAction(gui, preset.onFullInventoryAction);
                    return Results.SUCCESS();
                }
            }

            // Everything's become maintained partway through the route - no point walking the rest.
            if (nothingLeftToPickUp(gui, preset)) {
                gui.msg("Forager: every item is now at its Maintain quantity - nothing left to do, stopping.");
                performSafetyAction(gui, preset.afterFinishAction);
                return Results.SUCCESS();
            }
        }

        // After completing all sections, perform finish action
        performSafetyAction(gui, preset.afterFinishAction);

        return Results.SUCCESS();
        } catch (InterruptedException e) {
            // Distinguish the watcher's own deliberate stop from a genuine external cancel, which
            // must keep propagating. Consuming (not just reading) the claim means a second,
            // unrelated interrupt - e.g. a real external cancel landing right after the guard's
            // own - won't be misattributed to this same guard firing a second time.
            Guard triggeredGuard = pendingGuard.getAndSet(null);
            if (triggeredGuard != null) {
                // Retry the safety action itself on further interrupts - it's the character's actual way home, it must not give up partway.
                InterruptedException last = null;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        triggeredGuard.outcome.perform(gui);
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
        } catch (RuntimeException e) {
            // Without this, an unexpected bug (like the section/waypoint-index mismatch that used
            // to crash here) kills the bot thread with zero in-game feedback - the character just
            // stops with no explanation, which is exactly what made that bug so hard to diagnose.
            // Rethrown after reporting so the console still gets the full stack trace as before.
            gui.error("Forager: unexpected error (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + ") - bot stopped. Check the java console log for the full stack trace.");
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

    // Max world-unit distance for a single PathFinder hop - PathFinder's search grid fails to path once the span exceeds this.
    private static final double MAX_HOP_DISTANCE = 250.0;

    // Scan radius (world units) for finding actionable gobs to detour towards.
    private static final double SCAN_RADIUS = 100000.0;

    // Gobs within this many world units (5 tiles) of the last one a detour paid to reach are swept for free - see collectUntilExhausted.
    private static final double CLUSTER_RADIUS = 5 * MCache.tilesz.x;

    // How close to stop when approaching a milestone anchor, without pathing onto its own tile.
    private static final double MILESTONE_APPROACH_DIST = 20.0;

    // Preset finish / full-inventory action: hearth home, unload into the configured areas, then hearth again to end at the fire.
    public static final String HEARTH_UNLOAD_HEARTH = "hearth, unload, hearth";

    // checkStamina drinks once stamina drops below DRINK_BELOW, back up to DRINK_TARGET - RestoreResources' own numbers.
    private static final double DRINK_BELOW = 0.5;
    private static final double DRINK_TARGET = 0.9;
    // After a failed drink (e.g. somewhere drinking doesn't work), checkStamina waits this long before trying again.
    private static final long DRINK_RETRY_MS = 60_000;

    /** The preset's waterMode toggle OR-ed with live coracle-mount state, so a route with a coracle leg doesn't need waterMode set for its whole length. */
    private boolean effectiveWaterMode(NGameUI gui, NForagerProp.PresetData preset) {
        boolean baseWaterMode = guardingProfile != null ? guardingProfile.waterMode : preset.waterMode;
        return baseWaterMode || CoracleBot.isPlayerInCoracle(gui);
    }

    /** Sorts lower Priority first (checked/collected before anything else in range), unset (-1)
     *  last; ties (including every unset pair) broken by distance ascending. */
    private int priorityRank(int priority) {
        return priority < 0 ? Integer.MAX_VALUE : priority;
    }

    /** True if there's at least one pickup action (PICK/FLOWER_ACTION/RIGHT_CLICK - not
     *  CHAT_NOTIFY, a separate scan-and-notify mechanism unrelated to Maintain) and every one of
     *  them has a Maintain cap set and already met, i.e. there's genuinely nothing left this run
     *  could collect. A preset with no pickup actions at all (e.g. used purely for waypoint steps
     *  or chat-notify scanning) never counts as "nothing to look for" here - that's a different,
     *  valid use case. Always false if Maintain limits are ignored, or if any action has no cap at
     *  all (unset always means "keep collecting"). */
    private boolean nothingLeftToPickUp(NGameUI gui, NForagerProp.PresetData preset) throws InterruptedException {
        if (preset.ignoreMaintainLimits) return false;
        boolean sawPickupAction = false;
        for (ForagerAction action : preset.actions) {
            if (action.actionType == ForagerAction.ActionType.CHAT_NOTIFY) continue;
            sawPickupAction = true;
            if (action.maintainQuantity < 0) return false;
            int areaStock = (action.sourceItemName != null) ? maintainAreaStock.getOrDefault(action.sourceItemName, 0) : 0;
            int carried = (action.sourceItemResource != null) ? countByResource(gui, action.sourceItemResource) : 0;
            if (areaStock + carried < action.maintainQuantity) return false;
        }
        return sawPickupAction;
    }

    /** Nearest unprocessed, constraint-passing gob (exclusion zone/leash/cliff/Maintain) matching any of the preset's actions, preferring lower Priority actions first, skipping any the look-ahead leaves for a later stop.
     *  With clusterEntry set, only gobs within CLUSTER_RADIUS of it count - a cluster-sweep hop, which ignores the look-ahead. */
    private Pair<Gob, ForagerAction> findNearestActionableGob(NGameUI gui, Coord2d from, java.util.List<ForagerAction> actions, Coord2d leashAnchor, boolean ignoreMaintainLimits, boolean waterMode,
                                                              RouteLookahead lookahead, Coord2d clusterEntry) throws InterruptedException {
        MiniMap.Location sessloc = (gui.mmap != null) ? gui.mmap.sessloc : null;
        MCache map = (gui.map != null && gui.map.glob != null) ? gui.map.glob.map : null;
        Coord2d searchCenter = clusterEntry != null ? clusterEntry : from;
        double searchRadius = clusterEntry != null ? CLUSTER_RADIUS : SCAN_RADIUS;
        RouteLookahead leaveForLater = clusterEntry != null ? null : lookahead;

        List<Pair<Gob, ForagerAction>> candidates = new ArrayList<>();
        Map<Long, Double> distByGobId = new HashMap<>();
        for (ForagerAction action : actions) {
            if (action.actionType == ForagerAction.ActionType.CHAT_NOTIFY) continue;
            if (!ignoreMaintainLimits && action.maintainQuantity >= 0) {
                int areaStock = (action.sourceItemName != null) ? maintainAreaStock.getOrDefault(action.sourceItemName, 0) : 0;
                int carried = (action.sourceItemResource != null) ? countByResource(gui, action.sourceItemResource) : 0;
                if (areaStock + carried >= action.maintainQuantity) {
                    continue;
                }
            }
            for (Gob gob : Finder.findGobs(searchCenter, action.toNAlias(), null, searchRadius)) {
                if (processedGobs.contains(gob.id)) continue;
                if (routeConstraints.isGobExcluded(sessloc, gob)) continue;
                if (!routeConstraints.withinLeash(leashAnchor, gob.rc)) continue;
                // While in water mode (mounted in a coracle), a land-bound gob is structurally
                // unreachable without dismounting first - skip it rather than waste a detour
                // attempt PathFinder can never actually complete.
                if (waterMode && map != null && !isOnOrNearWater(map, gob.rc)) continue;
                if (leaveForLater != null && leaveForLater.leaveForLaterStop(gob, map, sessloc, waterMode)) continue;
                candidates.add(new Pair<>(gob, action));
                distByGobId.put(gob.id, from.dist(gob.rc));
            }
        }
        candidates.sort((a, b) -> {
            int byPriority = Integer.compare(priorityRank(a.b.priority), priorityRank(b.b.priority));
            if (byPriority != 0) return byPriority;
            return Double.compare(distByGobId.get(a.a.id), distByGobId.get(b.a.id));
        });

        boolean ignoreBats = guardingProfile != null && guardingProfile.ignoreBats;
        // With avoidance on, the walk itself goes around animals - only an item inside a danger zone is out of reach.
        List<PathFinder.AvoidZone> zones = (avoidZones != null) ? avoidZones.get() : null;
        for (Pair<Gob, ForagerAction> candidate : candidates) {
            if (map != null && routeConstraints.cliffCorridorBlocked(map, from, candidate.a.rc)) continue;
            if (map != null && routeConstraints.landCorridorBlocked(map, from, candidate.a.rc, waterMode)) continue;
            if (routeConstraints.corridorExcluded(sessloc, from, candidate.a.rc)) continue;
            if (zones != null ? PathFinder.AvoidZone.anyContains(zones, candidate.a.rc)
                    : routeConstraints.dangerousAnimalNearCorridor(from, candidate.a.rc, ignoreBats)) continue;
            return candidate;
        }
        return null;
    }

    /** Whether target's own tile is water a coracle could actually reach - same tileset check NPFMap's water-mode grid and CoracleBot use; an unstreamed tile is treated as reachable rather than guessed at. */
    private boolean isOnOrNearWater(MCache map, Coord2d target) {
        Coord tc = target.div(MCache.tilesz).floor();
        try {
            return nurgling.pf.NPFMap.isValidWaterTileName(map.tilesetname(map.gettile(tc)));
        } catch (Loading l) {
            return true;
        }
    }

    /** Counts inventory items by underlying resource (not display name, which varies by growth stage) for Maintain. */
    private int countByResource(NGameUI gui, String resource) throws InterruptedException {
        return AreaStock.countByResource(gui.getInventory(), resource);
    }

    /** Resolves each Maintain-configured action's area-stock baseline once, summing across every visible area with the item as "Put". */
    private Map<String, Integer> resolveMaintainAreaStock(NGameUI gui, NForagerProp.PresetData preset) throws InterruptedException {
        Map<String, Integer> stock = new HashMap<>();
        if (gui.map == null || gui.map.glob == null || gui.map.glob.map == null) {
            return stock;
        }
        for (ForagerAction action : preset.actions) {
            if (action.maintainQuantity < 0 || action.sourceItemName == null || action.sourceItemResource == null) continue;

            int total = 0;
            int areasChecked = 0;
            // gui.map.nols filtered by !isDisabled(), not area.isVisible() (which requires the area's grid already loaded).
            for (Integer id : gui.map.nols.keySet()) {
                if (id <= 0) continue;
                NArea area = gui.map.glob.map.areas.get(id);
                if (area == null || area.isDisabled()) continue;
                if (area.containOut(action.sourceItemName)) {
                    areasChecked++;
                    total += AreaStock.countItemsInAreaContainers(gui, area, action.sourceItemResource);
                }
            }
            // Silent when no Put area is configured (the common "cap my carried inventory" case).
            if (areasChecked > 0) {
                gui.msg("Forager Maintain: \"" + action.sourceItemName + "\" - " + total +
                        " already stored (target " + action.maintainQuantity + ")");
            }
            stock.put(action.sourceItemName, total);
        }
        return stock;
    }

    /** One detour off the route: the trail home, what it may still spend, and the cluster it's sweeping. */
    private static final class Detour {
        final ArrayList<Coord2d> breadcrumbs = new ArrayList<>();
        final DetourBranchBudget budget;
        // Route point the detour left from - leash centre and look-ahead anchor, held fixed for the whole detour.
        final Coord2d anchor;
        final RouteLookahead lookahead;
        // Last gob this detour paid budget to reach; gobs within CLUSTER_RADIUS of it are swept for free.
        Coord2d clusterEntry = null;

        Detour(DetourBranchBudget budget, Coord2d anchor, RouteLookahead lookahead) {
            this.budget = budget;
            this.anchor = anchor;
            this.lookahead = lookahead;
        }
    }

    /** Repeatedly walks to the nearest unprocessed actionable gob, recording breadcrumbs for {@link #returnToPathViaBreadcrumbs}, until none remain or inventory fills; gobs a stop from firstStopSection on passes closer to are left for that stop. */
    private void collectNearbyActionableGobs(NGameUI gui, NForagerProp.PresetData preset, ForagerPath path, int firstStopSection) throws InterruptedException {
        Gob player = NUtils.player();
        Coord2d anchor = player != null ? player.rc : null;
        Detour detour = new Detour(
                new DetourBranchBudget(routeConstraints.maxBranches(), routeConstraints.maxBranchDistanceTiles()),
                anchor, lookaheadFrom(gui, path, firstStopSection, anchor));
        gui.activeBotDetourTrail = detour.breadcrumbs;
        boolean interrupted = false;
        try {
            collectUntilExhausted(gui, preset, detour);
        } catch (InterruptedException e) {
            interrupted = true;
            throw e;
        } finally {
            if (interrupted) {
                gui.activeBotDetourTrail = null;
                gui.activeBotDetourTarget = null;
            } else {
                returnToPathViaBreadcrumbs(gui, preset, detour);
                gui.activeBotDetourTrail = null;
                gui.activeBotDetourTarget = null;
            }
        }
    }

    /** Grabs everything actionable in range, rescanning after each pickup, until nothing's found or inventory fills; shared by the outbound pass and the return walk.
     *  The budget pays for reaching each cluster - the rest of that cluster is swept first, for free, even once the budget is spent. */
    private void collectUntilExhausted(NGameUI gui, NForagerProp.PresetData preset, Detour detour) throws InterruptedException {
        while (true) {
            if (isInventoryFull(gui)) return;
            checkStamina(gui);
            if (sweepCluster(gui, preset, detour)) continue;
            if (!detour.budget.canBranch()) return;

            Gob player = NUtils.player();
            if (player == null) return;

            Pair<Gob, ForagerAction> nearest = findNearestActionableGob(gui, player.rc, preset.actions, detour.anchor, preset.ignoreMaintainLimits, effectiveWaterMode(gui, preset), detour.lookahead, null);
            if (nearest == null) {
                gui.activeBotDetourTarget = null;
                return;
            }
            gui.activeBotDetourTarget = nearest.a.rc;

            if (player.rc.dist(nearest.a.rc) > MAX_HOP_DISTANCE) {
                // Heading off to a new cluster - the one behind stops being free to come back to.
                detour.clusterEntry = null;
                // Too far for one PathFinder call - hop toward it; stop the whole pass if a hop fails rather than retrying forever.
                if (!walkInHops(gui, preset, nearest.a.rc, detour, null)) return;
                continue;
            }

            payAndPick(gui, preset, detour, player.rc, nearest);
        }
    }

    /** Picks the nearest gob within CLUSTER_RADIUS of the detour's cluster entry, without spending budget or consulting the look-ahead; false if none is left. */
    private boolean sweepCluster(NGameUI gui, NForagerProp.PresetData preset, Detour detour) throws InterruptedException {
        if (detour.clusterEntry == null) return false;
        Gob player = NUtils.player();
        if (player == null) return false;
        Pair<Gob, ForagerAction> next = findNearestActionableGob(gui, player.rc, preset.actions, detour.anchor, preset.ignoreMaintainLimits, effectiveWaterMode(gui, preset), null, detour.clusterEntry);
        if (next == null) return false;
        gui.activeBotDetourTarget = next.a.rc;
        detour.breadcrumbs.add(player.rc);
        performGobAction(gui, next.b, next.a, preset);
        return true;
    }

    /** A hop the budget pays for; the gob it reaches becomes the cluster the following hops sweep for free. */
    private void payAndPick(NGameUI gui, NForagerProp.PresetData preset, Detour detour, Coord2d from, Pair<Gob, ForagerAction> target) throws InterruptedException {
        detour.budget.spend(from.dist(target.a.rc));
        detour.breadcrumbs.add(from);
        detour.clusterEntry = target.a.rc;
        performGobAction(gui, target.b, target.a, preset);
    }

    /** Walks toward target in MAX_HOP_DISTANCE hops, detouring to closer gobs along the way; with a detour it tracks branch state and sweeps each cluster it picks into, on the main route (detour null, routeLookahead used instead) it doesn't. */
    private boolean walkInHops(NGameUI gui, NForagerProp.PresetData preset, Coord2d target, Detour detour, RouteLookahead routeLookahead) throws InterruptedException {
        boolean detourEpisode = detour != null;
        Coord2d leashAnchor;
        RouteLookahead lookahead;
        if (detourEpisode) {
            leashAnchor = detour.anchor;
            lookahead = detour.lookahead;
        } else {
            // Anchor held fixed for this whole call, same as the detour-episode case - re-deriving
            // it from the current position every hop let repeated hops drift arbitrarily far from
            // the route, since each hop's leash check only ever bounded the next hop from wherever
            // the last one left off.
            Gob startPlayer = NUtils.player();
            if (startPlayer == null) return false;
            leashAnchor = startPlayer.rc;
            lookahead = routeLookahead;
        }
        while (true) {
            if (isInventoryFull(gui)) return false;
            checkStamina(gui);
            if (detourEpisode) {
                if (sweepCluster(gui, preset, detour)) continue;
                if (!detour.budget.canBranch()) return false;
            }

            Gob player = NUtils.player();
            if (player == null) return false;

            double remaining = player.rc.dist(target);
            if (remaining <= MAX_HOP_DISTANCE) return true;

            Pair<Gob, ForagerAction> nearest = findNearestActionableGob(gui, player.rc, preset.actions, leashAnchor, preset.ignoreMaintainLimits, effectiveWaterMode(gui, preset), lookahead, null);
            if (nearest != null && player.rc.dist(nearest.a.rc) < remaining) {
                gui.activeBotDetourTarget = nearest.a.rc;
                if (detourEpisode) {
                    payAndPick(gui, preset, detour, player.rc, nearest);
                } else {
                    performGobAction(gui, nearest.b, nearest.a, preset);
                    gui.activeBotDetourTarget = null;
                }
                continue;
            }
            if (detourEpisode) {
                gui.activeBotDetourTarget = target;
            }

            // A hop point is arbitrary - out of any danger zone, so the hop plans around the zone instead of failing on it.
            Coord2d waypoint = outsideDangerZones(player.rc.add(target.sub(player.rc).norm(MAX_HOP_DISTANCE)));
            if (detourEpisode) {
                detour.budget.spend(player.rc.dist(waypoint));
                detour.breadcrumbs.add(player.rc);
            }
            if (!walk(gui, preset, new PathFinder(waypoint), !detourEpisode).IsSuccess()) {
                unstickAtCurrentPosition(gui, preset);
                return false;
            }
        }
    }

    /** Best-effort recovery from a failed hop that may have wedged the character against a gob's hitbox. */
    private void unstickAtCurrentPosition(NGameUI gui, NForagerProp.PresetData preset) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null) return;
        PathFinder unstick = new PathFinder(player.rc);
        unstick.waterMode = effectiveWaterMode(gui, preset);
        unstick.run(gui);
    }

    /** One of Forager's own walks: water mode, plus - while the "Dangerous animal" guard is on - planning around dangerous
     *  animals. A walk with no safe way around just fails; for a route leg the main loop then skips on to the next waypoint. */
    private Results walk(NGameUI gui, NForagerProp.PresetData preset, PathFinder pf, boolean routeLeg) throws InterruptedException {
        String what = routeLeg ? "route walk" : "pickup/detour walk";
        if (avoidZones != null) {
            stepOutOfDangerZone(gui, preset);
        }
        activity = what;
        pf.waterMode = effectiveWaterMode(gui, preset);
        pf.avoidZones = avoidZones;
        pf.learnedBlocks = stallSpots;
        Results result = pf.run(gui);
        activity = "stopped after " + what;
        if (routeLeg && pf.blockedByAvoidZones) {
            routeLegBlocked = true;
            gui.msg("Forager: a dangerous animal blocks the way to the next waypoint - skipping it");
            System.out.println("[ForagerAvoid] route leg blocked by a danger zone - skipping to the next waypoint");
        }
        return result;
    }

    /** If an animal has come within its danger zone of the player (it moved - walks never plan into one), backs straight away out
     *  of it first, instead of planning the next walk hugging the animal at its current distance, just outside the guard's trigger. */
    private void stepOutOfDangerZone(NGameUI gui, NForagerProp.PresetData preset) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null) return;
        for (PathFinder.AvoidZone z : avoidZones.get()) {
            if (z.contains(player.rc)) {
                Coord2d out = outsideDangerZones(z.pushOut(player.rc, MCache.tilesz.x));
                System.out.println(String.format("[ForagerAvoid] inside %s zone (dist %.0f of r=%.0f) - backing off to (%.0f,%.0f)",
                        z.label, z.dist(player.rc), z.r, out.x, out.y));
                activity = "backing off from " + z.label;
                PathFinder back = new PathFinder(out);
                back.waterMode = effectiveWaterMode(gui, preset);
                back.avoidZones = avoidZones;
                back.learnedBlocks = stallSpots;
                back.run(gui);
                return;
            }
        }
    }

    /** Testing aid: every dangerous animal near the player, against the guard trigger and zone radii, plus what the bot is doing. */
    private void logDangerTelemetry(NGameUI gui) {
        Gob player = gui.map.player();
        if (player == null || routeConstraints == null || guardingProfile == null) return;
        List<String> threats = routeConstraints.describeNearbyThreats(gui, guardingProfile.ignoreBats, player);
        if (threats.isEmpty()) return;
        System.out.println(String.format("[ForagerAvoid] t=%.1fs player=(%.0f,%.0f) pose=%s doing: %s | %s",
                (System.currentTimeMillis() - runStartMs) / 1000.0, player.rc.x, player.rc.y, player.pose(), activity,
                String.join(" | ", threats)));
    }

    /** p pushed out of any danger zone it falls in (a few passes, for overlapping zones); p itself while avoidance is off. */
    private Coord2d outsideDangerZones(Coord2d p) {
        if (avoidZones == null) return p;
        List<PathFinder.AvoidZone> zones = avoidZones.get();
        for (int pass = 0; pass < 4; pass++) {
            PathFinder.AvoidZone inside = null;
            for (PathFinder.AvoidZone z : zones) {
                if (z.contains(p)) {
                    inside = z;
                    break;
                }
            }
            if (inside == null) return p;
            p = inside.pushOut(p, MCache.tilesz.x);
        }
        return p;
    }

    /** The profile's "Dangerous animal" in-flight guard if it's enabled, else null. */
    private Guard resolveDangerGuard(GuardingProfile profile) {
        for (GuardEntry entry : profile.inflightGuards) {
            if ("dangerous_animal".equals(entry.guardId)) {
                return entry.toGuard();
            }
        }
        return null;
    }

    /** Walks the breadcrumb trail home, sweeping via collectUntilExhausted before each hop and jumping past breadcrumbs one hop can skip; keeps going even once inventory is full. */
    private void returnToPathViaBreadcrumbs(NGameUI gui, NForagerProp.PresetData preset, Detour detour) throws InterruptedException {
        ArrayList<Coord2d> breadcrumbs = detour.breadcrumbs;
        while (!breadcrumbs.isEmpty()) {
            collectUntilExhausted(gui, preset, detour);
            if (breadcrumbs.isEmpty()) return;

            Gob player = NUtils.player();
            if (player == null) return;

            // Oldest breadcrumb one PathFinder hop still reaches - everything newer is pickup spots with no need to retrace.
            int last = breadcrumbs.size() - 1;
            int oldestInReach = last;
            for (int k = 0; k < last; k++) {
                if (player.rc.dist(breadcrumbs.get(k)) <= MAX_HOP_DISTANCE) {
                    oldestInReach = k;
                    break;
                }
            }
            if (oldestInReach < last && hopTo(gui, preset, breadcrumbs.get(oldestInReach))) {
                breadcrumbs.subList(oldestInReach, breadcrumbs.size()).clear();
                continue;
            }
            // Retrace one breadcrumb, as before - nothing older is in reach, or the shortcut was blocked.
            hopTo(gui, preset, breadcrumbs.get(last));
            breadcrumbs.remove(last);
        }
    }

    /** One PathFinder walk to pos, shown as the detour target. */
    private boolean hopTo(NGameUI gui, NForagerProp.PresetData preset, Coord2d pos) throws InterruptedException {
        gui.activeBotDetourTarget = pos;
        return walk(gui, preset, new PathFinder(pos), false).IsSuccess();
    }

    /** Look-ahead over the stops from section firstSection on, where the main loop will gather, up to the first milestone - milestone sections don't gather, and beyond one positions are on the far side of a teleport. */
    private RouteLookahead lookaheadFrom(NGameUI gui, ForagerPath path, int firstSection, Coord2d anchor) {
        MiniMap.Location sessloc = gui.mmap != null ? gui.mmap.sessloc : null;
        ArrayList<Coord2d> stops = new ArrayList<>();
        for (int s = firstSection; s < path.getSectionCount(); s++) {
            ForagerSection section = path.getSection(s);
            if (section == null) continue;
            ForagerWaypoint toWp = path.waypoints.get(section.waypointIndex + 1);
            if (toWp.milestoneHash != null) break;
            Coord2d stop = sectionTarget(section, toWp, sessloc);
            // Every sub-section of a gap resolves to the same gap-end waypoint (see sectionTarget) - keep it once.
            if (stops.isEmpty() || stops.get(stops.size() - 1).dist(stop) > 0.5) {
                stops.add(stop);
            }
        }
        return new RouteLookahead(stops, anchor, routeConstraints);
    }

    /** Where the main loop walks for this section: its gap's end waypoint re-resolved against the live sessloc (a milestone crossing can shift the anchor the baked endPoint used), else the baked endPoint. */
    private Coord2d sectionTarget(ForagerSection section, ForagerWaypoint toWp, MiniMap.Location sessloc) {
        if (sessloc != null) {
            Coord2d fresh = toWp.toWorldCoord(sessloc);
            if (fresh != null) return fresh;
        }
        return section.endPoint;
    }

    /** Walks to and performs one action on a single gob, marking it processed once done. */
    private void performGobAction(NGameUI gui, ForagerAction action, Gob gob,
                                   NForagerProp.PresetData preset) throws InterruptedException {
        switch (action.actionType) {
            case PICK: {
                // Marks processed either way - an unreachable gob (e.g. on land while mounted in
                // a coracle) would otherwise keep getting re-picked as "nearest" forever.
                processedGobs.add(gob.id);
                if (!walk(gui, preset, new PathFinder(gob), false).IsSuccess()) break;
                new SelectFlowerAction("Pick", gob).run(gui);
                NUtils.getUI().core.addTask(new nurgling.tasks.WaitGobRemoval(gob.id));
                break;
            }
            case FLOWER_ACTION: {
                processedGobs.add(gob.id);
                if (!walk(gui, preset, new PathFinder(gob), false).IsSuccess()) break;
                SelectFlowerAction flowerAction = new SelectFlowerAction(action.toActionNameCandidates(), gob);
                flowerAction.run(gui);
                confirmActionName(action, flowerAction.getMatchedOpt());
                NUtils.getUI().core.addTask(new nurgling.tasks.WaitPose(NUtils.player(), "gfx/borka/idle"));
                break;
            }
            case RIGHT_CLICK: {
                // For objects with no flower menu - just gives the interaction a brief moment to register before moving on.
                NUtils.setSpeed(2);
                try {
                    processedGobs.add(gob.id);
                    if (!walk(gui, preset, new PathFinder(gob), false).IsSuccess()) break;
                    NUtils.rclickGob(gob);
                    NUtils.getUI().core.addTask(new nurgling.tasks.WaitTicks(30));
                } finally {
                    NUtils.setSpeed(1);
                }
                break;
            }
            default:
                // CHAT_NOTIFY never reaches here - findNearestActionableGob excludes it.
                break;
        }
    }

    /** Once a flower menu confirms which candidate action string was correct, narrows and persists it so future runs don't re-guess. */
    private void confirmActionName(ForagerAction action, String matched) {
        if (matched == null || matched.equals(action.actionName) || forageProp == null) {
            return;
        }
        action.actionName = matched;
        NForagerProp.set(forageProp);
    }

    /** CHAT_NOTIFY is a one-shot scan-and-notify action, not per-gob walk-to-and-interact, so it keeps its own per-section scan. */
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

    /** With QoL Auto-drink on, drinks back up to DRINK_TARGET once stamina drops below DRINK_BELOW. Only called
     *  between walks: AutoDrink itself stays paused while a bot runs, since a walk would cut the drink off. */
    private void checkStamina(NGameUI gui) throws InterruptedException {
        if (!(Boolean) NConfig.get(NConfig.Key.autoDrink)) return;
        double stamina = NUtils.getStamina();
        if (stamina < 0 || stamina >= DRINK_BELOW) return;
        if (gui.drinkMeter != null && gui.drinkMeter.getTotalDrinkable() <= 0) {
            if (!reportedNoWater) {
                reportedNoWater = true;
                gui.msg("Forager: out of water - can't drink, carrying on.");
            }
            return;
        }
        if (System.currentTimeMillis() - lastFailedDrinkMs < DRINK_RETRY_MS) return;
        activity = "drinking";
        if (!new Drink(DRINK_TARGET, false).run(gui).IsSuccess()) {
            lastFailedDrinkMs = System.currentTimeMillis();
        }
        activity = "stopped after drinking";
    }
    
    
    /** Runs a waypoint's attached steps; on failure dispatches onStepsFailAction. Returns true if the caller should return SUCCESS immediately. */
    private boolean runWaypointSteps(NGameUI gui, ForagerWaypoint wp) throws InterruptedException {
        if (wp.steps == null || wp.steps.isEmpty()) {
            return false;
        }
        activity = "waypoint steps";
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

    /** "nothing"/"logout"/"travel hearth"/HEARTH_UNLOAD_HEARTH dispatch for the preset's end-of-run action strings. The route is
     *  over by then, so the guard watcher is stopped first - a guard firing while unloading at home (standing at a chest, a
     *  villager passing) would otherwise interrupt it and hearth mid-unload. */
    private void performSafetyAction(NGameUI gui, String action) throws InterruptedException {
        if ("nothing".equals(action)) return;
        stopGuardWatcher();
        gui.msg("Forager: running safety action \"" + action + "\"");
        if (HEARTH_UNLOAD_HEARTH.equals(action)) {
            hearthUnloadHearth(gui);
        } else {
            GuardOutcome.fromId(action).perform(gui);
        }
        gui.msg("Forager: safety action \"" + action + "\" finished");
    }

    /** Stops the guard watcher and waits for it to exit, so it can't fire (and interrupt the bot thread) during an end-of-run action. */
    private void stopGuardWatcher() throws InterruptedException {
        if (threatWatcher != null) {
            threatWatcher.interrupt();
            threatWatcher.join(1000);
        }
    }

    /** HEARTH_UNLOAD_HEARTH: hearth home, unload into the configured areas (FreeInventory2), hearth again to end at the fire.
     *  Never unloads if the first hearth didn't land - that would unload wherever the route happened to end. */
    private void hearthUnloadHearth(NGameUI gui) throws InterruptedException {
        if (!GuardOutcome.travelHearth(gui).IsSuccess()) {
            gui.error("Forager: hearth travel failed - not unloading here");
            return;
        }
        if (!new FreeInventory2(new NContext(gui)).run(gui).IsSuccess()) {
            gui.msg("Forager: unloading didn't finish - hearthing back anyway");
        }
        GuardOutcome.travelHearth(gui);
    }
    
    /** Resolves the preset's own GuardingProfile, falling back to prop-level then GuardingProfile.withDefaults() rather than erroring. */
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

    /** Background thread polling in-flight guards independent of the bot thread; records the firing guard and interrupts the bot thread, but doesn't perform its outcome itself. */
    private Thread startGuardWatcher(NGameUI gui, GuardingProfile profile, Thread botThread) {
        List<Guard> guards = buildGuards(profile.inflightGuards);
        GuardContext ctx = new GuardContext(gui, profile.ignoreBats);
        // Bind the calling thread's NUI here too, mirroring BotExecutor.runAsync, so NConfig reads use this session's config.
        NUI boundUI = NUtils.getUI();
        Thread watcher = new Thread(() -> {
            if (boundUI != null) {
                nurgling.sessions.ThreadLocalUI.set(boundUI);
            }
            int tick = 0;
            try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    for (Guard guard : guards) {
                        if (guard.trigger.check(ctx)) {
                            // Only interrupt if this watcher actually won the claim - guards
                            // against a redundant interrupt if something else already has one pending.
                            if (pendingGuard.compareAndSet(null, guard)) {
                                gui.msg("Forager: " + guard.trigger.describe() + " (" + guard.outcome.id() + ")");
                                System.out.println("[ForagerAvoid] guard fired: " + guard.trigger.describe() + " (" + guard.outcome.id() + ")");
                                logDangerTelemetry(gui);
                                botThread.interrupt();
                            }
                            return;
                        }
                    }
                    // Testing aid: about once a second, where every nearby dangerous animal is relative to the player.
                    if (++tick % 3 == 0)
                        logDangerTelemetry(gui);
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    // Don't let one bad read kill the watcher for the rest of the run.
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
                    // Never a gate: an open one is empty space, so "walking to" it ends in the gateway, short of the waypoint -
                    // and that waypoint's steps (e.g. closing that very gate) would then run standing in it.
                    if (gob.id != NUtils.playerID() && gob.rc.dist(pos) <= radius && !(gob instanceof MapView.Plob) && gob.id > 0
                            && !GateDetector.isGate(gob)) {
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
