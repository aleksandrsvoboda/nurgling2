package nurgling.widgets;

import haven.*;
import nurgling.NStyle;

/**
 * An icon checkbox clickable anywhere in a square around its icon, instead of only on the icon's
 * opaque pixels. Hovering outlines the clickable square.
 */
public class PaddedICheckBox extends ICheckBox {
    private final Coord pad;

    public PaddedICheckBox(Tex up, Tex down, Tex hoverup, Tex hoverdown, int pad) {
        super(up, down, hoverup, hoverdown);
        this.pad = Coord.of(pad, pad);
        resize(up.sz().add(this.pad.mul(2)));
    }

    @Override
    public void draw(GOut g) {
        if (h) {
            g.chcolor(NStyle.border);
            g.frect(Coord.z, Coord.of(sz.x, 1));
            g.frect(Coord.of(0, sz.y - 1), Coord.of(sz.x, 1));
            g.frect(Coord.z, Coord.of(1, sz.y));
            g.frect(Coord.of(sz.x - 1, 0), Coord.of(1, sz.y));
            g.chcolor();
        }
        super.draw(g.reclip(pad, sz.sub(pad.mul(2))));
    }

    @Override
    public boolean checkhit(Coord c) {
        return c.isect(Coord.z, sz);
    }
}
