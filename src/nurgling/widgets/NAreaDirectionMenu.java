package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.areas.NArea;
import nurgling.areas.PileFillDirection;
import nurgling.i18n.L10n;
import nurgling.tools.Finder;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

/**
 * Picker for a zone's fill direction: a cross of arrows plus a live preview of the
 * order the chosen direction produces.
 *
 * Two things drive the design. First, the arrows are drawn as geometry rather than
 * typed as text - {@link Text} renders through the AWT logical font, which on many
 * systems has no U+2191..2193, so arrow glyphs come out as .notdef boxes. Second, an
 * arrow alone does not say much when the world axes are rotated against the screen,
 * so the preview grid shows the resulting order directly: shaded bright to dim, with
 * a path threading the cells and a dot on the one that gets filled first.
 *
 * The preview orders its cells with {@link Finder#positionComparator} - the same call
 * the bots make - so it cannot drift away from what actually happens in game.
 */
public class NAreaDirectionMenu extends Widget {
    private static final int CELL = UI.scale(30);
    private static final int PAD = UI.scale(8);
    private static final int GAP = UI.scale(6);
    private static final int PREVIEW_COLS = 4;
    private static final int PREVIEW_ROWS = 3;
    private static final int PREVIEW_CELL = UI.scale(20);
    private static final int PREVIEW_GAP = UI.scale(3);

    private static final Color BACKGROUND = new Color(35, 45, 45, 235);
    private static final Color BORDER = new Color(170, 125, 55, 255);
    private static final Color SELECTED = new Color(112, 82, 32, 255);
    private static final Color HOVER = new Color(70, 82, 78, 255);
    private static final Color CELL_BORDER = new Color(120, 130, 125, 200);
    private static final Color ARROW = new Color(215, 215, 205, 255);
    private static final Color ARROW_ACTIVE = new Color(255, 226, 160, 255);
    private static final Color PREVIEW_BACK = new Color(24, 30, 30, 255);
    private static final Color PREVIEW_FIRST = new Color(245, 205, 120, 255);
    private static final Color PREVIEW_LAST = new Color(72, 68, 55, 255);
    private static final Color PREVIEW_FLAT = new Color(78, 84, 80, 255);
    private static final Color PATH = new Color(255, 236, 190, 190);

    /**
     * One button of the cross. {@code gx}/{@code gy} place it in the 3x3 grid and
     * {@code angle} is how far the right-pointing arrow art is rotated clockwise to
     * face the way this button reads on screen.
     *
     * The slot-to-direction mapping is deliberate and not derivable from the enum
     * names: Haven's world axes are rotated against the screen, so the button that
     * reads as "up" selects the direction that walks rows in ascending world y. It
     * lives here alone so a correction is a single edit.
     */
    enum Slot {
        UP(1, 0, 270, PileFillDirection.TOP_TO_BOTTOM, "area.dir.top_to_bottom"),
        LEFT(0, 1, 180, PileFillDirection.RIGHT_TO_LEFT, "area.dir.right_to_left"),
        CENTER(1, 1, 0, PileFillDirection.DEFAULT, "area.dir.default"),
        RIGHT(2, 1, 0, PileFillDirection.LEFT_TO_RIGHT, "area.dir.left_to_right"),
        DOWN(1, 2, 90, PileFillDirection.BOTTOM_TO_TOP, "area.dir.bottom_to_top");

        final int gx, gy, angle;
        final PileFillDirection direction;
        final String key;

        Slot(int gx, int gy, int angle, PileFillDirection direction, String key) {
            this.gx = gx;
            this.gy = gy;
            this.angle = angle;
            this.direction = direction;
            this.key = key;
        }

        static Slot of(PileFillDirection direction) {
            for (Slot slot : values())
                if (slot.direction == direction) return slot;
            return CENTER;
        }

        String label() {
            return L10n.get(key);
        }
    }

    private final NArea area;
    /**
     * Built per instance rather than held statically: {@link Text}'s own class
     * initialiser reads the font settings out of NConfig, so touching it while this
     * class loads would blow up anywhere there is no live client (tests included).
     */
    private final Text.Foundry titleFnd = new Text.Foundry(Text.sans, 11).aa(true);
    private final Text.Foundry labelFnd = new Text.Foundry(Text.sans, 11).aa(true);
    private final EnumMap<Slot, Text> labels = new EnumMap<>(Slot.class);
    private final Text title;
    private final Coord crossOrigin;
    private final Coord previewOrigin;
    private final Coord labelCenter;
    private final int titleTop;
    private final int contentWidth;

    private UI.Grab mouseGrab;
    private UI.Grab keyGrab;
    private Slot hovered = null;
    private Slot keyFocus = null;

    public NAreaDirectionMenu(NArea area) {
        this.area = area;
        this.title = titleFnd.render(L10n.get("area.menu.fill_direction"), ARROW);
        for (Slot slot : Slot.values())
            labels.put(slot, labelFnd.render(slot.label(), ARROW_ACTIVE));

        int crossW = CELL * 3;
        int previewW = PREVIEW_COLS * PREVIEW_CELL + (PREVIEW_COLS - 1) * PREVIEW_GAP;
        int previewH = PREVIEW_ROWS * PREVIEW_CELL + (PREVIEW_ROWS - 1) * PREVIEW_GAP;

        int widest = Math.max(Math.max(crossW, previewW), title.sz().x);
        for (Text label : labels.values())
            widest = Math.max(widest, label.sz().x);
        int contentW = widest;

        int y = PAD;
        int titleY = y;
        y += title.sz().y + GAP;
        this.crossOrigin = new Coord(PAD + (contentW - crossW) / 2, y);
        y += CELL * 3 + GAP;
        this.previewOrigin = new Coord(PAD + (contentW - previewW) / 2, y);
        y += previewH + GAP;
        this.labelCenter = new Coord(PAD + contentW / 2, y);
        y += labelFnd.height() + PAD;

        resize(new Coord(contentW + PAD * 2, y));
        this.titleTop = titleY;
        this.contentWidth = contentW;
    }

    // -------------------- placement --------------------

    /**
     * Keep the panel fully on screen. It used to be one small square that could get
     * away with opening exactly at the cursor; at this size it would hang off the edge
     * whenever the zone list sits near a screen border.
     */
    static Coord clampToScreen(Coord at, Coord sz, Coord screen) {
        int x = Math.max(0, Math.min(at.x, screen.x - sz.x));
        int y = Math.max(0, Math.min(at.y, screen.y - sz.y));
        return new Coord(x, y);
    }

    /** Open the picker near the cursor, kept inside the screen. */
    public static NAreaDirectionMenu open(UI ui, NArea area) {
        NAreaDirectionMenu menu = new NAreaDirectionMenu(area);
        ui.root.add(menu, clampToScreen(ui.mc, menu.sz, ui.root.sz));
        return menu;
    }

    // -------------------- preview order --------------------

    /**
     * Rank of every preview cell in fill order, row-major, or null when the direction
     * has no fixed order to show.
     *
     * {@link PileFillDirection#DEFAULT} returns null on purpose: its order depends on
     * the zone's own aspect ratio, so any single arrangement drawn for a fixed-size
     * preview would only be true for zones of roughly that shape.
     */
    static int[] previewOrder(int cols, int rows, PileFillDirection direction) {
        if (direction == null || direction == PileFillDirection.DEFAULT)
            return null;
        int count = cols * rows;
        List<Coord2d> positions = new ArrayList<>(count);
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++)
                positions.add(Coord2d.of(x, y));

        Comparator<Coord2d> cmp = Finder.positionComparator(positions, direction);
        Integer[] cells = new Integer[count];
        for (int i = 0; i < count; i++)
            cells[i] = i;
        java.util.Arrays.sort(cells, (a, b) -> cmp.compare(positions.get(a), positions.get(b)));

        int[] rank = new int[count];
        for (int i = 0; i < count; i++)
            rank[cells[i]] = i;
        return rank;
    }

    // -------------------- arrow art --------------------

    private static final EnumMap<Slot, Tex> ARROWS = new EnumMap<>(Slot.class);
    private static final EnumMap<Slot, Tex> ARROWS_ACTIVE = new EnumMap<>(Slot.class);

    private static Tex arrow(Slot slot, boolean active) {
        EnumMap<Slot, Tex> cache = active ? ARROWS_ACTIVE : ARROWS;
        Tex tex = cache.get(slot);
        if (tex == null) {
            tex = new TexI(arrowImage(slot, UI.scale(20), active ? ARROW_ACTIVE : ARROW));
            cache.put(slot, tex);
        }
        return tex;
    }

    /**
     * A filled arrow pointing right, rotated to the slot's angle. Drawn through Java2D
     * because GOut can stroke lines and fill rectangles but cannot fill a polygon, and
     * a hand-stepped triangle would alias badly at this size.
     */
    static BufferedImage arrowImage(Slot slot, int size, Color color) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(color);

        double c = size / 2.0;
        if (slot == Slot.CENTER) {
            // No direction to point in - a dot reads as "unset" rather than as an arrow.
            double r = size * 0.17;
            g.fill(new java.awt.geom.Ellipse2D.Double(c - r, c - r, r * 2, r * 2));
            g.setStroke(new BasicStroke((float) Math.max(1.0, size * 0.07)));
            double ring = size * 0.34;
            g.draw(new java.awt.geom.Ellipse2D.Double(c - ring, c - ring, ring * 2, ring * 2));
        } else {
            double half = size * 0.40;
            double shaft = size * 0.11;
            double head = size * 0.24;
            Path2D.Double p = new Path2D.Double();
            p.moveTo(-half, -shaft);
            p.lineTo(half - head * 1.4, -shaft);
            p.lineTo(half - head * 1.4, -head);
            p.lineTo(half, 0);
            p.lineTo(half - head * 1.4, head);
            p.lineTo(half - head * 1.4, shaft);
            p.lineTo(-half, shaft);
            p.closePath();
            AffineTransform at = AffineTransform.getTranslateInstance(c, c);
            at.rotate(Math.toRadians(slot.angle));
            g.fill(at.createTransformedShape(p));
        }
        g.dispose();
        return img;
    }

    // -------------------- lifecycle --------------------

    @Override
    protected void added() {
        mouseGrab = ui.grabmouse(this);
        keyGrab = ui.grabkeys(this);
    }

    @Override
    public void destroy() {
        if (mouseGrab != null) mouseGrab.remove();
        if (keyGrab != null) keyGrab.remove();
        super.destroy();
    }

    // -------------------- hit testing --------------------

    private Coord slotUL(Slot slot) {
        return crossOrigin.add(slot.gx * CELL, slot.gy * CELL);
    }

    private Slot slotAt(Coord c) {
        for (Slot slot : Slot.values()) {
            Coord ul = slotUL(slot);
            if (c.x >= ul.x && c.y >= ul.y && c.x < ul.x + CELL && c.y < ul.y + CELL)
                return slot;
        }
        return null;
    }

    /** What the preview and label describe: the pointed-at option, else the current one. */
    private Slot shown() {
        if (hovered != null) return hovered;
        if (keyFocus != null) return keyFocus;
        return Slot.of(area.pileFillDirection);
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        Slot slot = slotAt(c);
        if (slot == Slot.CENTER)
            return L10n.get("area.dir.default_tip");
        if (slot != null)
            return slot.label();
        return super.tooltip(c, prev);
    }

    // -------------------- drawing --------------------

    @Override
    public void draw(GOut g) {
        g.chcolor(BACKGROUND);
        g.frect(Coord.z, sz);
        g.chcolor(BORDER);
        g.rect(Coord.z, sz);
        g.chcolor();

        g.image(title.tex(), new Coord(PAD + (contentWidth - title.sz().x) / 2, titleTop));

        Slot shown = shown();
        drawCross(g, shown);
        drawPreview(g, shown.direction);

        Text label = labels.get(shown);
        g.image(label.tex(), new Coord(labelCenter.x - label.sz().x / 2, labelCenter.y));
    }

    private void drawCross(GOut g, Slot shown) {
        Slot selected = Slot.of(area.pileFillDirection);
        for (Slot slot : Slot.values()) {
            Coord ul = slotUL(slot);
            Coord cell = new Coord(CELL, CELL);
            boolean isSelected = slot == selected;
            boolean isShown = slot == shown;

            if (isSelected) {
                g.chcolor(SELECTED);
                g.frect(ul, cell);
            } else if (isShown) {
                g.chcolor(HOVER);
                g.frect(ul, cell);
            }
            g.chcolor(isSelected ? BORDER : CELL_BORDER);
            g.rect(ul, cell);
            g.chcolor();

            Tex tex = arrow(slot, isSelected || isShown);
            g.image(tex, ul.add(cell.sub(tex.sz()).div(2)));
        }
    }

    private void drawPreview(GOut g, PileFillDirection direction) {
        int w = PREVIEW_COLS * PREVIEW_CELL + (PREVIEW_COLS - 1) * PREVIEW_GAP;
        int h = PREVIEW_ROWS * PREVIEW_CELL + (PREVIEW_ROWS - 1) * PREVIEW_GAP;
        g.chcolor(PREVIEW_BACK);
        g.frect(previewOrigin.sub(PREVIEW_GAP, PREVIEW_GAP),
                new Coord(w + PREVIEW_GAP * 2, h + PREVIEW_GAP * 2));
        g.chcolor(CELL_BORDER);
        g.rect(previewOrigin.sub(PREVIEW_GAP, PREVIEW_GAP),
                new Coord(w + PREVIEW_GAP * 2, h + PREVIEW_GAP * 2));
        g.chcolor();

        int[] rank = previewOrder(PREVIEW_COLS, PREVIEW_ROWS, direction);
        int count = PREVIEW_COLS * PREVIEW_ROWS;

        for (int i = 0; i < count; i++) {
            Coord ul = previewCellUL(i);
            g.chcolor(rank == null ? PREVIEW_FLAT : shade(rank[i], count));
            g.frect(ul, new Coord(PREVIEW_CELL, PREVIEW_CELL));
        }
        g.chcolor();

        if (rank == null)
            return;

        // Thread the cells in fill order so the walk itself is visible, not just the
        // start corner.
        Coord[] byRank = new Coord[count];
        for (int i = 0; i < count; i++)
            byRank[rank[i]] = previewCellUL(i).add(PREVIEW_CELL / 2, PREVIEW_CELL / 2);

        g.chcolor(PATH);
        for (int i = 1; i < count; i++)
            g.line(byRank[i - 1], byRank[i], UI.scale(1.5));
        g.fellipse(byRank[0], new Coord(UI.scale(4), UI.scale(4)));
        g.chcolor();
    }

    private Coord previewCellUL(int index) {
        int x = index % PREVIEW_COLS;
        int y = index / PREVIEW_COLS;
        return previewOrigin.add(x * (PREVIEW_CELL + PREVIEW_GAP),
                y * (PREVIEW_CELL + PREVIEW_GAP));
    }

    /** Bright for the first cell filled, fading to dim for the last. */
    static Color shade(int rank, int count) {
        double t = (count <= 1) ? 0 : (double) rank / (count - 1);
        return new Color(
                mix(PREVIEW_FIRST.getRed(), PREVIEW_LAST.getRed(), t),
                mix(PREVIEW_FIRST.getGreen(), PREVIEW_LAST.getGreen(), t),
                mix(PREVIEW_FIRST.getBlue(), PREVIEW_LAST.getBlue(), t),
                255);
    }

    private static int mix(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }

    // -------------------- input --------------------

    @Override
    public void mousemove(MouseMoveEvent ev) {
        hovered = slotAt(ev.c);
        super.mousemove(ev);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        Slot slot = slotAt(ev.c);
        if (slot != null) {
            if (ev.b == 1) choose(slot.direction);
            return true;
        }
        // The grab routes every click here, so distinguish "missed a button" from
        // "clicked away": only the latter dismisses.
        if (!within(ev.c, sz))
            close();
        return true;
    }

    static boolean within(Coord at, Coord sz) {
        return at.x >= 0 && at.y >= 0 && at.x < sz.x && at.y < sz.y;
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if (key_esc.match(ev)) {
            close();
            return true;
        }
        Slot moved = move(ev);
        if (moved != null) {
            keyFocus = moved;
            hovered = null;
            return true;
        }
        if (ev.awt.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER
                || ev.awt.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE) {
            choose(shown().direction);
            return true;
        }
        return false;
    }

    /** Arrow keys walk the cross, starting from whatever is currently shown. */
    private Slot move(KeyDownEvent ev) {
        int dx = 0, dy = 0;
        switch (ev.awt.getKeyCode()) {
            case java.awt.event.KeyEvent.VK_UP:    dy = -1; break;
            case java.awt.event.KeyEvent.VK_DOWN:  dy = 1;  break;
            case java.awt.event.KeyEvent.VK_LEFT:  dx = -1; break;
            case java.awt.event.KeyEvent.VK_RIGHT: dx = 1;  break;
            default: return null;
        }
        return step(shown(), dx, dy);
    }

    /**
     * Neighbour in the cross, or the centre when stepping off an arm - the cross has
     * no corners, so every move from an arm passes through the middle.
     */
    static Slot step(Slot from, int dx, int dy) {
        int gx = from.gx + dx, gy = from.gy + dy;
        for (Slot slot : Slot.values())
            if (slot.gx == gx && slot.gy == gy) return slot;
        return (from == Slot.CENTER) ? from : Slot.CENTER;
    }

    static boolean apply(NArea area, PileFillDirection direction) {
        return area.setPileFillDirection(direction);
    }

    private void choose(PileFillDirection direction) {
        if (apply(area, direction)) NConfig.needAreasUpdate();
        close();
    }

    private void close() {
        ui.destroy(this);
    }
}
