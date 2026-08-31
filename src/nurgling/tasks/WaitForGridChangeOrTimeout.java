package nurgling.tasks;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.NUtils;

/**
 * Waits for a teleport-style travel (e.g. hearth fire) to actually land: either the player's
 * grid changes to a different grid that has finished rendering (a genuine teleport happened
 * and the destination is now usable), or a timeout elapses with no grid change at all - which
 * happens when the destination is close enough that travel doesn't cross into a new grid, so
 * there is nothing else to detect and wait for. Uses wall-clock time for the timeout rather
 * than a tick count, since tick rate varies 60-144Hz foregrounded vs 5Hz backgrounded and a
 * tick-based budget would make the same real-world wait take wildly different amounts of time
 * depending on window focus.
 */
public class WaitForGridChangeOrTimeout extends NTask {
    private final NGameUI gui;
    private final long beforeGridId;
    private final long deadline;

    public WaitForGridChangeOrTimeout(NGameUI gui, long beforeGridId, long timeoutMs) {
        this.gui = gui;
        this.beforeGridId = beforeGridId;
        this.deadline = System.currentTimeMillis() + timeoutMs;
    }

    @Override
    public boolean check() {
        boolean timedOut = System.currentTimeMillis() > deadline;

        if (NUtils.player() == null || NUtils.player().rc == null) {
            return timedOut;
        }

        Coord2d rc = NUtils.player().rc;
        Coord tc = rc.div(MCache.tilesz).floor();
        Coord gc = tc.div(gui.ui.sess.glob.map.cmaps);

        if (gui.ui.sess.glob.map.grids.get(gc) == null) {
            return timedOut;
        }

        long currentGridId = gui.ui.sess.glob.map.getgridt(tc).id;
        if (currentGridId != beforeGridId) {
            // Confirmed teleport - only declare done once the new grid is actually render-ready,
            // so callers don't act on a destination that hasn't finished loading yet. Falls back
            // to the timeout if the grid itself never reports ready, same reasoning as
            // WaitForMapLoadNoCoord's timeout.
            for (MCache.Grid grid : gui.map.glob.map.grids.values()) {
                if (grid.id == currentGridId) {
                    for (MCache.Grid.Cut cut : grid.cuts) {
                        if (cut.mesh.isReady() && cut.fo.isReady()) {
                            return true;
                        }
                    }
                }
            }
        }

        return timedOut;
    }
}
