package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.conf.NAreaRad;
import nurgling.conf.NDiscordNotification;
import nurgling.conf.NForagerProp;
import nurgling.routes.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.NAlarmWdg;

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

        if (path.getSectionCount() == 0) {
            return Results.ERROR("Path has no sections");
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

            // Check if there are any target objects near the section endpoint (within 1 tile = 11 units)
            Coord2d sectionEnd = section.endPoint;
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

            // Process actions for this section
            processSection(gui, section, preset.actions, preset);

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
        }
    }

    private void processSection(NGameUI gui, ForagerSection section, java.util.List<ForagerAction> actions,
                                 NForagerProp.PresetData preset) throws InterruptedException {
        double radius = 250.0;

        // Use actions from preset, not from section
        for (ForagerAction action : actions) {
            processAction(gui, action, section.getCenterPoint(), radius, preset);
        }
    }
    
    private void processAction(NGameUI gui, ForagerAction action, Coord2d center, double radius, NForagerProp.PresetData preset) throws InterruptedException {
        ArrayList<Gob> gobs = Finder.findGobs(center, new NAlias(action.targetObjectPattern), null, radius);
        
        // Filter out already processed gobs
        gobs.removeIf(gob -> processedGobs.contains(gob.id));
        
        if (gobs.isEmpty()) {
            return;
        }
        
        
        switch (action.actionType) {
            case PICK:
                for (Gob gob : gobs) {
                    if (isInventoryFull(gui)) {
                        if (!preset.onFullInventoryAction.equals("nothing")) {
                            performSafetyAction(gui, preset.onFullInventoryAction);
                            return;
                        }
                        break;
                    }

                    PathFinder pfPick = new PathFinder(gob);
                    pfPick.waterMode = preset.waterMode;
                    pfPick.run(gui);
                    new SelectFlowerAction("Pick", gob).run(gui);
                    NUtils.getUI().core.addTask(new nurgling.tasks.WaitGobRemoval(gob.id));
                    
                    // Mark as processed
                    processedGobs.add(gob.id);
                }
                break;
                
            case FLOWER_ACTION:
                for (Gob gob : gobs) {
                    PathFinder pfFlower = new PathFinder(gob);
                    pfFlower.waterMode = preset.waterMode;
                    pfFlower.run(gui);
                    new SelectFlowerAction(action.actionName, gob).run(gui);
                    
                    // Wait for pose change
                    NUtils.getUI().core.addTask(new nurgling.tasks.WaitPose(NUtils.player(), "gfx/borka/idle"));
                    
                    // Mark as processed
                    processedGobs.add(gob.id);
                }
                break;
                
            case CHAT_NOTIFY:
                if (!gobs.isEmpty()) {
                    String message = String.format("Found %d %s objects!", gobs.size(), action.targetObjectPattern);
                    
                    // Send notification based on target
                    if (action.notifyTarget == ForagerAction.NotifyTarget.DISCORD) {
                        // Send Discord notification using general client settings
                        NDiscordNotification discordSettings = NDiscordNotification.get("general");
                        if (discordSettings != null && discordSettings.webhookUrl != null && !discordSettings.webhookUrl.isEmpty()) {
                            gui.msgToDiscord(discordSettings, message);
                        }
                    } else if (action.notifyTarget == ForagerAction.NotifyTarget.CHAT) {
                        // Send message to chat channel
                        if (action.chatChannelName != null && !action.chatChannelName.isEmpty()) {
                            // Find chat channel by name and send message
                            ChatUI.Channel targetChannel = findChatChannelByName(gui, action.chatChannelName);
                            if (targetChannel != null && targetChannel instanceof ChatUI.EntryChannel) {
                                ((ChatUI.EntryChannel) targetChannel).send(message);
                            }
                        }
                    }
                    
                    // Mark all notified objects as processed
                    for (Gob gob : gobs) {
                        processedGobs.add(gob.id);
                    }
                    
                    // Pause for 5 minutes (18000 frames at 60fps)
                    NUtils.getUI().core.addTask(new nurgling.tasks.WaitTicks(18000));
                    
                    // Signal to stop the bot after pause
                    throw new InterruptedException("CHAT_NOTIFY action triggered - stopping bot");
                }
                break;
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
     * danger, not after. Low energy and low/reduced hitpoints always resolve to "travel hearth",
     * regardless of {@code onAnimalAction}/{@code onPlayerAction} (including "nothing") -
     * unlike a nearby animal or player, which might just be passing through, a hungry or hurt
     * character is already in trouble, so this isn't something a preset should configure away.
     *
     * @return the safety action to perform ("logout"/"travel hearth"), or null if nothing was found
     */
    private String detectThreat(NGameUI gui, ArrayList<NAreaRad> animalRads,
                                 NForagerProp.PresetData preset) throws InterruptedException {
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

        if (!NAlarmWdg.borkas.isEmpty() && !preset.onPlayerAction.equals("nothing")) {
            return preset.onPlayerAction;
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
