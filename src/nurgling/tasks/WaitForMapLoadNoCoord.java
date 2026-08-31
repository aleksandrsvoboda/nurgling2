package nurgling.tasks;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.NUtils;

public class WaitForMapLoadNoCoord extends NTask  {
    private final NGameUI gui;

    // Once the destination grid's data is confirmed present, mesh/fog-of-war render-readiness
    // is given this many checks to catch up before this just gives up waiting on it
    // specifically. Without this, a grid whose cut/mesh never reports ready (observed: moving
    // a hearth fire from a cave to open-sky surface terrain) hangs this task - and whatever bot
    // is blocked on it - forever, with no way out at all.
    private static final int RENDER_READY_TIMEOUT_CHECKS = 600;
    private int checkCount = 0;

    public WaitForMapLoadNoCoord(NGameUI gui) {
        this.gui = gui;
    }

    @Override
    public boolean check() {
        checkCount++;

        if(NUtils.player() == null) {
            return false;
        }

        if (NUtils.player().rc == null) {
            return false;
        } else {
            boolean canContinue = false;

            Coord2d rc = NUtils.player().rc;

            Coord tc = rc.div(MCache.tilesz).floor();
            Coord gc = tc.div(NUtils.getGameUI().ui.sess.glob.map.cmaps);

            if(NUtils.getGameUI().ui.sess.glob.map.grids.get(gc) == null) {
                return false;
            }

            MCache.Grid currentGrid = NUtils.getGameUI().ui.sess.glob.map.getgridt(tc);

            long currentGridId = currentGrid.id;

            MCache.Grid grid = gui.map.glob.map.findGrid(currentGridId);
            if (grid != null) {
                for(MCache.Grid.Cut cut : grid.cuts) {
                    canContinue = cut.mesh.isReady() && cut.fo.isReady();
                }
                return canContinue || checkCount > RENDER_READY_TIMEOUT_CHECKS;
            }

            return true;
        }
    }
}
