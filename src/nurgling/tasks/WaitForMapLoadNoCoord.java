package nurgling.tasks;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.NUtils;

public class WaitForMapLoadNoCoord extends NTask  {
    private final NGameUI gui;

    // Checks to wait for mesh/fog-of-war render-readiness before giving up on it (a grid whose cut/mesh never reports ready would otherwise hang this task forever).
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
