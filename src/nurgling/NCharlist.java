package nurgling;

import haven.*;
import haven.Charlist;
import nurgling.conf.NCharTags;
import nurgling.i18n.L10n;
import nurgling.widgets.NAvaview;
import nurgling.widgets.NTagsWnd;
import nurgling.widgets.charsel.NWorldTabs;
import nurgling.widgets.cookbook.PillButton;
import nurgling.widgets.login.NLoginTheme;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Character list for the selection screen, laid straight onto the backdrop scrim: a heading with
 * the account and its badges, world tabs and sort, compact rows, and a footer with one Play
 * button. Selection, "play" and the big-avatar link still go through {@link Charlist}, so the
 * server protocol is unchanged.
 */
public class NCharlist extends Charlist {
    public static NCharlist instance;
    public static final int W = UI.scale(400), H = UI.scale(700);
    private static final int ROWH = UI.scale(60);
    private static final Coord AVSZ = UI.scale(46, 46);
    private static final int GAP = UI.scale(10);
    private static final double DBLCLICK = 0.4;
    private static final List<String> SORTS = Arrays.asList("played", "name", "world");

    /* Built in buildLayout(), which runs inside the Charlist constructor: none of these may have an
     * initialiser, or it would wipe the widget after super() returns. */
    private Heading heading;
    private NWorldTabs tabs;
    private PillButton sortbtn;
    private Button playbtn, newbtn, logoutbtn;

    private String world, sort;
    private List<Char> view;
    private List<Img> badgesrc;
    private IButton newcharsrc;
    private boolean userpicked, multiworld, playable;
    private Char lastclick;
    private double lastclickt;

    public NCharlist(int height) {
        super(height);
        instance = this;
        world = (String) NConfig.get(NConfig.Key.selectedWorld);
        Object s = NConfig.get(NConfig.Key.charlistSort);
        sort = SORTS.contains(s) ? (String) s : SORTS.get(0);
        playable = true;
        sortbtn.suffix(L10n.get("charlist.sort." + sort));
        newbtn.disable(true);
        refresh();
    }

    @Override
    protected void buildLayout() {
        resize(Coord.of(W, H));
        heading = add(new Heading(), Coord.z);
        tabs = add(new NWorldTabs(W, this::setWorld), Coord.z);
        sortbtn = add(new PillButton(L10n.get("charlist.sort"), true, this::cycleSort), Coord.z);
        list = add(new Boxlist(Coord.of(W, ROWH), ROWH, 0), Coord.z);
        logoutbtn = add(new Button(UI.scale(90), L10n.get("charlist.logout"), this::logout), Coord.z);
        newbtn = add(new Button(UI.scale(130), L10n.get("charlist.new"), this::newchar), Coord.z);
        playbtn = add(new Button(UI.scale(120), L10n.get("charlist.play"), () -> play(list.sel)), Coord.z);
        arrange();
    }

    @Override
    protected Charbox mkbox(Char chr, Coord sz) {
        return (new NCharRow(chr, sz));
    }

    private void arrange() {
        int y = 0;
        heading.move(Coord.of(0, y));
        y += heading.sz.y + GAP;
        if (tabs.visible) {
            tabs.move(Coord.of(0, y));
            y += tabs.sz.y + GAP;
        }
        sortbtn.move(Coord.of(W - sortbtn.sz.x, y));
        y += sortbtn.sz.y + GAP;
        int fy = sz.y - playbtn.sz.y;
        list.move(Coord.of(0, y));
        list.resize(Coord.of(W, Math.max(ROWH, fy - GAP - y)));
        logoutbtn.move(Coord.of(0, fy));
        newbtn.move(Coord.of(logoutbtn.sz.x + UI.scale(8), fy));
        playbtn.move(Coord.of(W - playbtn.sz.x, fy));
    }

    /* ------------------------------------------------------------------ the visible list */

    @Override
    protected List<Char> getDisplayChars() {
        if (sort == null) // still inside the Charlist constructor
            return (chars);
        /* refresh() clears this whenever the list, the world tab or the sort changes. */
        if (view == null)
            view = computeView();
        return (view);
    }

    private List<Char> computeView() {
        String acc = NCharTags.account(ui);
        String w = effectiveWorld();
        List<Char> all;
        synchronized (chars) {
            all = new ArrayList<>(chars);
        }
        List<Char> ret = new ArrayList<>();
        for (Char c : all) {
            if ((w != null) && !w.equals(c.disc))
                continue;
            ret.add(c);
        }
        Comparator<Char> byplayed = (a, b) -> Long.compare(NCharTags.played(acc, b.name), NCharTags.played(acc, a.name));
        if ("name".equals(sort))
            ret.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        else if ("world".equals(sort))
            ret.sort(Comparator.comparing((Char c) -> (c.disc == null) ? "" : c.disc).thenComparing(byplayed));
        else
            ret.sort(byplayed);
        return (ret);
    }

    private List<String> getWorlds() {
        Set<String> worlds = new LinkedHashSet<>();
        synchronized (chars) {
            for (Char c : chars) {
                if ((c.disc != null) && !c.disc.isEmpty())
                    worlds.add(c.disc);
            }
        }
        return (new ArrayList<>(worlds));
    }

    /* A saved world tab only filters once a character from that world is actually listed. */
    private String effectiveWorld() {
        return (((world != null) && getWorlds().contains(world)) ? world : null);
    }

    private void refresh() {
        view = null;
        List<String> worlds = getWorlds();
        multiworld = (worlds.size() > 1);
        Map<String, Integer> counts = new HashMap<>();
        int total;
        synchronized (chars) {
            total = chars.size();
            for (Char c : chars) {
                if (c.disc != null)
                    counts.merge(c.disc, 1, Integer::sum);
            }
        }
        tabs.set(worlds, counts, total, effectiveWorld());
        if (tabs.visible != multiworld) {
            if (multiworld)
                tabs.show();
            else
                tabs.hide();
            arrange();
        }
        List<Char> shown = getDisplayChars();
        if (((list.sel == null) || !shown.contains(list.sel)) && !shown.isEmpty())
            list.change(shown.get(0));
        else if (list.sel != null)
            list.display(list.sel);
        updplay();
    }

    private void updplay() {
        boolean p = (selected() != null);
        if (p != playable) {
            playable = p;
            playbtn.disable(!p);
        }
    }

    /** The selected character if the current filter shows it, else null. */
    public Char selected() {
        Char s = list.sel;
        return (((s != null) && getDisplayChars().contains(s)) ? s : null);
    }

    /** World whose art the backdrop shows: the world tab, else the selected character's world. */
    public String artWorld() {
        String w = effectiveWorld();
        if (w != null)
            return (w);
        Char s = selected();
        return ((s != null) ? s.disc : null);
    }

    public boolean multiworld() {
        return (multiworld);
    }

    /** "World 16  ·  played 3 h ago" - shared by the rows and the name plate. */
    public String metaline(String acc, Char c, boolean withworld) {
        long p = NCharTags.played(acc, c.name);
        String played = (p > 0) ? L10n.get("charlist.played", NLoginTheme.ago(p)) : L10n.get("charlist.never_played");
        boolean w = withworld && (c.disc != null) && !c.disc.isEmpty();
        return ((w ? (c.disc + "  ·  ") : "") + played);
    }

    private static String imgtip(Img img) {
        Object t = img.tooltip;
        if (t instanceof String)
            return ((String) t);
        if (t instanceof Widget.KeyboundTip)
            return (((Widget.KeyboundTip) t).base);
        if (t instanceof Text)
            return (((Text) t).text);
        return (null);
    }

    /* ------------------------------------------------------------ wiring from the screen */

    /** The server's verify/subscription images; shown as badges in the heading. */
    public void badgeSources(List<Img> imgs) {
        badgesrc = imgs;
    }

    /** The server's "New character" image button; the footer button clicks it. */
    public void newCharSource(IButton b) {
        newcharsrc = b;
        newbtn.disable(b == null);
    }

    /* --------------------------------------------------------------------------- actions */

    private void setWorld(String w) {
        world = w;
        NConfig.set(NConfig.Key.selectedWorld, w);
        refresh();
    }

    private void cycleSort() {
        sort = SORTS.get((SORTS.indexOf(sort) + 1) % SORTS.size());
        NConfig.set(NConfig.Key.charlistSort, sort);
        sortbtn.suffix(L10n.get("charlist.sort." + sort));
        refresh();
    }

    /* Open on the character played last, unless the user already picked one. */
    private void preselect() {
        String acc = NCharTags.account(ui);
        Char best = null;
        long bt = 0;
        for (Char c : getDisplayChars()) {
            long t = NCharTags.played(acc, c.name);
            if (t > bt) {
                bt = t;
                best = c;
            }
        }
        if ((best != null) && (best != list.sel))
            list.change(best);
    }

    private void play(Char c) {
        if ((c != null) && getDisplayChars().contains(c))
            wdgmsg("play", c.name);
    }

    private void logout() {
        RemoteUI rui = (RemoteUI) ui.rcvr;
        synchronized (rui.sess) {
            rui.sess.close();
        }
    }

    private void newchar() {
        if (newcharsrc != null)
            newcharsrc.click();
    }

    private void step(int d) {
        List<Char> v = getDisplayChars();
        if (v.isEmpty())
            return;
        int i = v.indexOf(list.sel);
        i = (i < 0) ? 0 : Utils.clip(i + d, 0, v.size() - 1);
        userpicked = true;
        list.change(v.get(i));
        updplay();
    }

    /* ------------------------------------------------------------------------ plumbing */

    @Override
    public boolean keydown(KeyDownEvent ev) {
        int page = Math.max(1, list.sz.y / ROWH);
        switch (ev.code) {
            case KeyEvent.VK_UP:
                step(-1);
                return (true);
            case KeyEvent.VK_DOWN:
                step(1);
                return (true);
            case KeyEvent.VK_PAGE_UP:
                step(-page);
                return (true);
            case KeyEvent.VK_PAGE_DOWN:
                step(page);
                return (true);
            case KeyEvent.VK_HOME:
                step(-(Integer.MAX_VALUE / 2));
                return (true);
            case KeyEvent.VK_END:
                step(Integer.MAX_VALUE / 2);
                return (true);
        }
        return (super.keydown(ev));
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        list.scrollval(Utils.clip(list.scrollval() + (ev.a * ROWH), 0, Math.max(0, list.scrollmax())));
        return (true);
    }

    @Override
    public void uimsg(String msg, Object... args) {
        super.uimsg(msg, args);
        if ((msg == "add") || (msg == "srv")) {
            view = null;
            if (!userpicked)
                preselect();
            refresh();
            NCharlist.play();
        }
    }

    /* Every "play" leaves through here, so this is where the last-played time is stamped. */
    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if ((sender == this) && "play".equals(msg) && (args.length > 0) && (args[0] instanceof String))
            NCharTags.setPlayed(NCharTags.account(ui), (String) args[0], System.currentTimeMillis());
        super.wdgmsg(sender, msg, args);
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        updplay();
    }

    @Override
    public void destroy() {
        NTagsWnd.close();
        super.destroy();
    }

    /* dispose() runs however the list goes away - destroyed directly, or disposed as a child of
     * the torn-down selection screen, where destroy() is never called. Clearing the instance here
     * keeps WaitCharlist from matching a dead list. */
    @Override
    public void dispose() {
        if (instance == this)
            instance = null;
        super.dispose();
    }

    public static void play() {
        if (NConfig.botmod != null && instance != null) {
            for (Char c : instance.chars) {
                if (c.name.equals(NConfig.botmod.character)) {
                    instance.wdgmsg("play", NConfig.botmod.character);
                    instance = null;
                    break;
                }
            }
        }
    }

    /* --------------------------------------------------------------------------- pieces */

    /** "Characters", the account name with its verified/subscriber badges, the accent underline. */
    private class Heading extends Widget {
        private final Text title = NLoginTheme.heading.render(L10n.get("charlist.heading"));
        private final int suby;
        private Text acct;
        private String accts;
        private final Map<String, Text> btexts = new HashMap<>();
        private final List<int[]> bareas = new ArrayList<>();
        private final List<Img> bimgs = new ArrayList<>();
        private final List<String> btips = new ArrayList<>();

        Heading() {
            super(Coord.z);
            suby = title.sz().y - UI.scale(4);
            resize(Coord.of(W, suby + UI.scale(28)));
        }

        public void draw(GOut g) {
            g.image(title.tex(), Coord.z);
            String a = NCharTags.account(ui).trim();
            if (!a.equals(accts)) {
                accts = a;
                acct = NLoginTheme.sub.render(a);
            }
            g.image(acct.tex(), Coord.of(0, suby));
            int x = acct.sz().x + UI.scale(10), cy = suby + (acct.sz().y / 2);
            bareas.clear();
            bimgs.clear();
            btips.clear();
            if (badgesrc != null) {
                for (Img img : badgesrc) {
                    String tip = imgtip(img);
                    if (tip == null)
                        continue;
                    String lbl;
                    Color col;
                    if (tip.contains("Verif")) {
                        lbl = L10n.get("charlist.badge.verified");
                        col = NLoginTheme.ok;
                    } else if (tip.contains("Subsc")) {
                        lbl = L10n.get("charlist.badge.subscriber");
                        col = NLoginTheme.accent;
                    } else {
                        lbl = (tip.length() > 24) ? (tip.substring(0, 23) + "…") : tip;
                        col = NLoginTheme.muted;
                    }
                    Text t = btexts.get(lbl);
                    if (t == null)
                        btexts.put(lbl, t = NLoginTheme.badge.render(lbl, col));
                    Coord bc = Coord.of(x, cy - (NLoginTheme.badgeh() / 2));
                    int w = NLoginTheme.drawBadge(g, bc, t, col);
                    bareas.add(new int[] {bc.x, bc.y, w, NLoginTheme.badgeh()});
                    bimgs.add(img);
                    btips.add(tip);
                    x += w + UI.scale(6);
                }
            }
            g.chcolor(NLoginTheme.accent);
            g.frect(Coord.of(0, sz.y - UI.scale(2)), Coord.of(UI.scale(40), UI.scale(2)));
            g.chcolor();
        }

        private int badgeat(Coord c) {
            for (int i = 0; i < bareas.size(); i++) {
                int[] r = bareas.get(i);
                if ((c.x >= r[0]) && (c.x < r[0] + r[2]) && (c.y >= r[1]) && (c.y < r[1] + r[3]))
                    return (i);
            }
            return (-1);
        }

        public Object tooltip(Coord c, Widget prev) {
            int i = badgeat(c);
            return ((i < 0) ? null : NLoginTheme.tiptext(btips.get(i)));
        }

        public boolean mousedown(MouseDownEvent ev) {
            int i = badgeat(ev.c);
            if ((i >= 0) && (ev.b == 1)) {
                Img img = bimgs.get(i);
                if (img.hit)
                    img.wdgmsg("click", Coord.z, 1, ui.modflags());
                return (true);
            }
            return (super.mousedown(ev));
        }
    }

    /** One compact row: avatar, name, then world/last played, note glyph and tag chips. */
    public class NCharRow extends Charbox {
        private final Text nm;
        private Text meta;
        private String metas;
        private boolean hover = false;
        private int editx = -1;

        public NCharRow(Char chr, Coord sz) {
            super(chr, sz);
            nm = NLoginTheme.cname.render(chr.name);
        }

        /* Runs inside the Charbox constructor. Charbox.tick() drives ava/name/disc, so they exist;
         * name and disc are drawn by drawbox() instead and stay hidden. */
        @Override
        protected void buildLayout() {
            ava = add(new NAvaview(AVSZ, -1, "avacam"), Coord.of(UI.scale(12), (sz.y - AVSZ.y) / 2));
            name = add(new ILabel(chr.name, NLoginTheme.cname), Coord.z);
            name.hide();
            disc = add(new ILabel("", NLoginTheme.meta), Coord.z);
            disc.hide();
        }

        @Override
        protected void drawbox(GOut g) {
            boolean s = (list.sel == chr);
            if (s) {
                g.chcolor(NLoginTheme.sel);
                g.frect(Coord.z, sz);
                g.chcolor(NLoginTheme.accent);
                g.frect(Coord.z, Coord.of(UI.scale(3), sz.y));
            } else if (hover) {
                g.chcolor(NLoginTheme.hover);
                g.frect(Coord.z, sz);
            }
            g.chcolor(NLoginTheme.rowline);
            g.frect(Coord.of(0, sz.y - 1), Coord.of(sz.x, 1));
            g.chcolor(NLoginTheme.outline);
            g.rect(ava.c.sub(1, 1), ava.sz.add(2, 2));
            g.chcolor();

            String acc = NCharTags.account(ui);
            int x = ava.c.x + ava.sz.x + UI.scale(12);
            g.image(nm.tex(), Coord.of(x, UI.scale(9)));
            String m = metaline(acc, chr, multiworld);
            if (!m.equals(metas)) {
                metas = m;
                meta = NLoginTheme.meta.render(m);
            }
            int my = UI.scale(34);
            g.image(meta.tex(), Coord.of(x, my));

            int ch = NLoginTheme.chiph();
            int cy = my + ((meta.sz().y - ch) / 2);
            int cx = x + meta.sz().x + UI.scale(8);
            int maxx = sz.x - UI.scale(32);
            if (NCharTags.hasnote(acc, chr.name)) {
                NLoginTheme.drawNote(g, Coord.of(cx, my + ((meta.sz().y - UI.scale(11)) / 2)), NLoginTheme.note);
                cx += UI.scale(14);
            }
            List<String> tags = NCharTags.tags(acc, chr.name);
            int shown = 0;
            for (String t : tags) {
                Text tt = NLoginTheme.chiptext(t);
                int w = tt.sz().x + UI.scale(8);
                int reserve = ((tags.size() - shown) > 1) ? UI.scale(24) : 0;
                if (cx + w > maxx - reserve)
                    break;
                NLoginTheme.drawChip(g, Coord.of(cx, cy), tt, NCharTags.color(t));
                cx += w + UI.scale(3);
                shown++;
            }
            if (shown < tags.size())
                NLoginTheme.drawChip(g, Coord.of(cx, cy), NLoginTheme.chiptext("+" + (tags.size() - shown)), NLoginTheme.muted);

            editx = -1;
            if (hover) {
                editx = sz.x - UI.scale(26);
                NLoginTheme.drawNote(g, Coord.of(editx + UI.scale(6), (sz.y - UI.scale(11)) / 2), NLoginTheme.muted);
            }
        }

        public void mousemove(MouseMoveEvent ev) {
            hover = ev.c.isect(Coord.z, sz);
            super.mousemove(ev);
        }

        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 3) {
                NTagsWnd.open(ui, NCharTags.account(ui), chr.name);
                return (true);
            }
            if (ev.b != 1)
                return (super.mousedown(ev));
            if ((editx >= 0) && (ev.c.x >= editx)) {
                NTagsWnd.open(ui, NCharTags.account(ui), chr.name);
                return (true);
            }
            double now = Utils.rtime();
            boolean dbl = (lastclick == chr) && ((now - lastclickt) < DBLCLICK);
            lastclick = chr;
            lastclickt = now;
            userpicked = true;
            super.mousedown(ev);
            updplay();
            if (dbl)
                play(chr);
            return (true);
        }

        public Object tooltip(Coord c, Widget prev) {
            if ((editx >= 0) && (c.x >= editx))
                return (NLoginTheme.tiptext(L10n.get("charlist.tags_tip")));
            String acc = NCharTags.account(ui);
            List<String> tags = NCharTags.tags(acc, chr.name);
            String note = NCharTags.note(acc, chr.name);
            StringBuilder sb = new StringBuilder();
            if (!tags.isEmpty())
                sb.append(String.join(", ", tags));
            if (!note.isEmpty()) {
                if (sb.length() > 0)
                    sb.append("\n\n");
                sb.append(note);
            }
            return ((sb.length() == 0) ? null : NLoginTheme.tiptext(sb.toString()));
        }
    }
}
