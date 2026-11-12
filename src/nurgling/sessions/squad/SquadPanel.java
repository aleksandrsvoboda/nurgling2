package nurgling.sessions.squad;

import haven.*;
import nurgling.NUI;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

import java.awt.Color;
import java.awt.Font;
import java.util.*;
import java.util.List;

/**
 * The squad at a glance: one row per selected character (health, what it is doing, how far away) and the squad
 * controls. Shown in Commander mode or whenever two or more characters are selected. Drawn like the session bar: dark
 * plate, square 1px frames, orange accent. Drag it by its title.
 */
public class SquadPanel extends Widget {
    private static final int W = UI.scale(250);
    private static final int ROW = UI.scale(16);
    private static final int PAD = UI.scale(5);
    private static final int BTN = UI.scale(17);
    private static final Color FRAME = new Color(0x91, 0x60, 0x2E);
    private static final Color TEXT = new Color(220, 220, 220);
    private static final Color DIM = new Color(150, 150, 150);
    private static final Color HP = new Color(0xB0, 0x3A, 0x3A);
    private static final long DOUBLE_MS = 400;

    private static Text.Foundry font;
    private final Map<String, Tex> texts = new HashMap<>();

    private final List<Cell> cells = new ArrayList<>();
    private UI.Grab drag;
    private Coord doff;
    private String lastRow;
    private long lastRowTime;

    private static final class Cell {
        final Area a;
        final Runnable click;
        final SessionContext row;

        Cell(Area a, Runnable click, SessionContext row) {
            this.a = a;
            this.click = click;
            this.row = row;
        }
    }

    public SquadPanel() {
        super(new Coord(W, UI.scale(40)));
    }

    @Override
    protected void added() {
        super.added();
        String p = Utils.getpref("squadpanel-pos", null);
        Coord at = null;
        if (p != null) {
            String[] xy = p.split(",");
            try {
                at = new Coord(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                /* Unreadable saved position: use the default. */
            }
        }
        if (at == null)
            at = new Coord(parent.sz.x - W - UI.scale(20), UI.scale(220));
        c = at;
    }

    private static Squad squad() {
        return SessionManager.getInstance().squad();
    }

    private boolean shown() {
        if (ui == null || ((NUI) ui).gui == null)
            return false;
        Squad sq = squad();
        return sq.commander() || sq.members().size() >= 2;
    }

    private Tex text(String s, Color col) {
        String k = col.getRGB() + s;
        Tex t = texts.get(k);
        if (t == null) {
            if (font == null)
                font = new Text.Foundry(Text.sans.deriveFont(Font.PLAIN), 11).aa(true);
            if (texts.size() > 200)
                texts.clear();
            t = font.render(s, col).tex();
            texts.put(k, t);
        }
        return t;
    }

    @Override
    public void draw(GOut g) {
        cells.clear();
        if (!shown())
            return;
        Squad sq = squad();
        List<SessionContext> m = sq.members();
        SessionContext vp = sq.viewpoint();
        int y = 0;
        int h = PAD + ROW + Math.max(1, m.size()) * ROW + PAD + 4 * (BTN + 3) + PAD;
        if (sz.y != h)
            resize(new Coord(W, h));
        g.chcolor(SquadView.PANEL);
        g.frect(Coord.z, sz);
        g.chcolor(FRAME);
        g.rect(Coord.z, sz);
        g.chcolor();
        y += PAD;
        g.image(text(L10n.get("squad.panel.title", m.size()), SquadView.TEAL), new Coord(PAD, y));
        Tex x = text("×", DIM);
        Coord xc = new Coord(W - PAD - x.sz().x, y);
        g.image(x, xc);
        cells.add(new Cell(Area.sized(xc.sub(2, 0), x.sz().add(4, 0)), () -> {
            sq.clear();
            sq.setCommander(false);
        }, null));
        y += ROW;
        if (m.isEmpty()) {
            g.image(text(L10n.get("squad.panel.empty"), DIM), new Coord(PAD, y));
            y += ROW;
        }
        for (SessionContext ctx : m) {
            MemberReport r = ctx.squadReport;
            if (r == null)
                continue;
            int grp = Squad.groupOf(r);
            if (grp > 0)
                g.image(text(Integer.toString(grp), SquadView.ORANGE), new Coord(PAD, y));
            g.image(text(clip(r.name, 14) + (ctx == vp ? " ◆" : ""), TEXT), new Coord(PAD + UI.scale(12), y));
            int bx = PAD + UI.scale(108), bw = UI.scale(40);
            g.chcolor(0, 0, 0, 160);
            g.frect(new Coord(bx, y + UI.scale(5)), new Coord(bw, UI.scale(5)));
            if (r.hp >= 0) {
                g.chcolor(HP);
                g.frect(new Coord(bx, y + UI.scale(5)), new Coord((int) (bw * Math.min(1, r.hp)), UI.scale(5)));
            }
            g.chcolor();
            String st = status(r, ctx, vp);
            Color sc = (r.status == MemberReport.Status.UNREACHABLE || r.status == MemberReport.Status.SKIPPED
                        || r.status == MemberReport.Status.COMBAT) ? SquadView.ORANGE : DIM;
            g.image(text(clip(st, 16), sc), new Coord(bx + bw + UI.scale(6), y));
            final SessionContext row = ctx;
            cells.add(new Cell(Area.sized(new Coord(0, y), new Coord(W, ROW)), null, row));
            y += ROW;
        }
        y += PAD;
        Formation.Kind fk = Squad.formation();
        y = buttons(g, y, new String[]{"squad.f.grid", "squad.f.line", "squad.f.keep", "squad.f.stack"},
                    new boolean[]{fk == Formation.Kind.GRID, fk == Formation.Kind.LINE, fk == Formation.Kind.KEEP,
                                  fk == Formation.Kind.STACK},
                    new Runnable[]{() -> Squad.setFormation(Formation.Kind.GRID), () -> Squad.setFormation(Formation.Kind.LINE),
                                   () -> Squad.setFormation(Formation.Kind.KEEP), () -> Squad.setFormation(Formation.Kind.STACK)});
        y = buttons(g, y, new String[]{"squad.b.stop", "squad.b.rally", "squad.b.follow"}, new boolean[3],
                    new Runnable[]{sq::stop, sq::rally, sq::follow});
        boolean pf = Squad.pathfind(), sb = Squad.stopBots();
        y = buttons(g, y, new String[]{pf ? "squad.b.pf_on" : "squad.b.pf_off", sb ? "squad.b.bots_stop" : "squad.b.bots_skip"},
                    new boolean[]{pf, sb},
                    new Runnable[]{() -> Squad.setPathfind(!Squad.pathfind()), () -> Squad.setStopBots(!Squad.stopBots())});
        buttons(g, y, new String[]{"squad.s.crawl", "squad.s.walk", "squad.s.run", "squad.s.sprint"}, new boolean[4],
                new Runnable[]{() -> sq.setSpeed(0), () -> sq.setSpeed(1), () -> sq.setSpeed(2), () -> sq.setSpeed(3)});
    }

    private int buttons(GOut g, int y, String[] keys, boolean[] on, Runnable[] act) {
        int n = keys.length, gap = UI.scale(3);
        int bw = (W - 2 * PAD - (n - 1) * gap) / n;
        for (int i = 0; i < n; i++) {
            Coord ul = new Coord(PAD + i * (bw + gap), y);
            Coord bsz = new Coord(bw, BTN);
            g.chcolor(on[i] ? SquadView.ORANGE : SquadView.PANEL);
            g.frect(ul, bsz);
            g.chcolor(on[i] ? SquadView.ORANGE : FRAME);
            g.rect(ul, bsz);
            g.chcolor();
            Tex t = text(L10n.get(keys[i]), on[i] ? new Color(0x25, 0x2B, 0x29) : TEXT);
            g.aimage(t, ul.add(bsz.div(2)), 0.5, 0.5);
            cells.add(new Cell(Area.sized(ul, bsz), act[i], null));
        }
        return y + BTN + gap;
    }

    private static String status(MemberReport r, SessionContext ctx, SessionContext vp) {
        String s = L10n.get("squad.st." + r.status.name().toLowerCase(Locale.ROOT));
        if (r.detail != null && (r.status == MemberReport.Status.BOT || r.status == MemberReport.Status.SKIPPED
                                 || r.status == MemberReport.Status.UNREACHABLE))
            s = s + ": " + r.detail;
        return s;
    }

    private static String clip(String s, int n) {
        return (s == null) ? "" : (s.length() <= n) ? s : s.substring(0, n - 1) + "…";
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (!shown() || ev.b != 1)
            return super.mousedown(ev);
        for (Cell cl : cells) {
            if (!cl.a.contains(ev.c))
                continue;
            if (cl.click != null) {
                cl.click.run();
            } else if (cl.row != null) {
                long now = System.currentTimeMillis();
                if (cl.row.sessionId.equals(lastRow) && now - lastRowTime < DOUBLE_MS)
                    SessionManager.getInstance().switchToSession(cl.row.sessionId);
                else if (ui.modctrl)
                    squad().toggle(cl.row);
                else
                    squad().select(Collections.singletonList(cl.row), false);
                lastRow = cl.row.sessionId;
                lastRowTime = now;
            }
            return true;
        }
        if (ev.c.y < PAD + ROW) {
            drag = ui.grabmouse(this);
            doff = ev.c;
            return true;
        }
        return true;
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        if (drag != null) {
            c = c.add(ev.c.sub(doff));
            return;
        }
        super.mousemove(ev);
    }

    @Override
    public boolean mouseup(MouseUpEvent ev) {
        if (drag != null) {
            drag.remove();
            drag = null;
            Utils.setpref("squadpanel-pos", c.x + "," + c.y);
            return true;
        }
        return super.mouseup(ev);
    }

    @Override
    public boolean checkhit(Coord c) {
        return shown() && super.checkhit(c);
    }
}
