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
import nurgling.tools.NParser;
import nurgling.widgets.NAlarmWdg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

public class Forager implements Action {

    private static final String PATH_AROUND = "path around";
    private HashSet<Long> processedGobs = new HashSet<>();
    private String presetName = null;

    private static class DangerousAnimalRule {
        final NAreaRad config;
        final NAlias alias;

        DangerousAnimalRule(NAreaRad config) {
            this.config = config;
            this.alias = new NAlias(config.name);
        }
    }

    private static class DangerousAnimal {
        final Gob gob;
        final DangerousAnimalRule rule;

        DangerousAnimal(Gob gob, DangerousAnimalRule rule) {
            this.gob = gob;
            this.rule = rule;
        }
    }

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
        try {

        // Get dangerous animal patterns from NConfig
        @SuppressWarnings("unchecked")
        ArrayList<NAreaRad> animalRads = (ArrayList<NAreaRad>) NConfig.get(NConfig.Key.animalrad);
        ArrayList<DangerousAnimalRule> dangerousAnimals = new ArrayList<>();
        if (animalRads != null) {
            for (NAreaRad rad : animalRads) {
                if (!(preset.ignoreBats && rad.name.toLowerCase().contains("bat"))) {
                    dangerousAnimals.add(new DangerousAnimalRule(rad));
                }
            }
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
        
        Results pathResult = runPathFinder(gui, new PathFinder(startPos), dangerousAnimals, preset);
        if (!pathResult.IsSuccess()) {
            return pathResult;
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

            
            // Check for dangerous players
            if (!NAlarmWdg.borkas.isEmpty()) {
                if (!preset.onPlayerAction.equals("nothing")) {
                    performSafetyAction(gui, preset.onPlayerAction);
                    return Results.SUCCESS();
                }
            }
            
            ArrayList<DangerousAnimal> visibleDanger = findDangerousAnimals(dangerousAnimals);
            if (shouldStopForAnimal(visibleDanger, preset)) {
                performSafetyAction(gui, preset.onAnimalAction);
                return Results.SUCCESS();
            }

            // Check if there are any target objects near the section endpoint (within 1 tile = 11 units)
            Coord2d sectionEnd = section.endPoint;
            Gob targetGob = findGobNear(sectionEnd, 11.0, visibleDanger, preset);

            if (targetGob != null)
            {
                // Go to the object if found within 1 tile
                pathResult = runPathFinder(gui, new PathFinder(targetGob), dangerousAnimals, preset);
            } else
            {
                // Go to the endpoint if no objects found nearby
                pathResult = runPathFinder(gui, new PathFinder(sectionEnd), dangerousAnimals, preset);
            }
            if (!pathResult.IsSuccess()) {
                return pathResult;
            }

            // Process actions for this section
            if (processSection(gui, section, preset.actions, dangerousAnimals, preset)) {
                return Results.SUCCESS();
            }

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
        } finally {
            gui.activeBotPath = null;
        }
    }
    
    private boolean processSection(NGameUI gui, ForagerSection section, java.util.List<ForagerAction> actions,
                                   ArrayList<DangerousAnimalRule> dangerousAnimals,
                                   NForagerProp.PresetData preset) throws InterruptedException {
        double radius = 250.0;
        
        // Use actions from preset, not from section
        for (ForagerAction action : actions) {
            // Check for safety before each action
            if (!NAlarmWdg.borkas.isEmpty() && !preset.onPlayerAction.equals("nothing")) {
                performSafetyAction(gui, preset.onPlayerAction);
                return true;
            }

            ArrayList<DangerousAnimal> visibleDanger = findDangerousAnimals(dangerousAnimals);
            if (shouldStopForAnimal(visibleDanger, preset)) {
                performSafetyAction(gui, preset.onAnimalAction);
                return true;
            }

            processAction(gui, action, section.getCenterPoint(), radius, dangerousAnimals,
                          visibleDanger, preset);
        }
        return false;
    }
    
    private void processAction(NGameUI gui, ForagerAction action, Coord2d center, double radius,
                               ArrayList<DangerousAnimalRule> dangerousAnimals,
                               ArrayList<DangerousAnimal> visibleDanger,
                               NForagerProp.PresetData preset) throws InterruptedException {
        ArrayList<Gob> gobs = Finder.findGobs(center, new NAlias(action.targetObjectPattern), null, radius);
        
        // Do not enter a hostile's configured safety circle to collect an item.
        gobs.removeIf(gob -> processedGobs.contains(gob.id) ||
                (isPathAround(preset) && isInsideDangerZone(gob.rc, visibleDanger)));
        
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

                    if (!runPathFinder(gui, new PathFinder(gob), dangerousAnimals, preset).IsSuccess()) {
                        continue;
                    }
                    if (isPathAround(preset) &&
                            isInsideDangerZone(gob.rc, findDangerousAnimals(dangerousAnimals))) {
                        continue;
                    }
                    new SelectFlowerAction("Pick", gob).run(gui);
                    NUtils.getUI().core.addTask(new nurgling.tasks.WaitGobRemoval(gob.id));
                    
                    // Mark as processed
                    processedGobs.add(gob.id);
                }
                break;
                
            case FLOWER_ACTION:
                for (Gob gob : gobs) {
                    if (!runPathFinder(gui, new PathFinder(gob), dangerousAnimals, preset).IsSuccess()) {
                        continue;
                    }
                    if (isPathAround(preset) &&
                            isInsideDangerZone(gob.rc, findDangerousAnimals(dangerousAnimals))) {
                        continue;
                    }
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
                gui.act("travel", "hearth");
                break;
            case PATH_AROUND:
            case "nothing":
            default:
                // Do nothing
                break;
        }
    }
    
    private PathFinder configurePathFinder(PathFinder pathFinder,
                                           ArrayList<DangerousAnimalRule> dangerousAnimals,
                                           NForagerProp.PresetData preset) throws InterruptedException {
        pathFinder.waterMode = preset.waterMode;
        if (isPathAround(preset)) {
            for (DangerousAnimal danger : findDangerousAnimals(dangerousAnimals)) {
                pathFinder.avoidGob(danger.gob, danger.rule.config.radius);
            }
        }
        return pathFinder;
    }

    private Results runPathFinder(NGameUI gui, PathFinder pathFinder,
                                  ArrayList<DangerousAnimalRule> dangerousAnimals,
                                  NForagerProp.PresetData preset) throws InterruptedException {
        return configurePathFinder(pathFinder, dangerousAnimals, preset).run(gui);
    }

    private boolean isPathAround(NForagerProp.PresetData preset) {
        return PATH_AROUND.equals(preset.onAnimalAction);
    }

    private boolean shouldStopForAnimal(ArrayList<DangerousAnimal> dangerousAnimals,
                                        NForagerProp.PresetData preset) {
        if ("nothing".equals(preset.onAnimalAction) || isPathAround(preset)) {
            return false;
        }
        Coord2d player = NUtils.player().rc;
        for (DangerousAnimal danger : dangerousAnimals) {
            if (danger.gob.rc.dist(player) < 200.0) {
                return true;
            }
        }
        return false;
    }

    private ArrayList<DangerousAnimal> findDangerousAnimals(
            ArrayList<DangerousAnimalRule> rules) throws InterruptedException {
        ArrayList<Gob> candidates = new ArrayList<>();
        synchronized (NUtils.getGameUI().ui.sess.glob.oc) {
            for (Gob gob : NUtils.getGameUI().ui.sess.glob.oc) {
                if (!(gob instanceof OCache.Virtual) && gob.id != NUtils.playerID() &&
                        !gob.attr.isEmpty() && !gob.getClass().getName().contains("GlobEffector")) {
                    candidates.add(gob);
                }
            }
        }

        ArrayList<DangerousAnimal> result = new ArrayList<>();
        for (Gob gob : candidates) {
            for (DangerousAnimalRule rule : rules) {
                boolean matches = gob.ngob != null && gob.ngob.name != null
                        ? NParser.checkName(gob.ngob.name, rule.alias)
                        : NParser.isIt(gob, rule.alias);
                if (matches) {
                    result.add(new DangerousAnimal(gob, rule));
                    break;
                }
            }
        }
        return result;
    }

    private boolean isInsideDangerZone(Coord2d pos, ArrayList<DangerousAnimal> dangerousAnimals) {
        for (DangerousAnimal danger : dangerousAnimals) {
            if (pos.dist(danger.gob.rc) <= danger.rule.config.radius) {
                return true;
            }
        }
        return false;
    }

    private Gob findGobNear(Coord2d pos, double radius,
                            ArrayList<DangerousAnimal> dangerousAnimals,
                            NForagerProp.PresetData preset) {
        synchronized (NUtils.getGameUI().ui.sess.glob.oc) {
            for (Gob gob : NUtils.getGameUI().ui.sess.glob.oc) {
                if (!(gob instanceof OCache.Virtual || gob.attr.isEmpty() || gob.getClass().getName().contains("GlobEffector"))) {
                    if (gob.id != NUtils.playerID() && gob.rc.dist(pos) <= radius && !(gob instanceof MapView.Plob) && gob.id > 0) {
                        if (isPathAround(preset) && isInsideDangerZone(gob.rc, dangerousAnimals)) {
                            continue;
                        }
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
