package nurgling.widgets;

import haven.*;
import nurgling.conf.NCharTags;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tag/note decoration hung on a {@link Charlist.Charbox}. It covers the whole box so it can
 * own the right-click, but paints only in the strip of dead space between the world label and
 * the Play button, and returns false for every left-click outside the chip row so the Play
 * button keeps working exactly as before.
 */
public class NCharTagStrip extends Widget {
    private static final Text.Foundry chipf = new Text.Foundry(Text.sans, 10).aa(true);
    private static final Color chiptxt = new Color(18, 20, 16);
    private static final Color morecol = new Color(150, 150, 150);
    private static final Color notecol = new Color(232, 214, 160);
    private static final Map<String, Text.Line> chipcache = new HashMap<>();
    private static final Text.Foundry tipf = new Text.Foundry(Text.sans, 12).aa(true);
    private static final Coord NOTESZ = UI.scale(new Coord(8, 11));

    private final Charlist.Charbox box;
    private final String acc;
    private final String chr;
    private final Widget play;
    private final int chiph;
    private String tiptext = null;
    private Text tip = null;

    public NCharTagStrip(Charlist.Charbox box, String acc) {
        super(box.sz);
        this.box = box;
        this.acc = acc;
        this.chr = box.chr.name;
        this.chiph = chipf.height() + UI.scale(3);
        Widget play = null;
        for (Widget w = box.child; w != null; w = w.next) {
            if (w instanceof Button)
                play = w;
        }
        this.play = play;
    }

    private static Text.Line chip(String tag) {
        Text.Line ret = chipcache.get(tag);
        if (ret == null)
            chipcache.put(tag, ret = chipf.render(tag, chiptxt));
        return (ret);
    }

    /* The row sits directly under the world label; both labels are real widgets on the box, so
     * reading their live geometry keeps us aligned even if haven re-lays the box out. */
    private int chipy() {
        /* Clamped into the box: how far down the name/world labels reach depends on the avatar
         * size the server sends, and a row that fell off the bottom edge would just be clipped
         * away. Overlapping the tail of the world label is the better failure. */
        return (Math.min(box.disc.c.y + box.disc.sz.y + UI.scale(1), sz.y - chiph - UI.scale(2)));
    }

    private int chipx() {
        return (box.name.c.x);
    }

    private int chipmaxx() {
        int max = sz.x - UI.scale(6);
        /* Only dodge the Play button if the row would actually run into it. */
        if ((play != null) && (chipy() + chiph > play.c.y))
            max = Math.min(max, play.c.x - UI.scale(4));
        return (max);
    }

    public void draw(GOut g) {
        List<String> tags = NCharTags.tags(acc, chr);
        int x = chipx(), y = chipy(), maxx = chipmaxx();
        /* The note glyph leads the row rather than sitting in a corner: the corners are all
         * occupied by vanilla widgets whose extent depends on the name length and avatar size,
         * whereas the head of the chip row is always free. */
        if (NCharTags.hasnote(acc, chr)) {
            drawnote(g, Coord.of(x, y + ((chiph - NOTESZ.y) / 2)));
            x += NOTESZ.x + UI.scale(4);
        }
        int shown = 0;
        for (String t : tags) {
            Text.Line ln = chip(t);
            int w = ln.sz().x + UI.scale(8);
            int left = tags.size() - shown;
            /* Reserve room for the overflow chip whenever more than this one remains. */
            int reserve = (left > 1) ? (UI.scale(24)) : 0;
            if (x + w > maxx - reserve)
                break;
            g.chcolor(NCharTags.color(t));
            g.frect(Coord.of(x, y), Coord.of(w, chiph));
            g.chcolor(Color.BLACK);
            g.rect(Coord.of(x, y), Coord.of(w, chiph));
            g.chcolor();
            g.image(ln.tex(), Coord.of(x + UI.scale(4), y + ((chiph - ln.sz().y) / 2)));
            x += w + UI.scale(3);
            shown++;
        }
        if (shown < tags.size()) {
            Text.Line ln = chip("+" + (tags.size() - shown));
            int w = ln.sz().x + UI.scale(6);
            g.chcolor(morecol);
            g.frect(Coord.of(x, y), Coord.of(w, chiph));
            g.chcolor(Color.BLACK);
            g.rect(Coord.of(x, y), Coord.of(w, chiph));
            g.chcolor();
            g.image(ln.tex(), Coord.of(x + UI.scale(3), y + ((chiph - ln.sz().y) / 2)));
        }
    }

    /** Little "there is written text here" page glyph - drawn rather than a font character,
     *  because the logical sans font is not guaranteed to carry a pencil codepoint. */
    private static void drawnote(GOut g, Coord c) {
        Coord psz = NOTESZ;
        g.chcolor(notecol);
        g.frect(c, psz);
        g.chcolor(Color.BLACK);
        g.rect(c, psz);
        for (int i = 0; i < 3; i++) {
            g.frect(c.add(UI.scale(2), UI.scale(2 + (i * 3))), UI.scale(new Coord(4, 1)));
        }
        g.chcolor();
    }

    public Object tooltip(Coord c, Widget prev) {
        List<String> tags = NCharTags.tags(acc, chr);
        String note = NCharTags.note(acc, chr);
        StringBuilder sb = new StringBuilder();
        if (!tags.isEmpty())
            sb.append(String.join(", ", tags));
        if (!note.isEmpty()) {
            if (sb.length() > 0)
                sb.append("\n\n");
            sb.append(note);
        }
        if (sb.length() == 0)
            return (null);
        String text = sb.toString();
        if (!text.equals(tiptext)) {
            tiptext = text;
            if (tip != null)
                tip.dispose();
            tip = tipf.renderwrap(text, UI.scale(260));
        }
        return (tip);
    }

    private boolean inchips(Coord c) {
        int y = chipy();
        return ((c.y >= y) && (c.y < y + chiph) && (c.x >= chipx() - UI.scale(2)) && (c.x <= chipmaxx()));
    }

    public boolean mousedown(MouseDownEvent ev) {
        if (ev.b == 3) {
            NCharTagsWnd.open(ui, acc, chr);
            return (true);
        }
        if ((ev.b == 1) && inchips(ev.c)) {
            NCharTagsWnd.open(ui, acc, chr);
            return (true);
        }
        /* Everything else - most importantly a left-click on Play - falls through. */
        return (false);
    }
}
