package nurgling.gattrr;

import haven.GAttrib;
import haven.Gob;
import haven.render.MixColor;
import haven.render.Pipe;

import java.awt.Color;

/**
 * Colour highlight applied to every gob of one resource type, set through the Ctrl+RMB
 * "Configure" window and stored per resource in {@link nurgling.tools.GobCustomize}.
 *
 * <p>Uses the same {@link MixColor} blend as the global item search highlight, so the colour's
 * alpha is the strength of the tint rather than a transparency: full alpha paints the object as a
 * flat silhouette, lower values let its own materials show through.
 */
public class NGobCustomTint extends GAttrib implements Gob.SetupMod {
    public final Color color;
    private final MixColor mix;

    public NGobCustomTint(Gob gob, Color color) {
        super(gob);
        this.color = color;
        // Built once: gobstate() is polled every tick by Gob.updstate().
        this.mix = new MixColor(color);
    }

    @Override
    public Pipe.Op gobstate() {
        return mix;
    }
}
