package nurgling.widgets.cookbook;

import haven.*;
import nurgling.cookbook.CookbookModel;
import nurgling.cookbook.FepAttr;
import nurgling.i18n.L10n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The FEPs filter: a +1 and a +2 box for each attribute, the any/all/none mode, and check or
 * uncheck all. Clicking an attribute's name ticks or clears both of its tiers.
 */
public class FepFilterPanel extends Popover {
    public FepFilterPanel(PillButton owner, CookbookModel model) {
        super(owner);
        CText title = add(new CText(CookbookTheme.bold, CookbookTheme.fg).set(L10n.get("cookbook.fep.title")), Coord.of(PAD, PAD));
        ModeRadio mode = add(new ModeRadio(model::fepMode, model::setFepMode), Coord.of(PAD + title.sz.x + UI.scale(20), PAD));
        int y = PAD + Math.max(title.sz.y, mode.sz.y) + UI.scale(6);

        LinkButton all = add(new LinkButton(L10n.get("cookbook.check_all"), null, () -> model.setFepCodes(allCodes())), Coord.of(PAD, y));
        add(new LinkButton(L10n.get("cookbook.uncheck_all"), null, () -> model.setFepCodes(Collections.<String>emptySet())),
            Coord.of(all.c.x + all.sz.x + UI.scale(14), y));
        y += all.sz.y + UI.scale(6);

        int nameW = 0;
        for(FepAttr a : FepAttr.KNOWN)
            nameW = Math.max(nameW, CookbookTheme.bold.strsize(a.title).x);
        nameW += CookbookTheme.SWATCH + CookbookTheme.GAP + UI.scale(16);
        int tierW = UI.scale(60);
        for(FepAttr a : FepAttr.KNOWN) {
            add(new LinkButton(a.title, a.swatch(2), () -> toggleBoth(model, a)), Coord.of(PAD, y))
                .settip(L10n.get("cookbook.fep.both_tip", a.title));
            for(int tier = 1; tier <= 2; tier++) {
                String key = a.key(tier);
                add(new SwatchCheck(0, "+" + tier, a.swatch(tier), () -> model.fepCodes().contains(key), () -> model.toggleFep(key)),
                    Coord.of(PAD + nameW + (tier - 1) * tierW, y));
            }
            y += UI.scale(21);
        }

        int w = nameW + 2 * tierW;
        add(new Img(CookbookTheme.small.renderwrap(L10n.get("cookbook.fep.hint"), CookbookTheme.muted, w).tex()), Coord.of(PAD, y + UI.scale(4)));
        fit();
        mode.move(Coord.of(sz.x - PAD - mode.sz.x, PAD));
    }

    private static List<String> allCodes() {
        List<String> ret = new ArrayList<>();
        for(FepAttr a : FepAttr.KNOWN) {
            ret.add(a.key(1));
            ret.add(a.key(2));
        }
        return ret;
    }

    /* Ticks both tiers, or clears both when both are already ticked -- as the site does. */
    private static void toggleBoth(CookbookModel model, FepAttr a) {
        Set<String> next = new HashSet<>(model.fepCodes());
        boolean on = !(next.contains(a.key(1)) && next.contains(a.key(2)));
        for(int tier = 1; tier <= 2; tier++) {
            if(on)
                next.add(a.key(tier));
            else
                next.remove(a.key(tier));
        }
        model.setFepCodes(next);
    }
}
