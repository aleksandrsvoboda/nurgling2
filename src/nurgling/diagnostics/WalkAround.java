package nurgling.diagnostics;

import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.Coord;
import static haven.OCache.posres;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.tasks.NTask;

import java.util.Random;

/**
 * Benchmark walker for bot mode without a scenario: runs between random points near where the
 * character logged in, saving a movement/frame trace every {@link #TRACE_EVERY} seconds.
 */
public class WalkAround implements Action {
    private static final double RADIUS = 30 * MCache.tilesz.x;
    private static final double TRACE_EVERY = 11;
    private final double seconds;

    public WalkAround(double seconds) {
        this.seconds = seconds;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null)
            return Results.FAIL();
        Coord2d home = player.rc;
        Random rnd = new Random();
        NUtils.setSpeed(2);
        long end = System.currentTimeMillis() + (long) (seconds * 1000);
        long nextTrace = System.currentTimeMillis() + (long) (TRACE_EVERY * 1000);
        int legs = 0, failed = 0;
        while (System.currentTimeMillis() < end) {
            double a = rnd.nextDouble() * 2 * Math.PI, r = RADIUS * (0.5 + rnd.nextDouble() * 0.5);
            Coord2d target = home.add(Math.cos(a) * r, Math.sin(a) * r);
            if (walk(gui, target))
                legs++;
            else
                failed++;
            if (System.currentTimeMillis() >= nextTrace) {
                MovementTrace.trigger(gui.ui);
                nextTrace = System.currentTimeMillis() + (long) (TRACE_EVERY * 1000);
            }
        }
        System.out.println("[WalkAround] done: " + legs + " legs walked, " + failed + " unreachable");
        return Results.SUCCESS();
    }

    /* Clicks straight at the target and waits until the player arrives, stops moving, or 12 s pass. */
    private static boolean walk(NGameUI gui, Coord2d target) throws InterruptedException {
        gui.map.wdgmsg("click", Coord.z, target.floor(posres), 1, 0);
        long until = System.currentTimeMillis() + 12000;
        Coord2d last = null;
        int still = 0;
        while (System.currentTimeMillis() < until) {
            NUtils.addTask(new Ticks(10));
            Gob p = NUtils.player();
            if (p == null)
                return false;
            if (p.rc.dist(target) < 2)
                return true;
            still = (last != null && p.rc.dist(last) < 0.5) ? still + 1 : 0;
            if (still >= 6)
                return false;
            last = p.rc;
        }
        return false;
    }

    private static class Ticks extends NTask {
        private int left;

        Ticks(int n) {
            left = n;
        }

        @Override
        public boolean check() {
            return --left <= 0;
        }
    }
}
