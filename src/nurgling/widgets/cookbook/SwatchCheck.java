package nurgling.widgets.cookbook;

import haven.*;

import java.awt.Color;
import java.util.function.BooleanSupplier;

/**
 * A square checkbox with an optional colour swatch and a label. It reads its state from the
 * model each frame, so it never drifts from what the filters really are.
 */
public class SwatchCheck extends Widget {
    private final String text;
    private final Tex label;
    private final Color swatch;
    private final BooleanSupplier state;
    private final Runnable toggle;
    private final int natural;
    private boolean hover = false;

    /** @param w the width, or 0 to fit the label */
    public SwatchCheck(int w, String text, Color swatch, BooleanSupplier state, Runnable toggle) {
        super(Coord.z);
        this.text = text;
        this.label = CookbookTheme.render(CookbookTheme.body, text, CookbookTheme.fg);
        this.swatch = swatch;
        this.state = state;
        this.toggle = toggle;
        this.natural = labelX() + label.sz().x;
        resize(Coord.of((w > 0) ? w : natural, Math.max(label.sz().y, UI.scale(18))));
    }

    private int labelX() {
        int x = CookbookTheme.BOX + CookbookTheme.GAP;
        if(swatch != null)
            x += CookbookTheme.SWATCH + CookbookTheme.GAP;
        return x;
    }

    @Override
    public void draw(GOut g) {
        int box = CookbookTheme.BOX, by = (sz.y - box) / 2;
        CookbookTheme.frame(g, Coord.of(0, by), Coord.of(box, box), hover ? CookbookTheme.accent : CookbookTheme.outline);
        if(state.getAsBoolean()) {
            int in = UI.scale(3);
            CookbookTheme.fill(g, Coord.of(in, by + in), Coord.of(box - 2 * in, box - 2 * in), CookbookTheme.accent);
        }
        if(swatch != null) {
            int sx = box + CookbookTheme.GAP;
            CookbookTheme.fill(g, Coord.of(sx, (sz.y - CookbookTheme.SWATCH) / 2), Coord.of(CookbookTheme.SWATCH, CookbookTheme.SWATCH), swatch);
        }
        int x = labelX();
        GOut cg = g.reclip(Coord.of(x, 0), Coord.of(Math.max(sz.x - x, 0), sz.y));
        cg.image(label, Coord.of(0, (sz.y - label.sz().y) / 2));
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b == 1) {
            toggle.run();
            return true;
        }
        return super.mousedown(ev);
    }

    @Override
    public boolean mousehover(MouseHoverEvent ev, boolean hovering) {
        hover = hovering;
        return hovering;
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        /* Only a label that got cut off needs its full text. */
        return (natural > sz.x) ? text : null;
    }
}
