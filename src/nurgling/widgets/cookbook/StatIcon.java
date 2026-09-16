package nurgling.widgets.cookbook;

import haven.*;
import nurgling.cookbook.CookbookModel;
import nurgling.cookbook.FepAttr;
import nurgling.i18n.L10n;

/**
 * One of the nine attribute icons in the FEPs column header. Clicking it sorts the table by that
 * attribute, highest first, at the tier on the +1/+2 switch; clicking it again reverses the sort.
 */
public class StatIcon extends Widget {
    private final FepAttr attr;
    private final CookbookModel model;
    private boolean hover = false;

    public StatIcon(FepAttr attr, CookbookModel model) {
        super(UI.scale(new Coord(18, 18)));
        this.attr = attr;
        this.model = model;
    }

    @Override
    public void draw(GOut g) {
        TexI[] t = CookbookTheme.statIcon(attr);
        boolean active = (model.sortAttr() == attr);
        g.image(active ? t[2] : (hover ? t[1] : t[0]), Coord.z, sz);
        if(active)
            CookbookTheme.frame(g, Coord.z, sz, CookbookTheme.accent);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b == 1) {
            model.sortByStat(attr);
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
        return L10n.get("cookbook.tip.stat", attr.title, String.valueOf(model.statTier()));
    }
}
