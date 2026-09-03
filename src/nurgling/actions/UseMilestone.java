package nurgling.actions;

import haven.Button;
import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.Widget;
import haven.Window;
import nurgling.NGameUI;
import nurgling.NUtils;
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

    public UseMilestone(String milestoneHash, ForagerWaypoint expectedDestination) {
        this.milestoneHash = milestoneHash;
        this.expectedDestination = expectedDestination;
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

        long beforeGridId = currentGridId(gui);

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

        // Wait out the peek window (interruptible), then click-confirm at wherever the camera is centered.
        NUtils.getUI().core.addTask(new WaitDuration(PEEK_WAIT_MS));
        Coord2d confirmPoint = new Coord2d(gui.map.getcc());
        NUtils.lclick(confirmPoint);

        NUtils.getUI().core.addTask(new WaitForGridChangeOrTimeout(gui, beforeGridId, TRAVEL_TIMEOUT_MS));

        // Unlike TravelToHearthFire, a milestone always crosses grids - a timeout with no grid change means the click never actually triggered a teleport.
        long afterGridId = currentGridId(gui);
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
                return new TravelToHearthFire().run(gui);
            }
        }

        return Results.SUCCESS();
    }

    private static long currentGridId(NGameUI gui) {
        Gob player = NUtils.player();
        if (player == null || player.rc == null) {
            return -1;
        }
        Coord2d rc = player.rc;
        Coord tc = rc.div(MCache.tilesz).floor();
        Coord gc = tc.div(gui.ui.sess.glob.map.cmaps);
        if (gui.ui.sess.glob.map.grids.get(gc) == null) {
            return -1;
        }
        return gui.ui.sess.glob.map.getgridt(tc).id;
    }
}
