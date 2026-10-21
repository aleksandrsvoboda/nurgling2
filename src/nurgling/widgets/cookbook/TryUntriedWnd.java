package nurgling.widgets.cookbook;

import haven.*;
import nurgling.actions.bots.TryUntriedVariations;
import nurgling.cookbook.Variations;
import nurgling.i18n.L10n;
import nurgling.tools.ItemIcons;
import nurgling.widgets.NDropbox;

import java.awt.Color;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Sets up the Try untried variations bot for one food: which choice is varied, the options that will
 * be tried, and for every other choice a drop-down of what to keep it at. Start hands the picks over.
 */
public class TryUntriedWnd extends Window {
    private static final int LABEL_W = UI.scale(150);
    private static final int BOX_W = UI.scale(200);
    private static final int ROW_H = UI.scale(22);
    private static final int ITEM_H = UI.scale(18);
    private static final int ICON = UI.scale(16);
    private static final Color WARN = new Color(255, 179, 107);

    /* One entry of a drop-down: an option, going without the input, or leaving it to autocraft. */
    private static final class Choice {
        final String label;
        /* The option's name, TryUntriedVariations.NONE, or null for autocraft's own pick. */
        final String value;
        final Variations.Option option;

        Choice(String label, String value, Variations.Option option) {
            this.label = label;
            this.value = value;
            this.option = option;
        }
    }

    private final Map<String, NDropbox<Choice>> boxes = new LinkedHashMap<>();

    /**
     * @param stocked whether the village's storage holds an option
     * @param onStart gets, per other choice's title, the option name or {@link TryUntriedVariations#NONE};
     *                a choice left to autocraft is absent
     */
    public TryUntriedWnd(Variations.Dish dish, String varied, Predicate<Variations.Option> stocked, Consumer<Map<String, String>> onStart) {
        super(Coord.z, L10n.get("cookbook.try.title", dish.name));
        Variations.Slot slot = null;
        for(Variations.Slot s : dish.slots) {
            if(varied.equals(s.title))
                slot = s;
        }
        int w = LABEL_W + BOX_W;
        int y = 0;
        y = text(L10n.get("cookbook.try.varying", varied), Text.std, y, w);
        List<String> tries = new ArrayList<>();
        if(slot != null) {
            for(Variations.Option o : slot.options) {
                if(!o.tried() && stocked.test(o))
                    tries.add(o.name);
            }
            if(slot.optional && !slot.noneTried)
                tries.add(L10n.get("cookbook.var.none"));
        }
        y = text(L10n.get("cookbook.try.list", String.join(", ", tries), String.valueOf(tries.size())), Text.std, y + UI.scale(2), w);
        y += UI.scale(10);

        List<Variations.Slot> others = new ArrayList<>();
        for(Variations.Slot s : dish.slots) {
            if(!s.wood() && (s != slot))
                others.add(s);
        }
        if(!others.isEmpty()) {
            y = text(L10n.get("cookbook.try.others"), Text.std, y, w) + UI.scale(4);
            for(Variations.Slot s : others) {
                add(new Label(CookbookTheme.ellipsize(Text.std, s.title, LABEL_W - UI.scale(8))), Coord.of(0, y + UI.scale(2)));
                List<Choice> choices = choices(s, stocked);
                NDropbox<Choice> box = add(new NDropbox<Choice>(BOX_W, Math.min(choices.size(), 12), ITEM_H) {
                    @Override
                    protected Choice listitem(int i) {
                        return choices.get(i);
                    }

                    @Override
                    protected int listitems() {
                        return choices.size();
                    }

                    @Override
                    protected void drawitem(GOut g, Choice c, int i) {
                        int x = UI.scale(2);
                        if(c.option != null) {
                            ItemIcons.draw(g, VariationsPanel.icon(c.option), Coord.of(x, (ITEM_H - ICON) / 2), ICON);
                            x += ICON + UI.scale(4);
                        }
                        g.text(c.label, Coord.of(x, (ITEM_H - Text.std.height()) / 2));
                    }
                }, Coord.of(LABEL_W, y));
                box.sel = choices.get(0);
                boxes.put(s.title, box);
                y += ROW_H;
                if(choices.get(0).value == null) {
                    Label warn = add(new Label(L10n.get("cookbook.try.none_stocked")), Coord.of(LABEL_W, y - UI.scale(2)));
                    warn.setcolor(WARN);
                    y += warn.sz.y;
                }
            }
            y += UI.scale(10);
        }

        Button start = add(new Button(UI.scale(100), L10n.get("cookbook.try.start")) {
            @Override
            public void click() {
                Map<String, String> picks = new LinkedHashMap<>();
                for(Map.Entry<String, NDropbox<Choice>> e : boxes.entrySet()) {
                    Choice c = e.getValue().sel;
                    if((c != null) && (c.value != null))
                        picks.put(e.getKey(), c.value);
                }
                onStart.accept(picks);
                TryUntriedWnd.this.reqdestroy();
            }
        }, Coord.of(w - UI.scale(210), y));
        start.settip(L10n.get("cookbook.try.start_tip"));
        add(new Button(UI.scale(100), L10n.get("cookbook.try.cancel")) {
            @Override
            public void click() {
                TryUntriedWnd.this.reqdestroy();
            }
        }, Coord.of(w - UI.scale(100), y));
        pack();
    }

    /* Stocked options, most used first, then going without it, then autocraft's pick. */
    private static List<Choice> choices(Variations.Slot s, Predicate<Variations.Option> stocked) {
        List<Choice> ret = new ArrayList<>();
        for(Variations.Option o : s.options) {
            if(o.tried() && stocked.test(o))
                ret.add(new Choice(o.name, o.name, o));
        }
        for(Variations.Option o : s.options) {
            if(!o.tried() && stocked.test(o))
                ret.add(new Choice(o.name + " " + L10n.get("cookbook.try.untried_mark"), o.name, o));
        }
        if(s.optional)
            ret.add(new Choice(L10n.get("cookbook.var.none"), TryUntriedVariations.NONE, null));
        ret.add(new Choice(L10n.get("cookbook.try.auto"), null, null));
        return ret;
    }

    private int text(String s, Text.Foundry f, int y, int w) {
        Img img = add(new Img(f.renderwrap(s, w).tex()), Coord.of(0, y));
        return y + img.sz.y;
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if((sender == this) && msg.equals("close"))
            reqdestroy();
        else
            super.wdgmsg(sender, msg, args);
    }
}
