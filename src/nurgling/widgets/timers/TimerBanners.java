package nurgling.widgets.timers;

import haven.*;
import nurgling.NGameUI;
import nurgling.i18n.L10n;
import nurgling.timers.Timer;
import nurgling.timers.TimerDurations;
import nurgling.timers.TimerPlacement;
import nurgling.timers.TimerStore;
import nurgling.widgets.cookbook.CookbookTheme;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Banners that drop in at the top of the screen when timers become ready, phone-notification style.
 *
 * <p>The newest banner shows in full with the older ones peeking out underneath; clicking the peek expands
 * the stack. A banner goes away by itself after {@link #SHOW_SECONDS} (hovering holds it) - the timer then
 * waits in the bell and the timers panel - or at once when the player acts on it.
 *
 * <p>The widget is only as big as the banners it shows, so everywhere else clicks reach the map as usual.
 */
public class TimerBanners extends Widget {
    private static final double SHOW_SECONDS = 8;
    private static final int W = UI.scale(310);
    private static final int PAD = UI.scale(8);
    private static final int GAP = UI.scale(5);
    private static final int BTN_H = UI.scale(20);
    private static final int PEEK = UI.scale(7);
    private static final int TOP = UI.scale(46);
    private static final int MAX_EXPANDED = 5;
    private static final Color BG = new Color(0x1C, 0x25, 0x26, 0xF4);

    /** One banner: a single timer, or several that became ready together. */
    private static final class Banner {
        final List<String> ids;
        final boolean away;
        double shownAt;

        Banner(List<String> ids, boolean away, double shownAt) {
            this.ids = ids;
            this.away = away;
            this.shownAt = shownAt;
        }
    }

    /** A clickable rectangle laid out during the last draw. */
    private static final class Hit {
        final Coord ul, sz;
        final Runnable action;

        Hit(Coord ul, Coord sz, Runnable action) {
            this.ul = ul;
            this.sz = sz;
            this.action = action;
        }
    }

    private final List<Banner> banners = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private boolean expanded = false;
    private boolean hover = false;

    public TimerBanners() {
        super(Coord.z);
    }

    private NGameUI gui() {
        return (NGameUI) parent;
    }

    private TimerStore store() {
        return gui().timerStore;
    }

    /** Raise a banner for timers that just became ready. */
    public void post(List<Timer> due, boolean away) {
        List<String> ids = new ArrayList<>();
        for(Timer t : due)
            ids.add(t.id);
        banners.add(0, new Banner(ids, away, Utils.rtime()));
        expanded = false;
        raise();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        double now = Utils.rtime();
        long ms = System.currentTimeMillis();
        TimerStore store = store();
        for(java.util.Iterator<Banner> it = banners.iterator(); it.hasNext(); ) {
            Banner b = it.next();
            // Acted on somewhere else - the panel, another banner, a villager removing it.
            b.ids.removeIf(id -> {
                Timer t = store.get(id);
                if(t == null)
                    return true;
                TimerStore.Local l = store.local(id);
                return !t.isReady(ms) || l.ackedStart == t.startedAt || l.snoozeUntil > ms;
            });
            if(hover)
                b.shownAt = now;
            if(b.ids.isEmpty() || now - b.shownAt > SHOW_SECONDS)
                it.remove();
        }
        if(banners.size() <= 1)
            expanded = false;
        resize(Coord.of(W, banners.isEmpty() ? 0 : height()));
        if(parent != null)
            c = Coord.of((parent.sz.x - W) / 2, TOP);
    }

    private int height() {
        if(expanded) {
            int h = 0;
            for(int i = 0; i < Math.min(banners.size(), MAX_EXPANDED); i++)
                h += bannerHeight() + GAP;
            return h;
        }
        int peeks = Math.min(banners.size() - 1, 2);
        int h = bannerHeight() + peeks * PEEK;
        if(banners.size() > 1)
            h += CookbookTheme.small.height() + UI.scale(4);
        return h;
    }

    private static int bannerHeight() {
        return PAD + CookbookTheme.small.height() + UI.scale(3) + CookbookTheme.bold.height()
            + CookbookTheme.small.height() + UI.scale(6) + BTN_H + PAD;
    }

    @Override
    public void draw(GOut g) {
        hits.clear();
        if(banners.isEmpty())
            return;
        if(expanded) {
            int y = 0;
            for(int i = 0; i < Math.min(banners.size(), MAX_EXPANDED); i++) {
                drawBanner(g, banners.get(i), y);
                y += bannerHeight() + GAP;
            }
            return;
        }
        int peeks = Math.min(banners.size() - 1, 2);
        int bh = bannerHeight();
        for(int i = peeks; i >= 1; i--) {
            int inset = UI.scale(6) * i;
            Coord ul = Coord.of(inset, bh + (i - 1) * PEEK - UI.scale(2));
            Coord sz = Coord.of(W - inset * 2, PEEK + UI.scale(2));
            CookbookTheme.fill(g, ul, sz, BG);
            CookbookTheme.frame(g, ul, sz, new Color(233, 156, 84, 150 - i * 40));
        }
        drawBanner(g, banners.get(0), 0);
        if(banners.size() > 1) {
            String more = L10n.get("timers.banner.more", banners.size() - 1);
            Tex t = TimerIcons.text(CookbookTheme.small, more, CookbookTheme.muted);
            int y = bh + peeks * PEEK + UI.scale(2);
            g.image(t, Coord.of((W - t.sz().x) / 2, y));
            hits.add(new Hit(Coord.of(0, bh), Coord.of(W, sz.y - bh), () -> expanded = true));
        }
    }

    private void drawBanner(GOut g, Banner b, int y) {
        TimerStore store = store();
        List<Timer> timers = new ArrayList<>();
        for(String id : b.ids) {
            Timer t = store.get(id);
            if(t != null)
                timers.add(t);
        }
        if(timers.isEmpty())
            return;
        long now = System.currentTimeMillis();
        Timer first = timers.get(0);
        int h = bannerHeight();
        CookbookTheme.fill(g, Coord.of(0, y), Coord.of(W, h), BG);
        CookbookTheme.frame(g, Coord.of(0, y), Coord.of(W, h), CookbookTheme.accent);

        // Header: icon, kind, age, close
        int iy = y + PAD;
        int icon = CookbookTheme.small.height() + UI.scale(2);
        TimerIcons.drawKindIcon(g, first, Coord.of(PAD, iy - UI.scale(1)), icon);
        String kind = (timers.size() > 1) ? L10n.get("timers.banner.kind_many")
            : L10n.get("timers.banner.kind_" + first.kind.key());
        g.image(TimerIcons.text(CookbookTheme.small, kind.toUpperCase(), CookbookTheme.muted), Coord.of(PAD + icon + UI.scale(5), iy));
        Tex close = TimerIcons.text(CookbookTheme.bold, "✕", CookbookTheme.fg);
        Coord cul = Coord.of(W - PAD - close.sz().x, iy - UI.scale(2));
        g.image(close, cul);
        hits.add(new Hit(cul.sub(UI.scale(3), UI.scale(3)), close.sz().add(UI.scale(6), UI.scale(6)), () -> dismissAll(b)));
        String age = TimerDurations.format(now - first.readyAt());
        Tex ageTex = TimerIcons.text(CookbookTheme.small, L10n.get("timers.ago", age), CookbookTheme.muted);
        g.image(ageTex, Coord.of(cul.x - UI.scale(8) - ageTex.sz().x, iy));

        // Title and detail line
        int ty = iy + CookbookTheme.small.height() + UI.scale(3);
        String title;
        String sub;
        if(timers.size() == 1) {
            title = L10n.get("timers.banner.ready", displayName(first));
            sub = detailLine(first, now);
        } else {
            title = b.away ? L10n.get("timers.banner.away", timers.size()) : L10n.get("timers.banner.ready_many", timers.size());
            List<String> names = new ArrayList<>();
            for(Timer t : timers)
                names.add(displayName(t));
            sub = String.join(", ", names);
        }
        if(timers.size() == 1 && b.away)
            sub = L10n.get("timers.banner.away_one") + " · " + sub;
        g.image(TimerIcons.text(CookbookTheme.bold, CookbookTheme.ellipsize(CookbookTheme.bold, title, W - PAD * 2), CookbookTheme.fg),
            Coord.of(PAD, ty));
        int sy = ty + CookbookTheme.bold.height();
        g.image(TimerIcons.text(CookbookTheme.small, CookbookTheme.ellipsize(CookbookTheme.small, sub, W - PAD * 2), CookbookTheme.muted),
            Coord.of(PAD, sy));

        // Actions
        int by = sy + CookbookTheme.small.height() + UI.scale(6);
        int bx = PAD;
        if(timers.size() == 1) {
            Timer t = first;
            if(t.hasLocation())
                bx = button(g, bx, by, L10n.get("timers.action.show_on_map"), true, () -> {
                    if(!TimerPlacement.showOnMap(gui(), t))
                        gui().msg(L10n.get("timers.not_on_map"));
                });
            bx = button(g, bx, by, L10n.get("timers.action.restart", TimerDurations.formatShort(t.durationMs)), !t.hasLocation(), () -> {
                store.restart(t.id, System.currentTimeMillis());
                store.rememberDuration(TimerStore.durationKey(t.kind, t.resType), t.durationMs);
            });
            bx = button(g, bx, by, L10n.get("timers.action.snooze"), false,
                () -> store.snooze(t.id, System.currentTimeMillis() + 15 * 60 * 1000L));
            button(g, bx, by, L10n.get("timers.action.dismiss"), false, () -> dismissAll(b));
        } else {
            bx = button(g, bx, by, L10n.get("timers.action.open"), true, () -> gui().showTimersPanel());
            button(g, bx, by, L10n.get("timers.action.dismiss_all"), false, () -> dismissAll(b));
        }
    }

    private int button(GOut g, int x, int y, String text, boolean primary, Runnable action) {
        Tex t = TimerIcons.text(CookbookTheme.small, text, primary ? CookbookTheme.ink : CookbookTheme.accent);
        Coord sz = Coord.of(t.sz().x + UI.scale(14), BTN_H);
        Coord ul = Coord.of(x, y);
        if(primary)
            CookbookTheme.fill(g, ul, sz, CookbookTheme.accent);
        CookbookTheme.frame(g, ul, sz, CookbookTheme.accent);
        g.image(t, ul.add((sz.x - t.sz().x) / 2, (sz.y - t.sz().y) / 2));
        hits.add(new Hit(ul, sz, action));
        return x + sz.x + UI.scale(6);
    }

    private void dismissAll(Banner b) {
        long now = System.currentTimeMillis();
        for(String id : new ArrayList<>(b.ids))
            store().dismiss(id, now);
        banners.remove(b);
    }

    static String displayName(Timer t) {
        if(!t.name.isEmpty())
            return t.name;
        return L10n.get("timers.banner.kind_" + t.kind.key());
    }

    /** "NW of here · set by Olga 6h ago" style detail for one timer. */
    static String detailLine(Timer t, long now) {
        String who = TimerStore.isLocalCharacter(t.setBy) ? L10n.get("timers.by_you") : t.setBy;
        return L10n.get("timers.set_ago", who, TimerDurations.format(now - t.startedAt));
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        for(int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if(ev.c.isect(h.ul, h.sz)) {
                h.action.run();
                return true;
            }
        }
        // A click on the banner itself is swallowed so it never walks the character somewhere.
        return ev.c.isect(Coord.z, sz);
    }

    @Override
    public boolean mousehover(MouseHoverEvent ev, boolean hovering) {
        hover = hovering;
        return hovering;
    }
}
