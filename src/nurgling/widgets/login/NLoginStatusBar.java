package nurgling.widgets.login;

import haven.*;
import nurgling.i18n.L10n;

import java.awt.Color;
import java.net.URI;

/**
 * One line along the bottom of the login art: server state and players online on the left, the
 * client version and an "update available" badge on the right.
 */
public class NLoginStatusBar extends Widget {
    private static final Color UP = new Color(127, 236, 58);
    private static final Color DOWN = NLoginTheme.err;

    private final HttpStatus stat;
    private volatile String local = null, remote = null;
    private Text left = null, right = null, upd = null, updtip = null;
    private String lefts = null, rights = null;
    private int updx = -1;

    public NLoginStatusBar(URI svc, int w) {
        super(Coord.of(w, UI.scale(20)));
        this.stat = new HttpStatus(svc);
    }

    /** Called from the version-check thread; {@code remote} is null unless it is newer. */
    public void versions(String local, String remote) {
        this.local = local;
        this.remote = remote;
    }

    protected void added() {
        stat.start();
    }

    public void dispose() {
        stat.quit();
        super.dispose();
    }

    public void draw(GOut g) {
        String st = null;
        Color dot = null;
        synchronized (stat) {
            if (stat.syn && (stat.status != null)) {
                if ("up".equals(stat.status)) {
                    st = L10n.get("login.server_up") + "  ·  " + L10n.get("login.players_online", stat.users);
                    dot = UP;
                } else if ("shutdown".equals(stat.status)) {
                    st = L10n.get("login.server_down");
                    dot = DOWN;
                } else if ("terminating".equals(stat.status)) {
                    st = L10n.get("login.server_shutdown");
                    dot = NLoginTheme.warn;
                } else if ("crashed".equals(stat.status)) {
                    st = L10n.get("login.server_crashed");
                    dot = DOWN;
                }
            }
        }
        int cy = sz.y / 2;
        if (st != null) {
            if (!st.equals(lefts)) {
                lefts = st;
                left = NLoginTheme.status.render(st);
            }
            int d = UI.scale(8);
            g.chcolor(dot);
            g.frect(Coord.of(0, cy - (d / 2)), Coord.of(d, d));
            g.chcolor();
            g.image(left.tex(), Coord.of(d + UI.scale(6), cy - (left.sz().y / 2)));
        }
        String lv = local, rv = remote;
        int x = sz.x;
        updx = -1;
        if (rv != null) {
            if (upd == null)
                upd = NLoginTheme.badge.render(L10n.get("login.update_badge"), NLoginTheme.accent);
            x -= upd.sz().x + UI.scale(10);
            updx = x;
            NLoginTheme.drawBadge(g, Coord.of(x, cy - (NLoginTheme.badgeh() / 2)), upd, NLoginTheme.accent);
            x -= UI.scale(8);
        }
        if (lv != null) {
            String s = L10n.get("login.version", lv);
            if (!s.equals(rights)) {
                rights = s;
                right = NLoginTheme.status.render(s);
            }
            x -= right.sz().x;
            g.image(right.tex(), Coord.of(x, cy - (right.sz().y / 2)));
        }
    }

    public Object tooltip(Coord c, Widget prev) {
        String rv = remote;
        if ((rv == null) || (updx < 0) || (c.x < updx))
            return (null);
        if (updtip == null)
            updtip = NLoginTheme.tip.render(L10n.get("login.update_tip", rv));
        return (updtip);
    }
}
