package nurgling.sessions.commander;

import haven.*;
import haven.error.FileLogger;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.render.remembered.RememberedGround;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.multilogin.LoginRun;
import nurgling.sessions.squad.MemberReport;
import nurgling.sessions.squad.Squad;
import nurgling.sessions.squad.SquadExecutor;
import nurgling.sessions.squad.SquadHelpWindow;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;

/**
 * Runs the Commander measurements and checks unattended: {@code -Dnurgling.commanderrun=<json file>} with
 * {@code {"picks": [["account", "character"], ...], "script": "probe" | "rts"}} logs those characters in from the
 * login screen (the "Log in to several" run, saved login tokens), runs the script and exits. "probe" is phase 0
 * (switch timings, far camera, pans); "rts" drives the Commander layer: theaters, jumps, camera pans with hand-overs,
 * keep-visible ground, queued orders, the bot picker. Every step runs on the UI loop's thread, from
 * {@link CommanderProbe#frame}; everything is logged as {@code [Commander ...]} lines, and "shot NAME" lines ask an
 * outside watcher for a window screenshot.
 */
public final class CommanderRun {
    private static final String PROP = "nurgling.commanderrun";
    private static final double LOGIN_TIMEOUT = 240, SETTLE = 20;
    private static final double GRID = MCache.cmaps.x * MCache.tilesz.x;

    private static volatile CommanderRun run;
    private static boolean tried = false;

    private final Map<String, String> picks;
    private final String script;
    private final List<Step> steps = new ArrayList<>();
    private final boolean rememberedWas;
    private final double t0 = Utils.rtime();
    private LoginRun login;
    private int next = 0;
    private double nextAt = -1;
    private final List<SessionContext> members = new ArrayList<>();
    /* A scripted camera glide and its frame times. */
    private Focus animFrom, animTo;
    private double animT0, animDur, animLast;
    private String animName;
    private final List<Double> animDts = new ArrayList<>();
    private final Set<Long> painted = new HashSet<>();
    private Coord2d homeAt;
    private Focus keyFrom;
    private Object savedBar;
    private int ghostsBefore;

    private static int ghostCount(NGameUI g) {
        nurgling.planning.PlanningLayerManager plm = ((nurgling.NUI) g.ui).core.planningLayer;
        nurgling.planning.PlanningLayer l = plm.getActiveLayer();
        return (l == null) ? 0 : plm.ghostsOf(l.id).size();
    }

    private static final class Step {
        final String name;
        final double after;
        final Consumer<CommanderRun> act;

        Step(String name, double after, Consumer<CommanderRun> act) {
            this.name = name;
            this.after = after;
            this.act = act;
        }
    }

    private CommanderRun(Map<String, String> picks, String script) {
        this.picks = picks;
        this.script = script;
        this.rememberedWas = Boolean.TRUE.equals(NConfig.get(NConfig.Key.rememberedGround));
    }

    /** Called from the login screen's tick: starts the run once when the property is set. */
    public static void loginScreen(UILoop loop) {
        if (tried || loop == null)
            return;
        tried = true;
        String path = System.getProperty(PROP);
        if (path == null)
            return;
        Map<String, String> picks = new LinkedHashMap<>();
        String script;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
            JSONArray a = o.getJSONArray("picks");
            for (int i = 0; i < a.length(); i++)
                picks.put(a.getJSONArray(i).getString(0), a.getJSONArray(i).getString(1));
            script = o.optString("script", "probe");
        } catch (java.io.IOException | org.json.JSONException e) {
            log("cannot read " + path + ": " + e);
            return;
        }
        CommanderRun r = new CommanderRun(picks, script);
        r.login = SessionManager.getInstance().multiLogin().start(loop, picks);
        run = r;
        log("logging in " + picks.keySet() + ", script " + script);
    }

    static void frame(double now) {
        CommanderRun r = run;
        if (r != null)
            r.step(now);
    }

    private void step(double now) {
        if (nextAt < 0) {
            waitLogin(now);
            return;
        }
        animate(now);
        if (now < nextAt)
            return;
        if (next >= steps.size()) {
            finish();
            return;
        }
        Step s = steps.get(next++);
        log("step " + next + "/" + steps.size() + ": " + s.name);
        try {
            s.act.accept(this);
        } catch (RuntimeException e) {
            log("step " + s.name + " failed: " + e);
            FileLogger.logError(e);
        }
        nextAt = now + s.after;
    }

    private void waitLogin(double now) {
        boolean pending = false;
        for (LoginRun.Entry e : login.entries()) {
            LoginRun.State st = e.state();
            if (st != LoginRun.State.IN_GAME && st != LoginRun.State.FAILED && st != LoginRun.State.STOPPED)
                pending = true;
        }
        if (pending && now - t0 < LOGIN_TIMEOUT)
            return;
        for (LoginRun.Entry e : login.entries())
            log("login " + e.account + "/" + e.character + ": " + e.state() + ((e.why() == null) ? "" : (" " + e.why())));
        for (String acct : picks.keySet()) {
            for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
                if (acct.equals(ctx.username) && ctx.getGameUI() != null)
                    members.add(ctx);
            }
        }
        if (members.isEmpty()) {
            log("nobody in game; giving up");
            finish();
            return;
        }
        if ("rts".equals(script))
            rts();
        else if ("smooth".equals(script))
            smooth();
        else if ("idle".equals(script))
            idle();
        else
            probe();
        nextAt = now + SETTLE;
        log(members.size() + " in game; script starts in " + (int) SETTLE + " s");
    }

    private void add(String name, double after, Consumer<CommanderRun> act) {
        steps.add(new Step(name, after, act));
    }

    private NGameUI gui() {
        SessionContext a = SessionManager.getInstance().getActiveSession();
        return (a == null) ? null : a.getGameUI();
    }

    private void cmd(String... args) {
        NGameUI g = gui();
        if (g == null) {
            log("no game screen for " + String.join(" ", args));
            return;
        }
        String[] full = new String[args.length + 1];
        full[0] = "commander";
        System.arraycopy(args, 0, full, 1, args.length);
        CommanderProbe.run(g, full);
    }

    private void show(int i) {
        if (i < members.size())
            SessionManager.getInstance().switchToSession(members.get(i).sessionId, true);
    }

    private static void remembered(boolean on) {
        NConfig.set(NConfig.Key.rememberedGround, on);
        RememberedGround.reload();
    }

    private static Commander commander() {
        return SessionManager.getInstance().commander();
    }

    private static Squad squad() {
        return SessionManager.getInstance().squad();
    }

    /* ---- Phase 0 ---- */

    private void probe() {
        add("show the first character", 10, r -> r.show(0));
        add("trace in chat", 1, r -> r.cmd("trace"));
        add("status, remembered ground off", 1, r -> { remembered(false); r.cmd("status"); });
        add("segments", 3, r -> r.cmd("segs"));
        for (int i = 0; i < 6; i++) {
            int to = (i + 1) % Math.max(1, members.size());
            add("switch to " + to, 10, r -> r.show(to));
        }
        add("back to the first", 10, r -> r.show(0));
        add("far 300, off", 12, r -> r.cmd("far", "300"));
        add("home", 5, r -> r.cmd("home"));
        for (int i = 0; i < 4; i++) {
            boolean on = (i % 2) == 1;
            add("remembered ground " + (on ? "on" : "off"), 10, r -> remembered(on));
            add("status before pan", 1, r -> r.cmd("status"));
            add("pan 600 tiles / 20 s, remembered " + (on ? "on" : "off"), 25, r -> r.cmd("pan", "600", "20"));
        }
        add("remembered ground on", 10, r -> remembered(true));
        add("far 300, on", 12, r -> r.cmd("far", "300"));
        add("far 800, on", 12, r -> r.cmd("far", "800"));
        add("home", 5, r -> r.cmd("home"));
        add("all switches", 2, r -> r.cmd("switches"));
    }

    /* ---- RTS ---- */

    private void rts() {
        add("show the first character", 8, r -> r.show(0));
        add("commander on", 4, r -> {
            squad().setCommander(true);
            SwitchTrace.chat(true);
        });
        add("where everyone is", 1, CommanderRun::positions);
        add("shot hud", 3, r -> shot("hud"));
        add("status", 1, r -> log(commander().describe(r.gui())));
        /* The arrow keys: hold right for two seconds. */
        add("hold the right arrow", 2, r -> {
            r.keyFrom = commander().cameraAt(r.gui());
            NGameUI g = r.gui();
            if (g != null && g.map instanceof nurgling.NMapView)
                ((nurgling.NMapView) g.map).commanderView.keydown(java.awt.event.KeyEvent.VK_RIGHT);
        });
        add("release it", 1, r -> {
            NGameUI g = r.gui();
            if (g != null && g.map instanceof nurgling.NMapView)
                ((nurgling.NMapView) g.map).commanderView.keyup(java.awt.event.KeyEvent.VK_RIGHT);
            Focus to = commander().cameraAt(g);
            log(String.format("arrow pan moved the camera %.0f tiles", (r.keyFrom == null || to == null) ? -1.0
                              : to.pos.dist(r.keyFrom.pos) / MCache.tilesz.x));
        });
        add("shot panned", 2, r -> shot("panned"));
        add("back on the character", 3, r -> commander().jumpTo(r.members.get(0)));
        add("segments", 2, r -> r.cmd("segs"));
        /* Theaters: jump to the second one and back with Tab. */
        add("jump to the other theater", 8, r -> {
            List<Theater> ts = commander().theaters();
            log("theaters: " + ts.size());
            Theater cur = commander().current(r.gui());
            for (Theater t : ts)
                if (cur == null || t.seg != cur.seg) {
                    commander().jumpTo(t);
                    break;
                }
        });
        add("after the jump", 1, r -> log("on screen: " + viewpointName() + "; " + commander().describe(r.gui())));
        add("shot theater2", 3, r -> shot("theater2"));
        add("Tab back", 8, r -> commander().nextTheater(r.gui()));
        add("after Tab", 1, r -> log("on screen: " + viewpointName() + "; " + commander().describe(r.gui())));
        /* The camera glides away from the character on screen and back: remembered ground, black, the live area. */
        add("glide 3 grids east and back", 1, r -> r.glideFromHere(3 * GRID, 0, 6, "glide out"));
        add("wait for glide", 7, r -> {});
        add("shot far", 2, r -> shot("far"));
        add("glide back", 1, r -> r.glideTo(Focus.of(r.members.get(0).squadReport.pos), 6, "glide back"));
        add("wait for glide back", 8, r -> {});
        /* Hand-over by camera: glide from the first character to each other one on the same theater, and back. */
        add("hand-over glides", 1, CommanderRun::handoverGlides);
        add("wait for hand-overs", 30, r -> {});
        /* Card double-click: jump to every character and back. */
        for (int i = 1; i < members.size(); i++) {
            int k = i;
            add("jump to character " + k, 6, r -> commander().jumpTo(r.members.get(k)));
            add("on screen after jump " + k, 1, r -> log("on screen: " + viewpointName()));
        }
        add("jump back to the first", 6, r -> commander().jumpTo(r.members.get(0)));
        add("follow (C twice)", 3, r -> {
            squad().select(Collections.singletonList(r.members.get(0)), false);
            commander().followSelection();
        });
        add("after follow", 1, r -> log("camera " + ((commander().focus() == null) ? "rides on the character" : "is free")));
        /* Keep visible: mark the first character's 3x3 grids, then look from 4 grids away. */
        add("paint mode on", 2, r -> commander().setPainting(true));
        add("shot paint", 2, r -> shot("paint"));
        add("paint with the mouse: a drag across the screen", 1, r -> r.mouse(0));
        add("drag on", 1, r -> r.mouse(1));
        add("release", 1, r -> r.mouse(2));
        add("paint the same drag again (unmarks)", 1, r -> r.mouse(0));
        add("drag on again", 1, r -> r.mouse(1));
        add("release again", 1, r -> r.mouse(2));
        add("mark grids north of the first character", 2, CommanderRun::markHome);
        add("paint mode off", 2, r -> commander().setPainting(false));
        add("glide 2.5 grids north", 1, r -> r.glideFromHere(0, -2.5 * GRID, 5, "glide north"));
        add("wait", 7, r -> {});
        add("status far north", 1, r -> {
            log(commander().describe(r.gui()));
            NGameUI g = r.gui();
            if (g != null)
                log(g.map.remembered.stats());
        });
        add("shot remembered", 2, r -> shot("remembered"));
        add("back home", 1, r -> r.glideTo(Focus.of(r.members.get(0).squadReport.pos), 4, "glide home"));
        add("wait", 6, r -> {});
        /* Queued orders: two short legs and back, on the first character. */
        add("queue two waypoints", 1, CommanderRun::queueTest);
        add("watch the queue", 4, r -> log("queue now: " + queueOf(r.members.get(0))));
        add("watch the queue", 4, r -> log("queue now: " + queueOf(r.members.get(0))));
        add("watch the queue", 6, r -> log("queue now: " + queueOf(r.members.get(0))));
        add("walk back", 8, CommanderRun::walkBack);
        /* The order panel's bot picker and the help. */
        add("inventory from the card", 4, r -> commander().openInventory(r.members.get(1)));
        add("shot inventory", 2, r -> shot("inventory"));
        add("back", 3, r -> commander().jumpTo(r.members.get(0)));
        add("quick bar: drop a bot and an action, try an empty slot", 2, r -> {
            NGameUI g = r.gui();
            QuickBar qb = null;
            for (haven.Widget w = g.child; w != null; w = w.next)
                if (w instanceof nurgling.widgets.NDraggableWidget && ((nurgling.widgets.NDraggableWidget) w).content instanceof QuickBar)
                    qb = (QuickBar) ((nurgling.widgets.NDraggableWidget) w).content;
            if (qb == null) {
                log("no quick bar");
                return;
            }
            r.savedBar = NConfig.getGlobal(NConfig.Key.commanderQuickBar);
            nurgling.widgets.NBotsMenu.NButton bot = g.botsMenu.find("choper");
            boolean b1 = (bot != null) && qb.dropthing(Coord.of(UI.scale(10), UI.scale(10)), bot);
            MenuGrid.Pagina any = null;
            synchronized (g.menu.paginae) {
                for (MenuGrid.Pagina p : g.menu.paginae)
                    if (CommanderActions.resName(p) != null) {
                        any = p;
                        break;
                    }
            }
            boolean b2 = (any != null) && qb.dropthing(Coord.of(UI.scale(50), UI.scale(10)), any);
            log("quick bar drops: bot " + b1 + ", action " + b2 + " (" + CommanderActions.resName(any) + "); bar " + NConfig.getGlobal(NConfig.Key.commanderQuickBar));
            QuickBar.use(9, g);
        });
        add("shot quickbar", 2, r -> shot("quickbar"));
        add("restore the quick bar", 1, r -> NConfig.set(NConfig.Key.commanderQuickBar, (r.savedBar == null) ? "" : r.savedBar));
        /* A building from the action menu: placed as a planner ghost on the character on screen, then cancelled. */
        add("choose a kiln", 3, r -> {
            NGameUI g = r.gui();
            r.ghostsBefore = ghostCount(g);
            MenuGrid.Pagina pag = g.menu.paginafor(Resource.remote().load("paginae/bld/kiln"));
            try {
                g.menu.use(pag.button(), new MenuGrid.Interaction(1, 0, null, null), false);
            } catch (Loading l) {
                log("kiln not loaded");
            }
        });
        add("shot placing", 2, r -> {
            log("placing: " + (r.gui().map.placing != null));
            shot("placing");
        });
        add("left-click: a ghost", 2, r -> {
            nurgling.NMapView mv = (nurgling.NMapView) r.gui().map;
            mv.mousedown(new Widget.MouseDownEvent(mv.sz.div(2), 1));
            log("ghosts " + r.ghostsBefore + " -> " + ghostCount(r.gui()) + "; still placing: " + (mv.placing != null));
        });
        add("Esc: done placing", 3, r -> {
            nurgling.NMapView mv = (nurgling.NMapView) r.gui().map;
            log("cancel placing: " + mv.cancelPlacing());
        });
        add("shot ghost", 2, r -> {
            log("placing after Esc: " + (r.gui().map.placing != null));
            shot("ghost");
        });
        add("remove the test ghost", 1, r -> {
            nurgling.planning.PlanningLayerManager plm = ((nurgling.NUI) r.gui().ui).core.planningLayer;
            nurgling.planning.PlanningLayer l = plm.getActiveLayer();
            if (l != null && ghostCount(r.gui()) > r.ghostsBefore) {
                java.util.List<nurgling.planning.PlanningGhost> gs = plm.ghostsOf(l.id);
                plm.removeGhost(gs.get(gs.size() - 1));
            }
            log("ghosts now " + ghostCount(r.gui()));
        });
        add("help", 2, r -> {
            NGameUI g = r.gui();
            if (g != null)
                SquadHelpWindow.open(g);
        });
        add("shot help", 3, r -> shot("help"));
        add("close help", 1, r -> {
            NGameUI g = r.gui();
            if (g != null)
                for (Widget w = g.child; w != null; w = w.next)
                    if (w instanceof SquadHelpWindow)
                        w.reqdestroy();
        });
        /* A long glide for frame times with the Commander screen up. */
        add("perf glide", 1, r -> r.glideFromHere(6 * GRID, 2 * GRID, 12, "perf glide"));
        add("wait", 13, r -> {});
        add("perf glide back", 1, r -> r.glideTo(Focus.of(r.members.get(0).squadReport.pos), 12, "perf glide back"));
        add("wait", 13, r -> {});
        add("unmark", 1, CommanderRun::unmark);
        add("all switches", 2, r -> r.cmd("switches"));
        add("commander off", 2, r -> squad().setCommander(false));
    }

    /* ---- Smoothness: a screen recording of pans and switches ---- */

    private double recT0 = -1;

    private void mark(String what) {
        log(String.format("rec +%.3f %s", (recT0 < 0) ? -1 : Utils.rtime() - recT0, what));
    }

    private void smooth() {
        add("commander on", 6, r -> {
            r.show(0);
            squad().setCommander(true);
        });
        add("camera on the first character", 4, r -> commander().jumpTo(r.members.get(0)));
        add("record 55 s", 2, r -> r.record("smooth", 55));
        add("glide 1.2 grids west", 1, r -> { r.mark("glide west"); r.glideFromHere(-1.2 * GRID, 0, 4, "glide west"); });
        add("wait", 5, r -> {});
        add("glide 2.4 grids east", 1, r -> { r.mark("glide east"); r.glideFromHere(2.4 * GRID, 0.3 * GRID, 6, "glide east"); });
        add("wait", 7, r -> {});
        add("back", 1, r -> { r.mark("jump home"); commander().jumpTo(r.members.get(0)); });
        add("wait", 3, r -> {});
        add("switch to the second", 1, r -> { r.mark("switch to 1"); commander().jumpTo(r.members.get(1)); });
        add("wait", 3, r -> {});
        add("switch to the first", 1, r -> { r.mark("switch to 0"); commander().jumpTo(r.members.get(0)); });
        add("wait", 3, r -> {});
        add("other theater", 1, r -> { r.mark("switch to 2"); commander().jumpTo(r.members.get(Math.min(2, r.members.size() - 1))); });
        add("wait", 3, r -> {});
        add("back home", 1, r -> { r.mark("switch home"); commander().jumpTo(r.members.get(0)); });
        add("wait", 3, r -> {});
        add("glide to the third character", 1, r -> {
            r.mark("glide to 2");
            SessionContext far = r.members.get(Math.min(2, r.members.size() - 1));
            r.glideTo(Focus.of(far.squadReport.pos), 8, "glide to " + far.getDisplayName());
        });
        add("wait for the recording", 12, r -> {});
        add("commander off", 2, r -> squad().setCommander(false));
    }

    /** The Commander screen left alone, recorded: for things that blink or move by themselves. */
    private void idle() {
        add("commander on", 6, r -> {
            r.show(0);
            squad().setCommander(true);
        });
        add("camera on the first character", 6, r -> commander().jumpTo(r.members.get(0)));
        add("record 8 s", 10, r -> r.record("idle", 8));
        /* One layout for every session: move the roster here, switch, and look at the other session's roster. */
        add("move the roster", 2, r -> {
            r.savedLayout = NConfig.getGlobal(NConfig.Key.commanderLayout);
            nurgling.widgets.NDraggableWidget w = roster(r.gui());
            r.ownBefore = String.valueOf(nurgling.conf.NDragProp.get(Roster.DRAG_NAME).c);
            log("roster at " + ((w == null) ? null : w.target_c) + " in " + viewpointName());
            if (w != null)
                CommanderLayout.record(w, Coord.of(UI.scale(30), UI.scale(300)));
        });
        add("switch", 4, r -> commander().jumpTo(r.members.get(1)));
        add("where the other session put it", 1, r -> {
            nurgling.widgets.NDraggableWidget w = roster(r.gui());
            log("roster at " + ((w == null) ? null : w.target_c) + " in " + viewpointName() + " (wanted "
                + Coord.of(UI.scale(30), UI.scale(300)) + "); own layout " + r.ownBefore + " -> "
                + nurgling.conf.NDragProp.get(Roster.DRAG_NAME).c);
        });
        add("back", 4, r -> commander().jumpTo(r.members.get(0)));
        add("and in the first", 1, r -> {
            nurgling.widgets.NDraggableWidget w = roster(r.gui());
            log("roster at " + ((w == null) ? null : w.target_c) + " in " + viewpointName());
        });
        add("restore the layout", 1, r -> NConfig.set(NConfig.Key.commanderLayout, (r.savedLayout == null) ? "" : r.savedLayout));
        add("commander off", 2, r -> squad().setCommander(false));
        add("normal drag mode", 2, r -> {
            log("panels shown after the mode: " + panelsShown(r.gui()));
            ((nurgling.NUI) r.gui().ui).core.mode = nurgling.NCore.Mode.DRAG;
        });
        add("shot normal drag", 2, r -> {
            log("panels shown in normal drag mode: " + panelsShown(r.gui()));
            shot("normal-drag");
            ((nurgling.NUI) r.gui().ui).core.mode = nurgling.NCore.Mode.IDLE;
        });
    }

    private static int panelsShown(NGameUI g) {
        int n = 0;
        for (haven.Widget w = g.child; w != null; w = w.next)
            if (w.visible && w instanceof nurgling.widgets.NDraggableWidget && ((nurgling.widgets.NDraggableWidget) w).content instanceof HudPanel)
                n++;
        return n;
    }

    private Object savedLayout;
    private String ownBefore;

    private static nurgling.widgets.NDraggableWidget roster(NGameUI g) {
        for (haven.Widget w = g.child; w != null; w = w.next)
            if (w instanceof nurgling.widgets.NDraggableWidget && ((nurgling.widgets.NDraggableWidget) w).content instanceof Roster)
                return (nurgling.widgets.NDraggableWidget) w;
        return null;
    }

    /** A screen recording (ffmpeg's desktop grabber at 144 fps) into bench/rec. */
    private void record(String name, int secs) {
        String dir = System.getProperty("user.dir");
        new java.io.File(dir, "bench\\rec").mkdirs();
        try {
            new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "ddagrab=framerate=144:output_idx=0",
                               "-c:v", "h264_nvenc", "-preset", "p1", "-rc", "constqp", "-qp", "20", "-t", Integer.toString(secs),
                               dir + "\\bench\\rec\\" + name + ".mp4")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            recT0 = Utils.rtime() + 0.5;
            log("recording " + name + " for " + secs + " s (t0 is about half a second after this)");
        } catch (java.io.IOException e) {
            log("cannot record: " + e);
        }
    }

    private static String viewpointName() {
        SessionContext a = SessionManager.getInstance().getActiveSession();
        return (a == null) ? "-" : a.getDisplayName();
    }

    private void positions() {
        for (SessionContext c : members) {
            MemberReport r = c.squadReport;
            log(String.format("%s: %s", c.getDisplayName(), (r == null) ? "no report" : String.valueOf(r.pos)));
        }
    }

    private void handoverGlides() {
        MemberReport a = members.get(0).squadReport;
        for (int i = 1; i < members.size(); i++) {
            MemberReport b = members.get(i).squadReport;
            if (a == null || b == null || a.pos == null || b.pos == null || a.pos.segId != b.pos.segId)
                continue;
            log(String.format("glide %s -> %s: %.0f tiles", a.name, b.name, a.pos.segPos.dist(b.pos.segPos) / MCache.tilesz.x));
            Focus from = Focus.of(a.pos), to = Focus.of(b.pos);
            glide(from, to, 8, "hand-over to " + b.name);
            return;
        }
        log("no second character on the first one's theater: no hand-over glide");
    }

    private void glideFromHere(double dx, double dy, double secs, String name) {
        Focus at = commander().cameraAt(gui());
        if (at != null)
            glide(at, at.add(Coord2d.of(dx, dy)), secs, name);
    }

    private void glideTo(Focus to, double secs, String name) {
        Focus at = commander().cameraAt(gui());
        if (at != null && to != null && at.seg == to.seg)
            glide(at, to, secs, name);
        else if (to != null)
            commander().jumpTo(to);
    }

    private void glide(Focus from, Focus to, double secs, String name) {
        animFrom = from;
        animTo = to;
        animDur = secs;
        animT0 = Utils.rtime();
        animLast = -1;
        animName = name;
        animDts.clear();
        commander().setFocus(from);
    }

    private void animate(double now) {
        if (animFrom == null)
            return;
        if (animLast >= 0)
            animDts.add(now - animLast);
        animLast = now;
        double u = Math.min(1, (now - animT0) / animDur);
        commander().setFocus(new Focus(animFrom.seg, animFrom.pos.add(animTo.pos.sub(animFrom.pos).mul(u))));
        if (u >= 1) {
            double[] s = new double[animDts.size()];
            for (int i = 0; i < s.length; i++)
                s[i] = animDts.get(i);
            Arrays.sort(s);
            int over33 = 0, over50 = 0;
            for (double d : s) {
                if (d > 0.033) over33++;
                if (d > 0.050) over50++;
            }
            log(String.format("%s done: %d frames, %.0f fps, ms p50 %.1f p95 %.1f p99 %.1f max %.1f, >33 ms %d, >50 ms %d; on screen %s",
                              animName, s.length, s.length / animDur, pct(s, 0.5), pct(s, 0.95), pct(s, 0.99),
                              (s.length == 0) ? 0 : s[s.length - 1] * 1000, over33, over50, viewpointName()));
            animFrom = animTo = null;
        }
    }

    private static double pct(double[] sorted, double q) {
        if (sorted.length == 0)
            return 0;
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))] * 1000;
    }

    private void markHome() {
        MemberReport r = members.get(0).squadReport;
        NGameUI g = gui();
        MiniMap.Location sl = SquadExecutor.sessloc(g);
        if (r == null || r.pos == null || sl == null || sl.seg.id != r.pos.segId) {
            log("cannot mark: no placed first character");
            return;
        }
        /* The row of grids just north of the character's live area: what only a mark can show. */
        Coord gc = Focus.gridOf(r.pos.segPos);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -3; dy <= -2; dy++) {
                Long id = sl.seg.map.get(gc.add(dx, dy));
                if (id != null && !commander().visible.contains(id) && commander().visible.set(id, true))
                    painted.add(id);
            }
        }
        commander().visible.save();
        log("marked " + painted.size() + " grids");
    }

    private int paintBefore;

    /** A left-button drag from the screen's centre to its left edge on the map view, as the player would paint. */
    private void mouse(int phase) {
        NGameUI g = gui();
        if (g == null || !(g.map instanceof nurgling.NMapView))
            return;
        nurgling.NMapView mv = (nurgling.NMapView) g.map;
        Coord ctr = mv.sz.div(2), end = Coord.of(UI.scale(40), mv.sz.y / 2);
        if (phase == 0) {
            paintBefore = commander().visible.size();
            mv.commanderView.mousedown(new Widget.MouseDownEvent(ctr, 1));
        } else if (phase == 1) {
            for (int i = 1; i <= 10; i++)
                mv.commanderView.mousemove(ctr.add(end.sub(ctr).mul(i).div(10)));
        } else {
            mv.commanderView.mouseup(new Widget.MouseUpEvent(end, 1));
            log(String.format("paint drag: keep-visible grids %d -> %d", paintBefore, commander().visible.size()));
        }
    }

    private void unmark() {
        for (Long id : painted)
            commander().visible.set(id, false);
        commander().visible.save();
        log("unmarked " + painted.size() + " grids");
        painted.clear();
    }

    private void queueTest() {
        SessionContext c = members.get(0);
        NGameUI g = c.getGameUI();
        Gob pl = (g == null) ? null : g.map.player();
        if (pl == null || c != SessionManager.getInstance().getActiveSession()) {
            log("queue test needs the first character on screen; on screen: " + viewpointName());
            return;
        }
        homeAt = pl.rc;
        squad().select(Collections.singletonList(c), false);
        double t = MCache.tilesz.x;
        squad().moveTo(pl.rc.add(2 * t, 0), false);
        squad().moveTo(pl.rc.add(2 * t, 2 * t), true);
        squad().moveTo(pl.rc.add(0, 2 * t), true);
        log("queued: now " + queueOf(c));
    }

    private void walkBack() {
        if (homeAt != null)
            squad().moveTo(homeAt, false);
        log("walking back; queue " + queueOf(members.get(0)));
    }

    private static String queueOf(SessionContext c) {
        MemberReport r = c.squadReport;
        return (r == null) ? "-" : (r.status + " +" + r.queue.size());
    }

    /** A screenshot of the game window into bench/shots (Windows only; the run is a development tool). */
    static void shot(String name) {
        log("shot " + name);
        String dir = System.getProperty("user.dir");
        Thread t = new Thread(() -> {
            try {
                Process p = new ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File",
                                               dir + "\\bench\\shot.ps1", dir + "\\bench\\shots\\" + name + ".png")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                p.waitFor();
            } catch (java.io.IOException e) {
                log("shot " + name + " failed: " + e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Commander shot");
        t.setDaemon(true);
        t.start();
    }

    private void finish() {
        run = null;
        remembered(rememberedWas);
        if (!painted.isEmpty())
            unmark();
        log("done; remembered ground back to " + rememberedWas + "; exiting in 5 s");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2000);
                for (SessionContext ctx : SessionManager.getInstance().getAllSessions())
                    SessionManager.getInstance().removeSession(ctx.sessionId);
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "Commander-run exit");
        t.setDaemon(true);
        t.start();
    }

    /* Only FileLogger: stderr is routed into the same log, and printing there from the UI thread can deadlock with
     * the log's writer thread (each holds one of the two locks the other needs). */
    static void log(String line) {
        FileLogger.log("[Commander run] " + line);
    }
}
