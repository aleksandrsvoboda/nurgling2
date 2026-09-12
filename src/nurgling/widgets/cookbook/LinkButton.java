package nurgling.widgets.cookbook;

import haven.*;

import java.awt.Color;

/** Clickable text, optionally after a colour swatch, that turns orange on hover. */
public class LinkButton extends Widget {
    private final Tex normal, hot;
    private final Color swatch;
    private final Runnable action;
    private boolean hover = false;

    public LinkButton(String text, Color swatch, Runnable action) {
        super(Coord.z);
        this.normal = CookbookTheme.render(CookbookTheme.bold, text, CookbookTheme.fg);
        this.hot = CookbookTheme.render(CookbookTheme.bold, text, CookbookTheme.accent);
        this.swatch = swatch;
        this.action = action;
        int sw = (swatch != null) ? CookbookTheme.SWATCH + CookbookTheme.GAP : 0;
        resize(Coord.of(sw + normal.sz().x, Math.max(normal.sz().y, UI.scale(18))));
    }

    @Override
    public void draw(GOut g) {
        int x = 0;
        if(swatch != null) {
            CookbookTheme.fill(g, Coord.of(0, (sz.y - CookbookTheme.SWATCH) / 2), Coord.of(CookbookTheme.SWATCH, CookbookTheme.SWATCH), swatch);
            x = CookbookTheme.SWATCH + CookbookTheme.GAP;
        }
        Tex t = hover ? hot : normal;
        g.image(t, Coord.of(x, (sz.y - t.sz().y) / 2));
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b == 1) {
            action.run();
            return true;
        }
        return super.mousedown(ev);
    }

    @Override
    public boolean mousehover(MouseHoverEvent ev, boolean hovering) {
        hover = hovering;
        return hovering;
    }
}
