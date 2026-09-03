package nurgling.tasks;

import haven.Coord;
import haven.Coord2d;
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
