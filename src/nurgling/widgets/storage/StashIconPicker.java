package nurgling.widgets.storage;

import haven.*;
import nurgling.NStyle;
import nurgling.i18n.L10n;
import nurgling.tools.ItemIcons;

import java.awt.Color;
import java.util.List;
import java.util.function.Consumer;

/** A grid of ready-made tab icons. Each is an item name, drawn from the offline icon catalogue. */
public class StashIconPicker extends Window {
    public static final List<String> ICONS = List.of(
            "Raw Beef", "Raw Wild Pork", "Pike", "Carrot", "Barley Seed", "Blueberries", "Chantrelles",
            "Chicken Egg", "Abbaye", "Bread", "Apple Pie", "Honey", "Wine", "Salt",
            "Flour", "Fresh Tea Leaves", "Poppy Flower", "Block of Pine", "Board of Pine", "Branch", "Cassiterite",
            "Bar of Bronze", "Coal", "Granite", "Acre Clay", "Brick", "Rock Crystal", "Pearl",
            "Bear Hide", "Bone Material", "Wool", "Flax Fibres", "Linen Cloth", "Rope", "Metal Axe",
            "Leather Armor", "Tallow Candle", "Beeswax", "Glass", "Barrel", "Large Chest", "Bucket");
    private static final int COLS = 7;
    private static final int CELL = UI.scale(38);
    private static final Color CELL_BG = new Color(16, 24, 26);
    private static final Color FRAME = new Color(59, 71, 72);

    private final Consumer<String> onPick;

    public StashIconPicker(String current, Consumer<String> onPick) {
        super(Coord.z, L10n.get("storage.stash.pick_icon"));
        this.onPick = onPick;
        int rows = (ICONS.size() + COLS - 1) / COLS;
        add(new Grid(current, Coord.of(COLS * CELL, rows * CELL)), Coord.z);
        pack();
    }

    private class Grid extends Widget {
        private final String current;
        private Coord hover;

        Grid(String current, Coord sz) {
            super(sz);
            this.current = current;
        }

        private int at(Coord c) {
            if (!c.isect(Coord.z, sz))
                return -1;
            int i = (c.y / CELL) * COLS + c.x / CELL;
            return i < ICONS.size() ? i : -1;
        }

        @Override
        public void draw(GOut g) {
            int hov = hover == null ? -1 : at(hover);
            for (int i = 0; i < ICONS.size(); i++) {
                Coord c = Coord.of((i % COLS) * CELL, (i / COLS) * CELL);
                Coord box = Coord.of(CELL - UI.scale(2), CELL - UI.scale(2));
                g.chcolor(CELL_BG);
                g.frect(c, box);
                g.chcolor();
                Tex icon = ItemIcons.get("", ICONS.get(i), false);
                if (icon != null)
                    ItemIcons.draw(g, icon, c.add(UI.scale(3), UI.scale(3)), CELL - UI.scale(8));
                Color col = ICONS.get(i).equalsIgnoreCase(current) || i == hov ? NStyle.border : FRAME;
                g.chcolor(col);
                g.frect(c, Coord.of(box.x, 1));
                g.frect(c.add(0, box.y - 1), Coord.of(box.x, 1));
                g.frect(c, Coord.of(1, box.y));
                g.frect(c.add(box.x - 1, 0), Coord.of(1, box.y));
                g.chcolor();
            }
        }

        @Override
        public void mousemove(MouseMoveEvent ev) {
            hover = ev.c;
            super.mousemove(ev);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            int i = at(ev.c);
            if (ev.b == 1 && i >= 0) {
                onPick.accept(ICONS.get(i));
                StashIconPicker.this.reqdestroy();
                return true;
            }
            return super.mousedown(ev);
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            int i = at(c);
            return i >= 0 ? ICONS.get(i) : super.tooltip(c, prev);
        }
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (sender == this && msg.equals("close"))
            reqdestroy();
        else
            super.wdgmsg(sender, msg, args);
    }
}
