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
import nurgling.tasks.WaitDuration;
import nurgling.tasks.WaitForGridChangeOrTimeout;
import nurgling.tasks.WaitTicks;
import nurgling.tools.Finder;
import nurgling.tools.MilestoneRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Right-clicks a milestone (signpost) gob and clicks "Travel" in the dialog that opens, to
 * actually use a milestone spliced into a Forager route (see ForagerRouteMap.spliceMilestone()/
 * ForagerWaypoint.milestoneHash), rather than the normal straight-line PathFinder walk between
 * two waypoints.
 * <p>
 * The milestone dialog's Java class ships as a compiled server resource, not present in this
 * codebase (see the Signpost Travel Recording plan) - its "Travel" button is found generically,
 * the same way several other bot actions already click buttons on dialogs they don't have a
 * typed reference to (e.g. {@code TakeFromBarrel}, {@code NGameUI.getWindowWithButton}):
 * right-click the gob, wait, then search every open window's descendant {@link Button}s for one
 * whose visible text is "Travel".
 * <p>
 * <b>Only works for a milestone whose dialog shows exactly one "Travel" button.</b> A
 * multi-path milestone would need to know which path corresponds to the recorded destination,
 * and there's currently no way to tell them apart from outside the dialog (its buttons carry no
 * information beyond their label) - explicitly deferred, not guessed at.
 */
public class UseMilestone implements Action {

    private static final int DIALOG_WAIT_TICKS = 30;
    private static final long TRAVEL_TIMEOUT_MS = 20_000;

    // Extra settle time after WaitForGridChangeOrTimeout confirms the new grid's mesh/fog-of-war
    // is render-ready, before this returns. Mesh readiness isn't the same as nearby gobs having
    // finished syncing into OCache - a waypoint step attached to the destination anchor (e.g.
    // CoracleBot, needing to find/path to a specific gob right away) can otherwise start before
    // its target actually exists client-side yet, causing it to spin retrying instead of working
    // on the first try (reported live, on a route with exactly this shape - a coracle step on the
    // milestone's destination waypoint).
    private static final long POST_TRAVEL_SETTLE_MS = 2_000;

    // After clicking Travel, the game shows a "peek" preview of the destination (camera/view
    // moves there; the player's own Gob physically stays at the source) and waits for a
    // confirming left-click before the teleport actually completes - confirmed live, roughly
    // any tile works, same main map view. Started at 3s (the minimum needed for the click to be
    // accepted at all), bumped to 7s per direct request - not just click-timing margin, but
    // deliberately giving the safety watchdog more of this window to catch something and
    // interrupt before the trip is ever confirmed. Wall-clock, not tick-based (WaitTicks' tick
    // rate isn't reliable for a real-time duration - see WaitForGridChangeOrTimeout's own
    // reasoning), and a plain interruptible wait: if the watchdog interrupts this thread here,
    // the confirm click below is never sent, and since the character's body hasn't actually left
    // the source yet, nothing unsafe
    // has happened.
    private static final long PEEK_WAIT_MS = 7_000;

    private final String milestoneHash;

    public UseMilestone(String milestoneHash) {
        this.milestoneHash = milestoneHash;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Map<String, Object> entry = MilestoneRegistry.getMilestone(milestoneHash);
        if (entry == null) {
            return Results.ERROR("Milestone not recorded: " + milestoneHash);
        }

        // By durable gob hash (Finder.findGob(String), same identity MilestoneRegistry is keyed
        // by), not by resource name - road milestones are a repeated decoration type, so a
        // name-only search (Finder.findGob(NAlias)) can find a completely different, unrelated
        // milestone instead of the one actually recorded here.
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

        // Wait out the peek window (see PEEK_WAIT_MS) - interruptible, giving the safety
        // watchdog its chance - then send a plain left-click-to-walk at wherever the camera is
        // currently centered (NUtils.lclick, the same low-level "click this world point" primitive
        // several other bot actions already use for a bare walk-click) to confirm the travel.
        NUtils.getUI().core.addTask(new WaitDuration(PEEK_WAIT_MS));
        Coord2d confirmPoint = new Coord2d(gui.map.getcc());
        NUtils.lclick(confirmPoint);

        NUtils.getUI().core.addTask(new WaitForGridChangeOrTimeout(gui, beforeGridId, TRAVEL_TIMEOUT_MS));

        // WaitForGridChangeOrTimeout returns on EITHER a real grid change OR a plain timeout -
        // unlike TravelToHearthFire (which can legitimately land in the same grid, so a timeout
        // there is an expected outcome), a milestone almost always crosses a huge real-world
        // distance, so "timed out with no grid change" here means the click didn't actually
        // trigger a teleport (dialog closed without traveling, wrong button, server rejected it,
        // etc.) - report that explicitly instead of silently continuing as if it worked, which
        // otherwise leaves the bot stranded at the source and produces a confusing "can't find
        // path" several waypoints later instead of a clear failure right here.
        long afterGridId = currentGridId(gui);
        if (afterGridId == beforeGridId || afterGridId == -1) {
            gui.msg("Forager: clicked Travel but never detected a grid change - milestone travel likely failed.");
            return Results.ERROR("Milestone travel did not complete (no grid change detected)");
        }

        NUtils.getUI().core.addTask(new WaitDuration(POST_TRAVEL_SETTLE_MS));

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
