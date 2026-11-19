package nurgling.sessions.commander;

import haven.*;
import nurgling.NGameUI;
import nurgling.colony.ColonistReport;
import nurgling.colony.Colony;
import nurgling.colony.ColonyConfig;
import nurgling.colony.ColonyItems;
import nurgling.colony.ColonyStorage;
import nurgling.colony.SpawnPlan;
import nurgling.conf.NSavedAccounts;
import nurgling.i18n.L10n;
import nurgling.sessions.BackgroundLogin;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.SquadExecutor;
import nurgling.sessions.squad.WorldRef;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The Colony panel of the Commander screen (plan: .lavish/colony-spawn-plan.html). Phase 1 is read-only: home and its
 * reach, who is at home and what they can do, and the plan for spawning a colonist. Its Accounts page holds the
 * permissions: which saved accounts take part, which may have characters created on them, and which characters may
 * spend LP on skills. Nothing is allowed until ticked.
 */
class ColonyPanel extends HudPanel {
    static final String DRAG_NAME = "Commander colony";
    private static final int W = UI.scale(330), ROW = UI.scale(16), BTN = UI.scale(20), GAP = UI.scale(3);
    private static final int CLIP = 54;
    private static final long ACCOUNTS_MS = 5000;

    private static final long WHY_MS = 1000;

    private boolean accountsPage;
    private final TextEntry nameField;
    private boolean nameLoaded;
    private String why;
    private long whyAt;
    private List<NSavedAccounts.Account> accounts = new ArrayList<>();
    private long accountsAt;

    ColonyPanel() {
        super(new Coord(W, UI.scale(120)));
        nameField = add(new TextEntry(UI.scale(130), "") {
            @Override
            protected void changed() {
                colony().config.setNextName(text());
            }
        }, Coord.z);
        nameField.hide();
    }

    private static Colony colony() {
        return SessionManager.getInstance().colony();
    }

    @Override
    void render(GOut g) {
        if (!nameLoaded) {
            nameLoaded = true;
            nameField.settext(colony().config.nextName());
        }
        nameShownNow = false;
        int h = accountsPage ? renderAccounts(g, false) : renderPlan(g, false);
        fit(new Coord(W, h));
        background(g);
        if (accountsPage)
            renderAccounts(g, true);
        else
            renderPlan(g, true);
        if (nameShownNow != nameField.visible) {
            if (nameShownNow)
                nameField.show();
            else
                nameField.hide();
        }
    }

    /* Set by the plan page's drawing pass when the name field has a place this frame. */
    private boolean nameShownNow;

    /* Both pages are laid out twice: once to measure (draw = false), once to draw. */

    private int header(GOut g, boolean draw) {
        int hh = UI.scale(18);
        if (draw) {
            g.aimage(text(L10n.get("colony.title"), TEAL, true), new Coord(PAD, PAD + hh / 2), 0, 0.5);
            int bw = UI.scale(70);
            Coord ul = new Coord(W - PAD - 2 * bw - GAP, PAD);
            button(g, ul, new Coord(bw, BTN - UI.scale(2)), L10n.get("colony.tab.plan"), null, accountsPage ? 0 : 2, "tab.plan",
                   () -> accountsPage = false, null);
            button(g, ul.add(bw + GAP, 0), new Coord(bw, BTN - UI.scale(2)), L10n.get("colony.tab.accounts"), null,
                   accountsPage ? 2 : 0, "tab.acc", () -> accountsPage = true, L10n.get("colony.tip.accounts"));
        }
        return PAD + Math.max(hh, BTN) + GAP;
    }

    private int renderPlan(GOut g, boolean draw) {
        Colony col = colony();
        ColonyConfig cfg = col.config;
        Colony.Snapshot s = col.snapshot();
        int y = header(g, draw);

        /* Home and its reach. */
        WorldRef home = cfg.home();
        if (draw) {
            String hs = (home == null) ? L10n.get("colony.home.none") : L10n.get("colony.home.set", cfg.radius());
            g.aimage(text(hs, (home == null) ? ORANGE : TEXT), new Coord(PAD, y + BTN / 2), 0, 0.5);
            int bw = UI.scale(96), sw = UI.scale(20);
            int x = W - PAD - bw - 2 * (sw + GAP);
            button(g, new Coord(x, y), new Coord(bw, BTN), L10n.get("colony.home.here"), null, 0, "home",
                   this::setHomeHere, L10n.get("colony.tip.home"));
            button(g, new Coord(x + bw + GAP, y), new Coord(sw, BTN), "-", null, 0, "r-",
                   () -> { cfg.setRadius(cfg.radius() - 1); col.invalidate(); }, L10n.get("colony.tip.radius"));
            button(g, new Coord(x + bw + GAP + sw + GAP, y), new Coord(sw, BTN), "+", null, 0, "r+",
                   () -> { cfg.setRadius(cfg.radius() + 1); col.invalidate(); }, L10n.get("colony.tip.radius"));
        }
        y += BTN + GAP;
        if (home == null) {
            if (draw)
                g.image(text(L10n.get("colony.home.hint"), DIM), new Coord(PAD, y));
            return y + ROW + PAD;
        }

        /* Who is at home. */
        y = section(g, draw, y, L10n.get("colony.crew.title", s.crew.size()));
        if (s.crew.isEmpty()) {
            if (draw)
                g.image(text(L10n.get("colony.crew.none"), DIM), new Coord(PAD, y));
            y += ROW;
        }
        for (SpawnPlan.Member m : s.crew) {
            if (draw)
                colonist(g, y, m);
            y += ROW;
        }
        if (!s.away.isEmpty() || !s.others.isEmpty()) {
            if (draw)
                g.image(text(L10n.get("colony.crew.rest", s.away.size(), s.others.size()), DIM), new Coord(PAD, y));
            y += ROW;
        }

        /* The plan. */
        String st = storageText(col.storage.state());
        y = section(g, draw, y, L10n.get("colony.plan.title"));
        if (draw)
            g.image(text(st, DIM), new Coord(PAD, y));
        y += ROW;
        for (SpawnPlan.Row r : s.plan) {
            if (draw)
                planRow(g, y, r);
            y += ROW;
        }
        y = spawn(g, draw, y);
        return y + PAD;
    }

    /** The Spawn colonist row: name, button, why it can't start, and the run's progress. */
    private int spawn(GOut g, boolean draw, int y) {
        Colony col = colony();
        y = section(g, draw, y, L10n.get("colony.spawn.title"));
        long now = System.currentTimeMillis();
        if (now - whyAt > WHY_MS) {
            whyAt = now;
            why = col.whyNot(col.snapshot(), new nurgling.colony.SpawnRun.Plan[1]);
        }
        nurgling.colony.SpawnRun run = col.run();
        boolean running = run != null && run.running();
        if (draw) {
            g.aimage(text(L10n.get("colony.spawn.name"), TEXT), new Coord(PAD, y + BTN / 2), 0, 0.5);
            nameField.c = new Coord(PAD + UI.scale(60), y + (BTN - nameField.sz.y) / 2);
            nameShownNow = true;
            int bw = UI.scale(110);
            Coord ul = new Coord(W - PAD - bw, y);
            if (running) {
                button(g, ul, new Coord(bw, BTN), L10n.get("colony.spawn.stop"), null, 2, "stop", run::stop,
                       L10n.get("colony.tip.stop"));
            } else {
                button(g, ul, new Coord(bw, BTN), L10n.get("colony.spawn.go"), null, (why == null) ? 2 : 0, "spawn",
                       this::spawnNow, (why == null) ? L10n.get("colony.tip.spawn") : why);
            }
        }
        y += BTN + GAP;
        if (!running && why != null) {
            if (draw)
                g.image(text(clip(why, CLIP), ORANGE), new Coord(PAD, y));
            if (draw && why.length() > CLIP)
                cells.add(new Cell(Area.sized(new Coord(0, y), new Coord(W, ROW)), null, (ev, dbl) -> {}, why));
            y += ROW;
        }
        if (run != null) {
            String t = run.text();
            Color c = (run.phase() == nurgling.colony.SpawnRun.Phase.DONE) ? TEAL
                : (run.phase() == nurgling.colony.SpawnRun.Phase.FAILED) ? RED : TEXT;
            if (draw) {
                g.image(text(clip(t, CLIP), c), new Coord(PAD, y));
                if (t.length() > CLIP)
                    cells.add(new Cell(Area.sized(new Coord(0, y), new Coord(W, ROW)), null, (ev, dbl) -> {}, t));
            }
            y += ROW;
            String note = run.secretNote();
            if (note != null) {
                if (draw)
                    g.image(text(clip(note, CLIP), ORANGE), new Coord(PAD, y));
                y += ROW;
            }
        }
        return y;
    }

    private void spawnNow() {
        NGameUI vp = gui();
        String w = colony().spawn(vp);
        whyAt = 0;
        if (w != null && vp != null)
            vp.msg(w);
    }

    private int section(GOut g, boolean draw, int y, String title) {
        y += UI.scale(3);
        if (draw) {
            g.chcolor(FRAME);
            g.line(new Coord(PAD, y), new Coord(W - PAD, y), 1);
            g.chcolor();
            g.image(text(title, TEAL, true), new Coord(PAD, y + UI.scale(3)));
        }
        return y + UI.scale(3) + ROW;
    }

    private void colonist(GOut g, int y, SpawnPlan.Member m) {
        ColonistReport r = m.r;
        g.image(text(clip(r.name, 18), TEXT), new Coord(PAD, y));
        g.image(text(m.idle ? L10n.get("colony.crew.idle") : L10n.get("colony.crew.busy"), m.idle ? TEAL : DIM),
                new Coord(PAD + UI.scale(120), y));
        StringBuilder tags = new StringBuilder();
        if (r.knows(SpawnPlan.CARPENTRY))
            tags.append(L10n.get("colony.tag.carpentry")).append(' ');
        if (r.knows(SpawnPlan.LUMBERJACKING))
            tags.append(L10n.get("colony.tag.lumber")).append(' ');
        if (r.tools.containsKey(ColonyItems.Tool.AXE))
            tags.append(L10n.get("colony.tag.axe")).append(' ');
        if (r.tools.containsKey(ColonyItems.Tool.SAW))
            tags.append(L10n.get("colony.tag.saw")).append(' ');
        if (m.learns)
            tags.append(L10n.get("colony.tag.learns"));
        g.image(text(tags.toString().trim(), ORANGE), new Coord(PAD + UI.scale(165), y));
        Tex lp = text((r.lp < 0) ? "?" : L10n.get("colony.crew.lp", r.lp), DIM);
        g.image(lp, new Coord(W - PAD - lp.sz().x, y));
        cells.add(new Cell(Area.sized(new Coord(0, y), new Coord(W, ROW)), "c" + r.key,
                           (ev, dbl) -> cmd().jumpTo(SessionManager.getInstance().getSession(r.sessionId)),
                           L10n.get("colony.tip.colonist")));
    }

    private void planRow(GOut g, int y, SpawnPlan.Row r) {
        int x = PAD + r.depth * UI.scale(12);
        Color c = (r.state == SpawnPlan.State.OK) ? TEAL : (r.state == SpawnPlan.State.JOB) ? ORANGE : RED;
        g.chcolor(c);
        g.frect(new Coord(x, y + ROW / 2 - UI.scale(3)), UI.scale(new Coord(6, 6)));
        g.chcolor();
        String full = L10n.get(r.key, r.args);
        int room = CLIP - r.depth * 3;
        g.image(text(clip(full, room), (r.state == SpawnPlan.State.OK) ? DIM : TEXT), new Coord(x + UI.scale(10), y));
        if (full.length() > room)
            cells.add(new Cell(Area.sized(new Coord(0, y), new Coord(W, ROW)), null, (ev, dbl) -> {}, full));
    }

    private static String storageText(ColonyStorage.State st) {
        switch (st) {
            case READY:
                return L10n.get("colony.storage.ready");
            case LOADING:
                return L10n.get("colony.storage.loading");
            default:
                return L10n.get("colony.storage.off");
        }
    }

    /** Home goes where the camera looks: on the character, or wherever it was panned to. */
    private void setHomeHere() {
        NGameUI vp = gui();
        if (vp == null || vp.map == null)
            return;
        MiniMap.Location sl = SquadExecutor.sessloc(vp);
        Focus f = cmd().cameraAt(vp);
        Coord2d local = (f == null) ? null : f.localIn(sl);
        if (local == null) {
            Gob pl = vp.map.player();
            local = (pl == null) ? null : pl.rc;
        }
        WorldRef where = WorldRef.of(vp.map.glob.map, sl, local);
        if (where == null) {
            vp.msg(L10n.get("colony.msg.nohome"));
            return;
        }
        colony().setHome(where);
        vp.msg(L10n.get("colony.msg.home"));
    }

    /* ---------------------------------------------------------------- accounts page */

    private int renderAccounts(GOut g, boolean draw) {
        Colony col = colony();
        ColonyConfig cfg = col.config;
        long now = System.currentTimeMillis();
        if (now - accountsAt > ACCOUNTS_MS) {
            accountsAt = now;
            accounts = NSavedAccounts.list(BackgroundLogin.confname());
        }
        int y = header(g, draw);
        if (draw)
            g.image(text(L10n.get("colony.acc.hint"), DIM), new Coord(PAD, y));
        y += ROW;
        y = section(g, draw, y, L10n.get("colony.acc.title"));
        if (accounts.isEmpty()) {
            if (draw)
                g.image(text(L10n.get("colony.acc.none"), DIM), new Coord(PAD, y));
            y += ROW;
        }
        int bw = UI.scale(62);
        for (NSavedAccounts.Account a : accounts) {
            if (draw) {
                boolean online = SessionManager.getInstance().findByUsername(a.name) != null;
                g.aimage(text(clip(a.name, 22), online ? TEXT : DIM), new Coord(PAD, y + BTN / 2), 0, 0.5);
                int x = W - PAD - 2 * bw - GAP;
                button(g, new Coord(x, y), new Coord(bw, BTN - UI.scale(2)), L10n.get("colony.acc.joins"), null,
                       cfg.joins(a.name) ? 2 : 0, "j" + a.name,
                       () -> { cfg.setJoins(a.name, !cfg.joins(a.name)); col.invalidate(); }, L10n.get("colony.tip.joins"));
                button(g, new Coord(x + bw + GAP, y), new Coord(bw, BTN - UI.scale(2)), L10n.get("colony.acc.creates"), null,
                       cfg.creates(a.name) ? 2 : 0, "c" + a.name,
                       () -> { cfg.setCreates(a.name, !cfg.creates(a.name)); col.invalidate(); }, L10n.get("colony.tip.creates"));
            }
            y += BTN;
        }

        /* Characters of ticked accounts: may they spend LP on skills? */
        Colony.Snapshot s = col.snapshot();
        List<ColonistReport> chars = new ArrayList<>();
        for (SpawnPlan.Member m : s.crew)
            chars.add(m.r);
        chars.addAll(s.away);
        y = section(g, draw, y, L10n.get("colony.chr.title"));
        if (chars.isEmpty()) {
            if (draw)
                g.image(text(L10n.get("colony.chr.none"), DIM), new Coord(PAD, y));
            y += ROW;
        }
        for (ColonistReport r : chars) {
            if (draw) {
                g.aimage(text(clip(r.name, 20) + "  (" + clip(r.account, 14) + ")", TEXT), new Coord(PAD, y + BTN / 2), 0, 0.5);
                button(g, new Coord(W - PAD - bw, y), new Coord(bw, BTN - UI.scale(2)), L10n.get("colony.chr.learns"), null,
                       cfg.learns(r.key) ? 2 : 0, "l" + r.key,
                       () -> { cfg.setLearns(r.key, !cfg.learns(r.key)); col.invalidate(); }, L10n.get("colony.tip.learns"));
            }
            y += BTN;
        }
        return y + PAD;
    }
}
