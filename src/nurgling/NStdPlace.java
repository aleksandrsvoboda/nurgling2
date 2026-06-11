package nurgling;

import haven.*;
import nurgling.tools.PlacementSnap;

/**
 * Placement adjuster that adds Ctrl-held object-to-object snapping on top of the
 * stock behaviour. Without Ctrl this defers entirely to {@link MapView.StdPlace}
 * (tile grid, or fine grid with Shift). With Ctrl held, the ghost snaps its
 * footprint edges flush against nearby objects; if no snap target is within
 * range it places freely (ungridded), and the current rotation is preserved
 * (no auto-face-player) so the snapped edge stays valid.
 *
 * @see PlacementSnap
 */
public class NStdPlace extends MapView.StdPlace {
    @Override
    public void adjust(MapView.Plob plob, Coord pc, Coord2d mc, int modflags) {
        if((modflags & UI.MOD_CTRL) != 0) {
            Coord2d snapped = PlacementSnap.snap(plob, mc);
            // snapped == null => footprint unresolved; fall back to free placement
            plob.move(snapped != null ? snapped : mc);
            return;
        }
        super.adjust(plob, pc, mc, modflags);
    }
}
