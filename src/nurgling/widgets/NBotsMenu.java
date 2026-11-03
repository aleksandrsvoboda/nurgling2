package nurgling.widgets;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.actions.bots.CatchBugsAround;
import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;
import nurgling.i18n.L10n;
import nurgling.sessions.BotExecutor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * The bot menu: a strip of folder icons (Favourites first). Opening a folder shows its bots beside
 * the strip, split into labelled groups; clicking the open folder again closes the panel. The
 * drag-mode turn button picks the side the panel opens on: right, up, left or down.
 * <p>
 * Tiles are drawn by this widget rather than being child buttons, because a favourite shows the
 * same {@link NButton} twice (its folder and Favourites). Belt bindings, recent actions and the
 * action search keep finding buttons through {@link #find(String)}.
 */
public class NBotsMenu extends Widget implements NDraggableWidget.Turnable
{
    final static String dir_path = "nurgling/bots/icons/";

    private static final List<BotDescriptor.BotType> ORDER = List.of(
            BotDescriptor.BotType.RESOURCES,
            BotDescriptor.BotType.PRODUCTIONS,
            BotDescriptor.BotType.BATTLE,
            BotDescriptor.BotType.FARMING,
            BotDescriptor.BotType.FARMING_QUALITY,
            BotDescriptor.BotType.ANIMALS,
            BotDescriptor.BotType.UTILS,
            BotDescriptor.BotType.BUILD,
            BotDescriptor.BotType.TOOLS
    );
    private static final Map<BotDescriptor.BotType, String> FOLDER_ICON = Map.of(
            BotDescriptor.BotType.RESOURCES, "resources",
            BotDescriptor.BotType.PRODUCTIONS, "productions",
            BotDescriptor.BotType.BATTLE, "battle",
            BotDescriptor.BotType.FARMING, "farming",
            BotDescriptor.BotType.FARMING_QUALITY, "quality",
            BotDescriptor.BotType.ANIMALS, "animals",
            BotDescriptor.BotType.UTILS, "utils",
            BotDescriptor.BotType.BUILD, "build",
            BotDescriptor.BotType.TOOLS, "tools"
    );

    private static final Color GOLD = new Color(242, 201, 76);
    private static final Color MUTE = new Color(143, 163, 164);
    private static final Color BG = new Color(16, 28, 29, 225);
    private static final Color RAIL_BG = new Color(12, 22, 23, 235);
    private static final Color HOVER = new Color(255, 255, 255, 18);
    private static final Color ACTIVE = new Color(122, 209, 122);
    private static final Text.Foundry railf = new Text.Foundry(Text.sans, 12).aa(true);
    private static final Text.Foundry grpf = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 10).aa(true);

    private static final int FAV = -1;
    private static final int NONE = -3;   // no folder open: only the icon column shows
    private static final int MIN_COLS = 7, MAX_COLS = 14;
    /** Side the panel opens on, in turn-button order (counter-clockwise, like its arrow). */
    private static final int RIGHT = 0, UP = 1, LEFT = 2, DOWN = 3;
    private final int TILE = UI.scale(34);
    private final int PAD = UI.scale(4);
    private final int ROW = UI.scale(34);
    private final int HDR = UI.scale(16);
    private final int GAP = UI.scale(6);

    private final List<NButton> buttons = new ArrayList<>();
    private final Map<String, NButton> byId = new HashMap<>();
    private final List<Folder> folders = new ArrayList<>();
    private final BotFavourites favs = new BotFavourites();
    private final Tex starBig = new TexI(star(UI.scale(26)));
    private final Tex starSmall = new TexI(star(UI.scale(11)));

    private final int railW;      // strip thickness
    private int stripLen;         // strip length
    /** Strip entries: FAV, then the indexes of the folders shown. */
    private final List<Integer> rail = new ArrayList<>();
    private int cols = MIN_COLS;
    private int dir = RIGHT;
    private Coord railOff = Coord.z, paneOff = Coord.z, paneSz = Coord.z, shift = Coord.z;

    private int sel = NONE;
    private boolean debug;
    private boolean dirty = true;
    private final List<Item> items = new ArrayList<>();
    private final List<int[]> secSpan = new ArrayList<>(); // favourites: {top, bottom} per section, content coords
    private final Map<String, Tex> textCache = new HashMap<>();

    private Coord hover = null;
    private Item pressed = null;
    private Item dragging = null;
    private Coord pressAt = null;
    private UI.Grab grab = null;
    private DropAt dropAt = null;
    private double pulseUntil = 0;
    private int renaming = -1;
    private TextEntry renameBox = null;
    private UI.Grab renameKeys = null, renameMouse = null;

    public NBotsMenu() {
        for (BotDescriptor bot : BotRegistry.allowedInBotMenu()) {
            NButton b = (bot.clazz == CatchBugsAround.class)
                    ? new NToggleNButton(bot, bot.instantiate(Map.of()))
                    : new NButton(bot, bot.instantiate(Map.of()));
            buttons.add(b);
            byId.put(bot.id, b);
        }
        for (BotDescriptor.BotType type : ORDER)
            folders.add(new Folder(type));
        railW = TILE + 2 * PAD;
        debug = isDebug();
        buildRail();
        sz = stripSz();
        favs.sync();
    }

    private static boolean isDebug() {
        return Boolean.TRUE.equals(NConfig.get(NConfig.Key.debug));
    }

    private class Folder {
        final BotDescriptor.BotType type;
        final String name;
        final Tex icon;
        final List<Group> groups = new ArrayList<>();

        Folder(BotDescriptor.BotType type) {
            this.type = type;
            this.name = L10n.get("bot.type." + type.name().toLowerCase());
            String ic = FOLDER_ICON.getOrDefault(type, type.name().toLowerCase());
            this.icon = new TexI(Resource.loadsimg(dir_path + ic + "/u"));
        }

        void rebuild() {
            groups.clear();
            Map<String, Group> byKey = new LinkedHashMap<>();
            for (String key : BotRegistry.groupsOf(type))
                byKey.put(key, new Group(L10n.get("bot.group." + key)));
            Group other = new Group(L10n.get("bot.group.other"));
            for (NButton b : buttons) {
                if (b.bot.type != type)
                    continue;
                Group g = (b.bot.group() == null) ? null : byKey.get(b.bot.group());
                (g == null ? other : g).bots.add(b);
            }
            groups.addAll(byKey.values());
            groups.add(other);
            groups.removeIf(g -> g.bots.isEmpty());
            for (Group g : groups)
                g.bots.sort(Comparator.comparing(b -> b.bot.getDisplayName(), String.CASE_INSENSITIVE_ORDER));
        }
    }

    private static class Group {
        final String name;
        final List<NButton> bots = new ArrayList<>();

        Group(String name) {
            this.name = name;
        }
    }

    private enum Kind { TILE, HEADER, FAV_HEADER, PLACEHOLDER, ADD_SECTION, EMPTY }

    private static class Item {
        final Kind kind;
        final Coord ul, sz;      // content coords (before scrolling)
        NButton b;
        String text;
        int sec = -1, idx = -1;

        Item(Kind kind, Coord ul, Coord sz) {
            this.kind = kind;
            this.ul = ul;
            this.sz = sz;
        }

        boolean has(Coord c) {
            return c.isect(ul, sz);
        }
    }

    /** Where a drag would land: a favourites position, a section slot, or the Favourites tab. */
    private static class DropAt {
        boolean tab;
        int sec = -1, pos = -1;   // tile: insert into section at pos
        int secSlot = -1;         // section header: move before this section
    }

    /* ---------------------------------------------------------------- layout */

    private boolean horizontal() {
        return dir == UP || dir == DOWN;
    }

    private Coord stripSz() {
        return horizontal() ? new Coord(stripLen, railW) : new Coord(railW, stripLen);
    }

    /**
     * Lays the open folder out without scrolling. Every folder uses the same column count, so
     * look-alike folders such as Farming and Quality line up and icons don't shift between folders.
     * Beside a column of icons that is the fewest columns letting the biggest folder fit the column's
     * height (up to MAX_COLS); under or over a row of icons the panel is as wide as the row and grows
     * downward/upward instead. Favourites may go wider when it holds more.
     */
    private void relayout() {
        dirty = false;
        for (Folder f : folders)
            f.rebuild();
        buildRail();
        if (sel >= 0 && !rail.contains(sel))
            sel = NONE;
        Coord nsz;
        if (sel == NONE) {
            items.clear();
            secSpan.clear();
            railOff = paneOff = paneSz = shift = Coord.z;
            nsz = stripSz();
        } else {
            boolean hor = horizontal();
            int c = hor ? Math.max(MIN_COLS, (stripLen - 2 * PAD) / TILE) : MIN_COLS;
            if (!hor) {
                for (int i : rail)
                    if (i != FAV)
                        c = Math.max(c, fitCols(i));
            }
            if (sel == FAV)
                c = Math.max(c, fitCols(FAV));
            cols = c;
            int h = layoutItems(sel);
            int pw = PAD + cols * TILE + PAD;
            int sep = UI.scale(1);
            if (hor) {
                pw = Math.max(pw, stripLen);
                paneSz = new Coord(pw, h + PAD);
                railOff = (dir == DOWN) ? Coord.z : new Coord(0, paneSz.y + sep);
                paneOff = (dir == DOWN) ? new Coord(0, railW + sep) : Coord.z;
                nsz = new Coord(pw, railW + sep + paneSz.y);
                shift = (dir == UP) ? new Coord(0, paneSz.y + sep) : Coord.z;
            } else {
                paneSz = new Coord(pw, Math.max(stripLen, h));
                railOff = (dir == RIGHT) ? Coord.z : new Coord(pw + sep, 0);
                paneOff = (dir == RIGHT) ? new Coord(railW + sep, 0) : Coord.z;
                nsz = new Coord(railW + sep + pw, paneSz.y);
                shift = (dir == LEFT) ? new Coord(pw + sep, 0) : Coord.z;
            }
        }
        if (!nsz.equals(sz)) {
            resize(nsz);
            if (parent instanceof NDraggableWidget)
                parent.resize(nsz.add(NDraggableWidget.delta));
        }
        if (parent instanceof NDraggableWidget)
            ((NDraggableWidget) parent).place();
    }

    @Override
    public void rotate(int rot) {
        commitRename();
        dir = Math.floorMod(rot, 4);
        relayout();
    }

    @Override
    public Coord anchorShift() {
        return shift;
    }

    /** Fewest columns (up to MAX_COLS) that fit a folder in the icon strip's length. */
    private int fitCols(int folder) {
        for (cols = MIN_COLS; cols < MAX_COLS; cols++)
            if (layoutItems(folder) <= stripLen)
                return cols;
        return MAX_COLS;
    }

    /** Builds the items for a folder at the current column count; returns the content height. */
    private int layoutItems(int sel) {
        items.clear();
        secSpan.clear();
        int w = cols * TILE;
        int y = PAD;
        if (sel == FAV) {
            List<BotFavourites.Section> secs = favs.sections();
            if (favs.count() == 0) {
                Item e = new Item(Kind.EMPTY, new Coord(PAD, y), new Coord(w, UI.scale(64)));
                items.add(e);
                y += e.sz.y;
            }
            for (int i = 0; i < secs.size(); i++) {
                int top = y;
                Item h = new Item(Kind.FAV_HEADER, new Coord(PAD, y), new Coord(w, HDR));
                h.sec = i;
                h.text = secs.get(i).name;
                items.add(h);
                y += HDR + UI.scale(2);
                List<String> ids = secs.get(i).ids;
                if (ids.isEmpty()) {
                    Item p = new Item(Kind.PLACEHOLDER, new Coord(PAD, y), new Coord(w, TILE));
                    p.sec = i;
                    items.add(p);
                    y += TILE;
                } else {
                    int j = 0;
                    for (String id : ids) {
                        NButton b = byId.get(id);
                        if (b == null) continue;
                        Item t = new Item(Kind.TILE, new Coord(PAD + (j % cols) * TILE, y + (j / cols) * TILE), new Coord(TILE, TILE));
                        t.b = b;
                        t.sec = i;
                        t.idx = j++;
                        items.add(t);
                    }
                    y += ((j + cols - 1) / cols) * TILE;
                }
                secSpan.add(new int[]{top, y});
                y += GAP;
            }
            items.add(new Item(Kind.ADD_SECTION, new Coord(PAD, y), new Coord(w, UI.scale(18))));
            y += UI.scale(18) + PAD;
        } else {
            Folder f = folders.get(sel);
            // Headings whenever the folder has named groups, so Farming and Quality read alike.
            boolean headers = !BotRegistry.groupsOf(f.type).isEmpty();
            for (Group g : f.groups) {
                if (headers) {
                    Item h = new Item(Kind.HEADER, new Coord(PAD, y), new Coord(w, HDR));
                    h.text = g.name;
                    items.add(h);
                    y += HDR + UI.scale(2);
                }
                int j = 0;
                for (NButton b : g.bots) {
                    Item t = new Item(Kind.TILE, new Coord(PAD + (j % cols) * TILE, y + (j / cols) * TILE), new Coord(TILE, TILE));
                    t.b = b;
                    items.add(t);
                    j++;
                }
                y += ((j + cols - 1) / cols) * TILE + GAP;
            }
            y -= GAP;  // no gap under the last group
        }
        return y;
    }

    /** Rail click: opens a folder, or closes the panel when it is the open one. */
    private void toggle(int folder) {
        open(folder == sel ? NONE : folder);
    }

    private void open(int folder) {
        commitRename();
        sel = folder;
        relayout();
    }

    /* ---------------------------------------------------------------- geometry helpers */

    private boolean inPane(Coord c) {
        return sel != NONE && c.isect(paneOff, paneSz);
    }

    /** Widget coords to pane content coords. */
    private Coord content(Coord c) {
        return c.sub(paneOff);
    }

    /** The Tools folder holds developer tools: it only shows while the Debug option is on. */
    private void buildRail() {
        rail.clear();
        rail.add(FAV);
        for (int i = 0; i < folders.size(); i++)
            if (debug || folders.get(i).type != BotDescriptor.BotType.TOOLS)
                rail.add(i);
        stripLen = rail.size() * ROW + 2 * PAD;
    }

    /** Top-left of strip entry r, widget coords. */
    private Coord cellUl(int r) {
        return railOff.add(horizontal() ? new Coord(PAD + r * ROW, 0) : new Coord(0, PAD + r * ROW));
    }

    private Coord cellSz() {
        return horizontal() ? new Coord(ROW, railW) : new Coord(railW, ROW);
    }

    /** Strip entry under c: -2 none, FAV favourites, else folder index. */
    private int railAt(Coord c) {
        Coord rc = c.sub(railOff);
        int across = horizontal() ? rc.y : rc.x, along = horizontal() ? rc.x : rc.y;
        if (across < 0 || across >= railW || along < PAD)
            return -2;
        int r = (along - PAD) / ROW;
        return (r < rail.size()) ? rail.get(r) : -2;
    }

    private Item itemAt(Coord c) {
        if (!inPane(c))
            return null;
        Coord cc = content(c);
        for (Item it : items)
            if (it.has(cc))
                return it;
        return null;
    }

    private Coord deleteBoxUl(Item h) {
        return new Coord(h.ul.x + h.sz.x - HDR, h.ul.y);
    }

    private boolean onDelete(Item h, Coord c) {
        return content(c).isect(deleteBoxUl(h), new Coord(HDR, HDR));
    }

    /* ---------------------------------------------------------------- tick & draw */

    @Override
    public void tick(double dt) {
        boolean d = isDebug();
        if (d != debug) {
            debug = d;
            dirty = true;
        }
        if (favs.sync()) {
            if (renaming >= 0) commitRename();
            dirty = true;
        }
        if (dirty)
            relayout();
        super.tick(dt);
    }

    private Tex text(String s, Color c, Text.Foundry f) {
        String key = s + "\0" + c.getRGB() + "\0" + System.identityHashCode(f);
        Tex t = textCache.get(key);
        if (t == null) {
            if (textCache.size() > 256) textCache.clear();
            textCache.put(key, t = f.render(s, c).tex());
        }
        return t;
    }

    @Override
    public void draw(GOut g) {
        if (dirty)
            relayout();
        boolean hor = horizontal();
        g.chcolor(RAIL_BG);
        g.frect(railOff, hor ? new Coord(sz.x, railW) : new Coord(railW, sz.y));
        if (sel != NONE) {
            g.chcolor(BG);
            g.frect(paneOff, paneSz);
            g.chcolor(NStyle.separator);
            if (hor)
                g.frect(new Coord(0, (dir == DOWN) ? railW : paneSz.y), new Coord(sz.x, UI.scale(1)));
            else
                g.frect(new Coord((dir == RIGHT) ? railW : paneSz.x, 0), new Coord(UI.scale(1), sz.y));
        }
        g.chcolor();
        drawRail(g);
        if (sel != NONE)
            drawPane(g.reclip(paneOff, paneSz));
        super.draw(g);
        if (dragging != null)
            drawGhost();
    }

    private void drawRail(GOut g) {
        int railHover = (hover != null && dragging == null) ? railAt(hover) : -2;
        boolean dropTab = dropAt != null && dropAt.tab;
        double now = Utils.rtime();
        Coord csz = cellSz();
        int bar = UI.scale(3);
        for (int r = 0; r < rail.size(); r++) {
            int key = rail.get(r);
            Coord ul = cellUl(r);
            boolean on = key == sel;
            if (key == FAV && (dropTab || now < pulseUntil)) {
                int a = dropTab ? 90 : (int) (90 * (pulseUntil - now) / 0.6);
                g.chcolor(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), Math.max(0, a));
                g.frect(ul, csz);
            } else if (on) {
                g.chcolor(NStyle.rowOdd);
                g.frect(ul, csz);
            } else if (key == railHover) {
                g.chcolor(HOVER);
                g.frect(ul, csz);
            }
            if (on) {
                // Marker on the strip's outer edge, away from the panel.
                g.chcolor(key == FAV ? GOLD : NStyle.border);
                switch (dir) {
                    case RIGHT: g.frect(ul, new Coord(bar, csz.y)); break;
                    case LEFT:  g.frect(ul.add(csz.x - bar, 0), new Coord(bar, csz.y)); break;
                    case DOWN:  g.frect(ul, new Coord(csz.x, bar)); break;
                    default:    g.frect(ul.add(0, csz.y - bar), new Coord(csz.x, bar)); break;
                }
            }
            g.chcolor();
            Tex icon = (key == FAV) ? starBig : folders.get(key).icon;
            g.image(icon, ul.add(csz.sub(icon.sz()).div(2)));
        }
    }

    private void drawPane(GOut g) {
        Coord off = Coord.z;
        Coord hc = (hover != null && dragging == null && inPane(hover)) ? content(hover) : null;
        for (Item it : items) {
            Coord ul = it.ul.add(off);
            if (ul.y + it.sz.y < 0 || ul.y > g.sz().y)
                continue;
            boolean hov = hc != null && it.has(hc);
            switch (it.kind) {
                case TILE:
                    drawTile(g, it, ul, hov);
                    break;
                case HEADER:
                    drawHeader(g, ul, it.sz.x, it.text, MUTE, NStyle.separator);
                    break;
                case FAV_HEADER:
                    drawHeader(g, ul, it.sz.x, it.text, GOLD, new Color(80, 74, 40));
                    if (renaming != it.sec) {
                        boolean del = hov && onDelete(it, hover);
                        drawCross(g, ul.add(it.sz.x - HDR, 0), del ? NStyle.border : MUTE);
                    }
                    if (dropAt != null && dropAt.secSlot == it.sec) {
                        g.chcolor(GOLD);
                        g.frect(ul.sub(0, UI.scale(3)), new Coord(it.sz.x, UI.scale(2)));
                        g.chcolor();
                    }
                    break;
                case PLACEHOLDER: {
                    boolean target = dropAt != null && dropAt.sec == it.sec;
                    g.chcolor(target ? GOLD : NStyle.separator);
                    g.rect(ul, it.sz);
                    g.chcolor();
                    Tex t = text(L10n.get("botmenu.fav.drop_here"), MUTE, railf);
                    g.image(t, ul.add(it.sz.sub(t.sz()).div(2)));
                    break;
                }
                case ADD_SECTION: {
                    Tex t = text("+ " + L10n.get("botmenu.fav.new_section"), hov ? GOLD : MUTE, railf);
                    g.image(t, ul.add(0, (it.sz.y - t.sz().y) / 2));
                    break;
                }
                case EMPTY: {
                    int y = ul.y + UI.scale(6);
                    for (String line : L10n.get("botmenu.fav.empty").split("\n")) {
                        Tex t = text(line, MUTE, railf);
                        g.image(t, new Coord(ul.x + (it.sz.x - t.sz().x) / 2, y));
                        y += t.sz().y + UI.scale(2);
                    }
                    break;
                }
            }
        }
        if (dropAt != null && dropAt.sec >= 0 && dropAt.pos >= 0)
            drawInsertMarker(g, off);
        if (dropAt != null && dropAt.secSlot == favs.sections().size() && !secSpan.isEmpty()) {
            int y = secSpan.get(secSpan.size() - 1)[1] + off.y + UI.scale(1);
            g.chcolor(GOLD);
            g.frect(new Coord(PAD, y), new Coord(cols * TILE, UI.scale(2)));
            g.chcolor();
        }
    }

    private void drawTile(GOut g, Item it, Coord ul, boolean hov) {
        NButton b = it.b;
        boolean down = hov && pressed == it && dragging == null;
        Tex t = b.tex(down ? 2 : (hov ? 1 : 0));
        g.image(t, ul.add(new Coord(TILE, TILE).sub(t.sz()).div(2)));
        if (b instanceof NToggleNButton && ((NToggleNButton) b).active) {
            g.chcolor(ACTIVE);
            g.rect(ul.add(1, 1), new Coord(TILE - 2, TILE - 2));
            g.chcolor();
        }
        if (sel != FAV && favs.contains(b.bot.id))
            g.image(starSmall, ul.add(UI.scale(2), UI.scale(2)));
    }

    private void drawHeader(GOut g, Coord ul, int w, String name, Color col, Color line) {
        Tex t = text(name.toUpperCase(), col, grpf);
        g.image(t, ul.add(UI.scale(2), (HDR - t.sz().y) / 2 - UI.scale(1)));
        g.chcolor(line);
        g.frect(new Coord(ul.x, ul.y + HDR - UI.scale(1)), new Coord(w, UI.scale(1)));
        g.chcolor();
    }

    private void drawCross(GOut g, Coord ul, Color col) {
        int s = UI.scale(7);
        Coord a = ul.add((HDR - s) / 2, (HDR - s) / 2 - UI.scale(1));
        g.chcolor(col);
        g.line(a, a.add(s, s), UI.scale(1.5));
        g.line(a.add(s, 0), a.add(0, s), UI.scale(1.5));
        g.chcolor();
    }

    private void drawInsertMarker(GOut g, Coord off) {
        List<String> ids = favs.sections().get(dropAt.sec).ids;
        if (ids.isEmpty())
            return;  // the placeholder lights up instead
        int i = Math.min(dropAt.pos, ids.size());
        int col, row;
        int top = secSpan.get(dropAt.sec)[0] + HDR + UI.scale(2);
        if (i < ids.size()) {
            col = i % cols;
            row = i / cols;
        } else {
            col = ((i - 1) % cols) + 1;
            row = (i - 1) / cols;
        }
        Coord p = new Coord(PAD + col * TILE - UI.scale(1), top + row * TILE + UI.scale(2)).add(off);
        g.chcolor(GOLD);
        g.frect(p, new Coord(UI.scale(2), TILE - UI.scale(4)));
        g.chcolor();
    }

    private void drawGhost() {
        final Tex t;
        if (dragging.kind == Kind.TILE)
            t = dragging.b.tex(0);
        else
            t = text(dragging.text.toUpperCase(), GOLD, grpf);
        final boolean removing = dragging.kind == Kind.TILE && dragging.sec >= 0 && dropAt == null && hover != null && !hover.isect(Coord.z, sz);
        ui.drawafter(new UI.AfterDraw() {
            public void draw(GOut g) {
                if (removing) g.chcolor(255, 255, 255, 90);
                g.image(t, ui.mc.sub(t.sz().div(2)));
                g.chcolor();
            }
        });
    }

    private static BufferedImage star(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Path2D.Double p = new Path2D.Double();
        double cx = size / 2.0, cy = size / 2.0 + size * 0.04, ro = size * 0.47, ri = ro * 0.45;
        for (int i = 0; i < 10; i++) {
            double r = (i % 2 == 0) ? ro : ri;
            double a = -Math.PI / 2 + i * Math.PI / 5;
            double x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        g.setColor(GOLD);
        g.fill(p);
        g.setStroke(new BasicStroke(Math.max(1f, size / 14f)));
        g.setColor(new Color(60, 40, 10));
        g.draw(p);
        g.dispose();
        return img;
    }

    /* ---------------------------------------------------------------- mouse */

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (renameBox != null) {
            if (ev.c.isect(renameBox.c, renameBox.sz))
                return super.mousedown(ev);
            commitRename();
            return true;
        }
        if (ev.b == 1) {
            int r = railAt(ev.c);
            if (r != -2) {
                toggle(r);
                return true;
            }
            Item it = itemAt(ev.c);
            if (it != null) {
                switch (it.kind) {
                    case TILE:
                        press(it, ev.c);
                        return true;
                    case FAV_HEADER:
                        if (onDelete(it, ev.c)) {
                            favs.removeSection(it.sec);
                            dirty = true;
                        } else {
                            press(it, ev.c);
                        }
                        return true;
                    case ADD_SECTION:
                        beginRename(favs.addSection(L10n.get("botmenu.fav.new_section")));
                        return true;
                    default:
                        break;
                }
            }
        } else if (ev.b == 3) {
            Item it = itemAt(ev.c);
            if (it != null && it.kind == Kind.TILE) {
                openPopup(it.b);
                return true;
            }
        }
        return ev.c.isect(Coord.z, sz) || super.mousedown(ev);
    }

    private void press(Item it, Coord c) {
        pressed = it;
        pressAt = c;
        if (grab == null)
            grab = ui.grabmouse(this);
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        hover = ev.c;
        if (pressed != null && dragging == null && ev.c.dist(pressAt) > UI.scale(5))
            dragging = pressed;
        if (dragging != null)
            dropAt = dropTarget(ev.c);
        super.mousemove(ev);
    }

    @Override
    public boolean mouseup(MouseUpEvent ev) {
        if (ev.b == 1 && grab != null) {
            grab.remove();
            grab = null;
            Item p = pressed, d = dragging;
            pressed = dragging = null;
            DropAt at = dropAt;
            dropAt = null;
            if (d != null) {
                drop(d, at, ev.c);
            } else if (p != null) {
                if (p.kind == Kind.TILE && itemAt(ev.c) == p)
                    p.b.btn.click();
                else if (p.kind == Kind.FAV_HEADER && itemAt(ev.c) == p)
                    beginRename(p.sec);
            }
            return true;
        }
        return super.mouseup(ev);
    }

    private DropAt dropTarget(Coord c) {
        DropAt at = new DropAt();
        if (dragging.kind == Kind.FAV_HEADER) {
            if (sel != FAV || !inPane(c) || secSpan.isEmpty())
                return null;
            int y = content(c).y;
            at.secSlot = secSpan.size();
            for (int i = 0; i < secSpan.size(); i++) {
                int[] s = secSpan.get(i);
                if (y < (s[0] + s[1]) / 2) {
                    at.secSlot = i;
                    break;
                }
            }
            if (at.secSlot == dragging.sec || at.secSlot == dragging.sec + 1)
                return null;
            return at;
        }
        if (railAt(c) == FAV) {
            at.tab = true;
            return at;
        }
        if (sel != FAV || !inPane(c) || secSpan.isEmpty())
            return null;
        Coord cc = content(c);
        int sec = secSpan.size() - 1;
        for (int i = 0; i < secSpan.size(); i++) {
            if (cc.y < secSpan.get(i)[1] + GAP / 2) {
                sec = i;
                break;
            }
        }
        at.sec = sec;
        at.pos = favs.sections().get(sec).ids.size();
        for (Item it : items) {
            if (it.kind != Kind.TILE || it.sec != sec)
                continue;
            if (cc.y < it.ul.y || (cc.y < it.ul.y + it.sz.y && cc.x < it.ul.x + it.sz.x / 2)) {
                at.pos = it.idx;
                break;
            }
        }
        return at;
    }

    private void drop(Item d, DropAt at, Coord c) {
        if (d.kind == Kind.FAV_HEADER) {
            if (at != null && at.secSlot >= 0)
                favs.moveSection(d.sec, at.secSlot);
        } else if (at != null && at.tab) {
            if (favs.add(d.b.bot.id, defaultSectionName()))
                pulse();
        } else if (at != null && at.sec >= 0) {
            if (favs.put(d.b.bot.id, at.sec, at.pos, defaultSectionName()))
                pulse();
        } else if (!c.isect(Coord.z, sz)) {
            boolean taken = DropTarget.dropthing(ui.root, ui.mc, d.b);
            if (!taken && d.sec >= 0)
                favs.remove(d.b.bot.id);
        }
        dirty = true;
    }

    private void pulse() {
        pulseUntil = Utils.rtime() + 0.6;
    }

    private static String defaultSectionName() {
        return L10n.get("botmenu.fav.default_section");
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        if (dragging != null)
            return null;
        Item it = itemAt(c);
        if (it != null && it.kind == Kind.TILE)
            return it.b.btn.tooltip(c, prev);
        if (it != null && it.kind == Kind.FAV_HEADER && onDelete(it, c))
            return L10n.get("botmenu.fav.delete_section");
        int r = railAt(c);
        if (r == FAV)
            return L10n.get("botmenu.favourites");
        if (r >= 0)
            return folders.get(r).name;
        return super.tooltip(c, prev);
    }

    /* ---------------------------------------------------------------- popup & rename */

    private void openPopup(NButton b) {
        String id = b.bot.id;
        int[] at = favs.find(id);
        List<NBotMenuPopup.Entry> entries = new ArrayList<>();
        List<BotFavourites.Section> secs = favs.sections();
        if (secs.isEmpty()) {
            entries.add(new NBotMenuPopup.Entry(L10n.get("botmenu.fav.add"), () -> {
                if (favs.add(id, defaultSectionName())) pulse();
                dirty = true;
            }));
        }
        for (int i = 0; i < secs.size(); i++) {
            if (at != null && at[0] == i)
                continue;
            final int s = i;
            String label = L10n.get(at == null ? "botmenu.fav.add_to" : "botmenu.fav.move_to", secs.get(i).name);
            entries.add(new NBotMenuPopup.Entry(label, () -> {
                if (favs.put(id, s, null, defaultSectionName())) pulse();
                dirty = true;
            }));
        }
        entries.add(new NBotMenuPopup.Entry(L10n.get("botmenu.fav.to_new_section"), () -> {
            int s = favs.addSection(L10n.get("botmenu.fav.new_section"));
            if (favs.put(id, s, null, defaultSectionName())) pulse();
            beginRename(s);
        }));
        if (at != null) {
            entries.add(new NBotMenuPopup.Entry(L10n.get("botmenu.fav.remove"), () -> {
                favs.remove(id);
                dirty = true;
            }));
        }
        NBotMenuPopup p = new NBotMenuPopup(entries);
        Coord pc = ui.mc.add(UI.scale(4), UI.scale(4));
        pc = new Coord(Math.max(0, Math.min(pc.x, ui.root.sz.x - p.sz.x)), Math.max(0, Math.min(pc.y, ui.root.sz.y - p.sz.y)));
        ui.root.add(p, pc);
    }

    private void beginRename(int sec) {
        commitRename();
        if (sec < 0 || sec >= favs.sections().size())
            return;
        open(FAV);
        Item h = null;
        for (Item it : items)
            if (it.kind == Kind.FAV_HEADER && it.sec == sec)
                h = it;
        if (h == null)
            return;
        renaming = sec;
        String name = favs.sections().get(sec).name;
        renameBox = add(new TextEntry(h.sz.x - HDR, name) {
            @Override
            public void activate(String text) {
                commitRename();
            }

            @Override
            public boolean keydown(KeyDownEvent ev) {
                if (key_esc.match(ev)) {
                    cancelRename();
                    return true;
                }
                return super.keydown(ev);
            }
        }, paneOff.add(h.ul.x, h.ul.y - UI.scale(2)));
        renameBox.settext(name);
        renameKeys = ui.grabkeys(renameBox);
        // Own grab, so a click anywhere else saves the name (the press grab ends on mouse-up).
        renameMouse = ui.grabmouse(this);
    }

    private void commitRename() {
        if (renameBox == null)
            return;
        String v = renameBox.text().trim();
        int sec = renaming;
        closeRename();
        if (!v.isEmpty())
            favs.renameSection(sec, v);
        dirty = true;
    }

    private void cancelRename() {
        closeRename();
        dirty = true;
    }

    private void closeRename() {
        TextEntry box = renameBox;
        renameBox = null;
        renaming = -1;
        if (renameMouse != null) {
            renameMouse.remove();
            renameMouse = null;
        }
        if (renameKeys != null) {
            renameKeys.remove();
            renameKeys = null;
        }
        if (box != null)
            box.reqdestroy();
    }

    /* ---------------------------------------------------------------- lookup */

    public NButton find(String path) {
        for (NButton b : buttons)
            if (b.path != null && b.path.equals(path))
                return b;
        return null;
    }

    /* ---------------------------------------------------------------- buttons */

    public class NButton
    {
        public final IButton btn;
        public final BotDescriptor bot;
        public String path;
        public boolean disStacks;
        private final Tex[] tex = new Tex[3];

        NButton(BotDescriptor bot, Action action) {
            this.bot = bot;
            this.path = bot.iconPath;
            this.disStacks = bot.disStacks;
            Resource res = Resource.remote().loadwait(dir_path + path + "/u");
            btn = new IButton(Resource.loadsimg(dir_path + path + "/u"), Resource.loadsimg(dir_path + path + "/d"), Resource.loadsimg(dir_path + path + "/h")) {
                TexI rtip;

                @Override
                public Object tooltip(Coord c, Widget prev) {
                    if (rtip == null && res.layers(Resource.Tooltip.class) != null) {
                        List<ItemInfo> info = new ArrayList<>();
                        int count = 0;
                        for (Resource.Tooltip tt : res.layers(Resource.Tooltip.class)) {
                            if(count == 0)
                            {
                                info.add(new ItemInfo.Tip(null) {
                                    @Override
                                    public BufferedImage tipimg() {
                                        return Text.render(tt.text()).img;
                                    }
                                });
                            }
                            else {
                                info.add(new ItemInfo.Pagina(null, tt.text()));
                            }
                            count++;
                        }
                        BufferedImage img = ItemInfo.longtip(info);
                        if(img!=null)
                            rtip = new TexI(img);
                    }
                    return (rtip);
                }
            }.action(() -> start(path, action));
        }

        /** 0 up, 1 hover, 2 pressed. */
        Tex tex(int state) {
            if (tex[state] == null)
                tex[state] = new TexI(state == 0 ? btn.up : (state == 1 ? btn.hover : btn.down));
            return tex[state];
        }

        void start(String path, Action action)
        {
            NUI boundUI = NUtils.getUI();
            NGameUI gui = (boundUI != null) ? boundUI.gui : null;

            if (gui != null && gui.recentActionsPanel != null) {
                gui.recentActionsPanel.addBotAction(path, action);
            }

            Runnable onComplete = () -> {
                if(action instanceof ActionWithFinal)
                {
                    ((ActionWithFinal)action).endAction();
                }
            };

            BotExecutor.runWithSupports(path, action, disStacks, onComplete);
        }
    }

    public class NToggleNButton extends NButton {
        private boolean active = false;
        private Thread thread;

        NToggleNButton(BotDescriptor bot, Action action) {
            super(bot, action);

            btn.action(new Runnable() {
                @Override
                public void run() {
                    NUI boundUI = NUtils.getUI();
                    NGameUI gui = (boundUI != null) ? boundUI.gui : null;

                    if (!active) {
                        if (gui != null && gui.recentActionsPanel != null) {
                            gui.recentActionsPanel.addBotAction(path, action);
                        }

                        thread = BotExecutor.runAsync(path + "-ToggleThread", action, disStacks);
                        if (gui != null) {
                            gui.msg("Started: " + path);
                        }
                    } else {
                        if (thread != null && thread.isAlive()) {
                            if (action instanceof ActionWithFinal) {
                                ((ActionWithFinal)action).endAction();
                            }
                            thread.interrupt();
                            if (gui != null) {
                                gui.msg("Stopped: " + path);
                            }
                        }
                    }
                    active = !active;
                }
            });
        }
    }
}
