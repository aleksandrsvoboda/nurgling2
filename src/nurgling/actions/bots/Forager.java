package nurgling.actions.bots;

import haven.*;
import haven.res.ui.obj.buddy.Buddy;
import nurgling.*;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.conf.NAreaRad;
import nurgling.conf.NDiscordNotification;
import nurgling.conf.NForagerProp;
import nurgling.routes.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.NAlarmWdg;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

public class Forager implements Action {

    private HashSet<Long> processedGobs = new HashSet<>();
    private String presetName = null;

    // Set by the threat watcher just before it interrupts the bot thread, so run() can tell
    // "the watcher stopped me on purpose" apart from a genuine external cancel (e.g. the user
    // clicking the bot's stop button) - which must still propagate as a real interrupt.
    private volatile boolean threatStopTriggered = false;

    // The safety action the watcher detected a need for ("logout"/"travel hearth"), performed
    // by the bot thread itself after it's been interrupted - not by the watcher thread. See
    // detectThreat()'s javadoc for why the watcher only detects and never acts.
    private volatile String pendingSafetyAction = null;

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

        ForagerPath path = preset.foragerPath;

        if (path.waypoints == null || path.waypoints.size() < 2) {
            return Results.ERROR("Path has fewer than 2 waypoints");
        }

        gui.activeBotPath = path;
        Thread threatWatcher = null;
        try {

        // Get dangerous animal patterns (and their configured danger radius) from NConfig
        @SuppressWarnings("unchecked")
        ArrayList<NAreaRad> animalRads = (ArrayList<NAreaRad>) NConfig.get(NConfig.Key.animalrad);
        if (animalRads == null) {
            animalRads = new ArrayList<>();
        }

        // Runs continuously in the background for as long as this bot is active, so an
        // animal wandering into range - or an unknown player appearing - mid-walk (not just
        // between sections/actions) still triggers the safety action immediately.
        threatWatcher = startThreatWatcher(gui, animalRads, preset, Thread.currentThread());

        // If configured, ChunkNav-travel to the preset's start area first - this is what
        // lets the bot be started from anywhere (indoors, a different map entirely) rather
        // than requiring the player to already be standing near the recorded path. The path's
        // own waypoints are stored as persistent map-segment + tile coordinates
        // (ForagerWaypoint), which only resolve to a world position while already in that
        // exact segment - ChunkNav operates on a different (live, cross-cell) coordinate
        // system entirely, so this area is the bridge between the two: once physically
        // standing on/near it, the segment-based lookup below can resolve normally.
        if (preset.startAreaId >= 0) {
            NArea startArea = gui.map.glob.map.areas.get(preset.startAreaId);
            if (startArea == null) {
                return Results.ERROR("Forager: configured start area no longer exists");
            }
            gui.msg("Forager: traveling to start area \"" + startArea.name + "\"");
            if (!NUtils.navigateToArea(startArea, true)) {
                return Results.ERROR("Forager: failed to reach start area \"" + startArea.name + "\"");
            }
        }

        // ForagerSection geometry is expressed in world coordinates relative to sessloc,
        // which only make sense within the map segment sessloc anchors - so sections can only
        // be (re)computed correctly once actually standing on the path's segment. They were
        // already generated once, at load time (see ForagerPath's constructor calling
        // generateSections()), but that could easily have happened from an entirely different
        // segment (e.g. this preset's window was opened while indoors, before the start-area
        // travel above ever ran) - in which case that first pass produced zero sections, not
        // an error, just quietly nothing. Regenerate now that we're actually on the right
        // segment (whether we just arrived via the start-area travel above, or were already
        // there) so the section count and geometry checked below are trustworthy.
        path.generateSections();
        if (path.getSectionCount() == 0) {
            return Results.ERROR("Forager: could not resolve path waypoints from the current location " +
                    "(wrong map/segment - configure a start area, or begin the bot from near the path)");
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
        pf.waterMode = preset.waterMode;
        pf.run(gui);

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

            Coord2d sectionEnd = section.endPoint;

            // Before committing to the walk toward this section's target, check whether a
            // known actionable gob is already closer than the target itself - if so, detour
            // to collect it (and anything else nearby, via the rescan inside the collection
            // loop) before continuing toward the path. "Return to" here means back to
            // wherever the character is standing right now, since that's where the section
            // walk below still needs to depart from.
            Gob playerBeforeWalk = NUtils.player();
            if (playerBeforeWalk != null) {
                Pair<Gob, ForagerAction> nearest = findNearestActionableGob(playerBeforeWalk.rc, preset.actions, SCAN_RADIUS);
                if (nearest != null && playerBeforeWalk.rc.dist(nearest.a.rc) < playerBeforeWalk.rc.dist(sectionEnd)) {
                    collectNearbyActionableGobs(gui, preset);
                    if (isInventoryFull(gui) && !preset.onFullInventoryAction.equals("nothing")) {
                        performSafetyAction(gui, preset.onFullInventoryAction);
                        return Results.SUCCESS();
                    }
                }
            }

            // Check if there are any target objects near the section endpoint (within 1 tile = 11 units)
            Gob targetGob = findGobNear(sectionEnd, 11.0);

            if (targetGob != null)
            {
                // Go to the object if found within 1 tile
                PathFinder pfGob = new PathFinder(targetGob);
                pfGob.waterMode = preset.waterMode;
                pfGob.run(gui);
            } else
            {
                // Go to the endpoint if no objects found nearby
                PathFinder pfEnd = new PathFinder(sectionEnd);
                pfEnd.waterMode = preset.waterMode;
                pfEnd.run(gui);
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
                performSafetyAction(gui, pendingSafetyAction);
                gui.msg("Forager: stopped safely after safety action");
                return Results.SUCCESS();
            }
            throw e;
        } finally {
            if (threatWatcher != null) {
                threatWatcher.interrupt();
            }
            gui.activeBotPath = null;
            gui.activeBotDetourTrail = null;
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

    /**
     * Finds the nearest unprocessed gob matching any of the preset's PICK/FLOWER_ACTION/
     * RIGHT_CLICK actions within radius of the given position, along with which action it
     * matched. CHAT_NOTIFY is excluded - it's a one-shot "scan this area and notify" action,
     * not a per-gob walk-to-and-interact action, so it doesn't fit this "closest first" model
     * and keeps its own pass in {@link #processChatNotifyActions}.
     */
    private Pair<Gob, ForagerAction> findNearestActionableGob(Coord2d from, java.util.List<ForagerAction> actions, double radius) throws InterruptedException {
        Gob nearestGob = null;
        ForagerAction nearestAction = null;
        double nearestDist = Double.MAX_VALUE;
        for (ForagerAction action : actions) {
            if (action.actionType == ForagerAction.ActionType.CHAT_NOTIFY) continue;
            for (Gob gob : Finder.findGobs(from, new NAlias(action.targetObjectPattern), null, radius)) {
                if (processedGobs.contains(gob.id)) continue;
                double dist = from.dist(gob.rc);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestGob = gob;
                    nearestAction = action;
                }
            }
        }
        return nearestGob != null ? new Pair<>(nearestGob, nearestAction) : null;
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
     * safety action earlier this session (see {@link #detectThreat}'s javadoc); the same risk
     * applies here.
     */
    private void collectNearbyActionableGobs(NGameUI gui, NForagerProp.PresetData preset) throws InterruptedException {
        ArrayList<Coord2d> breadcrumbs = new ArrayList<>();
        gui.activeBotDetourTrail = breadcrumbs;
        boolean interrupted = false;
        try {
            collectUntilExhausted(gui, preset, breadcrumbs);
        } catch (InterruptedException e) {
            interrupted = true;
            throw e;
        } finally {
            if (interrupted) {
                gui.activeBotDetourTrail = null;
            } else {
                returnToPathViaBreadcrumbs(gui, breadcrumbs, preset);
                gui.activeBotDetourTrail = null;
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
    private void collectUntilExhausted(NGameUI gui, NForagerProp.PresetData preset,
                                        ArrayList<Coord2d> breadcrumbs) throws InterruptedException {
        while (true) {
            if (isInventoryFull(gui)) return;

            Gob player = NUtils.player();
            if (player == null) return;

            Pair<Gob, ForagerAction> nearest = findNearestActionableGob(player.rc, preset.actions, SCAN_RADIUS);
            if (nearest == null) return;

            if (player.rc.dist(nearest.a.rc) > MAX_HOP_DISTANCE) {
                // Too far for a single PathFinder call - hop towards it, opportunistically
                // detouring to anything closer found along the way. If it gives up partway
                // (a hop failed - a dead end), stop the whole pass rather than looping back
                // around to retry the same unreachable gob forever.
                if (!travelWithWaypoints(gui, preset, nearest.a.rc, breadcrumbs)) return;
                continue;
            }

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
    private boolean travelWithWaypoints(NGameUI gui, NForagerProp.PresetData preset, Coord2d target,
                                         ArrayList<Coord2d> breadcrumbs) throws InterruptedException {
        while (true) {
            if (isInventoryFull(gui)) return false;

            Gob player = NUtils.player();
            if (player == null) return false;

            double remaining = player.rc.dist(target);
            if (remaining <= MAX_HOP_DISTANCE) return true;

            Pair<Gob, ForagerAction> nearest = findNearestActionableGob(player.rc, preset.actions, SCAN_RADIUS);
            if (nearest != null && player.rc.dist(nearest.a.rc) < remaining) {
                breadcrumbs.add(player.rc);
                performGobAction(gui, nearest.b, nearest.a, preset);
                continue;
            }

            Coord2d waypoint = player.rc.add(target.sub(player.rc).norm(MAX_HOP_DISTANCE));
            breadcrumbs.add(player.rc);
            PathFinder hop = new PathFinder(waypoint);
            hop.waterMode = preset.waterMode;
            if (!hop.run(gui).IsSuccess()) return false;
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
    private void returnToPathViaBreadcrumbs(NGameUI gui, ArrayList<Coord2d> breadcrumbs,
                                             NForagerProp.PresetData preset) throws InterruptedException {
        while (!breadcrumbs.isEmpty()) {
            collectUntilExhausted(gui, preset, breadcrumbs);
            if (breadcrumbs.isEmpty()) return;

            Gob player = NUtils.player();
            if (player == null) return;

            Coord2d nextStop = breadcrumbs.get(breadcrumbs.size() - 1);
            PathFinder hop = new PathFinder(nextStop);
            hop.waterMode = preset.waterMode;
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
                pfPick.waterMode = preset.waterMode;
                pfPick.run(gui);
                new SelectFlowerAction("Pick", gob).run(gui);
                NUtils.getUI().core.addTask(new nurgling.tasks.WaitGobRemoval(gob.id));
                processedGobs.add(gob.id);
                break;
            }
            case FLOWER_ACTION: {
                PathFinder pfFlower = new PathFinder(gob);
                pfFlower.waterMode = preset.waterMode;
                pfFlower.run(gui);
                new SelectFlowerAction(action.actionName, gob).run(gui);
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
                    pfRclick.waterMode = preset.waterMode;
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
     * CHAT_NOTIFY is a one-shot "scan this section and notify if found" action, not a
     * per-gob walk-to-and-interact action - it doesn't fit collectNearbyActionableGobs'
     * nearest-first model at all, so it keeps its own simple per-section scan, same as before.
     */
    private void processChatNotifyActions(NGameUI gui, ForagerSection section,
                                           java.util.List<ForagerAction> actions) throws InterruptedException {
        for (ForagerAction action : actions) {
            if (action.actionType != ForagerAction.ActionType.CHAT_NOTIFY) continue;

            ArrayList<Gob> gobs = Finder.findGobs(section.getCenterPoint(), new NAlias(action.targetObjectPattern), null, MAX_HOP_DISTANCE);
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
    
    
    private void performSafetyAction(NGameUI gui, String action) throws InterruptedException {
        switch (action) {
            case "logout":
                gui.act("lo");
                break;
            case "travel hearth":
                // gui.act("travel", "hearth") only sends the request and returns immediately -
                // the character is still mid-channel/mid-teleport when this bot then reports
                // done, so whatever runs next in a chain starts (and can cancel the travel by
                // making the character move) before they've actually arrived home. Run the
                // dedicated TravelToHearthFire bot instead - it waits through the full pose
                // sequence and the resulting map load, so this doesn't return until the
                // character is genuinely home.
                new TravelToHearthFire().run(gui);
                break;
            case "nothing":
            default:
                // Do nothing
                break;
        }
    }
    
    /**
     * Starts a background thread that polls for threats (unknown players, dangerous animals)
     * for as long as the bot is running, independent of whatever the bot thread is doing at
     * the time - including mid-walk to a single distant gob, which the bot's own inline
     * checks (only run between sections/actions) would otherwise miss entirely. On detecting
     * a threat it only records which safety action is needed and interrupts the bot thread -
     * it does not perform the action itself. See {@link #detectThreat} for why.
     */
    private Thread startThreatWatcher(NGameUI gui, ArrayList<NAreaRad> animalRads,
                                       NForagerProp.PresetData preset, Thread botThread) {
        Thread watcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String action = detectThreat(gui, animalRads, preset);
                    if (action != null) {
                        pendingSafetyAction = action;
                        threatStopTriggered = true;
                        botThread.interrupt();
                        return;
                    }
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    // Don't let one bad read (e.g. a gob disappearing mid-check) kill the
                    // watcher for the rest of the bot's run.
                }
            }
        }, "ForagerThreatWatcher");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    // Same low-energy threshold Validator.java uses to gate every other bot's pre-flight
    // check (22% energy). Unconditional - fires regardless of HP or the co-factor thresholds
    // below.
    private static final double LOW_ENERGY_THRESHOLD = 0.22;

    // Minimum acceptable max soft hitpoints - below this the character's health pool itself
    // is too small to safely keep working, even at full.
    private static final int MIN_MAX_SOFT_HP = 80;

    // HP-loss trigger requires BOTH of these, not HP alone - a single nettle sting/bee
    // stitch dips HP but is harmless and shouldn't send the character home every time; it's
    // only worth interrupting the run when the character is both hurt AND already getting low
    // on energy (i.e. actually needs to head back soon regardless).
    private static final double HP_TRIGGER_THRESHOLD = 0.5;
    private static final double HP_TRIGGER_ENERGY_THRESHOLD = 0.8;

    // If the character hasn't moved more than this many world units in STUCK_TIMEOUT_MS,
    // treat it as stuck rather than let it retry indefinitely - the most common cause is
    // PathFinder repeatedly retrying a cliff climb (or snagging on an object right at a cliff
    // face) without ever giving up. All normal stationary moments (picking a flower, a brief
    // gate wait) finish well under this. Tracked across detectThreat() calls, so state lives
    // on the instance rather than as locals.
    private static final double STUCK_DISTANCE_THRESHOLD = 3.0;
    private static final long STUCK_TIMEOUT_MS = 10_000;
    private Coord2d lastStuckCheckPos = null;
    private long lastMovedTime = 0;

    /**
     * Checks for unknown players, dangerous animals, low energy, or reduced/low soft
     * hitpoints, returning which safety action is needed - but, deliberately, never performs
     * it. This runs on the watcher thread, in parallel with whatever the bot thread is doing;
     * if it ran a multi-second action like "travel hearth" here, the bot thread would keep
     * walking/acting the whole time (it hasn't been told to stop yet), and that movement can
     * cancel the hearth-travel channel it's supposed to be waiting for - which looked like the
     * bot's "running" indicator disappearing before the character actually got home. The
     * watcher's only job is to detect and interrupt; {@link #run} performs the actual action
     * after the interrupt has stopped the bot thread's own movement.
     * <p>
     * Each animal's trigger distance is three times its configured danger radius (Options >
     * Ring Settings) - the same per-species distances used for the on-screen warning circles,
     * just with extra margin since this is meant to pull the character out before real
     * danger, not after. Only animal detection is still gated by {@code onAnimalAction}
     * (including "nothing") - an animal might just be passing through. Low energy, low/
     * reduced soft hitpoints, and an unknown/hostile player are all unconditional and always
     * resolve to "travel hearth" regardless of preset settings - none of them are something a
     * preset should be able to configure away (an unknown player ignored because
     * {@code onPlayerAction} defaulted to "nothing" is exactly how the character got robbed).
     *
     * @return the safety action to perform ("logout"/"travel hearth"), or null if nothing was found
     */
    private String detectThreat(NGameUI gui, ArrayList<NAreaRad> animalRads,
                                 NForagerProp.PresetData preset) throws InterruptedException {
        Gob playerForStuckCheck = NUtils.player();
        if (playerForStuckCheck != null) {
            if (lastStuckCheckPos == null || playerForStuckCheck.rc.dist(lastStuckCheckPos) > STUCK_DISTANCE_THRESHOLD) {
                lastStuckCheckPos = playerForStuckCheck.rc;
                lastMovedTime = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastMovedTime > STUCK_TIMEOUT_MS) {
                gui.msg("Forager: stuck in place for over " + (STUCK_TIMEOUT_MS / 1000) + "s - traveling to hearth");
                return "travel hearth";
            }
        }

        double energy = NUtils.getEnergy();
        if (energy >= 0 && energy < LOW_ENERGY_THRESHOLD) {
            gui.msg("Forager: energy at " + Math.round(energy * 100) + "% (below " +
                    Math.round(LOW_ENERGY_THRESHOLD * 100) + "%) - traveling to hearth");
            return "travel hearth";
        }

        // Primary HP check: the meter's fill fraction, not the tip-text-derived raw numbers
        // (getCurrentHP/getMaxHP) - those depend on tooltip data that isn't guaranteed to
        // update during an unattended run and were confirmed NOT to (2026-08-18: character
        // was knocked out twice overnight while this bot kept running, because curHP/maxHP
        // had silently stayed at -1 the whole time). getHPFraction() reads the same always-
        // live bar value getEnergy()/getStamina() already rely on successfully.
        //
        // Requires energy also below HP_TRIGGER_ENERGY_THRESHOLD - a minor scrape (nettle
        // burn, bee stitch) dips HP but is harmless on its own and shouldn't send the
        // character home every time; only trip this when they're both hurt and already
        // getting low on energy.
        double hpFrac = NUtils.getHPFraction();
        if (hpFrac >= 0 && hpFrac < HP_TRIGGER_THRESHOLD && energy >= 0 && energy < HP_TRIGGER_ENERGY_THRESHOLD) {
            gui.msg("Forager: soft hitpoints at " + Math.round(hpFrac * 100) + "% and energy at " +
                    Math.round(energy * 100) + "% - traveling to hearth");
            return "travel hearth";
        }

        // Secondary check: max HP pool size. Best-effort only - falls back to skipped (not
        // blocking) if the tip data isn't available, same caveat as above.
        int maxHP = NUtils.getMaxHP();
        if (maxHP >= 0 && maxHP <= MIN_MAX_SOFT_HP) {
            gui.msg("Forager: max soft hitpoints only " + maxHP + " (at or below " +
                    MIN_MAX_SOFT_HP + ") - traveling to hearth");
            return "travel hearth";
        }

        // Unknown/hostile player detection - unconditional, always "travel hearth" regardless
        // of preset.onPlayerAction (including "nothing"), same reasoning as HP/energy above.
        // This preset field defaults to "nothing", so relying on it left the character
        // getting robbed by a stranger with zero response - not something a preset should be
        // able to leave silently disabled.
        //
        // NAlarmWdg.borkas (populated by NGob.java for every rendered player-character gob)
        // is every player nearby, not just unknown ones - a green/known-ally walking past
        // isn't a threat. Filter to the same "unknown or hostile" definition the alarm/arrow
        // system uses: no buddy-list entry at all, or a buddy in the white (unclassified) or
        // red (hostile) kin group.
        synchronized (NAlarmWdg.borkas) {
            for (Long id : NAlarmWdg.borkas) {
                Gob otherPlayer = Finder.findGob(id);
                if (otherPlayer == null) {
                    continue;
                }
                Buddy buddy = otherPlayer.getattr(Buddy.class);
                boolean unknownOrHostile;
                if (buddy == null || buddy.b == null) {
                    unknownOrHostile = true;
                } else {
                    Color groupColor = BuddyWnd.gc[buddy.b.group];
                    unknownOrHostile = groupColor.equals(Color.WHITE) || groupColor.equals(Color.RED);
                }
                if (unknownOrHostile) {
                    gui.msg("Forager: unknown/hostile player nearby - traveling to hearth");
                    return "travel hearth";
                }
            }
        }

        if (!preset.onAnimalAction.equals("nothing")) {
            Gob player = NUtils.player();
            if (player == null) {
                return null;
            }
            for (NAreaRad rad : animalRads) {
                if (preset.ignoreBats && rad.name.contains("bat")) {
                    continue;
                }
                // Ring Settings (NConfig.Key.animalrad) is a general "draw an awareness ring
                // around this critter" list, not a danger list - it includes plain Rat
                // (gfx/kritter/rat/rat) right alongside the genuinely aggressive Cave Rat
                // (gfx/kritter/rat/caverat). Plain Rat is harmless and shouldn't trip the
                // safety stop just because it happens to also have a configured ring.
                if (rad.name.equals("gfx/kritter/rat/rat")) {
                    continue;
                }
                double triggerDist = rad.radius * 3.0;
                Gob animal = Finder.findGob(player.rc, new NAlias(rad.name), null, triggerDist);
                if (animal != null) {
                    return preset.onAnimalAction;
                }
            }
        }

        return null;
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
