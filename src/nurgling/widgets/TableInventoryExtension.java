package nurgling.widgets;

import haven.*;
import nurgling.NInventory;
import nurgling.NStyle;
import nurgling.NUtils;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static haven.CharWnd.iconfilter;
import static haven.PUtils.convolve;

/**
 * Enhanced "Table" furniture window.
 *
 * The server lays the table window out as: a 3x3 tableware grid and a small 1x2 grid at
 * the top, a variable food grid below them, and "Hunger modifier" / "Food event bonus"
 * labels plus a (conditional) "Feast!" button beneath that.
 *
 * {@link TableController} re-lays this every tick: the tableware/food grids are left as
 * anchors; the HR/FEB lines and Feast button are moved up beside the tableware grid
 * (renamed "HR:" / "FEB:"); a FEP element is placed below the food grid; and a right
 * panel (base attributes + food satiation) is placed to the right of the food grid.
 *
 * The whole custom UI is gated on the Feast button: it is shown only while the table
 * has one, and the window reverts to the vanilla server layout when it does not.
 */
public class TableInventoryExtension {

    private TableInventoryExtension() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static final Set<String> TABLE_RES = new HashSet<>();
    static {
        TABLE_RES.add("gfx/terobjs/furn/table-stone");
        TABLE_RES.add("gfx/terobjs/furn/table-rustic");
        TABLE_RES.add("gfx/terobjs/furn/table-elegant");
        TABLE_RES.add("gfx/terobjs/furn/cottagetable");
    }

    private static final String[] ATTR_KEYS = {
        "str", "agi", "int", "con", "prc", "csm", "dex", "wil", "psy"
    };

    private static final int GAP = UI.scale(6);

    public static void installIfTable(NInventory inv) {
        if (inv == null || inv.parent == null) return;
        if (!isTableInventory(inv)) return;
        Window wnd = inv.getparent(Window.class);
        if (wnd == null) return;
        // Idempotent: the table has several inventories, each of which fires added();
        // only the first one installs the controller and our widgets.
        if (findController(wnd) != null) return;

        // Our widgets start hidden; the controller shows them only while the table
        // has a Feast button.
        RightPanel rightPanel = new RightPanel();
        rightPanel.visible = false;
        FepElement fepElement = new FepElement();
        fepElement.visible = false;
        Label hrLabel = new Label("");
        hrLabel.visible = false;
        Label febLabel = new Label("");
        febLabel.visible = false;
        DrinkList drinkList = new DrinkList();
        drinkList.visible = false;
        TableController ctrl = new TableController(rightPanel, fepElement, hrLabel, febLabel, drinkList);

        wnd.add(rightPanel, Coord.z);
        wnd.add(fepElement, Coord.z);
        wnd.add(hrLabel, Coord.z);
        wnd.add(febLabel, Coord.z);
        wnd.add(drinkList, Coord.z);
        wnd.add(ctrl, Coord.z);
    }

    private static boolean isTableInventory(NInventory inv) {
        if (inv.parentGob == null) return false;
        Drawable d = inv.parentGob.getattr(Drawable.class);
        if (d == null || d.getres() == null) return false;
        return TABLE_RES.contains(d.getres().name);
    }

    private static TableController findController(Widget wnd) {
        for (Widget w = wnd.child; w != null; w = w.next)
            if (w instanceof TableController) return (TableController) w;
        return null;
    }

    /**
     * Invisible per-window controller. Each tick it discovers the server widgets and
     * re-lays the whole table window, then sizes the window to fit.
     */
    public static class TableController extends Widget {
        private final RightPanel rightPanel;
        private final FepElement fepElement;
        private final Label hrLabel, febLabel;
        private final DrinkList drinkList;
        private int feastFullW = -1;  // server's original Feast button width, captured once

        TableController(RightPanel rp, FepElement fe, Label hr, Label fb, DrinkList dl) {
            super(new Coord(1, 1));
            visible = false;
            this.rightPanel = rp;
            this.fepElement = fe;
            this.hrLabel = hr;
            this.febLabel = fb;
            this.drinkList = dl;
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            layout();
        }

        private void layout() {
            Window wnd = getparent(Window.class);
            if (wnd == null) return;

            // --- discover server widgets ---
            Inventory food = null, tableware = null;
            long foodArea = -1;
            Button feast = null;
            Label srvHR = null, srvFEB = null;
            for (Widget w = wnd.child; w != null; w = w.next) {
                if (w instanceof Inventory) {
                    Inventory iv = (Inventory) w;
                    long area = (long) iv.sz.x * iv.sz.y;
                    if (area > foodArea) { foodArea = area; food = iv; }
                    if (iv.isz != null && iv.isz.x == 3 && iv.isz.y == 3) tableware = iv;
                } else if (w instanceof Button) {
                    Button b = (Button) w;
                    if (b.text != null && "Feast!".equals(b.text.text)) feast = b;
                } else if ((w instanceof Label) && (w != hrLabel) && (w != febLabel)) {
                    String t = ((Label) w).text();
                    if (t != null) {
                        if (t.startsWith("Hunger")) srvHR = (Label) w;
                        else if (t.startsWith("Food event")) srvFEB = (Label) w;
                    }
                }
            }
            if (food == null) return;  // window not fully loaded yet

            // The whole custom UI is gated on the Feast button: a table without one
            // (used only as food storage) keeps the vanilla server layout.
            if (feast == null) {
                if (srvHR != null) srvHR.visible = true;
                if (srvFEB != null) srvFEB.visible = true;
                hrLabel.visible = false;
                febLabel.visible = false;
                fepElement.visible = false;
                rightPanel.visible = false;
                drinkList.visible = false;
                wnd.pack();
                return;
            }
            hrLabel.visible = true;
            febLabel.visible = true;
            fepElement.visible = true;
            rightPanel.visible = true;

            int foodTop = food.c.y, foodBottom = food.c.y + food.sz.y;
            int foodLeft = food.c.x, foodRight = food.c.x + food.sz.x;

            // --- right edge of the top band (tableware grid + the 1x2 grid) ---
            int topBandRight = 0;
            for (Widget w = wnd.child; w != null; w = w.next) {
                if (w == this || w == rightPanel || w == fepElement
                        || w == hrLabel || w == febLabel || w == feast) continue;
                if (w == wnd.deco || !w.visible) continue;
                if (w.c.y + w.sz.y <= foodTop)
                    topBandRight = Math.max(topBandRight, w.c.x + w.sz.x);
            }

            // --- HR / FEB / Feast block: top-right, bottom-aligned to the tableware grid ---
            if (srvHR != null) {
                srvHR.visible = false;
                hrLabel.settext(shorten(srvHR.text(), "HR"));
            }
            if (srvFEB != null) {
                srvFEB.visible = false;
                febLabel.settext(shorten(srvFEB.text(), "FEB"));
            }
            int blockX = topBandRight + GAP;
            int blockBottom = (tableware != null) ? tableware.c.y + tableware.sz.y : foodTop;
            int y = blockBottom;
            if (feast != null) {
                // Halve the (very wide) server Feast button and shift it left a bit.
                if (feastFullW < 0) feastFullW = feast.sz.x;
                int targetW = feastFullW / 2;
                if (feast.sz.x != targetW) {
                    feast.resize(new Coord(targetW, feast.sz.y));
                    feast.redraw();
                }
                feast.c = new Coord(blockX - UI.scale(40), y - feast.sz.y);
                y -= feast.sz.y + UI.scale(3);
            }
            febLabel.c = new Coord(blockX, y - febLabel.sz.y);
            y -= febLabel.sz.y + UI.scale(3);
            hrLabel.c = new Coord(blockX, y - hrLabel.sz.y);

            // --- FEP element: below the food grid, exactly the food grid's width ---
            if (fepElement.sz.x != food.sz.x)
                fepElement.resize(new Coord(food.sz.x, FepElement.HEIGHT));
            fepElement.c = new Coord(foodLeft, foodBottom + GAP);

            // --- right panel: to the right of the food grid, top-aligned with the window ---
            rightPanel.c = new Coord(foodRight + GAP, 0);

            // --- drink list: below the right panel, only when the buff has rows ---
            boolean hasDrinks = drinkList.rowCount() > 0;
            drinkList.visible = hasDrinks;
            if (hasDrinks) {
                if (drinkList.sz.x != rightPanel.sz.x)
                    drinkList.resize(new Coord(rightPanel.sz.x, drinkList.sz.y));
                drinkList.c = new Coord(rightPanel.c.x, rightPanel.c.y + rightPanel.sz.y + GAP);
            }

            // --- size the window to bound everything (invisible children excluded) ---
            wnd.pack();
        }

        /** "Hunger modifier: 35%" + "HR" -> "HR: 35%". */
        private static String shorten(String text, String prefix) {
            if (text == null) return "";
            int ci = text.indexOf(':');
            return (ci < 0) ? prefix : prefix + text.substring(ci);
        }
    }

    /**
     * Togglable right panel: the base-attributes column with the food-satiation column
     * beside it.
     */
    public static class RightPanel extends Widget {
        final StatsColumn stats;
        final SatiationColumn sat;

        RightPanel() {
            super(Coord.z);
            stats = add(new StatsColumn(), Coord.z);
            sat = add(new SatiationColumn(StatsColumn.HEIGHT), new Coord(stats.sz.x + GAP, 0));
            resize(new Coord(stats.sz.x + GAP + sat.sz.x, StatsColumn.HEIGHT));
        }
    }

    /**
     * Single-column readout of the 9 base attributes (icon + white number). Each number
     * is tinted by its rank: the three highest shade green, the three lowest red.
     */
    public static class StatsColumn extends Widget {
        static final int ICON_SZ = UI.scale(16);
        static final int ROW_H = UI.scale(17);
        static final int PAD = UI.scale(3);
        static final int NUM_W = UI.scale(34);
        static final int COL_W = PAD + ICON_SZ + UI.scale(4) + NUM_W + PAD;
        static final int HEIGHT = ROW_H * 9;

        private static final Color GREEN_BASE = new Color(60, 220, 60);
        private static final Color RED_BASE = new Color(235, 70, 70);
        private static final Color[] GREEN = {
            GREEN_BASE,
            Utils.blendcol(GREEN_BASE, Color.WHITE, 0.40),
            Utils.blendcol(GREEN_BASE, Color.WHITE, 0.70),
        };
        private static final Color[] RED = {
            RED_BASE,
            Utils.blendcol(RED_BASE, Color.WHITE, 0.40),
            Utils.blendcol(RED_BASE, Color.WHITE, 0.70),
        };

        private final Tex[] icons = new Tex[ATTR_KEYS.length];
        private final int[] lastBase = new int[ATTR_KEYS.length];
        private final Color[] lastColor = new Color[ATTR_KEYS.length];
        private final Text[] valText = new Text[ATTR_KEYS.length];
        private long lastTick = -1;

        StatsColumn() {
            super(new Coord(COL_W, HEIGHT));
            for (int i = 0; i < lastBase.length; i++) lastBase[i] = -1;
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            long now = NUtils.getTickId();
            if (now == lastTick) return;
            if (now % 10 != 0 && lastTick != -1) return;
            lastTick = now;
            refresh();
        }

        private void refresh() {
            Glob glob = (ui != null && ui.sess != null) ? ui.sess.glob : null;
            if (glob == null) return;
            int[] vals = new int[ATTR_KEYS.length];
            for (int i = 0; i < ATTR_KEYS.length; i++) {
                Glob.CAttr a = glob.getcattr(ATTR_KEYS[i]);
                vals[i] = a.base;
                if (icons[i] == null) {
                    try {
                        Resource res = a.res().get();
                        if (res != null) {
                            Resource.Image img = res.layer(Resource.imgc);
                            if (img != null)
                                icons[i] = new TexI(convolve(img.img,
                                    new Coord(ICON_SZ, ICON_SZ), iconfilter));
                        }
                    } catch (Loading e) {
                        // try again next refresh
                    }
                }
            }
            for (int i = 0; i < ATTR_KEYS.length; i++) {
                Color col = rankColor(vals, i);
                if (vals[i] != lastBase[i] || col != lastColor[i]) {
                    lastBase[i] = vals[i];
                    lastColor[i] = col;
                    valText[i] = NStyle.nattrf.render(Integer.toString(vals[i]), col);
                }
            }
        }

        private static Color rankColor(int[] vals, int i) {
            int v = vals[i], gt = 0, lt = 0;
            for (int x : vals) {
                if (x > v) gt++;
                else if (x < v) lt++;
            }
            boolean green = gt <= 2, red = lt <= 2;
            if (green && red) return Color.WHITE;
            if (green) return GREEN[gt];
            if (red) return RED[lt];
            return Color.WHITE;
        }

        @Override
        public void draw(GOut g) {
            for (int i = 0; i < ATTR_KEYS.length; i++) {
                int cy = i * ROW_H + ROW_H / 2;
                if (icons[i] != null)
                    g.aimage(icons[i], new Coord(PAD, cy), 0, 0.5);
                if (valText[i] != null)
                    g.aimage(valText[i].tex(), new Coord(COL_W - PAD, cy), 1, 0.5);
            }
        }
    }

    /**
     * Scrollable column of food-satiation items (icon + number), mirrored from the
     * character window's satiation list. A scrollbar appears when items overflow.
     */
    public static class SatiationColumn extends Scrollport {
        static final int WIDTH = UI.scale(74);

        SatiationColumn(int height) {
            super(new Coord(WIDTH, height));
            cont.add(new SatiationContent(cont.sz.x), Coord.z);
        }
    }

    /** Scroll content for {@link SatiationColumn}: mirrors chrwdg.battr.cons. */
    public static class SatiationContent extends Widget {
        static final int ICON_SZ = UI.scale(16);
        static final int ROW_H = UI.scale(17);
        static final int PAD = UI.scale(3);
        private static final Map<Indir<Resource>, Tex> iconCache = new HashMap<>();

        private static final class Row {
            Tex icon;
            Text num;
        }

        private List<Row> rows = new ArrayList<>();
        private long lastTick = -1;

        SatiationContent(int width) {
            super(new Coord(width, ROW_H));
        }

        private static BAttrWnd.Constipations sourceCons() {
            GameUI gui = NUtils.getGameUI();
            if (gui == null || gui.chrwdg == null || gui.chrwdg.battr == null) return null;
            return gui.chrwdg.battr.cons;
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            long now = NUtils.getTickId();
            if (now == lastTick) return;
            if (now % 10 != 0 && lastTick != -1) return;
            lastTick = now;
            rebuild();
        }

        private void rebuild() {
            BAttrWnd.Constipations cons = sourceCons();
            List<Row> nrows = new ArrayList<>();
            if (cons != null) {
                List<BAttrWnd.Constipations.El> els = new ArrayList<>(cons.els);
                els.sort(Comparator.comparingDouble(e -> e.a));
                for (BAttrWnd.Constipations.El el : els) {
                    Row r = new Row();
                    r.icon = icon(el.t);
                    int pct = Math.max((int) Math.round((1.0 - el.a) * 100), 1);
                    Color c = (el.a > 1.0) ? BAttrWnd.Constipations.buffed
                            : Utils.blendcol(BAttrWnd.Constipations.none,
                                             BAttrWnd.Constipations.full, el.a);
                    r.num = NStyle.nattrf.render(pct + "%", c);
                    nrows.add(r);
                }
            }
            rows = nrows;
            int h = Math.max(rows.size() * ROW_H, ROW_H);
            if (sz.y != h) {
                resize(new Coord(sz.x, h));
                if (parent instanceof Scrollport.Scrollcont)
                    ((Scrollport.Scrollcont) parent).update();
            }
        }

        private static Tex icon(ResData t) {
            if (t == null || t.res == null) return null;
            Tex cached = iconCache.get(t.res);
            if (cached != null) return cached;
            try {
                Resource res = t.res.get();
                if (res != null) {
                    Resource.Image img = res.layer(Resource.imgc);
                    if (img != null) {
                        Tex tex = new TexI(convolve(img.img,
                            new Coord(ICON_SZ, ICON_SZ), iconfilter));
                        iconCache.put(t.res, tex);
                        return tex;
                    }
                }
            } catch (Loading e) {
                // try again next rebuild
            }
            return null;
        }

        @Override
        public void draw(GOut g) {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                int cy = i * ROW_H + ROW_H / 2;
                if (r.icon != null)
                    g.aimage(r.icon, new Coord(PAD, cy), 0, 0.5);
                if (r.num != null)
                    g.aimage(r.num.tex(), new Coord(sz.x - PAD, cy), 1, 0.5);
            }
        }
    }

    /**
     * The FEP element placed below the food grid: a "sum/cap" readout on the left and a
     * thin FEP meter filling the rest. Sized by the controller to the food grid's width.
     */
    public static class FepElement extends Widget {
        static final int HEIGHT = UI.scale(16);
        static final int BAR_H = UI.scale(12);
        static final int NUM_W = UI.scale(62);

        private final FepBar bar;
        private Text valTex;
        private String lastStr = null;

        FepElement() {
            super(new Coord(UI.scale(120), HEIGHT));
            bar = add(new FepBar(new Coord(UI.scale(60), BAR_H)),
                      new Coord(NUM_W, (HEIGHT - BAR_H) / 2));
        }

        @Override
        public void resize(Coord nsz) {
            super.resize(nsz);
            int bw = Math.max(UI.scale(1), nsz.x - NUM_W);
            bar.resize(new Coord(bw, BAR_H));
            bar.c = new Coord(NUM_W, (nsz.y - BAR_H) / 2);
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            BAttrWnd.FoodMeter fm = FepBar.source();
            String s = "";
            if (fm != null) {
                double sum = 0;
                for (BAttrWnd.FoodMeter.El el : fm.els) sum += el.a;
                s = String.format("%.0f/%.0f", sum, fm.cap);
            }
            if (!s.equals(lastStr)) {
                lastStr = s;
                valTex = s.isEmpty() ? null : NStyle.nattrf.render(s, Color.WHITE);
            }
        }

        @Override
        public void draw(GOut g) {
            if (valTex != null)
                g.aimage(valTex.tex(), new Coord(UI.scale(2), sz.y / 2), 0, 0.5);
            super.draw(g);
        }
    }

    /**
     * Thin FEP meter. The server feeds only the character window's FoodMeter, so this
     * mirrors its live cap/els and renders its own slim segmented bar; hovering delegates
     * to the source meter's tooltip (identical event breakdown to the character sheet).
     */
    public static class FepBar extends Widget {
        private static final Color METER_BG = new Color(22, 39, 51);
        private static final int BORDER = Math.max(1, UI.scale(1));

        FepBar(Coord sz) {
            super(sz);
        }

        static BAttrWnd.FoodMeter source() {
            GameUI gui = NUtils.getGameUI();
            if (gui == null || gui.chrwdg == null || gui.chrwdg.battr == null) return null;
            return gui.chrwdg.battr.feps;
        }

        @Override
        public void draw(GOut g) {
            g.chcolor(METER_BG);
            g.frect(Coord.z, sz);
            g.chcolor();

            BAttrWnd.FoodMeter fm = source();
            if (fm != null && fm.cap > 0) {
                double x = 0;
                int w = sz.x;
                for (BAttrWnd.FoodMeter.El el : fm.els) {
                    int l = (int) Math.floor((x / fm.cap) * w);
                    int r = (int) Math.floor(((x += el.a) / fm.cap) * w);
                    try {
                        g.chcolor(el.ev().col);
                        g.frect(new Coord(l, 0), new Coord(r - l, sz.y));
                    } catch (Loading ignored) {
                    }
                }
                g.chcolor();
            }

            g.chcolor(NStyle.separator);
            g.frect(Coord.z, new Coord(sz.x, BORDER));
            g.frect(new Coord(0, sz.y - BORDER), new Coord(sz.x, BORDER));
            g.frect(Coord.z, new Coord(BORDER, sz.y));
            g.frect(new Coord(sz.x - BORDER, 0), new Coord(BORDER, sz.y));
            g.chcolor();
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            BAttrWnd.FoodMeter fm = source();
            return (fm != null) ? fm.tooltip(c, prev) : null;
        }
    }

    /**
     * Lines from the "Drink, and be Merry" buff (e.g. "Milk: 1", "Wine: 3"), shown below
     * the right panel. The buff lives on the GameUI's Bufflist; we walk to it each
     * refresh, find the buff by name, and read each drink info entry's fields. The buff
     * pushes one server-loaded {@code Drinkbuff} info per drink type, carrying the
     * drink's display name and the sip count -- read here via cached reflection.
     */
    public static class DrinkList extends Widget {
        static final int ROW_H = UI.scale(16);
        static final int SEP_H = Math.max(1, UI.scale(1));
        static final int TOP_PAD = SEP_H + UI.scale(3);
        private static final String BUFF_NAME = "Drink, and be Merry";
        private static final String DRINKBUFF_SUFFIX = ".drinkbuff.Drinkbuff";

        private static final class Row {
            final String text;
            final Text rendered;
            Row(String t, Text r) { text = t; rendered = r; }
        }

        private List<Row> rows = new ArrayList<>();
        private long lastTick = -1;

        // Reflection cache for the server-loaded Drinkbuff info class: public fields
        // "nm" (String) and "n" (int). Cached per Class instance.
        private static Class<?> drinkbuffClass;
        private static java.lang.reflect.Field drinkNmField;
        private static java.lang.reflect.Field drinkNField;

        DrinkList() {
            super(new Coord(UI.scale(140), 1));
        }

        public int rowCount() { return rows.size(); }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            long now = NUtils.getTickId();
            if (now == lastTick) return;
            if (now % 10 != 0 && lastTick != -1) return;
            lastTick = now;
            rebuild();
        }

        private void rebuild() {
            Buff buff = findBuff();
            List<String> newLines = new ArrayList<>();
            if (buff != null) {
                try {
                    List<ItemInfo> info = buff.info();
                    if (info != null) {
                        for (ItemInfo ii : info) {
                            if (ii.getClass().getName().endsWith(DRINKBUFF_SUFFIX)) {
                                String line = drinkLine(ii);
                                if (line != null) newLines.add(line);
                            }
                        }
                    }
                } catch (Loading e) {
                    return;  // try again next refresh
                }
            }
            if (!linesChanged(newLines)) return;
            // Dispose old Texts before replacing the row list.
            for (Row old : rows) {
                if (old.rendered != null) old.rendered.dispose();
            }
            List<Row> nrows = new ArrayList<>(newLines.size());
            for (String s : newLines)
                nrows.add(new Row(s, Text.std.render(s, Color.WHITE)));
            rows = nrows;
            int h = rows.isEmpty() ? 0 : TOP_PAD + rows.size() * ROW_H;
            if (sz.y != Math.max(h, 1))
                resize(new Coord(sz.x, Math.max(h, 1)));
        }

        /** Read "nm" and "n" from a Drinkbuff info entry by reflection, format as "nm: n". */
        private static String drinkLine(ItemInfo ii) {
            try {
                Class<?> c = ii.getClass();
                if (c != drinkbuffClass) {
                    java.lang.reflect.Field fnm = c.getDeclaredField("nm");
                    java.lang.reflect.Field fn = c.getDeclaredField("n");
                    fnm.setAccessible(true);
                    fn.setAccessible(true);
                    drinkNmField = fnm;
                    drinkNField = fn;
                    drinkbuffClass = c;
                }
                Object nm = drinkNmField.get(ii);
                int n = drinkNField.getInt(ii);
                return (nm == null) ? null : (nm + ": " + n);
            } catch (Throwable t) {
                return null;
            }
        }

        private boolean linesChanged(List<String> newLines) {
            if (newLines.size() != rows.size()) return true;
            for (int i = 0; i < newLines.size(); i++)
                if (!newLines.get(i).equals(rows.get(i).text)) return true;
            return false;
        }

        private static Buff findBuff() {
            GameUI gui = NUtils.getGameUI();
            if (gui == null) return null;
            // GameUI.buffs is the canonical Bufflist; it's wrapped in an NDraggableWidget
            // so it isn't a direct child of GameUI -- use the public field instead.
            Bufflist bufflist = gui.buffs;
            if (bufflist == null) return null;
            for (Widget b = bufflist.child; b != null; b = b.next) {
                if (!(b instanceof Buff)) continue;
                Buff buff = (Buff) b;
                try {
                    Resource r = buff.res.get();
                    Resource.Tooltip ttl = r.layer(Resource.tooltip);
                    if (ttl != null) {
                        String name = ttl.text();
                        if (name != null && name.contains(BUFF_NAME))
                            return buff;
                    }
                } catch (Loading e) {
                    // skip and continue searching
                }
            }
            return null;
        }

        @Override
        public void draw(GOut g) {
            if (rows.isEmpty()) return;
            // Thin separator above, matching the FEP element / footer look.
            g.chcolor(NStyle.separator);
            g.frect(Coord.z, new Coord(sz.x, SEP_H));
            g.chcolor();
            int y = TOP_PAD;
            for (Row r : rows) {
                if (r.rendered != null)
                    g.image(r.rendered.tex(), new Coord(UI.scale(2), y));
                y += ROW_H;
            }
        }
    }
}
