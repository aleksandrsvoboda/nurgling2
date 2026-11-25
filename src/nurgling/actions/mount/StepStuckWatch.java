package nurgling.actions.mount;

import haven.Coord2d;
import haven.Gob;
import nurgling.NGameUI;

import java.util.function.BooleanSupplier;

/**
 * A GoTo abort check for walking on horseback: true once the character hasn't moved for {@link #STUCK_MS}.
 *
 * <p>Mounted, GoTo waits for the horse to leave its moving poses, and a horse standing still against something
 * can keep such a pose indefinitely (measured: a 60 s hang), so the wait never ends by itself. Polled on the
 * task thread; ending the step lets the pathfinder count it as reached or re-plan.
 */
public final class StepStuckWatch implements BooleanSupplier {
    private static final long STUCK_MS = 3000;

    private final NGameUI gui;
    private Coord2d lastPos = null;
    private long lastMove = System.currentTimeMillis();
    private volatile boolean stuck = false;

    public StepStuckWatch(NGameUI gui) {
        this.gui = gui;
    }

    public boolean stuck() {
        return stuck;
    }

    @Override
    public boolean getAsBoolean() {
        if (stuck)
            return true;
        Gob player = gui.map.player();
        if (player == null)
            return false;
        long now = System.currentTimeMillis();
        // A horse pressed against something wobbles about a unit at a time without getting anywhere.
        if (lastPos == null || player.rc.dist(lastPos) > 2) {
            lastPos = player.rc;
            lastMove = now;
        } else if (now - lastMove > STUCK_MS) {
            stuck = true;
            return true;
        }
        return false;
    }
}
