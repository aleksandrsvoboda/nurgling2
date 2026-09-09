package nurgling.tasks;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.NUtils;

/** Waits for a teleport-style travel to land (grid change + render-ready) or a wall-clock timeout, since a nearby destination may never cross into a new grid at all. */
public class WaitForGridChangeOrTimeout extends NTask {
    private final NGameUI gui;
    private final long beforeGridId;
    private final long deadline;

    public WaitForGridChangeOrTimeout(NGameUI gui, long beforeGridId, long timeoutMs) {
        this.gui = gui;
        this.beforeGridId = beforeGridId;
        this.deadline = System.currentTimeMillis() + timeoutMs;
    }

    /** Current grid id from the player's position, or -1 if the player or their grid isn't resolvable. Shared by every travel-completion check (milestone/hearth-fire) so a future fix to grid-id resolution only needs to land in one place. */
    public static long currentGridId(NGameUI gui) {
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

    @Override
    public boolean check() {
        boolean timedOut = System.currentTimeMillis() > deadline;

        long currentGridId = currentGridId(gui);
        if (currentGridId == -1) {
            return timedOut;
        }

        if (currentGridId != beforeGridId) {
            // Only declare done once the new grid is actually render-ready.
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
