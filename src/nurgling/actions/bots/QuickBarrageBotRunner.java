package nurgling.actions.bots;

import haven.Fightsess;
import haven.Fightview;
import haven.Gob;
import haven.Loading;
import haven.NFightsess;
import haven.Session;
import haven.Utils;
import nurgling.NGameUI;
import nurgling.conf.NCombatData;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.bots.QuickBarrageBotWnd;

import java.awt.Color;

/**
 * Alternates two combat moves against the current target: it spams Quick Barrage to
 * build the Cornered opening, and fires Full Circle once Cornered reaches the configured
 * threshold, going back to building when the opening decays.
 *
 * The two moves are located by resource name in the character's combat deck rather than
 * by a fixed slot number, so the deck can be arranged in any order.
 */
public class QuickBarrageBotRunner implements Runnable {
    public static final String BUILD_MOVE = "paginae/atk/barrage";
    public static final String FINISH_MOVE = "paginae/atk/fullcircle";

    /** Cornered has to fall this far below the threshold before building resumes. */
    private static final int DROP_THRESHOLD = 3;
    /**
     * Floor between two uses. The server only reports a move's real cooldown after it has
     * been used, so without a floor a whole round trip's worth of uses goes out at once.
     */
    private static final long MIN_USE_GAP_MS = 300;
    private static final long POLL_MS = 50;

    private final NGameUI gui;
    private final int targetThreshold;
    private final QuickBarrageBotWnd window;
    private volatile boolean enabled = true;
    private long lastUseTime = 0;
    private boolean buildingCornered = true;
    private String reportedIssue = null;

    public QuickBarrageBotRunner(NGameUI gui, int targetThreshold, QuickBarrageBotWnd window) {
        this.gui = gui;
        this.targetThreshold = targetThreshold;
        this.window = window;
    }

    @Override
    public void run() {
        try {
            while (enabled && !Thread.currentThread().isInterrupted()) {
                NFightsess fs = fightsess();
                if (fs == null || gui.fv == null || gui.fv.current == null) {
                    /* Between fights - keep waiting quietly rather than reporting it. */
                    Thread.sleep(POLL_MS);
                    continue;
                }

                Fightview.Relation current = gui.fv.current;
                if (isTargetDead(current)) {
                    report("Target down");
                    break;
                }

                int cornered = corneredValue(fs);
                if (buildingCornered) {
                    if (cornered >= targetThreshold)
                        buildingCornered = false;
                } else {
                    if (cornered <= (targetThreshold - DROP_THRESHOLD))
                        buildingCornered = true;
                }

                String want = buildingCornered ? BUILD_MOVE : FINISH_MOVE;
                int idx = findAction(fs, want);
                if (idx < 0) {
                    /* Nothing to do until the move is in the deck, but say so once - a bot that
                     * silently does nothing looks identical to one that is not running. */
                    report(moveName(want) + " is not in your combat deck");
                    Thread.sleep(POLL_MS);
                    continue;
                }

                report(null);
                long now = System.currentTimeMillis();
                if ((now - lastUseTime >= MIN_USE_GAP_MS) && ready(fs, idx)) {
                    fs.wdgmsg("use", idx, 1, 0);
                    lastUseTime = now;
                }

                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            /* Stop requested. */
        } finally {
            enabled = false;
            if (window != null)
                window.setRunning(false);
        }
    }

    /**
     * The live combat-session widget, or null when not in a fight. {@code gui.fsess} is
     * never cleared when the server destroys the widget, so the parent link is what says
     * whether it is still attached.
     */
    private NFightsess fightsess() {
        NFightsess fs = gui.fsess;
        return((fs != null && fs.parent != null) ? fs : null);
    }

    /** Cornered on the current target, 0-100. Already maintained per frame by NFightsess. */
    private int corneredValue(NFightsess fs) {
        return(fs.openings()[NCombatData.RED]);
    }

    /** Slot of the move with this resource name in the combat deck, or -1. */
    private static int findAction(Fightsess fs, String resnm) {
        if (fs.actions == null)
            return(-1);
        for (int i = 0; i < fs.actions.length; i++) {
            Fightsess.Action act = fs.actions[i];
            if (act == null || act.res == null)
                continue;
            String nm = null;
            if (act.res instanceof Session.CachedRes.Ref)
                nm = ((Session.CachedRes.Ref)act.res).resnm();
            if (nm == null) {
                try {
                    nm = act.res.get().name;
                } catch (Loading l) {
                    continue;
                }
            }
            if (resnm.equals(nm))
                return(i);
        }
        return(-1);
    }

    /** Whether the move in this slot is off cooldown, per the server's last "acool". */
    private static boolean ready(Fightsess fs, int idx) {
        Fightsess.Action act = fs.actions[idx];
        return((act == null) || (Utils.rtime() >= act.ct));
    }

    private boolean isTargetDead(Fightview.Relation rel) {
        if (rel == null)
            return(true);
        Gob target = Finder.findGob(rel.gobid);
        if (target == null)
            return(true);
        String pose = target.pose();
        return((pose != null) && NParser.checkName(pose, new NAlias("dead", "knock")));
    }

    /** Says each distinct problem once, so a stuck bot explains itself without flooding chat. */
    private void report(String issue) {
        if (issue == null) {
            reportedIssue = null;
            return;
        }
        if (issue.equals(reportedIssue))
            return;
        reportedIssue = issue;
        gui.msg("Quick Barrage Bot: " + issue, Color.YELLOW);
    }

    private static String moveName(String resnm) {
        return(BUILD_MOVE.equals(resnm) ? "Quick Barrage" : "Full Circle");
    }

    public void stop() {
        enabled = false;
    }
}
