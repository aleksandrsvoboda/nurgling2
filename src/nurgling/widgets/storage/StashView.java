package nurgling.widgets.storage;

import haven.*;
import nurgling.NFlowerMenu;
import nurgling.NInventory;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.db.dao.StorageItemDao;
import nurgling.i18n.L10n;
import nurgling.tools.ItemIcons;
import nurgling.tools.StorageClassifier;
import nurgling.widgets.NStorageItemsWidget;
import nurgling.widgets.TextInputWindow;
import nurgling.widgets.db.DbClipboard;

import java.awt.Color;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * The Storage window's tab view: a strip of icon tabs on top and, below it, the active tab's
 * stored items as inventory-like slots with the count in the corner. Pinned tabs keep a slot for
 * every pinned item, so an empty slot shows what is missing; auto tabs show whatever is stored of
 * their category.
 */
public class StashView extends Widget {
    /** A slot: the icon on top, a strip under it with the count left and the quality right. */
    private static final int ICON = UI.scale(32);
    private static final int STRIP = UI.scale(13);
    private static final Coord CELL = Coord.of(ICON + UI.scale(10), UI.scale(3) + ICON + STRIP + UI.scale(1));
    private static final int TAB = UI.scale(36);
    private static final int GAP = UI.scale(2);
    private static final int HEADER = UI.scale(16);
    private static final int TITLE = UI.scale(20);
    private static final Color FRAME = new Color(59, 71, 72);
    private static final Color LOW = new Color(192, 57, 43);
    private static final Color SECTION = new Color(143, 163, 164);

    private static final Color CELL_BG = new Color(16, 24, 26);
    private static final Color STRIP_BG = new Color(8, 12, 13);
    private static final Text.Foundry countFont = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 11, Color.WHITE).aa(true);
    private static final Text.Foundry lowFont = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 11, new Color(255, 123, 107)).aa(true);
    private static final Text.Foundry qFont = new Text.Foundry(Text.sans, 10, new Color(242, 197, 124)).aa(true);
    private static final Text.Foundry qUnknownFont = new Text.Foundry(Text.sans, 10, new Color(143, 163, 164)).aa(true);
    private static final Text.Foundry initialsFont = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 12, new Color(143, 163, 164)).aa(true);
    private static final Text.Foundry tipFont = new Text.Foundry(Text.sans, 12, Color.WHITE).aa(true);
    private static final Text.Foundry tipHintFont = new Text.Foundry(Text.sans, 11, SECTION).aa(true);
    private static final Text.Foundry headFont = new Text.Foundry(Text.sans, 10, SECTION).aa(true);
    private static final Text.Foundry titleFont = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 12, NStyle.border).aa(true);
    private static final Text.Foundry infoFont = new Text.Foundry(Text.sans, 10, SECTION).aa(true);

    /** Everything stored under one item name. */
    public static final class Stock {
        public final String name;
        public final List<StorageItemDao.StorageItemData> rows = new ArrayList<>();
        public final String bulkUnit;
        public double bulkAmount;

        Stock(String name, String bulkUnit) {
            this.name = name;
            this.bulkUnit = bulkUnit;
        }

        /** Rows that pass the quality filter; unknown-quality rows only pass without a filter. */
        List<StorageItemDao.StorageItemData> rows(double minQ) {
            if (minQ <= 0)
                return rows;
            List<StorageItemDao.StorageItemData> out = new ArrayList<>();
            for (StorageItemDao.StorageItemData r : rows)
                if (r.hasQuality() && r.getQuality() >= minQ)
                    out.add(r);
            return out;
        }
    }

    /** A slot as laid out on the page. */
    private static final class Cell {
        final String name;
        final StashLayout.Slot pin; // null for auto-filled
        final Stock stock;          // null when none stored
        final int count;
        final double maxQ;
        final int unknown;
        final Coord c;

        Cell(String name, StashLayout.Slot pin, Stock stock, double minQ, Coord c) {
            this.name = name;
            this.pin = pin;
            this.stock = stock;
            this.c = c;
            int n = 0, u = 0;
            double q = 0;
            if (stock != null) {
                for (StorageItemDao.StorageItemData r : stock.rows(minQ)) {
                    n++;
                    if (r.hasQuality())
                        q = Math.max(q, r.getQuality());
                    else
                        u++;
                }
            }
            this.count = n;
            this.maxQ = q;
            this.unknown = u;
        }

        boolean bulk() {
            return stock != null && stock.bulkUnit != null;
        }

        boolean empty() {
            return bulk() ? stock.bulkAmount <= 0 : count == 0;
        }

        boolean low() {
            return pin != null && pin.target > 0 && !bulk() && count < pin.target;
        }
    }

    private static final class Header {
        final Tex text;
        final Coord c;

        Header(Tex text, Coord c) {
            this.text = text;
            this.c = c;
        }
    }

    private final StashLayout layout;
    private final Consumer<String> openInList;
    private volatile Map<String, Stock> stock = Collections.emptyMap();
    private volatile boolean dirty = true;
    private boolean builtWithCatalog = false;
    private String search = "";
    private double minQ = 0;
    private StashLayout.Tab active;

    private final List<Cell> cells = new ArrayList<>();
    private final List<Header> headers = new ArrayList<>();
    private final Map<StashLayout.Tab, Coord> tabPos = new LinkedHashMap<>();
    private final Map<StashLayout.Tab, Boolean> tabHasItems = new HashMap<>();
    private Coord plusPos = Coord.z;
    private int stripH = TAB;
    private Tex title;
    private int pageH = 0;
    private final Scrollbar sb;
    private Coord hover = null;
    private final Map<String, Tex> texts = new HashMap<>();
    private NFlowerMenu menu;
    /** Opened on the next tick: the window raises itself after the click, which would bury it. */
    private Runnable pendingMenu;
    private Object tipKey;
    private Tex tipTex;

    public StashView(Coord sz, Consumer<String> openInList) {
        super(sz);
        this.layout = StashLayout.load();
        this.openInList = openInList;
        this.active = layout.tabs.isEmpty() ? null : layout.tabs.get(0);
        sb = add(new Scrollbar(UI.scale(10), 0, 0), Coord.z);
        offerNewPresets();
    }

    /** Called with every reload of the storage rows; may run off the UI thread. */
    public void setItems(List<StorageItemDao.StorageItemData> items) {
        Map<String, Stock> map = new HashMap<>();
        for (StorageItemDao.StorageItemData r : items) {
            String key = StorageClassifier.normalize(r.getName()) + (r.isBulk() ? "#" + r.getBulkUnit() : "");
            Stock s = map.computeIfAbsent(key, k -> new Stock(r.getName(), r.getBulkUnit()));
            s.rows.add(r);
            s.bulkAmount += r.getBulkAmount();
        }
        for (Stock s : map.values())
            s.rows.sort((a, b) -> Double.compare(b.getQuality(), a.getQuality()));
        stock = map;
        dirty = true;
    }

    public void setSearch(String text) {
        search = text == null ? "" : text.toLowerCase(Locale.ROOT);
        dirty = true;
    }

    public void setMinQ(Double q) {
        minQ = q == null ? 0 : q;
        dirty = true;
    }

    // ---------------------------------------------------------------- layout

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (!builtWithCatalog && ItemIcons.ready())
            dirty = true; // categories computed before the catalogue loaded were guesses
        if (pendingMenu != null) {
            Runnable r = pendingMenu;
            pendingMenu = null;
            r.run();
        }
        if (dirty) {
            dirty = false;
            builtWithCatalog = ItemIcons.ready();
            rebuild();
        }
    }

    @Override
    public void resize(Coord sz) {
        super.resize(sz);
        dirty = true;
    }

    private String title(StashLayout.Tab t) {
        if (t.title != null)
            return t.title;
        StorageClassifier.Category c = t.category();
        return c == null ? t.id : L10n.get(c.l10nKey());
    }

    private Stock stockFor(String name) {
        Map<String, Stock> map = stock;
        String key = StorageClassifier.normalize(name);
        Stock s = map.get(key);
        if (s != null)
            return s;
        for (Map.Entry<String, Stock> e : map.entrySet())
            if (e.getKey().startsWith(key + "#"))
                return e.getValue();
        return null;
    }

    private boolean matchesSearch(String name) {
        return search.isEmpty() || name.toLowerCase(Locale.ROOT).contains(search);
    }

    /** Stored items of a category not pinned in the given tab, most plentiful first. */
    private List<Stock> autoFill(StashLayout.Tab tab) {
        StorageClassifier.Category cat = tab.category();
        List<Stock> out = new ArrayList<>();
        if (cat == null)
            return out;
        for (Stock s : stock.values()) {
            if (StorageClassifier.classify(s.name) != cat || (tab.pinned && tab.find(s.name) != null))
                continue;
            if (!s.rows(minQ).isEmpty() || (s.bulkUnit != null && s.bulkAmount > 0 && minQ <= 0))
                out.add(s);
        }
        out.sort(Comparator.<Stock>comparingDouble(s -> s.bulkUnit != null ? s.bulkAmount : s.rows(minQ).size()).reversed()
                .thenComparing(s -> s.name));
        return out;
    }

    private void rebuild() {
        if (active == null || !layout.tabs.contains(active))
            active = layout.tabs.isEmpty() ? null : layout.tabs.get(0);

        // Tab strip: pinned tabs, a gap, auto tabs, then "+". Wraps when the window is narrow.
        tabPos.clear();
        tabHasItems.clear();
        int x = 0, y = 0;
        boolean sawAuto = false;
        for (StashLayout.Tab t : layout.tabs) {
            if (!t.pinned && !sawAuto) {
                sawAuto = true;
                if (x > 0)
                    x += UI.scale(8);
            }
            if (x + TAB > sz.x) {
                x = 0;
                y += TAB + GAP;
            }
            tabPos.put(t, Coord.of(x, y));
            tabHasItems.put(t, (t.pinned && t.slots().stream().anyMatch(s -> stockFor(s.name) != null)) || !autoFill(t).isEmpty());
            x += TAB + GAP;
        }
        if (x + TAB > sz.x) {
            x = 0;
            y += TAB + GAP;
        }
        plusPos = Coord.of(x, y);
        stripH = y + TAB;

        // Page
        tipKey = null;
        cells.clear();
        headers.clear();
        int w = sz.x - sb.sz.x - UI.scale(4);
        int perRow = Math.max(1, (w + GAP) / (CELL.x + GAP));
        int py = 0;
        int slots = 0, empty = 0;
        if (active != null) {
            if (active.pinned) {
                for (StashLayout.Section sec : active.sections) {
                    List<StashLayout.Slot> shown = new ArrayList<>();
                    for (StashLayout.Slot s : sec.slots)
                        if (matchesSearch(s.name))
                            shown.add(s);
                    if (shown.isEmpty() && !search.isEmpty())
                        continue;
                    if (!sec.title.isEmpty()) {
                        String head = sec.title.startsWith("@") ? L10n.get(sec.title.substring(1)) : sec.title;
                        headers.add(new Header(headFont.render(head.toUpperCase()).tex(), Coord.of(0, py)));
                        py += HEADER;
                    }
                    int i = 0;
                    for (StashLayout.Slot s : shown) {
                        Cell cell = new Cell(s.name, s, stockFor(s.name), minQ, Coord.of((i % perRow) * (CELL.x + GAP), py + (i / perRow) * (CELL.y + GAP)));
                        cells.add(cell);
                        slots++;
                        if (cell.empty())
                            empty++;
                        i++;
                    }
                    if (i > 0)
                        py += ((i + perRow - 1) / perRow) * (CELL.y + GAP) + UI.scale(4);
                }
            }
            List<Stock> extra = new ArrayList<>();
            for (Stock s : autoFill(active))
                if (matchesSearch(s.name))
                    extra.add(s);
            if (!extra.isEmpty()) {
                if (active.pinned) {
                    headers.add(new Header(headFont.render(L10n.get("storage.stash.also_stored").toUpperCase()).tex(), Coord.of(0, py)));
                    py += HEADER;
                }
                int i = 0;
                for (Stock s : extra) {
                    cells.add(new Cell(s.name, null, s, minQ, Coord.of((i % perRow) * (CELL.x + GAP), py + (i / perRow) * (CELL.y + GAP))));
                    i++;
                }
                py += ((i + perRow - 1) / perRow) * (CELL.y + GAP);
            }
            if (!active.pinned)
                slots = cells.size();
        }
        pageH = py;

        String info = active == null ? "" : (active.pinned ? L10n.get("storage.stash.info_pinned", slots, empty)
                : L10n.get("storage.stash.info_auto", slots));
        title = active == null ? null : titleFont.render(title(active) + "   ").tex();
        texts.put("#info", infoFont.render(info).tex());

        int viewH = sz.y - pageTop();
        sb.resize(Coord.of(sb.sz.x, Math.max(UI.scale(10), viewH)));
        sb.c = Coord.of(sz.x - sb.sz.x, pageTop());
        sb.max = Math.max(0, pageH - viewH);
        sb.val = Math.min(sb.val, sb.max);
    }

    private int pageTop() {
        return stripH + GAP + TITLE;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void draw(GOut g) {
        for (Map.Entry<StashLayout.Tab, Coord> e : tabPos.entrySet())
            drawTab(g, e.getKey(), e.getValue());
        drawPlus(g);
        g.chcolor(NStyle.border);
        g.frect(Coord.of(0, stripH), Coord.of(sz.x, 1));
        g.chcolor();

        if (title != null) {
            int ty = stripH + GAP + UI.scale(3);
            g.image(title, Coord.of(UI.scale(2), ty));
            Tex info = texts.get("#info");
            if (info != null)
                g.image(info, Coord.of(UI.scale(2) + title.sz().x, ty + title.sz().y - info.sz().y));
        }

        GOut pg = g.reclip(Coord.of(0, pageTop()), Coord.of(sz.x - sb.sz.x, sz.y - pageTop()));
        int off = sb.val;
        for (Header h : headers)
            pg.image(h.text, h.c.sub(0, off));
        Coord mouse = hover == null ? null : hover.sub(0, pageTop() - off);
        int viewH = pg.sz().y;
        for (Cell cell : cells) {
            Coord at = cell.c.sub(0, off);
            if (at.y + CELL.y > 0 && at.y < viewH)
                drawCell(pg, cell, at, mouse != null && mouse.isect(cell.c, CELL));
        }
        if (cells.isEmpty()) {
            String key = stock.isEmpty() ? "storage.stash.no_data" : "storage.stash.empty_tab";
            pg.image(text(key, infoFont, L10n.get(key)), Coord.of(UI.scale(4), UI.scale(6)));
        }
        super.draw(g);
    }

    private void drawTab(GOut g, StashLayout.Tab t, Coord c) {
        boolean on = t == active;
        g.chcolor(on ? new Color(40, 52, 54) : new Color(16, 24, 26));
        g.frect(c, Coord.of(TAB, TAB));
        g.chcolor();
        if (!tabHasItems.getOrDefault(t, false))
            g.chcolor(255, 255, 255, 110);
        drawIcon(g, t.icon, c.add(UI.scale(2), UI.scale(2)), TAB - UI.scale(4));
        g.chcolor();
        boolean hov = hover != null && hover.isect(c, Coord.of(TAB, TAB));
        outline(g, c, Coord.of(TAB, TAB), on ? NStyle.border : hov ? SECTION : FRAME, !t.pinned && !on);
    }

    private void drawPlus(GOut g) {
        g.chcolor(16, 24, 26, 255);
        g.frect(plusPos, Coord.of(TAB, TAB));
        g.chcolor();
        Tex plus = text("#plus", titleFont, "+");
        g.image(plus, plusPos.add(Coord.of(TAB, TAB).sub(plus.sz()).div(2)));
        boolean hov = hover != null && hover.isect(plusPos, Coord.of(TAB, TAB));
        outline(g, plusPos, Coord.of(TAB, TAB), hov ? SECTION : FRAME, false);
    }

    private void drawCell(GOut g, Cell cell, Coord c, boolean hov) {
        g.chcolor(CELL_BG);
        g.frect(c, CELL);
        boolean empty = cell.empty();
        if (empty)
            g.chcolor(255, 255, 255, 60);
        else
            g.chcolor();
        drawIcon(g, cell.name, c.add((CELL.x - ICON) / 2, UI.scale(3)), ICON);
        Coord strip = c.add(1, CELL.y - STRIP - 1);
        g.chcolor(STRIP_BG);
        g.frect(strip, Coord.of(CELL.x - 2, STRIP));
        g.chcolor();
        if (!empty) {
            String n = cell.bulk() ? Utils.odformat2(cell.stock.bulkAmount, 0) : String.valueOf(cell.count);
            Tex nt = text((cell.low() ? "l" : "c") + n, cell.low() ? lowFont : countFont, n);
            g.image(nt, strip.add(UI.scale(2), (STRIP - nt.sz().y) / 2));
            String q = cell.maxQ > 0 ? String.valueOf((int) cell.maxQ) : cell.bulk() ? "" : "?";
            if (!q.isEmpty()) {
                Tex qt = text("q" + q, cell.maxQ > 0 ? qFont : qUnknownFont, q);
                int qx = Math.max(nt.sz().x + UI.scale(4), CELL.x - 2 - qt.sz().x - UI.scale(2));
                g.image(qt, strip.add(qx, (STRIP - qt.sz().y) / 2));
            }
        }
        if (cell.low())
            outline(g, c, CELL, LOW, false);
        else
            outline(g, c, CELL, FRAME, cell.pin == null);
        if (hov)
            outline(g, c, CELL, NStyle.border, false);
    }

    /** The item's icon, or its initials while the icon loads or when the catalogue has none. */
    private void drawIcon(GOut g, String name, Coord c, int side) {
        Tex icon = ItemIcons.get("", name, false);
        if (icon != null) {
            ItemIcons.draw(g, icon, c, side);
            return;
        }
        StringBuilder in = new StringBuilder();
        for (String w : name.split("\\s+"))
            if (!w.isEmpty() && in.length() < 2 && Character.isLetter(w.charAt(0)) && !w.equalsIgnoreCase("of"))
                in.append(Character.toUpperCase(w.charAt(0)));
        Tex t = text("i" + in, initialsFont, in.length() == 0 ? "?" : in.toString());
        g.image(t, c.add(Coord.of(side, side).sub(t.sz()).div(2)));
    }

    private Tex text(String key, Text.Furnace font, String s) {
        if (texts.size() > 600)
            texts.clear();
        return texts.computeIfAbsent(key, k -> font.render(s).tex());
    }

    /** A 1px frame from filled edges (GOut.rect ignores the clip). Dashed for auto-filled slots. */
    private static void outline(GOut g, Coord c, Coord sz, Color col, boolean dashed) {
        g.chcolor(col);
        if (!dashed) {
            g.frect(c, Coord.of(sz.x, 1));
            g.frect(c.add(0, sz.y - 1), Coord.of(sz.x, 1));
            g.frect(c, Coord.of(1, sz.y));
            g.frect(c.add(sz.x - 1, 0), Coord.of(1, sz.y));
        } else {
            int d = UI.scale(3);
            for (int i = 0; i < sz.x; i += 2 * d) {
                int l = Math.min(d, sz.x - i);
                g.frect(c.add(i, 0), Coord.of(l, 1));
                g.frect(c.add(i, sz.y - 1), Coord.of(l, 1));
            }
            for (int i = 0; i < sz.y; i += 2 * d) {
                int l = Math.min(d, sz.y - i);
                g.frect(c.add(0, i), Coord.of(1, l));
                g.frect(c.add(sz.x - 1, i), Coord.of(1, l));
            }
        }
        g.chcolor();
    }

    // ---------------------------------------------------------------- hit testing

    private StashLayout.Tab tabAt(Coord c) {
        for (Map.Entry<StashLayout.Tab, Coord> e : tabPos.entrySet())
            if (c.isect(e.getValue(), Coord.of(TAB, TAB)))
                return e.getKey();
        return null;
    }

    private Cell cellAt(Coord c) {
        if (c.y < pageTop() || c.x >= sz.x - sb.sz.x)
            return null;
        Coord p = c.sub(0, pageTop() - sb.val);
        for (Cell cell : cells)
            if (p.isect(cell.c, CELL))
                return cell;
        return null;
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        hover = ev.c.isect(Coord.z, sz) ? ev.c : null;
        super.mousemove(ev);
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        if (ev.c.y >= pageTop() && sb.max > 0) {
            sb.val = Math.max(0, Math.min(sb.max, sb.val + ev.a * (CELL.y + GAP)));
            return true;
        }
        return super.mousewheel(ev);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (super.mousedown(ev))
            return true;
        StashLayout.Tab tab = tabAt(ev.c);
        if (tab != null) {
            if (ev.b == 1) {
                active = tab;
                sb.val = 0;
                dirty = true;
            } else if (ev.b == 3) {
                tabMenu(tab);
            }
            return true;
        }
        if (ev.c.isect(plusPos, Coord.of(TAB, TAB))) {
            plusMenu();
            return true;
        }
        Cell cell = cellAt(ev.c);
        if (cell != null) {
            if (ev.b == 1)
                openInList.accept(cell.name);
            else if (ev.b == 3)
                cellMenu(cell);
            return true;
        }
        return false;
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        StashLayout.Tab tab = tabAt(c);
        if (tab != null)
            return title(tab) + (tab.pinned ? "" : " (" + L10n.get("storage.stash.auto") + ")");
        if (c.isect(plusPos, Coord.of(TAB, TAB)))
            return L10n.get("storage.stash.plus_tip");
        Cell cell = cellAt(c);
        if (cell == null)
            return super.tooltip(c, prev);
        if (cell == tipKey && tipTex != null)
            return tipTex;
        List<java.awt.image.BufferedImage> lines = new ArrayList<>();
        lines.add(titleFont.render(cell.name).img);
        if (cell.bulk()) {
            lines.add(tipFont.render(Utils.odformat2(cell.stock.bulkAmount, 2) + " " + cell.stock.bulkUnit).img);
        } else if (cell.empty()) {
            lines.add(tipFont.render(L10n.get(minQ > 0 ? "storage.stash.none_at_q" : "storage.stash.none", (int) minQ)).img);
        } else {
            lines.add(tipFont.render(L10n.get("storage.stash.count", cell.count)).img);
            if (cell.maxQ > 0)
                lines.add(tipFont.render(L10n.get("storage.stash.best_q", Utils.odformat2(cell.maxQ, 1))).img);
            if (cell.unknown > 0)
                lines.add(tipFont.render(L10n.get("storage.stash.unknown_q", cell.unknown)).img);
        }
        if (cell.pin != null && cell.pin.target > 0)
            lines.add(tipFont.render(L10n.get("storage.stash.target", cell.pin.target)).img);
        lines.add(tipHintFont.render(L10n.get(cell.pin == null ? "storage.stash.hint_auto" : "storage.stash.hint")).img);
        if (tipTex != null)
            tipTex.dispose();
        tipKey = cell;
        tipTex = new TexI(ItemInfo.catimgs(UI.scale(2), lines.toArray(new java.awt.image.BufferedImage[0])));
        return tipTex;
    }

    // ---------------------------------------------------------------- menus

    private void openMenu(List<String> opts, Consumer<String> onPick) {
        pendingMenu = () -> showMenu(opts, onPick);
    }

    private void showMenu(List<String> opts, Consumer<String> onPick) {
        if (menu != null)
            menu.destroy();
        menu = new NFlowerMenu(opts.toArray(new String[0])) {
            @Override
            public boolean mousedown(MouseDownEvent ev) {
                if (super.mousedown(ev))
                    nchoose(null);
                return true;
            }

            @Override
            public void destroy() {
                menu = null;
                super.destroy();
            }

            @Override
            public void nchoose(NPetal option) {
                if (option != null)
                    onPick.accept(option.name);
                destroy();
            }
        };
        NUtils.getGameUI().add(menu, NUtils.getGameUI().ui.mc.sub(menu.sz.div(2)));
    }

    private void cellMenu(Cell cell) {
        String take = L10n.get("storage.menu_take"), find = L10n.get("storage.stash.find");
        String unpin = L10n.get("storage.stash.unpin"), target = L10n.get("storage.stash.set_target");
        String earlier = L10n.get("storage.stash.move_earlier"), later = L10n.get("storage.stash.move_later");
        Map<String, StashLayout.Tab> pinTo = new LinkedHashMap<>();
        for (StashLayout.Tab t : layout.pinnedTabs())
            if (t.find(cell.name) == null)
                pinTo.put(L10n.get("storage.stash.pin_to", title(t)), t);

        List<String> opts = new ArrayList<>();
        if (!cell.empty())
            opts.add(take);
        opts.add(find);
        if (cell.pin != null)
            opts.addAll(List.of(target, earlier, later, unpin));
        opts.addAll(pinTo.keySet());
        openMenu(opts, o -> {
            if (o.equals(take)) {
                take(cell);
            } else if (o.equals(find)) {
                NInventory inv = NUtils.getGameUI().getInventory();
                if (inv != null)
                    inv.searchFor(cell.name);
            } else if (o.equals(unpin)) {
                active.unpin(cell.name);
                changed();
            } else if (o.equals(target)) {
                prompt(L10n.get("storage.stash.set_target"), L10n.get("storage.stash.target_prompt", cell.name), s -> {
                    try {
                        cell.pin.target = Math.max(0, Integer.parseInt(s.trim()));
                        changed();
                    } catch (NumberFormatException e) {
                        NUtils.getGameUI().msg(L10n.get("storage.stash.bad_number"), Color.YELLOW);
                    }
                });
            } else if (o.equals(earlier) || o.equals(later)) {
                moveSlot(cell.pin, o.equals(earlier) ? -1 : 1);
            } else if (pinTo.containsKey(o)) {
                pinTo.get(o).pin(cell.stock != null ? cell.stock.name : cell.name);
                changed();
            }
        });
    }

    private void tabMenu(StashLayout.Tab tab) {
        String rename = L10n.get("storage.stash.rename"), left = L10n.get("storage.stash.move_left"), right = L10n.get("storage.stash.move_right");
        String section = L10n.get("storage.stash.add_section"), export = L10n.get("storage.stash.export");
        String reset = L10n.get("storage.stash.reset"), delete = L10n.get("storage.stash.delete");
        List<String> opts = new ArrayList<>(List.of(left, right));
        if (tab.pinned) {
            opts.addAll(List.of(rename, section, export));
            if (tab.preset != null)
                opts.add(reset);
            opts.add(delete);
        }
        openMenu(opts, o -> {
            if (o.equals(left) || o.equals(right)) {
                moveTab(tab, o.equals(left) ? -1 : 1);
            } else if (o.equals(rename)) {
                prompt(rename, L10n.get("storage.stash.tab_name"), s -> {
                    tab.title = s;
                    changed();
                });
            } else if (o.equals(section)) {
                prompt(section, L10n.get("storage.stash.section_name"), s -> {
                    tab.sections.add(new StashLayout.Section(s));
                    changed();
                });
            } else if (o.equals(export)) {
                if (DbClipboard.copy(StashLayout.export(tab)))
                    NUtils.getGameUI().msg(L10n.get("storage.stash.exported", title(tab)));
            } else if (o.equals(reset)) {
                StashLayout.Tab fresh = layout.resetPreset(tab);
                if (fresh != null) {
                    active = fresh;
                    changed();
                }
            } else if (o.equals(delete)) {
                layout.remove(tab);
                active = null;
                changed();
            }
        });
    }

    private void plusMenu() {
        String create = L10n.get("storage.stash.new_tab"), paste = L10n.get("storage.stash.import");
        String presets = L10n.get("storage.stash.add_presets");
        List<StashLayout.Tab> fresh = layout.newPresets();
        List<String> opts = new ArrayList<>(List.of(create, paste));
        if (!fresh.isEmpty())
            opts.add(presets);
        openMenu(opts, o -> {
            if (o.equals(create)) {
                prompt(create, L10n.get("storage.stash.tab_name"), s -> {
                    active = layout.newTab(s);
                    changed();
                    NUtils.getGameUI().msg(L10n.get("storage.stash.new_tab_hint"));
                });
            } else if (o.equals(paste)) {
                StashLayout.Tab t = layout.importTab(clipboard());
                if (t == null) {
                    NUtils.getGameUI().msg(L10n.get("storage.stash.import_failed"), Color.YELLOW);
                } else {
                    active = t;
                    changed();
                }
            } else if (o.equals(presets)) {
                layout.addPresets(fresh);
                changed();
            }
        });
    }

    /** Presets shipped after the user's layout file was made are announced once per session. */
    private void offerNewPresets() {
        List<StashLayout.Tab> fresh = layout.newPresets();
        if (!fresh.isEmpty() && NUtils.getGameUI() != null)
            NUtils.getGameUI().msg(L10n.get("storage.stash.presets_available", fresh.size()));
    }

    private void moveTab(StashLayout.Tab tab, int dir) {
        List<StashLayout.Tab> tabs = layout.tabs;
        int i = tabs.indexOf(tab), j = i + dir;
        // Pinned and auto tabs stay in their own groups.
        if (i < 0 || j < 0 || j >= tabs.size() || tabs.get(j).pinned != tab.pinned)
            return;
        Collections.swap(tabs, i, j);
        changed();
    }

    private void moveSlot(StashLayout.Slot slot, int dir) {
        List<StashLayout.Section> secs = active.sections;
        for (int s = 0; s < secs.size(); s++) {
            List<StashLayout.Slot> list = secs.get(s).slots;
            int i = list.indexOf(slot);
            if (i < 0)
                continue;
            int j = i + dir;
            if (j >= 0 && j < list.size()) {
                Collections.swap(list, i, j);
            } else if (s + dir >= 0 && s + dir < secs.size()) {
                // Off the end of a section: move into the neighbouring one.
                list.remove(i);
                List<StashLayout.Slot> next = secs.get(s + dir).slots;
                if (dir < 0)
                    next.add(slot);
                else
                    next.add(0, slot);
            }
            changed();
            return;
        }
    }

    private void take(Cell cell) {
        if (cell.stock == null)
            return;
        List<StorageItemDao.StorageItemData> rows = cell.stock.rows(minQ);
        NStorageItemsWidget.GroupedItem item = new NStorageItemsWidget.GroupedItem(cell.stock.name, -1, rows.size(), rows);
        NStorageItemsWidget.requestTake(item, rows);
    }

    private void changed() {
        layout.save();
        dirty = true;
    }

    private static void prompt(String title, String label, Consumer<String> onText) {
        TextInputWindow w = new TextInputWindow(title, label, s -> {
            if (s != null && !s.trim().isEmpty())
                onText.accept(s.trim());
        });
        NUtils.getGameUI().add(w, NUtils.getGameUI().sz.div(2).sub(w.sz.div(2)));
        w.show();
        w.raise();
    }

    private static String clipboard() {
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return data instanceof String ? (String) data : null;
        } catch (UnsupportedFlavorException | IOException | IllegalStateException | java.awt.HeadlessException e) {
            return null;
        }
    }
}
