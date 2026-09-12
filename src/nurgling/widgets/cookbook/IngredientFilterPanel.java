package nurgling.widgets.cookbook;

import haven.*;
import nurgling.cookbook.CookbookModel;
import nurgling.i18n.L10n;

import java.util.*;
import java.util.List;

/**
 * The Ingredients filter: every ingredient and smoking wood of the loaded recipes, a box to
 * narrow the list, the any/all/none mode, and check or uncheck all. Ticked names stay at the top
 * of the list whatever the box says.
 */
public class IngredientFilterPanel extends Popover {
    private static final int LIST_W = UI.scale(300);
    private static final int LIST_H = UI.scale(220);
    private static final int ITEM_H = UI.scale(20);

    private final CookbookModel model;
    private final HintTextEntry find;
    private final CText count;
    private List<String> shown = Collections.emptyList();
    private int seenVersion = -1;

    public IngredientFilterPanel(PillButton owner, CookbookModel model) {
        super(owner);
        this.model = model;
        CText title = add(new CText(CookbookTheme.bold, CookbookTheme.fg).set(L10n.get("cookbook.ing.title")), Coord.of(PAD, PAD));
        ModeRadio mode = add(new ModeRadio(model::ingredientMode, model::setIngredientMode), Coord.of(PAD + title.sz.x + UI.scale(20), PAD));
        int y = PAD + Math.max(title.sz.y, mode.sz.y) + UI.scale(6);

        find = add(new HintTextEntry(LIST_W, L10n.get("cookbook.ing.find"), this::refilter), Coord.of(PAD, y));
        y += find.sz.y + UI.scale(4);
        count = add(new CText(CookbookTheme.small, CookbookTheme.muted), Coord.of(PAD, y));
        y += CookbookTheme.small.height() + UI.scale(4);

        LinkButton all = add(new LinkButton(L10n.get("cookbook.check_all"), null, () -> model.setIngredients(shown)), Coord.of(PAD, y));
        add(new LinkButton(L10n.get("cookbook.uncheck_all"), null, () -> model.setIngredients(Collections.<String>emptySet())),
            Coord.of(all.c.x + all.sz.x + UI.scale(14), y));
        y += all.sz.y + UI.scale(4);

        add(new NameList(Coord.of(LIST_W, LIST_H)), Coord.of(PAD, y));
        fit();
        mode.move(Coord.of(sz.x - PAD - mode.sz.x, PAD));
        refilter();
    }

    @Override
    public void syncFromModel() {
        if(seenVersion != model.version())
            refilter();
    }

    private void refilter() {
        /* The find box reports its first edit while this constructor is still running. */
        if((find == null) || (count == null))
            return;
        seenVersion = model.version();
        String q = find.text().trim().toLowerCase(Locale.ROOT);
        Set<String> chosen = model.ingredients();
        List<String> sel = new ArrayList<>(), rest = new ArrayList<>();
        for(String n : model.ingredientNames()) {
            if(chosen.contains(n))
                sel.add(n);
            else if(q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q))
                rest.add(n);
        }
        sel.addAll(rest);
        shown = sel;
        int total = model.ingredientNames().size();
        if(total == 0)
            count.set(L10n.get("cookbook.ing.none"));
        else if(q.isEmpty())
            count.set(String.valueOf(total));
        else
            count.set(L10n.get("cookbook.ing.count", String.valueOf(shown.size()), String.valueOf(total)));
    }

    private class NameList extends SListBox<String, Widget> {
        NameList(Coord sz) {
            super(sz, ITEM_H);
        }

        @Override
        protected List<String> items() {
            return shown;
        }

        @Override
        protected Widget makeitem(String name, int idx, Coord sz) {
            return new SwatchCheck(sz.x, name, null, () -> model.ingredients().contains(name), () -> model.toggleIngredient(name));
        }

        @Override
        protected void drawbg(GOut g, String item, int idx, Area area) {
        }

        @Override
        protected void drawsel(GOut g, String item, int idx, Area area) {
        }
    }
}
