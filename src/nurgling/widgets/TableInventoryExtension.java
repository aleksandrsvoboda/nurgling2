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
 * "Food event bonus" labels and a "Feast!" button below it. To avoid colliding with any
 * of that, the panel is a thin single column that positions itself dynamically just to
 * the right of all sibling widgets every tick (see {@link BaseAttrsPanel#reposition()}),
 * top-aligned with the grid, rather than at a fixed offset.
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
     * Compact single-column readout of the 9 base attributes (icon + white number).
     * Sits to the right of the Table window content, top-aligned with the grid, and
     * refreshes when values change.
     */
    public static class BaseAttrsPanel extends Widget {
        private static final int ICON_SZ = UI.scale(16);
        private static final int ROW_H = UI.scale(17);
        private static final int SEP_W = Math.max(1, UI.scale(1));
        private static final int LEFT_PAD = SEP_W + UI.scale(4);
        private static final int MID_GAP = UI.scale(4);
        private static final int NUM_W = UI.scale(28);
        private static final int RIGHT_PAD = UI.scale(4);
        private static final int PANEL_W = LEFT_PAD + ICON_SZ + MID_GAP + NUM_W + RIGHT_PAD;
        private static final int GAP_RIGHT_OF_SIBLINGS = UI.scale(4);
        private static final Color VAL_COLOR = Color.WHITE;

        private final Tex[] icons = new Tex[ATTR_KEYS.length];
        private final int[] lastBase = new int[ATTR_KEYS.length];
        private final Text[] valText = new Text[ATTR_KEYS.length];
        private long lastRefreshTick = -1;

        public BaseAttrsPanel() {
            super(new Coord(PANEL_W, ATTR_KEYS.length * ROW_H));
            for (int i = 0; i < ATTR_KEYS.length; i++) lastBase[i] = -1;
        }

        /**
         * Place the panel just to the right of every other (visible) window child,
         * top-aligned with the topmost one (the grid), then grow the window to fit.
         * Excludes the deco and itself. Stable: since it never counts itself,
         * repacking does not feed back into its own target position.
         */
        public void reposition() {
            if (parent == null || !visible) return;
            Widget deco = (parent instanceof Window) ? ((Window) parent).deco : null;
            int maxX = 0;
            int minY = Integer.MAX_VALUE;
            for (Widget w = parent.child; w != null; w = w.next) {
                if (w == this || w == deco) continue;
                if (!w.visible) continue;
                int rx = w.c.x + w.sz.x;
                if (rx > maxX) maxX = rx;
                if (w.c.y < minY) minY = w.c.y;
            }
            if (minY == Integer.MAX_VALUE) minY = 0;
            Coord want = new Coord(maxX + GAP_RIGHT_OF_SIBLINGS, minY);
            if (!want.equals(c)) this.c = want;
            // Always pack while visible. Re-showing the panel after a hide leaves the
            // window shrunk; since the panel's position is unchanged from before the
            // hide, a move-gated pack would never grow the window back. pack()/resize()
            // early-return when the size is already correct, so this stays cheap.
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
            for (int i = 0; i < ATTR_KEYS.length; i++) {
                Glob.CAttr a = glob.getcattr(ATTR_KEYS[i]);
                if (a == null) continue;
                if (a.base != lastBase[i]) {
                    lastBase[i] = a.base;
                    valText[i] = NStyle.nattrf.render(Integer.toString(a.base), VAL_COLOR);
                }
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
        }

        @Override
        public void draw(GOut g) {
            // Thin vertical separator on the left edge, delineating from the grid.
            g.chcolor(NStyle.separator);
            g.frect(Coord.z, new Coord(SEP_W, sz.y));
            g.chcolor();

            for (int i = 0; i < ATTR_KEYS.length; i++) {
                int cy = i * ROW_H + ROW_H / 2;
                if (icons[i] != null) {
                    g.aimage(icons[i], new Coord(LEFT_PAD, cy), 0, 0.5);
                }
                if (valText[i] != null) {
                    g.aimage(valText[i].tex(),
                             new Coord(sz.x - RIGHT_PAD, cy), 1, 0.5);
                }
            }
        }
    }
}
