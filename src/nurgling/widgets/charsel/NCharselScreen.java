package nurgling.widgets.charsel;

import haven.*;
import nurgling.NCharlist;
import nurgling.NConfig;
import nurgling.conf.NCharTags;
import nurgling.widgets.login.NBackdrop;
import nurgling.widgets.login.NLoginTheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces the server's 800x600 character-selection container with one the size of our world art,
 * centred in the window like before. It draws the art through {@link NBackdrop} and places the
 * server's widgets by type instead of by their 800x600 coordinates. As observed on 2026-09-15, the
 * server sends: background Img, verify Img, subscription Img, the charlist, a ProxyFrame holding the
 * big avatar, and the "New character" IButton. The Imgs and the IButton are kept (hidden) because
 * the list's heading badges and footer button stand in for them.
 */
public class NCharselScreen extends Widget {
    private static final Coord SRVSZ = new Coord(800, 600);
    private static final Coord ARTSZ = new Coord(1376, 768);
    /* Children right of this (server coordinates) belong to the right side of the old layout. */
    private static final int SRV_RIGHT = 300;

    private final NBackdrop backdrop;
    private final NamePlate plate;
    private NCharlist list;
    private Widget avatar;
    private IButton newchar;
    private final List<Img> badges = new ArrayList<>();
    /* Anything unrecognised keeps its server position, re-anchored to the right edge if it sat on
     * the right half, so a server-side addition still shows up somewhere sensible. */
    private final Map<Widget, Coord> loose = new HashMap<>();

    public static boolean isCharsel(Coord srvsz) {
        return (SRVSZ.equals(srvsz));
    }

    public NCharselScreen() {
        super(UI.scale(ARTSZ));
        backdrop = add(new NBackdrop(this::art, NBackdrop.SCRIMW), Coord.z);
        plate = add(new NamePlate(), Coord.z);
    }

    /* The art follows the world tab, or the selected character's world when the tab is "all". */
    private Tex art() {
        return (Img.getCharselBg((list != null) ? list.artWorld() : (String) NConfig.get(NConfig.Key.selectedWorld)));
    }

    protected void added() {
        presize();
    }

    public void presize() {
        c = parent.sz.div(2).sub(sz.div(2));
    }

    public void addchild(Widget child, Object... args) {
        super.addchild(child, args);
        if (child instanceof Img) {
            Img img = (Img) child;
            switch (img.charselType) {
                case BACKGROUND:
                    img.hide();
                    break;
                case VERIFY:
                case SUB:
                    img.hide();
                    badges.add(img);
                    break;
                default:
                    loose.put(child, child.c);
            }
        } else if (child instanceof NCharlist) {
            list = (NCharlist) child;
        } else if (child instanceof ProxyFrame) {
            avatar = child;
            /* No frame: the avatar stands in the art like the rest of the screen. */
            ((ProxyFrame<?>) child).color = null;
        } else if (child instanceof Avaview) {
            avatar = child;
        } else if ((child instanceof IButton) && (newchar == null)) {
            newchar = (IButton) child;
            newchar.hide();
        } else {
            loose.put(child, child.c);
        }
        wire();
        layout();
    }

    public void cdestroy(Widget ch) {
        super.cdestroy(ch);
        if (ch == list)
            list = null;
        if (ch == avatar)
            avatar = null;
        if (ch == newchar)
            newchar = null;
        badges.remove(ch);
        loose.remove(ch);
        wire();
    }

    private void wire() {
        if (list != null) {
            list.badgeSources(badges);
            list.newCharSource(newchar);
        }
    }

    private void layout() {
        if (list != null)
            list.move(Coord.of(UI.scale(56), (sz.y - list.sz.y) / 2));

        /* The avatar and its name plate share the art area right of the scrim. */
        int cx = (backdrop.scrimw() + sz.x) / 2;
        int gap = UI.scale(10);
        int h = ((avatar != null) ? avatar.sz.y + gap : 0) + plate.sz.y;
        int y = (sz.y - h) / 2;
        if (avatar != null) {
            avatar.move(Coord.of(cx - (avatar.sz.x / 2), y));
            y += avatar.sz.y + gap;
        }
        plate.move(Coord.of(cx - (plate.sz.x / 2), y));

        int srvw = UI.scale(SRVSZ.x);
        for (Map.Entry<Widget, Coord> e : loose.entrySet()) {
            Coord o = e.getValue();
            e.getKey().move((o.x > UI.scale(SRV_RIGHT)) ? o.add(sz.x - srvw, 0) : o);
        }
    }

    /* The server re-colours the avatar frame after we blank it in addchild (a "col" message), which
     * brings haven's window-box border back. Nurgling's box graphic is a stub, so it shows up as a
     * short black dash in the frame's margin above the avatar rather than a border. Keep it blank. */
    public void tick(double dt) {
        super.tick(dt);
        if (avatar instanceof ProxyFrame)
            ((ProxyFrame<?>) avatar).color = null;
    }

    /** Name, world and last played, tags and the first line of the note, under the big avatar. */
    private class NamePlate extends Widget {
        private Charlist.Char shown = null;
        private Text nm = null, ln = null, nt = null;
        private String lns = null, nts = null;

        NamePlate() {
            super(UI.scale(new Coord(460, 110)));
        }

        public void draw(GOut g) {
            Charlist.Char c = (list != null) ? list.selected() : null;
            if (c == null)
                return;
            String acc = NCharTags.account(ui);
            if (c != shown) {
                shown = c;
                nm = NLoginTheme.plate.render(c.name);
            }
            String l = list.metaline(acc, c, true);
            if (!l.equals(lns)) {
                lns = l;
                ln = NLoginTheme.sub.render(l);
            }
            int y = 0;
            g.image(nm.tex(), Coord.of((sz.x - nm.sz().x) / 2, y));
            y += nm.sz().y - UI.scale(4);
            g.image(ln.tex(), Coord.of((sz.x - ln.sz().x) / 2, y));
            y += ln.sz().y + UI.scale(4);

            List<String> tags = NCharTags.tags(acc, c.name);
            if (!tags.isEmpty()) {
                int gap = UI.scale(4), tw = -gap;
                for (String t : tags)
                    tw += NCharlist.chiptext(t).sz().x + UI.scale(8) + gap;
                int x = (sz.x - tw) / 2;
                for (String t : tags)
                    x += NLoginTheme.drawChip(g, Coord.of(x, y), NCharlist.chiptext(t), NCharTags.color(t)) + gap;
                y += NLoginTheme.chiph() + UI.scale(6);
            }

            String note = NCharTags.note(acc, c.name);
            if (!note.isEmpty()) {
                String first = note.split("\n", 2)[0];
                if (first.length() > 70)
                    first = first.substring(0, 69) + "…";
                if (!first.equals(nts)) {
                    nts = first;
                    nt = NLoginTheme.hint.render(first);
                }
                g.image(nt.tex(), Coord.of((sz.x - nt.sz().x) / 2, y));
            }
        }
    }
}
