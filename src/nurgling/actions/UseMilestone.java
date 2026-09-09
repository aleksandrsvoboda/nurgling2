package nurgling.actions;

import haven.Button;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.Widget;
import haven.Window;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.bots.CoracleBot;
import nurgling.guarding.Guard;
import nurgling.guarding.GuardContext;
import nurgling.guarding.GuardEntry;
import nurgling.guarding.GuardingProfile;
import nurgling.routes.ForagerWaypoint;
import nurgling.tasks.WaitDuration;
import nurgling.tasks.WaitForGridChangeOrTimeout;
import nurgling.tasks.WaitTicks;
import nurgling.tools.Finder;
import nurgling.tools.MilestoneRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Right-clicks a milestone signpost and clicks its "Travel" button to teleport (see ForagerRouteMap.spliceMilestone()); only supports a dialog with exactly one Travel button, and validates arrival against the route's expected destination, falling back to TravelToHearthFire on mismatch. */
public class UseMilestone implements Action {

    private static final int DIALOG_WAIT_TICKS = 30;
    private static final long TRAVEL_TIMEOUT_MS = 20_000;

    // Extra settle time after grid-ready so nearby gobs finish syncing into OCache before a destination waypoint step (e.g. CoracleBot) looks for one.
    private static final long POST_TRAVEL_SETTLE_MS = 2_000;

    // Travel shows a "peek" preview and waits for a confirming click - held long enough to give the safety watchdog a chance to interrupt before the trip is confirmed.
    private static final long PEEK_WAIT_MS = 7_000;

    // How far from the route's recorded destination still counts as "arrived" - needs slack, but a genuinely broken route should land far outside it.
    private static final double WRONG_LOCATION_TOLERANCE = MCache.tilesz.x * 30;

    private final String milestoneHash;
    // The route's destination anchor for this splice; null skips post-arrival validation. Compared against actual landing, not MilestoneRegistry's current (possibly since-changed) data.
    private final ForagerWaypoint expectedDestination;
    // The bail-check reuses this profile's own "dangerous_animal"/"unknown_player" guard entries
    // (enabled flag, ignoreBats, and configured outcome) instead of hardcoding a trigger+outcome,
    // so it can't fire against a threat the user has deliberately disabled, and reacts the way
    // they've actually configured (not always "travel hearth").
    private final GuardingProfile guardingProfile;

    public UseMilestone(String milestoneHash, ForagerWaypoint expectedDestination, GuardingProfile guardingProfile) {
        this.milestoneHash = milestoneHash;
        this.expectedDestination = expectedDestination;
        this.guardingProfile = guardingProfile;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Map<String, Object> entry = MilestoneRegistry.getMilestone(milestoneHash);
        if (entry == null) {
            return Results.ERROR("Milestone not recorded: " + milestoneHash);
        }

        // By durable gob hash, not resource name - road milestones repeat, so a name search could find the wrong one.
        Gob milestoneGob = Finder.findGob(milestoneHash);
        if (milestoneGob == null) {
            return Results.ERROR("Milestone gob not found nearby: " + milestoneHash);
        }

        long beforeGridId = WaitForGridChangeOrTimeout.currentGridId(gui);

        NUtils.rclickGob(milestoneGob);
        NUtils.getUI().core.addTask(new WaitTicks(DIALOG_WAIT_TICKS));

        List<Button> travelButtons = new ArrayList<>();
        for (Widget w = gui.lchild; w != null; w = w.prev) {
            if (!(w instanceof Window)) {
                continue;
            }
            for (Button b : w.children(Button.class)) {
                if (b.text != null && "Travel".equals(b.text.text)) {
                    travelButtons.add(b);
                }
            }
        }
        if (travelButtons.isEmpty()) {
            return Results.ERROR("Milestone dialog/Travel button not found - did the dialog open?");
        }
        if (travelButtons.size() > 1) {
            gui.msg("Forager: this milestone has multiple paths - can't tell which Travel button "
                    + "matches the recorded destination yet, aborting.");
            return Results.ERROR("Multi-path milestone dialog not supported yet");
        }

        travelButtons.get(0).click();

        // Wait out the peek window (interruptible) - the peek previews the destination, so this is
        // our one chance to bail before actually landing there. Check the same "dangerous_animal"/
        // "unknown_player" guards the rest of the run already watches for, so this respects
        // whichever of them the user has enabled/disabled and whatever outcome they've configured,
        // instead of second-guessing that choice with a hardcoded trigger and outcome.
        NUtils.getUI().core.addTask(new WaitDuration(PEEK_WAIT_MS));

        Coord2d confirmPoint = new Coord2d(gui.map.getcc());
        Guard triggeredGuard = findTriggeredDangerGuard(gui);
        if (triggeredGuard != null) {
            gui.msg("Forager: bailing on milestone travel - " + triggeredGuard.trigger.describe()
                    + " detected at the destination. Cancelling.");
            NUtils.rclick(confirmPoint);
            triggeredGuard.outcome.perform(gui);
            return Results.SUCCESS();
        }

        NUtils.lclick(confirmPoint);

        NUtils.getUI().core.addTask(new WaitForGridChangeOrTimeout(gui, beforeGridId, TRAVEL_TIMEOUT_MS));

        // Unlike TravelToHearthFire, a milestone always crosses grids - a timeout with no grid change means the click never actually triggered a teleport.
        long afterGridId = WaitForGridChangeOrTimeout.currentGridId(gui);
        if (afterGridId == beforeGridId || afterGridId == -1) {
            gui.msg("Forager: clicked Travel but never detected a grid change - milestone travel likely failed.");
            return Results.ERROR("Milestone travel did not complete (no grid change detected)");
        }

        NUtils.getUI().core.addTask(new WaitDuration(POST_TRAVEL_SETTLE_MS));

        if (expectedDestination != null) {
            haven.MiniMap.Location sessloc = gui.mmap != null ? gui.mmap.sessloc : null;
            Coord2d expectedWorld = sessloc != null ? expectedDestination.toWorldCoord(sessloc) : null;
            Gob player = NUtils.player();
            boolean wrongPlace = expectedWorld == null || player == null
                    || player.rc.dist(expectedWorld) > WRONG_LOCATION_TOLERANCE;
            if (wrongPlace) {
                gui.msg("Forager: milestone travel didn't land near the route's recorded destination "
                        + "- route may be broken. Teleporting home.");
                if (CoracleBot.isPlayerInCoracle(gui)) {
                    new CoracleBot().run(gui);
                }
                return new TravelToHearthFire().run(gui);
            }
        }

        return Results.SUCCESS();
    }

    /** First enabled "dangerous_animal"/"unknown_player" guard (in that order) whose trigger currently fires, or null if neither is configured/enabled/triggered. */
    private Guard findTriggeredDangerGuard(NGameUI gui) throws InterruptedException {
        if (guardingProfile == null) {
            return null;
        }
        GuardContext safetyCtx = new GuardContext(gui, guardingProfile.ignoreBats);
        for (GuardEntry entry : guardingProfile.inflightGuards) {
            if (!"dangerous_animal".equals(entry.guardId) && !"unknown_player".equals(entry.guardId)) {
                continue;
            }
            Guard guard = entry.toGuard();
            if (guard == null) {
                continue; // disabled, or an unknown guard id
            }
            if (guard.trigger.check(safetyCtx)) {
                return guard;
            }
        }
        return null;
    }
}
