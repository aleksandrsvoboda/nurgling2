package nurgling.widgets.cookbook;

import haven.*;
import nurgling.i18n.L10n;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** The [+1|+2] switch beside the stat icons: which tier a stat icon sorts by. */
public class TierSwitch extends Widget {
    private final IntSupplier get;
    private final IntConsumer set;
    private final Tex[] lit = new Tex[2], dim = new Tex[2];

    public TierSwitch(IntSupplier get, IntConsumer set) {
        super(UI.scale(new Coord(44, 18)));
        this.get = get;
        this.set = set;
        for(int i = 0; i < 2; i++) {
            lit[i] = CookbookTheme.render(CookbookTheme.bold, "+" + (i + 1), CookbookTheme.ink);
            dim[i] = CookbookTheme.render(CookbookTheme.bold, "+" + (i + 1), CookbookTheme.fg);
        }
        settip(L10n.get("cookbook.tip.tier"));
    }

    @Override
    public void draw(GOut g) {
        int half = sz.x / 2;
        for(int i = 0; i < 2; i++) {
            boolean sel = (get.getAsInt() == i + 1);
            Coord ul = Coord.of(i * half, 0), csz = Coord.of((i == 0) ? half : sz.x - half, sz.y);
            if(sel)
                CookbookTheme.fill(g, ul, csz, CookbookTheme.accent);
            Tex t = sel ? lit[i] : dim[i];
            g.image(t, Coord.of(ul.x + (csz.x - t.sz().x) / 2, (sz.y - t.sz().y) / 2));
        }
        CookbookTheme.frame(g, Coord.z, sz, CookbookTheme.outline);
        CookbookTheme.fill(g, Coord.of(half, 0), Coord.of(Math.max(1, UI.scale(1)), sz.y), CookbookTheme.outline);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b == 1) {
            set.accept((ev.c.x < sz.x / 2) ? 1 : 2);
            return true;
        }
        return super.mousedown(ev);
    }
}
