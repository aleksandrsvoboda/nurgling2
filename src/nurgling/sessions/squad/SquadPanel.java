package nurgling.sessions.squad;

import haven.*;
import nurgling.NCore;
import nurgling.NUI;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.widgets.NDraggableWidget;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * The squad at a glance: one row per selected character (health, what it is doing) and the squad controls. Shown in
 * Commander mode, whenever two or more characters are selected, and in drag mode so it can be placed. It lives in an
 * NDraggableWidget on each game UI like the other HUD widgets, which gives it the drag-mode frame, lock, hide toggle
 * and saved position.
 */
public class SquadPanel extends Widget {
    /** Name of its draggable wrapper: the drag-mode label and the key of the saved position. */
    public static final String DRAG_NAME = "Squad";

    private static final int W = UI.scale(250);
    private static final int ROW = UI.scale(16);
    private static final int PAD = UI.scale(5);
    private static final int BTN = UI.scale(17);
    private static final long DOUBLE_MS = 400;
    private static final Color FRAME = new Color(0x91, 0x60, 0x2E);
    private static final Color TEXT = new Color(220, 220, 220);
    private static final Color DIM = new Color(150, 150, 150);
    private static final Color HP = new Color(0xB0, 0x3A, 0x3A);
    private static final Color ON_TEXT = new Color(0x25, 0x2B, 0x29);

    /** Button states: off, used by some selected characters (outline), on (filled). */
    private static final int OFF = 0, SOME = 1, ON = 2;

    /** The same help button as the inventory search bar. */
    private static final BufferedImage[] HELP = {
            Resource.loadsimg("nurgling/hud/buttons/inv/question/u"),
            Resource.loadsimg("nurgling/hud/buttons/inv/question/d"),
            Resource.loadsimg("nurgling/hud/buttons/inv/question/h")};

    private static Text.Foundry font;
    private final Map<String, Tex> texts = new HashMap<>();
    private final List<Cell> cells = new ArrayList<>();
    private final IButton help;
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
        super(new Coord(W, UI.scale(120)));
        help = add(new IButton(HELP[0], HELP[1], HELP[2]) {
            @Override
            public void click() {
                NUI nui = (NUI) ui;
                if (nui.gui != null)
                    SquadHelpWindow.open(nui.gui);
            }

            @Override
            public Object tooltip(Coord c, Widget prev) {
                return L10n.get("squad.panel.info");
            }
        });
    }

    private static Squad squad() {
        return SessionManager.getInstance().squad();
    }

    private boolean shown() {
        if (ui == null || ((NUI) ui).gui == null)
            return false;
        if (ui.core != null && ui.core.mode == NCore.Mode.DRAG)
            return true;
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

    private int header() {
        return Math.max(ROW, help.sz.y);
    }

    /** Height for this many member rows (at least room for the two-line hint). */
    private int height(int members) {
        int rows = (members == 0) ? 2 : members;
        return PAD + header() + rows * ROW + PAD + 4 * (BTN + UI.scale(3)) + PAD;
    }

    /** Follow the content's height; the draggable wrapper is sized around it. */
    private void fit(int h) {
        if (sz.y == h)
            return;
        if (parent instanceof NDraggableWidget)
            parent.resize(new Coord(W, h).add(NDraggableWidget.delta));
        else
            resize(new Coord(W, h));
    }

    @Override
    public void draw(GOut g) {
        cells.clear();
        boolean show = shown();
        help.visible = show;
        if (!show)
            return;
        Squad sq = squad();
        List<SessionContext> m = sq.members();
        SessionContext vp = sq.viewpoint();
        fit(height(m.size()));
        g.chcolor(SquadView.PANEL);
        g.frect(Coord.z, sz);
        g.chcolor(FRAME);
        g.rect(Coord.z, sz);
        g.chcolor();

        int hh = header();
        int y = PAD;
        g.aimage(text(L10n.get("squad.panel.title", m.size()), SquadView.TEAL), new Coord(PAD, y + hh / 2), 0, 0.5);
        Tex x = text("×", DIM);
        Coord xc = new Coord(W - PAD - x.sz().x, y + (hh - x.sz().y) / 2);
        g.image(x, xc);
        cells.add(new Cell(Area.sized(xc.sub(UI.scale(3), UI.scale(3)), x.sz().add(UI.scale(6), UI.scale(6))), () -> {
            sq.clear();
            sq.setCommander(false);
        }, null));
        help.move(new Coord(xc.x - UI.scale(6) - help.sz.x, y + (hh - help.sz.y) / 2));
        y += hh;

        if (m.isEmpty()) {
            g.image(text(L10n.get("squad.panel.empty"), DIM), new Coord(PAD, y));
            g.image(text(L10n.get("squad.panel.empty2"), DIM), new Coord(PAD, y + ROW));
            y += 2 * ROW;
        }
        int[] speeds = new int[4];
        for (SessionContext ctx : m) {
            MemberReport r = ctx.squadReport;
            if (r == null)
                continue;
            if (r.speed >= 0 && r.speed < speeds.length)
                speeds[r.speed]++;
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
            Color sc = (r.status == MemberReport.Status.UNREACHABLE || r.status == MemberReport.Status.SKIPPED
                        || r.status == MemberReport.Status.COMBAT) ? SquadView.ORANGE : DIM;
            g.image(text(clip(status(r), 16), sc), new Coord(bx + bw + UI.scale(6), y));
            cells.add(new Cell(Area.sized(new Coord(0, y), new Coord(W, ROW)), null, ctx));
            y += ROW;
        }
        y += PAD;

        Formation.Kind fk = Squad.formation();
        y = buttons(g, y, new String[]{"squad.f.grid", "squad.f.line", "squad.f.keep", "squad.f.stack"},
                    new int[]{st(fk == Formation.Kind.GRID), st(fk == Formation.Kind.LINE), st(fk == Formation.Kind.KEEP),
                              st(fk == Formation.Kind.STACK)},
                    new Runnable[]{() -> Squad.setFormation(Formation.Kind.GRID), () -> Squad.setFormation(Formation.Kind.LINE),
                                   () -> Squad.setFormation(Formation.Kind.KEEP), () -> Squad.setFormation(Formation.Kind.STACK)});
        y = buttons(g, y, new String[]{"squad.b.stop", "squad.b.rally", "squad.b.follow"}, new int[3],
                    new Runnable[]{sq::stop, sq::rally, sq::follow});
        boolean pf = Squad.pathfind(), sb = Squad.stopBots(), av = Squad.autoView();
        y = buttons(g, y, new String[]{pf ? "squad.b.pf_on" : "squad.b.pf_off", sb ? "squad.b.bots_stop" : "squad.b.bots_skip",
                                       av ? "squad.b.view_auto" : "squad.b.view_manual"},
                    new int[]{st(pf), st(sb), st(av)},
                    new Runnable[]{() -> Squad.setPathfind(!Squad.pathfind()), () -> Squad.setStopBots(!Squad.stopBots()),
                                   () -> Squad.setAutoView(!Squad.autoView())});
        /* Speed: filled when every selected character moves at it, outlined when only some do. */
        int[] sst = new int[4];
        int known = speeds[0] + speeds[1] + speeds[2] + speeds[3];
        for (int i = 0; i < 4; i++)
            sst[i] = (speeds[i] == 0) ? OFF : (speeds[i] == known) ? ON : SOME;
        buttons(g, y, new String[]{"squad.s.crawl", "squad.s.walk", "squad.s.run", "squad.s.sprint"}, sst,
                new Runnable[]{() -> sq.setSpeed(0), () -> sq.setSpeed(1), () -> sq.setSpeed(2), () -> sq.setSpeed(3)});

        super.draw(g);
    }

    private static int st(boolean on) {
        return on ? ON : OFF;
    }

    private int buttons(GOut g, int y, String[] keys, int[] state, Runnable[] act) {
        int n = keys.length, gap = UI.scale(3);
        int bw = (W - 2 * PAD - (n - 1) * gap) / n;
        for (int i = 0; i < n; i++) {
            Coord ul = new Coord(PAD + i * (bw + gap), y);
            Coord bsz = new Coord(bw, BTN);
            boolean on = state[i] == ON;
            g.chcolor(on ? SquadView.ORANGE : SquadView.PANEL);
            g.frect(ul, bsz);
            g.chcolor(state[i] == OFF ? FRAME : SquadView.ORANGE);
            g.rect(ul, bsz);
            if (state[i] == SOME)
                g.rect(ul.add(1, 1), bsz.sub(2, 2));
            g.chcolor();
            g.aimage(text(L10n.get(keys[i]), on ? ON_TEXT : TEXT), ul.add(bsz.div(2)), 0.5, 0.5);
            cells.add(new Cell(Area.sized(ul, bsz), act[i], null));
        }
        return y + BTN + gap;
    }

    private static String status(MemberReport r) {
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
        if (!shown())
            return false;
        /* A widget handles a press before its children and they only see it if it declines, so offer it to the
         * child widgets (the help button) first, as Window.DragDeco does. */
        if (ev.propagate(this))
            return true;
        if (ev.b != 1)
            return true;
        for (Cell cl : cells) {
            if (!cl.a.contains(ev.c))
                continue;
            if (cl.click != null) {
                cl.click.run();
            } else if (cl.row != null) {
                /* Double-click views the character; a single click selects (and views it when auto view is on). */
                long now = System.currentTimeMillis();
                if (cl.row.sessionId.equals(lastRow) && now - lastRowTime < DOUBLE_MS)
                    squad().view(cl.row);
                else if (ui.modctrl)
                    squad().toggle(cl.row);
                else
                    squad().select(Collections.singletonList(cl.row), false);
                lastRow = cl.row.sessionId;
                lastRowTime = now;
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean checkhit(Coord c) {
        return shown() && super.checkhit(c);
    }
}
