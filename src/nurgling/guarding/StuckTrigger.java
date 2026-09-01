package nurgling.guarding;

import haven.Coord2d;
import haven.Gob;

/** Fires if the character hasn't moved more than a configured distance within a configured
 *  timeout - the most common real cause is PathFinder repeatedly retrying a cliff climb (or
 *  snagging on an object right at a cliff face) without ever giving up. All normal stationary
 *  moments (picking a flower, a brief gate wait) should finish well under a sane timeout.
 *  Tracked across check() calls on this instance, so state lives on the trigger, not the
 *  context - each Guard gets its own StuckTrigger instance for the run. */
public class StuckTrigger implements GuardTrigger {
    private final double distanceThreshold;
    private final long timeoutMs;
    private Coord2d lastPos = null;
    private long lastMovedTime = 0;

    public StuckTrigger(double distanceThreshold, long timeoutMs) {
        this.distanceThreshold = distanceThreshold;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public boolean check(GuardContext ctx) {
        Gob player = ctx.player();
        if (player == null) {
            return false;
        }
        if (lastPos == null || player.rc.dist(lastPos) > distanceThreshold) {
            lastPos = player.rc;
            lastMovedTime = System.currentTimeMillis();
            return false;
        }
        return System.currentTimeMillis() - lastMovedTime > timeoutMs;
    }

    @Override
    public String describe() {
        return "stuck in place for over " + (timeoutMs / 1000) + "s";
    }
}
