package nurgling.styles;

import haven.Coord;
import haven.GOut;
import haven.UI;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Shared, flat UI chrome. Keep colours independent of Resource/UIFont initialization. */
public final class UITheme {
    private UITheme() {}
    public static final Color PANEL = new Color(28, 37, 38);
    public static final Color WINDOW = new Color(40, 52, 54, 245);
    public static final Color ROW = new Color(40, 48, 49);
    public static final Color INPUT = new Color(17, 24, 26);
    public static final Color HOVER = new Color(52, 62, 62);
    public static final Color SELECTED = new Color(62, 57, 44);
    public static final Color LINE = new Color(77, 91, 93);
    public static final Color ACCENT = new Color(233, 156, 84);
    public static final Color TEXT = new Color(231, 237, 237);
    public static final Color MUTED = new Color(164, 175, 187);
    public static final Color DISABLED = new Color(109, 122, 124);

    /** The "New UI" switch (Options > Interface). On by default; only an explicit "off" keeps the classic look. */
    public static boolean on() {
        return !Boolean.FALSE.equals(nurgling.NConfig.get(nurgling.NConfig.Key.newUi));
    }

    public static void panel(GOut g, Coord at, Coord size, Color fill, Color edge) {
        if(fill != null) { g.chcolor(fill); g.frect(at, size); }
        if(edge != null) {
            int b = Math.max(1, UI.scale(1));
            g.chcolor(edge);
            g.frect(at, new Coord(size.x, b));
            g.frect(at.add(0, size.y - b), new Coord(size.x, b));
            g.frect(at, new Coord(b, size.y));
            g.frect(at.add(size.x - b, 0), new Coord(b, size.y));
        }
        g.chcolor();
    }

    public static void selection(GOut g, Coord size) {
        panel(g, Coord.z, size, SELECTED, null);
        g.chcolor(ACCENT);
        g.frect(Coord.z, new Coord(Math.max(1, UI.scale(2)), size.y));
        g.chcolor();
    }

    /** Borderless toolbar feedback; leave the generated orange glyph unobstructed. */
    public static void iconHighlight(GOut g, Coord size, boolean hover, int active) {
        int alpha = Math.min(2, Math.max(0, active)) * 18 + (hover ? 25 : 0);
        if(alpha > 0) {
            g.chcolor(255, 255, 255, alpha);
            g.frect(Coord.z, size);
            g.chcolor();
        }
    }

    public static void button(Graphics2D g, int w, int h, boolean hover, boolean pressed, boolean disabled, int border) {
        button(g, w, h, hover, pressed, disabled, border, true);
    }

    public static void button(Graphics2D g, int w, int h, boolean hover, boolean pressed, boolean disabled, int border, boolean underline) {
        GeneratedButtons.plate(g, w, h, GeneratedButtons.state(hover, pressed, false, disabled));
    }

    /** Same mapping for drawing and mouse handling, including nonzero/empty ranges. */
    public static double fraction(int value, int min, int max) {
        return max <= min ? 0 : Math.max(0, Math.min(1, (value - (double)min) / (max - (double)min)));
    }

    /** A cell's fill without its outline. */
    public static BufferedImage slot(int width, int height) {
        BufferedImage image = cell(width, height);
        int fill = image.getRGB(width / 2, height / 2);
        for(int y = 0; y < height; y++)
            for(int x = 0; x < width; x++)
                image.setRGB(x, y, fill);
        return image;
    }

    public static BufferedImage cell(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        button(g, width, height, false, false, false, Math.max(1, UI.scale(1)));
        g.dispose();
        return image;
    }
}
