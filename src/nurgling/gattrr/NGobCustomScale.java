package nurgling.gattrr;

import haven.GAttrib;
import haven.Gob;
import haven.render.Location;
import haven.render.Pipe;

/**
 * User-chosen display size for every gob of one resource type, set through the
 * Ctrl+RMB "Configure" window and stored per resource in
 * {@link nurgling.tools.GobCustomize}.
 *
 * <p>Deliberately a separate attribute from {@link NHideStockpileScale},
 * {@link NTreeDisplayScale} and {@link NCustomScale}: those are driven by their own
 * settings, and keeping them apart lets a per-type resize compose with them instead of
 * one silently replacing the other.
 */
public class NGobCustomScale extends GAttrib implements Gob.SetupMod {
    public final float scale;

    public NGobCustomScale(Gob gob, float scale) {
        super(gob);
        this.scale = scale;
    }

    @Override
    public Pipe.Op gobstate() {
        return Location.scale(scale);
    }
}
