package nurgling.widgets.cookbook;

import haven.*;
import nurgling.cookbook.CookbookModel;
import nurgling.i18n.L10n;

/** The Options panel: how favourites are treated, and a button that clears every filter. */
public class OptionsPanel extends Popover {
    public OptionsPanel(PillButton owner, CookbookModel model, Runnable clear) {
        super(owner);
        SwatchCheck first = add(new SwatchCheck(0, L10n.get("cookbook.opt.fav_first"), null,
                                                model::favoritesFirst, () -> model.setFavoritesFirst(!model.favoritesFirst())),
                                Coord.of(PAD, PAD));
        SwatchCheck only = add(new SwatchCheck(0, L10n.get("cookbook.opt.fav_only"), null,
                                               model::favoritesOnly, () -> model.setFavoritesOnly(!model.favoritesOnly())),
                               Coord.of(PAD, first.c.y + first.sz.y + UI.scale(4)));
        int w = Math.max(UI.scale(200), Math.max(first.sz.x, only.sz.x));
        add(new PillButton(L10n.get("cookbook.opt.clear"), false, clear).minWidth(w),
            Coord.of(PAD, only.c.y + only.sz.y + UI.scale(14)));
        fit();
    }
}
