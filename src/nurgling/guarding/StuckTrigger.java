package nurgling.guarding;

import haven.Coord2d;
import haven.Gob;

/** Fires if the character hasn't moved more than a configured distance within a configured timeout; state lives on this per-Guard instance across check() calls. */
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
        // A channeled server action (harvesting, digging, crafting, ...) legitimately keeps the
        // character stationary - gui.prog is the same hourglass-progress widget those actions
        // already show, so its presence is an unambiguous "busy, not stuck" signal. Reset the
        // clock rather than let it keep counting through a long, perfectly healthy channel.
        if (ctx.gui != null && ctx.gui.prog != null) {
            lastPos = player.rc;
            lastMovedTime = System.currentTimeMillis();
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
