package nurgling.widgets;

import haven.CheckBox;
import haven.Coord;
import haven.GOut;
import haven.Label;
import haven.SListBox;
import haven.Tex;
import haven.Text;
import haven.UI;
import haven.Widget;
import haven.Window;
import nurgling.cookbook.CookbookIndex;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Which ingredients you have never tried in each dish.
 *
 * <p>Deliberately not a list of missing variants: a dish like Autumn Steak crosses roughly 30 meats
 * by 20 mushrooms by 25 seasonings, so enumerating the combinations it has never seen would run to
 * five figures of unusable rows. One row per <em>slot</em> stays finite and says the same thing, and
 * the column that matters is the last one - untried ingredients that are sitting in a container
 * right now.
 */
public class NVariantsWnd extends Window {
    private static final Color HEADING = new Color(210, 210, 235);
    private static final Color DIM = new Color(150, 150, 150);
    private static final Color HIT = new Color(255, 225, 120);

    private static final int COL_DISH = UI.scale(6);
    private static final int COL_SLOT = UI.scale(240);
    private static final int COL_TRIED = UI.scale(370);
    private static final int COL_STOCK = UI.scale(460);
    private static final int STOCK_WIDTH = UI.scale(520);
    private static final int ROW_H = UI.scale(20);

    private final List<Row> rows = new ArrayList<>();
    private final CheckBox inStockOnly;
    private final Label summary;
    private CookbookIndex index = CookbookIndex.EMPTY;

    public NVariantsWnd() {
        super(UI.scale(new Coord(1000, 520)), "Untried variants");

        summary = add(new Label(""), UI.scale(6, 4));

        inStockOnly = add(new CheckBox("Only slots I have untried ingredients for") {
            @Override
            public void changed(boolean val) {
                super.changed(val);
                rebuild();
            }
        }, UI.scale(6, 24));
        inStockOnly.a = true;

        int headerY = UI.scale(48);
        add(new Label("Dish"), new Coord(COL_DISH, headerY));
        add(new Label("Slot"), new Coord(COL_SLOT, headerY));
        add(new Label("Tried"), new Coord(COL_TRIED, headerY));
        add(new Label("Untried, in stock"), new Coord(COL_STOCK, headerY));

        add(new SlotList(UI.scale(new Coord(990, 420))), new Coord(0, headerY + UI.scale(20)));
        pack();
    }

    public void update(CookbookIndex index) {
        this.index = (index == null) ? CookbookIndex.EMPTY : index;
        rebuild();
    }

    private void rebuild() {
        boolean only = inStockOnly.state();
        List<Row> built = new ArrayList<>();
        int hits = 0;
        for (CookbookIndex.DishCoverage dish : index.dishes()) {
            for (CookbookIndex.SlotCoverage slot : dish.slots) {
                hits += slot.untriedInStock.size();
                if (slot.untried.isEmpty())
                    continue;
                if (only && slot.untriedInStock.isEmpty())
                    continue;
                built.add(new Row(dish, slot));
            }
        }
        synchronized (rows) {
            rows.clear();
            rows.addAll(built);
        }
        summary.settext(index.isEmpty()
                ? "No recipes loaded yet."
                : hits + " untried ingredients are in stock across "
                        + index.recipeCount() + " recorded variants."
                        + "  Domains only include ingredients this slot has accepted somewhere in your cookbook.");
    }

    /** One dish/slot pair. Text is rendered once here, never in {@link #draw}. */
    private static class Row extends Widget {
        private final Tex dish, slot, tried, stock;

        Row(CookbookIndex.DishCoverage dish, CookbookIndex.SlotCoverage slot) {
            this.dish = Text.std.render(dish.dish + "  (" + dish.variants + ")", HEADING).tex();
            this.slot = Text.std.render(slot.group, DIM).tex();
            this.tried = Text.std.render(slot.tried.size() + " / " + slot.domainSize(), DIM).tex();
            this.stock = Text.std.render(
                    slot.untriedInStock.isEmpty()
                            ? "-"
                            : truncate(join(slot.untriedInStock), STOCK_WIDTH),
                    slot.untriedInStock.isEmpty() ? DIM : HIT).tex();
            sz = new Coord(UI.scale(985), ROW_H);
        }

        @Override
        public void draw(GOut g) {
            g.image(dish, new Coord(COL_DISH, 0));
            g.image(slot, new Coord(COL_SLOT, 0));
            g.image(tried, new Coord(COL_TRIED, 0));
            g.image(stock, new Coord(COL_STOCK, 0));
            super.draw(g);
        }

        private static String join(List<String> names) {
            StringBuilder sb = new StringBuilder();
            for (String name : names) {
                if (sb.length() > 0)
                    sb.append(", ");
                sb.append(name);
            }
            return sb.toString();
        }

        /** Cut to width by measurement, so a long slot does not spill past the window edge. */
        private static String truncate(String text, int width) {
            if (Text.std.strsize(text).x <= width)
                return text;
            String ellipsis = "...";
            int lo = 0, hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (Text.std.strsize(text.substring(0, mid) + ellipsis).x <= width)
                    lo = mid;
                else
                    hi = mid - 1;
            }
            return text.substring(0, lo) + ellipsis;
        }
    }

    private class SlotList extends SListBox<Row, Widget> {
        SlotList(Coord sz) {
            super(sz, ROW_H);
        }

        @Override
        protected List<Row> items() {
            synchronized (rows) {
                return rows;
            }
        }

        @Override
        protected Widget makeitem(Row item, int idx, Coord sz) {
            return new ItemWidget<Row>(this, sz, item) {
                {
                    add(item);
                }
            };
        }

        private final Color bg = new Color(30, 40, 40, 160);

        @Override
        public void draw(GOut g) {
            g.chcolor(bg);
            g.frect(Coord.z, g.sz());
            super.draw(g);
        }
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (msg.equals("close"))
            hide();
        else
            super.wdgmsg(sender, msg, args);
    }
}
