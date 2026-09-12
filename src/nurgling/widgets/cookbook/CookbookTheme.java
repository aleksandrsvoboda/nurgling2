package nurgling.widgets.cookbook;

import haven.*;
import nurgling.NStyle;
import nurgling.conf.FontSettings;
import nurgling.cookbook.FepAttr;

import java.awt.Color;
import java.awt.font.TextAttribute;

/**
 * Colours, fonts and drawing helpers shared by the cookbook widgets. Square corners and NStyle
 * colours, like the rest of nurgling.
 */
public final class CookbookTheme {
    public static final Color bg = NStyle.infoBg;
    /** Toolbar strip and table header. */
    public static final Color head = new Color(55, 65, 62);
    public static final Color hover = NStyle.rowOdd;
    public static final Color line = NStyle.separator;
    /** Idle control borders. */
    public static final Color outline = new Color(84, 100, 98);
    public static final Color accent = NStyle.border;
    public static final Color fg = new Color(226, 226, 220);
    public static final Color muted = NStyle.questDim;
    public static final Color warn = new Color(232, 163, 61);
    public static final Color warnBg = new Color(232, 163, 61, 34);
    public static final Color popBg = new Color(0x22, 0x2C, 0x2E);
    /** Text on an accent-filled background. */
    public static final Color ink = new Color(24, 30, 32);

    public static final Text.Foundry body = new Text.Foundry(FontSettings.getOpenSans(), 12, fg).aa(true);
    public static final Text.Foundry bold = new Text.Foundry(FontSettings.getOpenSansSemibold(), 12, fg).aa(true);
    public static final Text.Foundry small = new Text.Foundry(FontSettings.getOpenSans(), 10, fg).aa(true);
    /* Attributes go in (key, value) pairs; anything not keyed by an Attribute is silently dropped. */
    public static final RichText.Foundry tip = new RichText.Foundry(TextAttribute.FAMILY, "SansSerif", TextAttribute.SIZE, UI.scale(12f)).aa(true);

    /** Side of a check or radio box. */
    public static final int BOX = UI.scale(12);
    public static final int SWATCH = UI.scale(10);
    public static final int GAP = UI.scale(5);

    /* Texture folders of the attribute buttons, in FepAttr order. */
    private static final String[] STAT_TEX = {"str", "agi", "int", "cons", "per", "cha", "dex", "wil", "psy"};
    private static TexI[][] statIcons = null;
    private static TexI star = null;

    private CookbookTheme() {
    }

    public static Tex render(Text.Foundry f, String s, Color c) {
        return f.render(s, c).tex();
    }

    public static void fill(GOut g, Coord ul, Coord sz, Color c) {
        g.chcolor(c);
        g.frect(ul, sz);
        g.chcolor();
    }

    /** A square 1px border. */
    public static void frame(GOut g, Coord ul, Coord sz, Color c) {
        int w = Math.max(1, UI.scale(1));
        g.chcolor(c);
        g.frect(ul, Coord.of(sz.x, w));
        g.frect(Coord.of(ul.x, ul.y + sz.y - w), Coord.of(sz.x, w));
        g.frect(ul, Coord.of(w, sz.y));
        g.frect(Coord.of(ul.x + sz.x - w, ul.y), Coord.of(w, sz.y));
        g.chcolor();
    }

    /** A small filled triangle centred on c, pointing down or up: drop-down carets and sort arrows. */
    public static void triangle(GOut g, Coord c, boolean down, Color col) {
        int h = UI.scale(3);
        g.chcolor(col);
        for(int i = 0; i < h; i++) {
            int w = (h - i) * 2 - 1;
            int y = down ? (c.y - h / 2 + i) : (c.y + h / 2 - i);
            g.frect(Coord.of(c.x - w / 2, y), Coord.of(w, 1));
        }
        g.chcolor();
    }

    /** The longest prefix of s that fits in w pixels, with an ellipsis when cut. */
    public static String ellipsize(Text.Foundry f, String s, int w) {
        if(w <= 0)
            return "";
        if(f.strsize(s).x <= w)
            return s;
        String dots = "…";
        int lo = 0, hi = s.length();
        while(lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if(f.strsize(s.substring(0, mid) + dots).x <= w)
                lo = mid;
            else
                hi = mid - 1;
        }
        return s.substring(0, lo).trim() + dots;
    }

    /** Idle, hover and pressed images of an attribute's cookbook button. */
    public static synchronized TexI[] statIcon(FepAttr a) {
        if(statIcons == null) {
            statIcons = new TexI[STAT_TEX.length][];
            for(int i = 0; i < STAT_TEX.length; i++) {
                String p = "nurgling/hud/buttons/cookbook/" + STAT_TEX[i] + "/";
                statIcons[i] = new TexI[] {
                    new TexI(Resource.loadsimg(p + "u")),
                    new TexI(Resource.loadsimg(p + "h")),
                    new TexI(Resource.loadsimg(p + "d")),
                };
            }
        }
        return statIcons[a.ordinal()];
    }

    public static synchronized TexI star() {
        if(star == null)
            star = new TexI(Resource.loadimg("nurgling/hud/star"));
        return star;
    }
}
