package nurgling.widgets.cookbook;

import haven.*;
import nurgling.cookbook.CookbookModel;
import nurgling.i18n.L10n;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The any / all / none choice of a filter panel. */
public class ModeRadio extends Widget {
    private static final CookbookModel.Mode[] MODES = CookbookModel.Mode.values();
    private static final int SPACING = UI.scale(10);

    private final Supplier<CookbookModel.Mode> get;
    private final Consumer<CookbookModel.Mode> set;
    private final Tex[] on = new Tex[MODES.length], off = new Tex[MODES.length];
    /* Left edge of each option, plus the right edge of the last. */
    private final int[] xs = new int[MODES.length + 1];

    public ModeRadio(Supplier<CookbookModel.Mode> get, Consumer<CookbookModel.Mode> set) {
        super(Coord.z);
        this.get = get;
        this.set = set;
        int x = 0, h = UI.scale(18);
        for(int i = 0; i < MODES.length; i++) {
            String t = L10n.get("cookbook.mode." + MODES[i].name().toLowerCase(Locale.ROOT));
            on[i] = CookbookTheme.render(CookbookTheme.body, t, CookbookTheme.fg);
            off[i] = CookbookTheme.render(CookbookTheme.body, t, CookbookTheme.muted);
            xs[i] = x;
            x += CookbookTheme.BOX + CookbookTheme.GAP + on[i].sz().x + SPACING;
            h = Math.max(h, on[i].sz().y);
        }
        xs[MODES.length] = x;
        resize(Coord.of(x - SPACING, h));
    }

    @Override
    public void draw(GOut g) {
        CookbookModel.Mode cur = get.get();
        int box = CookbookTheme.BOX, by = (sz.y - box) / 2;
        for(int i = 0; i < MODES.length; i++) {
            boolean sel = (cur == MODES[i]);
            CookbookTheme.frame(g, Coord.of(xs[i], by), Coord.of(box, box), sel ? CookbookTheme.accent : CookbookTheme.outline);
            if(sel) {
                int in = UI.scale(3);
                CookbookTheme.fill(g, Coord.of(xs[i] + in, by + in), Coord.of(box - 2 * in, box - 2 * in), CookbookTheme.accent);
            }
            Tex t = sel ? on[i] : off[i];
            g.image(t, Coord.of(xs[i] + box + CookbookTheme.GAP, (sz.y - t.sz().y) / 2));
        }
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b != 1)
            return super.mousedown(ev);
        for(int i = 0; i < MODES.length; i++) {
            if((ev.c.x >= xs[i]) && (ev.c.x < xs[i + 1]))
                set.accept(MODES[i]);
        }
        return true;
    }
}
