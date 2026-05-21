package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NInventory;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.NWindowDeco;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import static haven.CharWnd.iconfilter;
import static haven.PUtils.convolve;

/**
 * Adds a compact base-attribute readout panel to Table furniture inventory windows.
 *
 * The "Table" window is laid out by the server: an item grid plus "Hunger reduction" /
 * "Food event bonus" labels and a "Feast!" button below it. The panel is a footer placed
 * below all of that by {@link BaseAttrsPanel#reposition()} every tick, stretched to span
 * the full window width: a thin FEP meter on top and a 3x3 base-attribute grid below it.
 *
 * Install is hooked from NInventory.added(); detection is by parent gob resource name.
 * A star toggle button in the title bar shows/hides the panel; visibility is persisted
 * in NConfig under Key.tableBaseAttrsShow.
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
        "str", "agi", "int",
        "con", "prc", "csm",
        "dex", "wil", "psy"
    };

    public static void installIfTable(NInventory inv) {
        if (inv == null) return;
        if (!isTableInventory(inv)) return;
        if (inv.parent == null) return;
        // Idempotent: added() / install paths can fire repeatedly. The window deco and
        // the window itself persist across inventory re-creation, so detect an already
        // installed button/panel and bail rather than stacking duplicates.
        if (hasToggleButton(inv) || findPanel(inv.parent) != null) return;
        addPanel(inv);
        addToggleButton(inv);
    }

    private static boolean isTableInventory(NInventory inv) {
        if (inv.parentGob == null) return false;
        Drawable d = inv.parentGob.getattr(Drawable.class);
        if (d == null || d.getres() == null) return false;
        return TABLE_RES.contains(d.getres().name);
    }

    private static boolean hasToggleButton(NInventory inv) {
        Window wnd = inv.getparent(Window.class);
        if (wnd == null || !(wnd.deco instanceof NWindowDeco)) return false;
        NWindowDeco deco = (NWindowDeco) wnd.deco;
        for (Widget w = deco.child; w != null; w = w.next) {
            if (w instanceof StarToggleButton) return true;
        }
        return false;
    }

    private static BaseAttrsPanel findPanel(Widget wnd) {
        if (wnd == null) return null;
        for (Widget w = wnd.child; w != null; w = w.next) {
            if (w instanceof BaseAttrsPanel) return (BaseAttrsPanel) w;
        }
        return null;
    }

    private static void addPanel(NInventory inv) {
        BaseAttrsPanel panel = new BaseAttrsPanel();
        // Initial coord is a placeholder; the panel's first tick() runs reposition()
        // to place it below all siblings. Repositioning is deferred out of added()
        // so pack() is not called mid-add.
        inv.parent.add(panel, Coord.z);
        panel.visible = readShowConfig();
    }

    private static void addToggleButton(NInventory inv) {
        Window wnd = inv.getparent(Window.class);
        if (wnd == null || !(wnd.deco instanceof NWindowDeco)) return;
        NWindowDeco deco = (NWindowDeco) wnd.deco;

        StarToggleButton btn = new StarToggleButton(deco);
        btn.settip("Toggle Base Attributes");
        deco.add(btn);
        // tick() will reposition; seed an initial position left of cbtn so it
        // isn't drawn at (0,0) on the very first frame.
        if (deco.cbtn != null) {
            int centerY = deco.cbtn.c.y + (deco.cbtn.sz.y - btn.sz.y) / 2;
            btn.c = new Coord(deco.cbtn.c.x - btn.sz.x - UI.scale(2), centerY);
        }
    }

    private static boolean readShowConfig() {
        Object v = NConfig.get(NConfig.Key.tableBaseAttrsShow);
        return v instanceof Boolean && (Boolean) v;
    }

    /**
     * Star toggle button placed in the Table window title bar. Marker class so
     * installIfTable() can detect an already-installed instance and stay idempotent.
     */
    private static class StarToggleButton extends IButton {
        private final NWindowDeco deco;

        StarToggleButton(NWindowDeco deco) {
            super(Resource.loadsimg("nurgling/hud/buttons/inv/star/u"),
                  Resource.loadsimg("nurgling/hud/buttons/inv/star/d"),
                  Resource.loadsimg("nurgling/hud/buttons/inv/star/h"));
            this.deco = deco;
        }

        @Override
        public void click() {
            boolean nowShow = !readShowConfig();
            NConfig.set(NConfig.Key.tableBaseAttrsShow, nowShow);
            // deco.parent is the Table Window; the panel is a sibling of the inventory.
            BaseAttrsPanel panel = findPanel(deco.parent);
            if (panel == null) return;
            panel.visible = nowShow;
            if (nowShow) {
                panel.reposition();
            } else if (panel.parent != null) {
                panel.parent.pack();
            }
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            if (deco.cbtn == null) return;
            int leftmostX = deco.cbtn.c.x;
            int titleBottom = deco.cbtn.c.y + deco.cbtn.sz.y;
            for (Widget w = deco.child; w != null; w = w.next) {
                if (w == this || w == deco.cbtn) continue;
                if (w.c == null) continue;
                if (w.c.y > titleBottom) continue;
                if (w.c.x <= 0 || w.c.x >= deco.cbtn.c.x) continue;
                if (w.c.x < leftmostX) leftmostX = w.c.x;
            }
            int centerY = deco.cbtn.c.y + (deco.cbtn.sz.y - sz.y) / 2;
            c = new Coord(leftmostX - sz.x - UI.scale(2), centerY);
        }
    }

    /**
     * Footer panel below the Table window content: a thin FEP meter on top and a compact
     * 3x3 readout of the 9 base attributes (icon + white number) below it. The panel is
     * stretched to the window's full content width -- the FEP bar spans that width and
     * the three attribute columns are distributed evenly across it -- so it never leaves
     * dead space on wide tables nor balloons narrow ones sideways.
     */
    public static class BaseAttrsPanel extends Widget {
        private static final int COLS = 3;
        private static final int ROWS = 3;
        private static final int ICON_SZ = UI.scale(16);
        private static final int CELL_H = UI.scale(18);
        private static final int NUM_W = UI.scale(26);
        private static final int ICON_NUM_GAP = UI.scale(3);
        private static final int UNIT_W = ICON_SZ + ICON_NUM_GAP + NUM_W;
        private static final int SEP_H = Math.max(1, UI.scale(1));
        private static final int TOP_PAD = SEP_H + UI.scale(3);
        private static final int FEP_BAR_H = UI.scale(12);
        private static final int FEP_GAP = UI.scale(4);
        private static final int GRID_TOP = TOP_PAD + FEP_BAR_H + FEP_GAP;
        private static final int PANEL_H = GRID_TOP + ROWS * CELL_H;
        private static final int MIN_WIDTH = COLS * (UNIT_W + UI.scale(6));
        private static final int GAP_BELOW_CONTENT = UI.scale(4);
        private static final Color VAL_COLOR = Color.WHITE;
        // Rank tint: index 0 = most saturated (the very top / very bottom stat),
        // index 2 = subtlest. Lesser shades blend toward white.
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
        private final FepBar fepBar;
        private long lastRefreshTick = -1;

        public BaseAttrsPanel() {
            super(new Coord(MIN_WIDTH, PANEL_H));
            for (int i = 0; i < ATTR_KEYS.length; i++) lastBase[i] = -1;
            fepBar = add(new FepBar(new Coord(MIN_WIDTH, FEP_BAR_H)), new Coord(0, TOP_PAD));
        }

        /**
         * Stretch the panel to the window's full content width and place it as a footer
         * just below every other (visible) window child, then grow the window to fit.
         * Excludes the deco and itself. Stable: since it never counts itself, repacking
         * does not feed back into its own target width or position.
         */
        public void reposition() {
            if (parent == null || !visible) return;
            Widget deco = (parent instanceof Window) ? ((Window) parent).deco : null;
            int maxRight = 0;
            int contentBottom = 0;
            for (Widget w = parent.child; w != null; w = w.next) {
                if (w == this || w == deco || !w.visible) continue;
                maxRight = Math.max(maxRight, w.c.x + w.sz.x);
                contentBottom = Math.max(contentBottom, w.c.y + w.sz.y);
            }
            int width = Math.max(maxRight, MIN_WIDTH);
            if (sz.x != width || sz.y != PANEL_H) resize(new Coord(width, PANEL_H));
            if (fepBar.sz.x != width) fepBar.resize(new Coord(width, FEP_BAR_H));
            Coord want = new Coord(0, contentBottom + GAP_BELOW_CONTENT);
            if (!want.equals(c)) this.c = want;
            // Always pack while visible. Re-showing after a hide leaves the window
            // shrunk with the panel position unchanged, so a move-gated pack would
            // never grow it back. pack()/resize() early-return when the size is
            // already correct, so this stays cheap.
            parent.pack();
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            if (!visible) return;
            reposition();
            long now = NUtils.getTickId();
            if (now == lastRefreshTick) return;
            if (now % 10 != 0 && lastRefreshTick != -1) return;
            lastRefreshTick = now;
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
                            if (img != null) {
                                icons[i] = new TexI(convolve(img.img,
                                    new Coord(ICON_SZ, ICON_SZ), iconfilter));
                            }
                        }
                    } catch (Loading e) {
                        // try again next refresh
                    }
                }
            }
            // Colour each number by its rank: the three highest values shade green
            // (most saturated for the very top), the three lowest shade red, the
            // middle three stay white. A value's rank comes from strict greater/less
            // counts, so tied values always resolve to the same colour.
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
            int v = vals[i];
            int gt = 0, lt = 0;
            for (int value : vals) {
                if (value > v) gt++;
                else if (value < v) lt++;
            }
            boolean green = gt <= 2;
            boolean red = lt <= 2;
            if (green && red) return VAL_COLOR;  // heavy tie cluster: stay neutral
            if (green) return GREEN[gt];
            if (red) return RED[lt];
            return VAL_COLOR;
        }

        @Override
        public void draw(GOut g) {
            // Thin separator above the footer, delineating it from the content above.
            g.chcolor(NStyle.separator);
            g.frect(Coord.z, new Coord(sz.x, SEP_H));
            g.chcolor();

            // Children: the FEP meter bar.
            super.draw(g);

            // Three columns spread evenly across the full width; each icon+number
            // unit is kept tight and centered within its column slot.
            int third = sz.x / COLS;
            for (int i = 0; i < ATTR_KEYS.length; i++) {
                int col = i % COLS;
                int row = i / COLS;
                int unitX = col * third + (third - UNIT_W) / 2;
                int cy = GRID_TOP + row * CELL_H + CELL_H / 2;
                if (icons[i] != null) {
                    g.aimage(icons[i], new Coord(unitX, cy), 0, 0.5);
                }
                if (valText[i] != null) {
                    g.aimage(valText[i].tex(), new Coord(unitX + UNIT_W, cy), 1, 0.5);
                }
            }
        }
    }

    /**
     * Thin FEP (Food Event Points) meter for the table footer. The server only feeds the
     * character window's FoodMeter, so this widget cannot be its own server-fed meter --
     * instead it mirrors the live cap/els from {@code chrwdg.battr.feps} and renders its
     * own slim segmented bar. Hovering delegates to the source meter's tooltip, so it
     * shows exactly the same event breakdown as the character sheet.
     */
    public static class FepBar extends Widget {
        private static final Color METER_BG = new Color(22, 39, 51);
        private static final int BORDER = Math.max(1, UI.scale(1));

        public FepBar(Coord sz) {
            super(sz);
        }

        private static BAttrWnd.FoodMeter source() {
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

            // 1px border framing the meter.
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
}
