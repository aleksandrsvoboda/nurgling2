package nurgling.sessions.commander;

import haven.*;
import nurgling.i18n.L10n;

import java.util.List;

/**
 * Left side of the Commander screen: the latest alerts, newest on top, each with how long ago it was. Click one to
 * send the camera there; Space goes to the newest, then to older ones.
 */
class AlertFeed extends HudPanel {
    static final String DRAG_NAME = "Commander alerts";
    private static final int W = UI.scale(260), ROW = UI.scale(16), SHOW = 6;
    private static final long FRESH_MS = 120000;

    AlertFeed() {
        super(new Coord(W, ROW + 2 * PAD));
    }

    @Override
    boolean visibleNow() {
        List<Alerts.Alert> a = cmd().alerts.all();
        return !a.isEmpty() && System.currentTimeMillis() - a.get(0).time < FRESH_MS;
    }

    @Override
    void render(GOut g) {
        long now = System.currentTimeMillis();
        List<Alerts.Alert> all = cmd().alerts.all();
        int n = 0;
        for (Alerts.Alert a : all) {
            if (n >= SHOW || now - a.time >= FRESH_MS)
                break;
            n++;
        }
        int hh = UI.scale(16);
        fit(new Coord(W, PAD + hh + Math.max(1, n) * ROW + PAD));
        background(g);
        g.aimage(text(L10n.get("commander.alerts.title"), TEAL, true), new Coord(PAD, PAD + hh / 2), 0, 0.5);
        int y = PAD + hh;
        if (n == 0) {
            g.image(text(L10n.get("commander.alerts.none"), DIM), new Coord(PAD, y));
            return;
        }
        for (int i = 0; i < n; i++) {
            Alerts.Alert a = all.get(i);
            g.chcolor(a.urgent() ? RED : ORANGE);
            g.frect(new Coord(PAD, y + ROW / 2 - UI.scale(3)), UI.scale(new Coord(6, 6)));
            g.chcolor();
            Tex age = text(ago(now - a.time), DIM);
            g.image(text(clip(a.text, 38), TEXT), new Coord(PAD + UI.scale(10), y + UI.scale(1)));
            g.image(age, new Coord(W - PAD - age.sz().x, y + UI.scale(1)));
            cells.add(new Cell(Area.sized(new Coord(0, y), new Coord(W, ROW)), "a" + a.time + a.sessionId,
                               (ev, dbl) -> cmd().jumpTo(a.where), L10n.get("commander.tip.alertrow")));
            y += ROW;
        }
    }

    private static String ago(long ms) {
        long s = ms / 1000;
        return (s < 60) ? (s + "s") : ((s / 60) + "m");
    }
}
