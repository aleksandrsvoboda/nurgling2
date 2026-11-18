package nurgling.sessions.commander;

import haven.*;
import nurgling.NGameUI;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.MemberReport;
import nurgling.sessions.squad.Squad;

import java.awt.Color;
import java.util.*;
import java.util.List;

/**
 * The Commander screen's roster: every logged-in character as a card with its own portrait, and under it health,
 * stamina, energy, what it is doing and how full its inventory is. The cards are grouped by theater, each theater a
 * bordered section with its name on top (click it to send the camera there); the camera's theater is outlined orange.
 *
 * <p>A card: click selects, Shift- or Ctrl-click adds or removes, double-click sends the camera to the character (and
 * shows its session), right-click makes it the leader, the inventory line shows its inventory.
 *
 * <p>The portraits are the characters' own, from their own sessions: they are drawn over the roster right after the
 * frame's interface ({@link CommanderPortraits}), where each session's view can be drawn under its own lock.
 */
class Roster extends HudPanel {
    static final String DRAG_NAME = "Commander roster";
    private static final int GAP = UI.scale(3), SEC = UI.scale(4), HEAD = UI.scale(16), PER_ROW = 4;

    Roster() {
        super(UI.scale(new Coord(300, 160)));
    }

    static int portrait() {
        return UI.scale(40);
    }

    private static int cardW() {
        return UI.scale(62);
    }

    private static int cardH() {
        return UI.scale(3) + portrait() + UI.scale(3) + UI.scale(13) + 3 * UI.scale(5) + UI.scale(25);
    }

    /** Characters this close (in grids) are one group of a theater. */
    private static final int GROUP_GRIDS = 3;

    /**
     * The sections: each theater's characters, split into groups of characters that are near each other (one far
     * off on the same piece of map is a group of its own), then the characters not placed on the map yet.
     */
    private static List<List<SessionContext>> sections(Commander c, List<String> labels, List<Long> segs) {
        List<List<SessionContext>> out = new ArrayList<>();
        Set<SessionContext> placed = new HashSet<>();
        for (Theater t : c.theaters()) {
            for (List<SessionContext> grp : groups(t.members)) {
                out.add(grp);
                labels.add(new Theater(t.seg, grp).label());
                segs.add(t.seg);
            }
            placed.addAll(t.members);
        }
        List<SessionContext> rest = new ArrayList<>();
        for (SessionContext s : SessionManager.getInstance().squad().candidates())
            if (!placed.contains(s))
                rest.add(s);
        if (!rest.isEmpty()) {
            out.add(rest);
            labels.add(L10n.get("commander.roster.unplaced"));
            segs.add(null);
        }
        return out;
    }

    /** Characters linked by being near each other, in session-bar order. */
    private static List<List<SessionContext>> groups(List<SessionContext> ms) {
        List<List<SessionContext>> out = new ArrayList<>();
        boolean[] done = new boolean[ms.size()];
        for (int i = 0; i < ms.size(); i++) {
            if (done[i])
                continue;
            List<SessionContext> grp = new ArrayList<>();
            Deque<Integer> todo = new ArrayDeque<>();
            todo.add(i);
            done[i] = true;
            while (!todo.isEmpty()) {
                int a = todo.poll();
                grp.add(ms.get(a));
                for (int b = 0; b < ms.size(); b++) {
                    if (!done[b] && near(ms.get(a), ms.get(b))) {
                        done[b] = true;
                        todo.add(b);
                    }
                }
            }
            grp.sort(Comparator.comparingInt(ms::indexOf));
            out.add(grp);
        }
        return out;
    }

    private static boolean near(SessionContext a, SessionContext b) {
        Focus fa = Focus.of(a.squadReport.pos), fb = Focus.of(b.squadReport.pos);
        if (fa == null || fb == null)
            return false;
        Coord ga = fa.grid(), gb = fb.grid();
        return Math.abs(ga.x - gb.x) <= GROUP_GRIDS && Math.abs(ga.y - gb.y) <= GROUP_GRIDS;
    }

    /** The group the camera is looking at: on its theater, with a character within the group distance of it. */
    private static boolean underCamera(List<SessionContext> grp, Focus cam) {
        if (cam == null)
            return false;
        Coord cg = cam.grid();
        for (SessionContext s : grp) {
            Focus f = (s.squadReport == null) ? null : Focus.of(s.squadReport.pos);
            if (f != null && f.seg == cam.seg && Math.abs(f.grid().x - cg.x) <= GROUP_GRIDS && Math.abs(f.grid().y - cg.y) <= GROUP_GRIDS)
                return true;
        }
        return false;
    }

    @Override
    void render(GOut g) {
        Commander c = cmd();
        Squad sq = SessionManager.getInstance().squad();
        NGameUI gui = gui();
        List<String> labels = new ArrayList<>();
        List<Long> segs = new ArrayList<>();
        List<List<SessionContext>> secs = sections(c, labels, segs);
        int cw = cardW(), ch = cardH();
        /* Size first: sections side by side, left to right, each as wide as its cards (or its name), all as tall as
         * the tallest. */
        int w = PAD, sh = 0;
        int[] sw = new int[secs.size()];
        for (int i = 0; i < secs.size(); i++) {
            int n = Math.max(1, secs.get(i).size());
            int cols = Math.min(PER_ROW, n), rows = (n + PER_ROW - 1) / PER_ROW;
            String head = clip(labels.get(i), 18) + " \u00B7 " + secs.get(i).size();
            sw[i] = Math.max(2 * SEC + cols * cw + (cols - 1) * GAP, text(head, TEAL, true).sz().x + 2 * SEC);
            sh = Math.max(sh, HEAD + SEC + rows * ch + (rows - 1) * GAP + SEC);
            w += sw[i] + PAD;
        }
        int h = 2 * PAD + sh;
        if (secs.isEmpty()) {
            w = UI.scale(200);
            h = UI.scale(30);
        }
        fit(new Coord(w, h));
        background(g);
        List<CommanderPortraits.Slot> pics = new ArrayList<>();
        if (secs.isEmpty())
            g.aimage(text(L10n.get("commander.units.none"), DIM), sz.div(2), 0.5, 0.5);
        int x = PAD, y = PAD;
        SessionContext vp = sq.viewpoint(), ld = sq.leader();
        for (int i = 0; i < secs.size(); i++) {
            Long seg = segs.get(i);
            boolean here = (seg != null) && underCamera(secs.get(i), c.cameraAt(gui));
            Coord sul = new Coord(x, y), ssz = new Coord(sw[i], sh);
            g.chcolor(here ? new Color(0x33, 0x2A, 0x20, 0xC0) : new Color(0x1E, 0x23, 0x22, 0xC0));
            g.frect(sul, ssz);
            g.chcolor(here ? ORANGE : FRAME);
            g.rect(sul, ssz);
            if (here)
                g.rect(sul.add(1, 1), ssz.sub(2, 2));
            g.chcolor();
            String head = clip(labels.get(i), 18) + " \u00B7 " + secs.get(i).size();
            g.aimage(text(head, here ? ORANGE : TEAL, true), new Coord(sul.x + SEC, y + HEAD / 2 + UI.scale(2)), 0, 0.5);
            if (seg != null && !secs.get(i).isEmpty()) {
                /* The camera to the group: its first character. */
                final SessionContext first = secs.get(i).get(0);
                cells.add(new Cell(Area.sized(sul, new Coord(ssz.x, HEAD)), "h" + first.sessionId, (ev, dbl) -> {
                    if (ev.b == 1)
                        c.jumpTo(first);
                }, L10n.get("commander.tip.theater")));
            }
            List<SessionContext> ms = secs.get(i);
            for (int k = 0; k < ms.size(); k++) {
                Coord ul = new Coord(sul.x + SEC + (k % PER_ROW) * (cw + GAP), y + HEAD + SEC + (k / PER_ROW) * (ch + GAP));
                card(g, ul, ms.get(k), sq, vp, ld, pics);
            }
            x += sw[i] + PAD;
        }
        CommanderPortraits.want(ui, pics);
    }


    private void card(GOut g, Coord ul, SessionContext ctx, Squad sq, SessionContext vp, SessionContext ld,
                      List<CommanderPortraits.Slot> pics) {
        MemberReport r = ctx.squadReport;
        boolean sel = sq.isSelected(ctx);
        boolean combat = r != null && r.status == MemberReport.Status.COMBAT;
        int cw = cardW(), ch = cardH(), p = portrait();
        Coord csz = new Coord(cw, ch);
        g.chcolor(0x2E, 0x36, 0x33, 0xF0);
        g.frect(ul, csz);
        g.chcolor(combat ? RED : sel ? ORANGE : FRAME);
        g.rect(ul, csz);
        if (sel || combat)
            g.rect(ul.add(1, 1), csz.sub(2, 2));
        /* The portrait's place: a dark square, the portrait is drawn over it after the interface. */
        Coord pul = ul.add((cw - p) / 2, UI.scale(3));
        g.chcolor(0, 0, 0, 200);
        g.frect(pul, new Coord(p, p));
        g.chcolor();
        pics.add(new CommanderPortraits.Slot(ctx, Area.sized(rootpos().add(pul), new Coord(p, p))));
        if (r == null) {
            g.aimage(text(clip(ctx.getDisplayName(), 10), DIM), ul.add(cw / 2, p + UI.scale(12)), 0.5, 0.5);
            cells.add(new Cell(Area.sized(ul, csz), "c" + ctx.sessionId, (ev, dbl) -> clicked(ctx, ev, dbl), null));
            return;
        }
        int grp = Squad.groupOf(r);
        if (grp > 0) {
            Tex gt = text(Integer.toString(grp), ORANGE, true);
            g.chcolor(0, 0, 0, 170);
            g.frect(pul, gt.sz().add(UI.scale(4), 0));
            g.chcolor();
            g.image(gt, pul.add(UI.scale(2), 0));
        }
        int y = UI.scale(3) + p + UI.scale(2);
        String nm = clip(r.name, 7) + ((ctx == ld && !sq.isEmpty()) ? "\u265B" : "") + ((ctx == vp) ? "\u25C6" : "");
        g.aimage(text(nm, TEXT, true), ul.add(cw / 2, y + UI.scale(6)), 0.5, 0.5);
        y += UI.scale(14);
        int bx = ul.x + UI.scale(4), bw = cw - UI.scale(8);
        bar(g, new Coord(bx, ul.y + y), bw, UI.scale(3), r.hp, HP);
        bar(g, new Coord(bx, ul.y + y + UI.scale(5)), bw, UI.scale(3), r.stamina, STAM);
        bar(g, new Coord(bx, ul.y + y + UI.scale(10)), bw, UI.scale(3), r.energy, NRJ);
        y += UI.scale(15);
        Color sc = (r.status == MemberReport.Status.UNREACHABLE || r.status == MemberReport.Status.SKIPPED) ? ORANGE
                 : combat ? RED : (r.status == MemberReport.Status.IDLE) ? DIM : TEAL;
        g.aimage(text(clip(status(r), 9), sc), ul.add(cw / 2, y + UI.scale(5)), 0.5, 0.5);
        y += UI.scale(12);
        if (r.invTotal > 0) {
            Color ic = (r.invFree == 0) ? ORANGE : TEAL;
            Tex it = text(L10n.get("commander.units.inv", r.invFree), ic);
            Coord ip = ul.add((cw - it.sz().x) / 2, y);
            g.image(it, ip);
            cells.add(new Cell(Area.sized(ip.sub(UI.scale(2), UI.scale(1)), it.sz().add(UI.scale(4), UI.scale(2))),
                               "i" + ctx.sessionId, (ev, dbl) -> {
                                   if (ev.b == 1)
                                       cmd().openInventory(ctx);
                               }, L10n.get("commander.tip.inventory")));
        }
        cells.add(new Cell(Area.sized(ul, csz), "c" + ctx.sessionId, (ev, dbl) -> clicked(ctx, ev, dbl),
                           L10n.get("commander.tip.card")));
    }

    private void clicked(SessionContext ctx, MouseDownEvent ev, boolean dbl) {
        Squad sq = SessionManager.getInstance().squad();
        if (ev.b == 3) {
            sq.setLeader(ctx);
            return;
        }
        if (ev.b != 1)
            return;
        if (dbl) {
            cmd().jumpTo(ctx);
            return;
        }
        if (ui.modshift || ui.modctrl)
            sq.toggle(ctx);
        else
            sq.select(Collections.singletonList(ctx), false);
    }

    static String status(MemberReport r) {
        String s = L10n.get("squad.st." + r.status.name().toLowerCase(Locale.ROOT));
        if (r.detail != null && (r.status == MemberReport.Status.BOT || r.status == MemberReport.Status.SKIPPED
                                 || r.status == MemberReport.Status.UNREACHABLE))
            s = s + ": " + r.detail;
        return s;
    }
}
