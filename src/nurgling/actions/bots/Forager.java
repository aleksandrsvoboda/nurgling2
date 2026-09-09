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

    // The Guard whose trigger fired, claimed atomically by the catch block below - a plain
    // boolean+reference pair here couldn't tell a genuine external cancel racing the watcher's own
    // interrupt apart from the watcher's own stop (both would just see the boolean already true).
    // compareAndSet on claim, getAndSet(null) on consume: at most one InterruptedException gets
    // attributed to a given guard firing, and any interrupt that arrives once this is empty again
    // is unambiguously external.
    private final java.util.concurrent.atomic.AtomicReference<Guard> pendingGuard = new java.util.concurrent.atomic.AtomicReference<>();

    // Resolved once near the top of run() - see resolveGuardingProfile().
    private GuardingProfile guardingProfile = null;

    // Set once run() has resolved it, so performGobAction() can persist a confirmed flower-menu action.
    private NForagerProp forageProp = null;

    // Per-run Maintain baseline (see ForagerAction.maintainQuantity), keyed by sourceItemName so an in-run Edit Pattern save can't orphan it.
    private Map<String, Integer> maintainAreaStock = new HashMap<>();

    // Route-geometry limits configured per-route in Forager Settings - see ForagerRouteConstraints.
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
        Thread threatWatcher = null;
        try {

        guardingProfile = resolveGuardingProfile(prop, preset);

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

        PathFinder pf = new PathFinder(startPos);
        pf.waterMode = effectiveWaterMode(gui, preset);
        Results startResult = pf.run(gui);

        // Only run this waypoint's steps if we actually reached it.
        if (startResult.IsSuccess() && runWaypointSteps(gui, path.waypoints.get(0))) {
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

            // A waypoint gap longer than ForagerPath.SECTION_LENGTH gets split across multiple
            // sections (see generateSections()), so the section-loop counter i is NOT the same
            // as the waypoint index - always go through section.waypointIndex instead.
            ForagerWaypoint fromWp = path.waypoints.get(section.waypointIndex);
            ForagerWaypoint toWp = path.waypoints.get(section.waypointIndex + 1);
            gui.activeBotWaypointIndex = section.waypointIndex + 1;
            if (fromWp.milestoneHash != null && fromWp.milestoneHash.equals(toWp.milestoneHash)) {
                // toWp validates we actually landed near the expected destination.
                Results milestoneResult = new UseMilestone(fromWp.milestoneHash, toWp, guardingProfile).run(gui);
                if (!milestoneResult.IsSuccess()) {
                    return milestoneResult;
                }
                if (runWaypointSteps(gui, toWp)) {
                    return Results.SUCCESS();
                }
                continue;
            }

            Coord2d sectionEnd = section.endPoint;
            // Re-resolve against the current sessloc every iteration - a milestone crossing can shift the anchor the baked endPoint used.
            MiniMap.Location currentSessloc = gui.mmap.sessloc;
            if (currentSessloc != null) {
                Coord2d freshEnd = toWp.toWorldCoord(currentSessloc);
                if (freshEnd != null) {
                    sectionEnd = freshEnd;
                }
            }

            // Detour to a known actionable gob first if it's closer than this section's own target.
            Gob playerBeforeWalk = NUtils.player();
            if (playerBeforeWalk != null) {
                Pair<Gob, ForagerAction> nearest = findNearestActionableGob(gui, playerBeforeWalk.rc, preset.actions, SCAN_RADIUS, playerBeforeWalk.rc, preset.ignoreMaintainLimits, effectiveWaterMode(gui, preset));
                if (nearest != null && playerBeforeWalk.rc.dist(nearest.a.rc) < playerBeforeWalk.rc.dist(sectionEnd)) {
                    collectNearbyActionableGobs(gui, preset);
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
                    PathFinder pfApproach = new PathFinder(approachPoint);
                    pfApproach.waterMode = effectiveWaterMode(gui, preset);
                    arrivedNearMilestone = pfApproach.run(gui).IsSuccess();
                } else if (milestoneGob != null) {
                    PathFinder pfApproach = new PathFinder(milestoneGob.rc);
                    pfApproach.waterMode = effectiveWaterMode(gui, preset);
                    arrivedNearMilestone = pfApproach.run(gui).IsSuccess();
                }
                if (arrivedNearMilestone && runWaypointSteps(gui, toWp)) {
                    return Results.SUCCESS();
                }
                continue;
            }

            // Check if there are any target objects near the section endpoint (within 1 tile = 11 units)
            Gob targetGob = findGobNear(sectionEnd, 11.0);

            // Cover the ground in rescanning hops first, so a gob revealed mid-section still gets detoured to.
            boolean reachedApproach = walkInHops(gui, preset, targetGob != null ? targetGob.rc : sectionEnd, null, null, null);

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
                pfGob.waterMode = effectiveWaterMode(gui, preset);
                Results pfGobResult = pfGob.run(gui);
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
                pfEnd.waterMode = effectiveWaterMode(gui, preset);
                Results pfEndResult = pfEnd.run(gui);
                arrivedAtWaypoint = pfEndResult.IsSuccess();
                if (!arrivedAtWaypoint) {
                    gui.msg("Forager debug: section " + i + " failed pathing to sectionEnd=" + sectionEnd
                            + " - waterMode=" + pfEnd.waterMode + " mounted=" + CoracleBot.isPlayerInCoracle(gui));
                    gui.activeBotFailedWaypoints.add(section.waypointIndex + 1);
                }
            }

            // Waypoint steps only run once we've actually reached the real waypoint - not on an
            // intermediate sub-section of a gap that got split across multiple sections.
            if (arrivedAtWaypoint && section.isLastInGap && runWaypointSteps(gui, toWp)) {
                return Results.SUCCESS();
            }

            // Repeatedly grab the nearest unprocessed actionable gob until nothing more is found nearby.
            collectNearbyActionableGobs(gui, preset);

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

    // How close to stop when approaching a milestone anchor, without pathing onto its own tile.
    private static final double MILESTONE_APPROACH_DIST = 20.0;

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

    /** Nearest unprocessed, constraint-passing gob (exclusion zone/leash/cliff/Maintain) matching any of the preset's actions within radius, preferring lower Priority actions first. */
    private Pair<Gob, ForagerAction> findNearestActionableGob(NGameUI gui, Coord2d from, java.util.List<ForagerAction> actions, double radius, Coord2d leashAnchor, boolean ignoreMaintainLimits, boolean waterMode) throws InterruptedException {
        MiniMap.Location sessloc = (gui.mmap != null) ? gui.mmap.sessloc : null;
        MCache map = (gui.map != null && gui.map.glob != null) ? gui.map.glob.map : null;

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
            for (Gob gob : Finder.findGobs(from, action.toNAlias(), null, radius)) {
                if (processedGobs.contains(gob.id)) continue;
                if (routeConstraints.isGobExcluded(sessloc, gob)) continue;
                if (!routeConstraints.withinLeash(leashAnchor, gob.rc)) continue;
                // While in water mode (mounted in a coracle), a land-bound gob is structurally
                // unreachable without dismounting first - skip it rather than waste a detour
                // attempt PathFinder can never actually complete.
                if (waterMode && map != null && !isOnOrNearWater(map, gob.rc)) continue;
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
        for (Pair<Gob, ForagerAction> candidate : candidates) {
            if (map != null && routeConstraints.cliffCorridorBlocked(map, from, candidate.a.rc)) continue;
            if (map != null && routeConstraints.landCorridorBlocked(map, from, candidate.a.rc, waterMode)) continue;
            if (routeConstraints.corridorExcluded(sessloc, from, candidate.a.rc)) continue;
            if (routeConstraints.dangerousAnimalNearCorridor(from, candidate.a.rc, ignoreBats)) continue;
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

    /** Repeatedly walks to the nearest unprocessed actionable gob, recording breadcrumbs for {@link #returnToPathViaBreadcrumbs}, until none remain or inventory fills. */
    private void collectNearbyActionableGobs(NGameUI gui, NForagerProp.PresetData preset) throws InterruptedException {
        ArrayList<Coord2d> breadcrumbs = new ArrayList<>();
        gui.activeBotDetourTrail = breadcrumbs;
        Gob player = NUtils.player();
        Coord2d leashAnchor = player != null ? player.rc : null;
        nurgling.actions.bots.forager.DetourBranchBudget budget =
                new nurgling.actions.bots.forager.DetourBranchBudget(routeConstraints.maxBranches(), routeConstraints.maxBranchDistanceTiles());
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

    /** Grabs everything actionable in range, rescanning after each pickup, until nothing's found or inventory fills; shared by the outbound pass and the return walk. */
    private void collectUntilExhausted(NGameUI gui, NForagerProp.PresetData preset, ArrayList<Coord2d> breadcrumbs,
                                        nurgling.actions.bots.forager.DetourBranchBudget budget, Coord2d leashAnchor) throws InterruptedException {
        while (true) {
            if (isInventoryFull(gui)) return;
            if (!budget.canBranch()) return;

            Gob player = NUtils.player();
            if (player == null) return;

            Pair<Gob, ForagerAction> nearest = findNearestActionableGob(gui, player.rc, preset.actions, SCAN_RADIUS, leashAnchor, preset.ignoreMaintainLimits, effectiveWaterMode(gui, preset));
            if (nearest == null) {
                gui.activeBotDetourTarget = null;
                return;
            }
            gui.activeBotDetourTarget = nearest.a.rc;

            if (player.rc.dist(nearest.a.rc) > MAX_HOP_DISTANCE) {
                // Too far for one PathFinder call - hop toward it; stop the whole pass if a hop fails rather than retrying forever.
                if (!walkInHops(gui, preset, nearest.a.rc, breadcrumbs, budget, leashAnchor)) return;
                continue;
            }

            budget.spend(player.rc.dist(nearest.a.rc));
            breadcrumbs.add(player.rc);
            performGobAction(gui, nearest.b, nearest.a, preset);
        }
    }

    /** Walks toward target in MAX_HOP_DISTANCE hops, detouring to closer gobs along the way; detour-episode mode (breadcrumbs/budget non-null) tracks branch state, main-route mode doesn't. */
    private boolean walkInHops(NGameUI gui, NForagerProp.PresetData preset, Coord2d target, ArrayList<Coord2d> breadcrumbs,
                                nurgling.actions.bots.forager.DetourBranchBudget budget, Coord2d leashAnchor) throws InterruptedException {
        boolean detourEpisode = breadcrumbs != null;
        if (!detourEpisode) {
            // Anchor held fixed for this whole call, same as the detour-episode case - re-deriving
            // it from the current position every hop let repeated hops drift arbitrarily far from
            // the route, since each hop's leash check only ever bounded the next hop from wherever
            // the last one left off.
            Gob startPlayer = NUtils.player();
            if (startPlayer == null) return false;
            leashAnchor = startPlayer.rc;
        }
        while (true) {
            if (isInventoryFull(gui)) return false;
            if (detourEpisode && !budget.canBranch()) return false;

            Gob player = NUtils.player();
            if (player == null) return false;

            double remaining = player.rc.dist(target);
            if (remaining <= MAX_HOP_DISTANCE) return true;

            Pair<Gob, ForagerAction> nearest = findNearestActionableGob(gui, player.rc, preset.actions, SCAN_RADIUS, leashAnchor, preset.ignoreMaintainLimits, effectiveWaterMode(gui, preset));
            if (nearest != null && player.rc.dist(nearest.a.rc) < remaining) {
                gui.activeBotDetourTarget = nearest.a.rc;
                if (detourEpisode) {
                    budget.spend(player.rc.dist(nearest.a.rc));
                    breadcrumbs.add(player.rc);
                }
                performGobAction(gui, nearest.b, nearest.a, preset);
                if (!detourEpisode) {
                    gui.activeBotDetourTarget = null;
                }
                continue;
            }
            if (detourEpisode) {
                gui.activeBotDetourTarget = target;
            }

            Coord2d waypoint = player.rc.add(target.sub(player.rc).norm(MAX_HOP_DISTANCE));
            if (detourEpisode) {
                budget.spend(player.rc.dist(waypoint));
                breadcrumbs.add(player.rc);
            }
            PathFinder hop = new PathFinder(waypoint);
            hop.waterMode = effectiveWaterMode(gui, preset);
            if (!hop.run(gui).IsSuccess()) {
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

    /** Retraces the breadcrumb trail home most-recent-first, sweeping via collectUntilExhausted before each hop; keeps going even once inventory is full. */
    private void returnToPathViaBreadcrumbs(NGameUI gui, ArrayList<Coord2d> breadcrumbs, NForagerProp.PresetData preset,
                                             nurgling.actions.bots.forager.DetourBranchBudget budget, Coord2d leashAnchor) throws InterruptedException {
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

    /** Walks to and performs one action on a single gob, marking it processed once done. */
    private void performGobAction(NGameUI gui, ForagerAction action, Gob gob,
                                   NForagerProp.PresetData preset) throws InterruptedException {
        switch (action.actionType) {
            case PICK: {
                PathFinder pfPick = new PathFinder(gob);
                pfPick.waterMode = effectiveWaterMode(gui, preset);
                // Marks processed either way - an unreachable gob (e.g. on land while mounted in
                // a coracle) would otherwise keep getting re-picked as "nearest" forever.
                processedGobs.add(gob.id);
                if (!pfPick.run(gui).IsSuccess()) break;
                new SelectFlowerAction("Pick", gob).run(gui);
                NUtils.getUI().core.addTask(new nurgling.tasks.WaitGobRemoval(gob.id));
                break;
            }
            case FLOWER_ACTION: {
                PathFinder pfFlower = new PathFinder(gob);
                pfFlower.waterMode = effectiveWaterMode(gui, preset);
                processedGobs.add(gob.id);
                if (!pfFlower.run(gui).IsSuccess()) break;
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
                    PathFinder pfRclick = new PathFinder(gob);
                    pfRclick.waterMode = effectiveWaterMode(gui, preset);
                    processedGobs.add(gob.id);
                    if (!pfRclick.run(gui).IsSuccess()) break;
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
    
    
    /** Runs a waypoint's attached steps; on failure dispatches onStepsFailAction. Returns true if the caller should return SUCCESS immediately. */
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

    /** "nothing"/"logout"/"travel hearth" dispatch for the non-Guard action strings, delegating to GuardOutcome. */
    private void performSafetyAction(NGameUI gui, String action) throws InterruptedException {
        if (!"nothing".equals(action)) {
            gui.msg("Forager: running safety action \"" + action + "\"");
            GuardOutcome.fromId(action).perform(gui);
            gui.msg("Forager: safety action \"" + action + "\" finished");
        }
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
            try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    for (Guard guard : guards) {
                        if (guard.trigger.check(ctx)) {
                            // Only interrupt if this watcher actually won the claim - guards
                            // against a redundant interrupt if something else already has one pending.
                            if (pendingGuard.compareAndSet(null, guard)) {
                                gui.msg("Forager: " + guard.trigger.describe() + " (" + guard.outcome.id() + ")");
                                botThread.interrupt();
                            }
                            return;
                        }
                    }
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
