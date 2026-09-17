package nurgling.widgets.login;

import haven.*;
import nurgling.NStyle;
import nurgling.conf.FontSettings;
import nurgling.i18n.L10n;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared look for the login and character-selection screens. Their text sits straight on the
 * backdrop scrim rather than in a window, so headings and labels carry a soft shadow.
 */
public final class NLoginTheme {
    private NLoginTheme() {
    }

    public static final Color fg = new Color(226, 226, 220);
    public static final Color muted = NStyle.questDim;
    public static final Color accent = NStyle.border;
    public static final Color ok = new Color(143, 214, 160);
    public static final Color warn = new Color(232, 163, 61);
    public static final Color err = new Color(217, 87, 74);
    public static final Color note = new Color(232, 214, 160);
    public static final Color outline = new Color(84, 100, 98);
    public static final Color dim = new Color(201, 212, 210);
    public static final Color rowline = new Color(255, 255, 255, 20);
    public static final Color hover = new Color(255, 255, 255, 13);
    public static final Color sel = new Color(233, 156, 84, 33);

    public static final Text.Furnace heading = shadow(new Text.Foundry(FontSettings.getOpenSansSemibold(), 26, Color.WHITE).aa(true));
    public static final Text.Furnace plate = shadow(new Text.Foundry(FontSettings.getOpenSansSemibold(), 28, Color.WHITE).aa(true));
    public static final Text.Furnace sub = shadow(new Text.Foundry(FontSettings.getOpenSans(), 12, new Color(211, 222, 220)).aa(true));
    public static final Text.Furnace section = shadow(new Text.Foundry(FontSettings.getOpenSansSemibold(), 14, Color.WHITE).aa(true));
    public static final Text.Furnace label = shadow(new Text.Foundry(FontSettings.getOpenSansSemibold(), 11, muted).aa(true));
    public static final Text.Furnace hint = shadow(new Text.Foundry(FontSettings.getOpenSans(), 11, muted).aa(true));
    public static final Text.Furnace warnlabel = shadow(new Text.Foundry(FontSettings.getOpenSansSemibold(), 12, warn).aa(true));
    public static final Text.Furnace status = shadow(new Text.Foundry(FontSettings.getOpenSans(), 12, dim).aa(true));
    public static final Text.Furnace tab = shadow(new Text.Foundry(FontSettings.getOpenSansSemibold(), 12, Color.WHITE).aa(true));
    public static final Text.Furnace tabcount = shadow(new Text.Foundry(FontSettings.getOpenSans(), 12, muted).aa(true));
    public static final Text.Foundry name = new Text.Foundry(FontSettings.getOpenSansSemibold(), 13, Color.WHITE).aa(true);
    public static final Text.Foundry cname = new Text.Foundry(FontSettings.getOpenSansSemibold(), 14, Color.WHITE).aa(true);
    public static final Text.Foundry body = new Text.Foundry(FontSettings.getOpenSans(), 12, fg).aa(true);
    public static final Text.Foundry meta = new Text.Foundry(FontSettings.getOpenSans(), 11, muted).aa(true);
    public static final Text.Foundry badge = new Text.Foundry(FontSettings.getOpenSansSemibold(), 10, fg).aa(true);
    public static final Text.Foundry chip = new Text.Foundry(FontSettings.getOpenSansSemibold(), 10, new Color(18, 20, 16)).aa(true);
    public static final Text.Foundry tip = new Text.Foundry(Text.sans, 12).aa(true);

    /* Rendered text is expensive enough that the rows, which redraw every frame, cache it. Both
     * the character list and the login list draw the same chips and tooltips, so the caches live
     * here rather than in either screen. */
    private static final Map<String, Text> chiptexts = new HashMap<>();
    private static final Map<String, Text> tiptexts = new HashMap<>();

    /** Chip label for a tag, in the dark chip text colour. */
    public static Text chiptext(String tag) {
        Text t = chiptexts.get(tag);
        if (t == null)
            chiptexts.put(tag, t = chip.render(tag));
        return (t);
    }

    /** Wrapped tooltip text; notes make these long and varied, so the cache is bounded. */
    public static Text tiptext(String s) {
        Text t = tiptexts.get(s);
        if (t == null) {
            if (tiptexts.size() > 64)
                tiptexts.clear();
            tiptexts.put(s, t = tip.renderwrap(s, UI.scale(260)));
        }
        return (t);
    }

    private static Text.Furnace shadow(Text.Foundry f) {
        return (new PUtils.BlurFurn(f, UI.scale(2), UI.scale(1), Color.BLACK));
    }

    public static int badgeh() {
        return (badge.height() + UI.scale(3));
    }

    public static int chiph() {
        return (chip.height() + UI.scale(3));
    }

    /** Outlined badge in {@code col}; returns its width. */
    public static int drawBadge(GOut g, Coord c, Text t, Color col) {
        int w = t.sz().x + UI.scale(10), h = badgeh();
        g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), 38);
        g.frect(c, Coord.of(w, h));
        g.chcolor(col);
        g.rect(c, Coord.of(w, h));
        g.chcolor();
        g.image(t.tex(), c.add(UI.scale(5), (h - t.sz().y) / 2));
        return (w);
    }

    /** Filled tag chip in the tag's colour, like the tag editor draws them; returns its width. */
    public static int drawChip(GOut g, Coord c, Text t, Color col) {
        int w = t.sz().x + UI.scale(8), h = chiph();
        g.chcolor(col);
        g.frect(c, Coord.of(w, h));
        g.chcolor(Color.BLACK);
        g.rect(c, Coord.of(w, h));
        g.chcolor();
        g.image(t.tex(), c.add(UI.scale(4), (h - t.sz().y) / 2));
        return (w);
    }

    /** Small page glyph for "has a note" - drawn, because the fonts carry no pencil codepoint. */
    public static void drawNote(GOut g, Coord c, Color col) {
        Coord psz = UI.scale(new Coord(8, 11));
        g.chcolor(col);
        g.frect(c, psz);
        g.chcolor(Color.BLACK);
        g.rect(c, psz);
        for (int i = 0; i < 3; i++)
            g.frect(c.add(UI.scale(2), UI.scale(2 + (i * 3))), UI.scale(new Coord(4, 1)));
        g.chcolor();
    }

    /** Eight square dots chasing round {@code ctr}. */
    public static void drawSpinner(GOut g, Coord ctr, int r) {
        drawSpinner(g, ctr, r, Math.max(2, UI.scale(3)));
    }

    /** As above, with the dot size given: a big view needs dots to match. */
    public static void drawSpinner(GOut g, Coord ctr, int r, int dot) {
        int n = 8, d = Math.max(2, dot);
        int head = (int) (Utils.rtime() * 10) % n;
        for (int i = 0; i < n; i++) {
            double a = (2 * Math.PI * i) / n;
            int age = ((head - i) + n) % n;
            g.chcolor(accent.getRed(), accent.getGreen(), accent.getBlue(), 255 - ((age * 200) / n));
            g.frect(ctr.add((int) Math.round(Math.cos(a) * r) - (d / 2), (int) Math.round(Math.sin(a) * r) - (d / 2)), Coord.of(d, d));
        }
        g.chcolor();
    }

    /** "3 h ago" style relative time; empty for 0. */
    public static String ago(long when) {
        if (when <= 0)
            return ("");
        long min = Math.max(0, (System.currentTimeMillis() - when) / 60000);
        if (min < 1)
            return (L10n.get("login.ago.now"));
        if (min < 60)
            return (L10n.get("login.ago.min", min));
        long h = min / 60;
        if (h < 24)
            return (L10n.get("login.ago.hour", h));
        long d = h / 24;
        if (d < 7)
            return (L10n.get("login.ago.day", d));
        return (L10n.get("login.ago.week", d / 7));
    }
}
