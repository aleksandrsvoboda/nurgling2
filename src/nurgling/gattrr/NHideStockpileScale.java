package nurgling.gattrr;

import haven.GAttrib;
import haven.Gob;
import haven.render.Location;
import haven.render.Pipe;

/**
 * Shrinks hide stockpiles so they stop blocking the view of everything behind them.
 * The hitbox overlay compensates for this scale so it still shows the real footprint.
 */
public class NHideStockpileScale extends GAttrib implements Gob.SetupMod {
    public final float scale;

    public NHideStockpileScale(Gob gob, float scale) {
        super(gob);
        this.scale = scale;
    }

    @Override
    public Pipe.Op gobstate() {
        return Location.scale(scale);
    }
}
