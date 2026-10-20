package nurgling.widgets;

import haven.*;
import nurgling.i18n.L10n;
import nurgling.widgets.cookbook.CookbookTheme;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** The craft window's [Manual | Autocraft] switch, square like the cookbook's [+1|+2] TierSwitch. */
public class CraftModeSwitch extends Widget {
    private static final int PAD = UI.scale(10);
    private final BooleanSupplier get;
    private final Consumer<Boolean> set;
    private final Tex[] lit = new Tex[2], dim = new Tex[2];
    private final int half;

    public CraftModeSwitch(BooleanSupplier get, Consumer<Boolean> set) {
        String[] labels = {L10n.get("craft.mode_manual"), L10n.get("craft.mode_auto")};
        int w = 0;
        for(int i = 0; i < 2; i++) {
            lit[i] = CookbookTheme.render(CookbookTheme.bold, labels[i], CookbookTheme.ink);
            dim[i] = CookbookTheme.render(CookbookTheme.bold, labels[i], CookbookTheme.fg);
            w = Math.max(w, lit[i].sz().x);
        }
        half = w + PAD * 2;
        resize(Coord.of(half * 2, UI.scale(20)));
        this.get = get;
        this.set = set;
        settip(L10n.get("craft.mode_tip"));
    }

    @Override
    public void draw(GOut g) {
        int sel = get.getAsBoolean() ? 1 : 0;
        for(int i = 0; i < 2; i++) {
            Coord ul = Coord.of(i * half, 0), csz = Coord.of((i == 0) ? half : sz.x - half, sz.y);
            if(i == sel)
                CookbookTheme.fill(g, ul, csz, CookbookTheme.accent);
            Tex t = (i == sel) ? lit[i] : dim[i];
            g.image(t, Coord.of(ul.x + (csz.x - t.sz().x) / 2, (sz.y - t.sz().y) / 2));
        }
        CookbookTheme.frame(g, Coord.z, sz, CookbookTheme.outline);
        CookbookTheme.fill(g, Coord.of(half, 0), Coord.of(Math.max(1, UI.scale(1)), sz.y), CookbookTheme.outline);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b == 1) {
            boolean auto = ev.c.x >= half;
            if(auto != get.getAsBoolean())
                set.accept(auto);
            return true;
        }
        return super.mousedown(ev);
    }
}
