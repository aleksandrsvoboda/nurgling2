package nurgling.sessions.commander;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.Formation;
import nurgling.sessions.squad.MemberReport;
import nurgling.sessions.squad.Squad;
import nurgling.sessions.squad.SquadHelpWindow;
import nurgling.sessions.squad.SquadToggle;

import java.util.List;

/**
 * Bottom right of the Commander screen: the orders and settings, each button with its hotkey, in the order an RTS
 * command card has them: orders, camera and attention, formation, speed, how orders are carried out, and the plugins'
 * buttons. It replaces the squad panel while Commander mode is on.
 */
class CommandCard extends HudPanel {
    static final String DRAG_NAME = "Commander orders";
    static final int W = UI.scale(300), BTN = UI.scale(19), GAP = UI.scale(3);

    CommandCard() {
        super(new Coord(W, UI.scale(200)));
    }

    @Override
    void render(GOut g) {
        Squad sq = SessionManager.getInstance().squad();
        Commander c = cmd();
        NGameUI gui = gui();
        List<SessionContext> m = sq.members();
        List<SquadToggle> ext = sq.toggles();
        int rows = 7 + (ext.isEmpty() ? 0 : (ext.size() + 2) / 3);
        int hh = UI.scale(18);
        fit(new Coord(W, PAD + hh + rows * (BTN + GAP) + PAD));
        background(g);
        g.aimage(text(L10n.get("commander.orders.title", m.size()), TEAL, true), new Coord(PAD, PAD + hh / 2), 0, 0.5);
        Tex cam = text(cameraText(c), DIM);
        g.aimage(cam, new Coord(W - PAD, PAD + hh / 2), 1, 0.5);
        int y = PAD + hh;
        y = row(g, y, new String[]{L10n.get("squad.b.stop"), L10n.get("squad.b.rally"), L10n.get("squad.b.follow")},
                new String[]{"S", "R", "F"}, new int[3],
                new Runnable[]{sq::stop, sq::rally, sq::follow},
                new String[]{L10n.get("commander.tip.stop"), L10n.get("commander.tip.rally"), L10n.get("commander.tip.follow")});
        y = row(g, y, new String[]{L10n.get("commander.b.center"), L10n.get("commander.b.idle"), L10n.get("commander.b.build")},
                new String[]{"C", "I", "B"}, new int[]{0, 0, st(c.buildArmed())},
                new Runnable[]{c::centerSelection, () -> CommanderKeys.selectIdle(c, sq, gui, false), () -> c.setBuildArmed(!c.buildArmed())},
                new String[]{L10n.get("commander.tip.center"), L10n.get("commander.tip.idle"), L10n.get("commander.tip.build")});
        y = row(g, y, new String[]{L10n.get("commander.b.paint"), L10n.get("commander.b.theater"), L10n.get("commander.b.alert")},
                new String[]{"V", "Tab", "Spc"}, new int[]{st(c.painting()), 0, 0},
                new Runnable[]{() -> c.setPainting(!c.painting()), () -> c.nextTheater(gui), () -> {
                    Alerts.Alert a = c.jumpToAlert();
                    if (gui != null)
                        gui.msg((a == null) ? L10n.get("commander.msg.no_alert") : a.text);
                }},
                new String[]{L10n.get("commander.tip.paint"), L10n.get("commander.tip.next"), L10n.get("commander.tip.alert")});
        Formation.Kind fk = Squad.formation();
        y = row(g, y, new String[]{L10n.get("squad.f.grid"), L10n.get("squad.f.line"), L10n.get("squad.f.keep"), L10n.get("squad.f.stack")},
                null, new int[]{st(fk == Formation.Kind.GRID), st(fk == Formation.Kind.LINE), st(fk == Formation.Kind.KEEP),
                                st(fk == Formation.Kind.STACK)},
                new Runnable[]{() -> Squad.setFormation(Formation.Kind.GRID), () -> Squad.setFormation(Formation.Kind.LINE),
                               () -> Squad.setFormation(Formation.Kind.KEEP), () -> Squad.setFormation(Formation.Kind.STACK)},
                null);
        int[] speeds = new int[4];
        int known = 0;
        for (SessionContext ctx : m) {
            MemberReport r = ctx.squadReport;
            if (r != null && r.speed >= 0 && r.speed < 4) {
                speeds[r.speed]++;
                known++;
            }
        }
        int[] sst = new int[4];
        for (int i = 0; i < 4; i++)
            sst[i] = (speeds[i] == 0) ? 0 : (speeds[i] == known) ? 2 : 1;
        y = row(g, y, new String[]{L10n.get("squad.s.crawl"), L10n.get("squad.s.walk"), L10n.get("squad.s.run"), L10n.get("squad.s.sprint")},
                null, sst, new Runnable[]{() -> sq.setSpeed(0), () -> sq.setSpeed(1), () -> sq.setSpeed(2), () -> sq.setSpeed(3)}, null);
        boolean pf = Squad.pathfind(), sb = Squad.stopBots(), es = CommanderView.edgeScroll();
        y = row(g, y, new String[]{L10n.get(pf ? "squad.b.pf_on" : "squad.b.pf_off"), L10n.get(sb ? "squad.b.bots_stop" : "squad.b.bots_skip"),
                                   L10n.get(es ? "commander.b.edge_on" : "commander.b.edge_off")},
                null, new int[]{st(pf), st(sb), st(es)},
                new Runnable[]{() -> Squad.setPathfind(!Squad.pathfind()), () -> Squad.setStopBots(!Squad.stopBots()),
                               () -> NConfig.set(NConfig.Key.commanderEdgeScroll, !CommanderView.edgeScroll())},
                null);
        y = row(g, y, new String[]{L10n.get("commander.b.inventory"), L10n.get("commander.b.layout"), L10n.get("commander.b.help"),
                                   L10n.get("commander.b.exit")}, new String[]{null, null, null, "Esc"},
                new int[]{0, st(ui != null && ui.core != null && ui.core.mode == nurgling.NCore.Mode.DRAG), 0, 0},
                new Runnable[]{() -> {
                    List<SessionContext> sel = sq.members();
                    if (!sel.isEmpty())
                        c.openInventory(sel.get(0));
                    else if (gui != null)
                        gui.msg(L10n.get("commander.msg.select_first"));
                }, () -> {
                    /* Drag mode, as Options > Interface starts it: the guide window ends it. */
                    if (ui != null && ui.core != null && gui != null) {
                        ui.core.mode = nurgling.NCore.Mode.DRAG;
                        gui.guiinfo.show();
                        gui.guiinfo.raise();
                    }
                }, () -> {
                    if (gui != null)
                        SquadHelpWindow.open(gui);
                }, () -> sq.setCommander(false)},
                new String[]{L10n.get("commander.tip.inventory_btn"), L10n.get("commander.tip.layout"), null, null});
        for (int i = 0; i < ext.size(); i += 3) {
            int n = Math.min(3, ext.size() - i);
            String[] lbl = new String[n];
            int[] est = new int[n];
            Runnable[] act = new Runnable[n];
            for (int j = 0; j < n; j++) {
                SquadToggle t = ext.get(i + j);
                lbl[j] = t.label(m);
                est[j] = t.state(m);
                act[j] = () -> t.click(sq.members());
            }
            y = row(g, y, lbl, null, est, act, null);
        }
    }

    static String cameraText(Commander c) {
        if (c.painting())
            return L10n.get("commander.cam.paint");
        if (c.buildArmed())
            return L10n.get("commander.cam.build");
        if (c.focus() == null) {
            SessionContext vp = SessionManager.getInstance().getActiveSession();
            String n = (vp == null || vp.squadReport == null) ? "?" : vp.squadReport.name;
            return L10n.get("commander.cam.follow", clip(n, 16));
        }
        return L10n.get("commander.cam.free");
    }

    private static int st(boolean on) {
        return on ? 2 : 0;
    }

    private int row(GOut g, int y, String[] labels, String[] keys, int[] state, Runnable[] act, String[] tips) {
        int n = labels.length;
        int bw = (W - 2 * PAD - (n - 1) * GAP) / n;
        for (int i = 0; i < n; i++) {
            Coord ul = new Coord(PAD + i * (bw + GAP), y);
            button(g, ul, new Coord(bw, BTN), labels[i], (keys == null) ? null : keys[i], state[i], labels[i] + i + y, act[i],
                   (tips == null) ? null : tips[i]);
        }
        return y + BTN + GAP;
    }
}
