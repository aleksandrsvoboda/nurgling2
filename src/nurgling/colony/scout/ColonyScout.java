package nurgling.colony.scout;

import haven.*;
import nurgling.NFlowerMenu;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.conf.NAreaRad;
import nurgling.plugins.UiEvents;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.widgets.NEquipory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@code :colony} - phase 0 of the colony manager (plan: .lavish/colony-spawn-plan.html). Logging only.
 *
 * While it runs it writes one plain-text file, {@code colony-scout-<time>.log} in the client's working
 * directory, with what every session does: widgets the server creates, messages and destroys (character
 * select included, so a character limit shows up), every message a session sends (map clicks with the
 * gob's name, flower petals by name, skill purchases), items as they appear, and once a second the gobs
 * that come and go within {@link #RADIUS} of each character, with animals' poses. Lines are tagged with
 * the session's character or account name, so several characters can be followed in one file.
 *
 * Text the client sends is masked unless {@code :colony text on}, so a secret never lands in the file.
 * The same message repeating from one widget is cut down after {@link #BURST} lines in a row.
 *
 * <ul>
 *   <li>{@code start} / {@code stop} - start or stop the log.</li>
 *   <li>{@code mark [words]} - a numbered mark with a note, e.g. {@code :colony mark non-carpenter presses Build}.</li>
 *   <li>{@code dump} - every character's skills, LP, hands, equipment, belt and inventory, and the gobs near them.</li>
 *   <li>{@code text on|off} - log typed text instead of masking it.</li>
 *   <li>{@code status} - is it running, and where is the file.</li>
 * </ul>
 */
public final class ColonyScout implements UiEvents.Listener {
    private static final String USAGE = "Usage: colony start | stop | mark [note] | dump | text on|off | status";
    /** Gob changes are logged within this distance of each character. */
    static final double RADIUS = 30 * MCache.tilesz.x;
    private static final int MAXARGS = 400;
    /** Lines in a row for the same widget and message before they are only counted. */
    private static final int BURST = 12;

    private static ColonyScout current;

    private final File file;
    private final PrintWriter out;
    private final long started = System.currentTimeMillis();
    private int lines, marks;
    private volatile boolean logText;
    private final ScheduledExecutorService watcher;
    /* Repeat squelch: the last key per session, and how many times it came in a row. */
    private final Map<UI, String> lastKey = new HashMap<>();
    private final Map<UI, Integer> repeats = new HashMap<>();
    /* Gobs near each character at the last look: id -> description. */
    private final Map<UI, Map<Long, String>> seen = new HashMap<>();

    private ColonyScout(File file) throws IOException {
        this.file = file;
        this.out = new PrintWriter(new FileWriter(file), true);
        this.watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "colony-scout");
            t.setDaemon(true);
            return t;
        });
    }

    /* ------------------------------------------------------------------ console */

    public static void run(NGameUI gui, String[] args) {
        if (args.length < 2) {
            gui.msg(USAGE);
            return;
        }
        ColonyScout s = active();
        switch (args[1]) {
            case "start":
                stop();
                try {
                    s = start();
                    gui.msg("colony: scout log started: " + s.file);
                } catch (IOException e) {
                    gui.error("colony: could not open the scout log: " + e.getMessage());
                }
                break;
            case "stop":
                if (s == null) {
                    gui.msg("colony: no scout log running");
                    return;
                }
                File f = s.file;
                stop();
                gui.msg("colony: scout log stopped: " + f);
                break;
            case "mark":
                if (s == null) {
                    gui.msg("colony: start the log first (:colony start)");
                    return;
                }
                String note = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                int n = s.mark(gui.ui, note);
                gui.msg("colony: mark #" + n + (note.isEmpty() ? "" : " " + note));
                break;
            case "dump":
                if (s == null) {
                    gui.msg("colony: start the log first (:colony start)");
                    return;
                }
                s.dumpAll("dump command");
                gui.msg("colony: dumped every character to the scout log");
                break;
            case "text":
                if (s == null || args.length < 3) {
                    gui.msg(USAGE);
                    return;
                }
                s.logText = args[2].equals("on");
                s.line(null, "CONFIG", s.logText ? "typed text is now logged" : "typed text is now masked");
                gui.msg("colony: typed text " + (s.logText ? "logged" : "masked"));
                break;
            case "status":
                if (s == null)
                    gui.msg("colony: no scout log running");
                else
                    gui.msg(String.format("colony: scout log running %d s, %d lines: %s",
                            (System.currentTimeMillis() - s.started) / 1000, s.lines(), s.file));
                break;
            default:
                gui.msg(USAGE);
        }
    }

    private static synchronized ColonyScout active() {
        return current;
    }

    private static synchronized ColonyScout start() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        ColonyScout s = new ColonyScout(new File("colony-scout-" + ts + ".log").getAbsoluteFile());
        current = s;
        s.line(null, "START", "colony scout log; typed text masked; gob radius " + (int) (RADIUS / MCache.tilesz.x) + " tiles");
        UiEvents.addListener(s);
        s.dumpAll("start");
        s.watcher.scheduleWithFixedDelay(s::watch, 1, 1, TimeUnit.SECONDS);
        return s;
    }

    private static synchronized void stop() {
        ColonyScout s = current;
        if (s == null)
            return;
        current = null;
        UiEvents.removeListener(s);
        s.watcher.shutdownNow();
        s.line(null, "STOP", s.lines() + " lines");
        synchronized (s) {
            s.out.close();
        }
    }

    /* ------------------------------------------------------------------ writing */

    private synchronized int lines() {
        return lines;
    }

    private synchronized int mark(UI ui, String note) {
        marks++;
        Gob pl = player(ui);
        line(ui, "MARK", "#" + marks + (note.isEmpty() ? "" : " " + note) + ((pl == null) ? "" : " player at " + pos(pl.rc)));
        return marks;
    }

    /** One timestamped line, tagged with the session it came from ({@code null} = the scout itself). */
    synchronized void line(UI ui, String tag, String text) {
        if (current != this)
            return;
        String l = String.format("%s %-9s %-14s %s", new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()),
                tag, (ui == null) ? "-" : who(ui), text);
        out.println(l);
        lines++;
    }

    /** Logs {@code text} unless the same key already came {@link #BURST} times in a row from this session. */
    private synchronized void squelched(UI ui, String key, String tag, String text) {
        if (key.equals(lastKey.get(ui))) {
            int n = repeats.merge(ui, 1, Integer::sum);
            if (n == BURST)
                line(ui, tag, text + "  (repeating; further repeats only counted)");
            if (n >= BURST)
                return;
        } else {
            Integer n = repeats.get(ui);
            if ((n != null) && (n > BURST))
                line(ui, "REPEAT", (n - BURST) + " more of " + lastKey.get(ui));
            lastKey.put(ui, key);
            repeats.put(ui, 1);
        }
        line(ui, tag, text);
    }

    private static String who(UI ui) {
        SessionContext ctx = SessionManager.getInstance().findByUI(ui);
        if (ctx == null)
            return Integer.toHexString(System.identityHashCode(ui));
        if ((ctx.characterName != null) && !ctx.characterName.isEmpty())
            return ctx.characterName;
        /* Background sessions never cache their character name; read it from their game UI. */
        if ((ui.gui != null) && (ui.gui.chrid != null) && !ui.gui.chrid.isEmpty())
            return ui.gui.chrid;
        return (ctx.username != null) ? ctx.username + "@cs" : ctx.sessionId;
    }

    /* ------------------------------------------------------------------ server -> client */

    @Override
    public void onNewWidget(UI ui, int id, String type, Widget wdg, Object[] cargs) {
        String extra = (wdg instanceof GItem) ? " item=" + itemName((GItem) wdg) : "";
        squelched(ui, "new " + type, "IN", "new #" + id + " " + type + " (" + cls(wdg) + ")" + extra + " " + args(cargs, false));
    }

    @Override
    public void onAddWidget(UI ui, int id, Widget wdg, int parent, Widget pwdg, Object[] pargs) {
        squelched(ui, "add " + cls(pwdg), "IN", "add #" + id + " to #" + parent + " (" + cls(pwdg) + ") " + args(pargs, false));
    }

    @Override
    public void onUiMsg(UI ui, int id, Widget wdg, String msg, Object[] args) {
        squelched(ui, "msg " + id + " " + msg, "IN", "msg #" + id + " (" + cls(wdg) + ") " + msg + " " + args(args, wdg instanceof TextEntry));
    }

    @Override
    public void onDestroyWidget(UI ui, int id, Widget wdg) {
        String extra = (wdg instanceof GItem) ? " item=" + itemName((GItem) wdg) : "";
        squelched(ui, "del " + cls(wdg), "IN", "del #" + id + " (" + cls(wdg) + ")" + extra);
    }

    /* ------------------------------------------------------------------ client -> server */

    @Override
    public void onOutgoing(UI ui, int id, Widget sender, String msg, Object[] args) {
        if ((sender instanceof MapView) && msg.equals("click") && (args.length >= 4)) {
            mapClick(ui, args);
        } else if ((sender instanceof FlowerMenu) && msg.equals("cl") && (args.length >= 1)) {
            line(ui, "OUT", "petal " + petal((FlowerMenu) sender, args[0]));
        } else {
            squelched(ui, "out " + id + " " + msg, "OUT", "#" + id + " (" + cls(sender) + ") " + msg + " " + args(args, true));
        }
    }

    /* click args: screen coord, map coord in posres units, button, modflags[, overlay, gob id, gob rc, overlay id, mesh id] */
    private void mapClick(UI ui, Object[] args) {
        StringBuilder sb = new StringBuilder("click b" + args[2] + " mods=" + args[3]);
        if (args[1] instanceof Coord)
            sb.append(" at ").append(pos(((Coord) args[1]).mul(OCache.posres)));
        if ((args.length >= 6) && (args[5] instanceof Number)) {
            long gid = ((Number) args[5]).longValue();
            Gob g = ui.sess.glob.oc.getgob(gid);
            sb.append(" gob ").append(gid).append(' ').append((g == null) ? "(gone)" : describe(g));
        }
        line(ui, "OUT", sb.toString());
    }

    private static String petal(FlowerMenu fm, Object arg) {
        int num = (arg instanceof Number) ? ((Number) arg).intValue() : -2;
        if (num == -1)
            return "cancel";
        if (fm instanceof NFlowerMenu) {
            for (NFlowerMenu.NPetal p : ((NFlowerMenu) fm).nopts) {
                if (p.num == num)
                    return "\"" + p.name + "\" (" + num + ")";
            }
        } else if (fm.opts != null) {
            for (FlowerMenu.Petal p : fm.opts) {
                if (p.num == num)
                    return "\"" + p.name + "\" (" + num + ")";
            }
        }
        return "#" + arg;
    }

    /* ------------------------------------------------------------------ gobs */

    /** Once a second: the gobs that came near or left each character since the last look. */
    private void watch() {
        if (active() != this)
            return;
        for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            UI ui = ctx.ui;
            if (ui == null)
                continue;
            Map<Long, String> now = near(ui);
            Map<Long, String> before = seen.put(ui, now);
            if ((now == null) || (before == null))
                continue;
            for (Map.Entry<Long, String> e : before.entrySet()) {
                if (!now.containsKey(e.getKey()))
                    line(ui, "GOB-", e.getValue() + " id=" + e.getKey());
            }
            for (Map.Entry<Long, String> e : now.entrySet()) {
                String was = before.get(e.getKey());
                if (was == null)
                    line(ui, "GOB+", e.getValue() + " id=" + e.getKey());
                else if (changedBeyondPosition(was, e.getValue()))
                    line(ui, "GOB~", e.getValue() + " id=" + e.getKey() + "  (was " + was + ")");
            }
        }
    }

    /** A gob's description without its position, so walking animals don't flood the log. */
    private static boolean changedBeyondPosition(String a, String b) {
        return !stripPos(a).equals(stripPos(b));
    }

    private static String stripPos(String d) {
        int i = d.indexOf(" at ");
        int j = (i < 0) ? -1 : d.indexOf(' ', i + 4);
        return (i < 0) ? d : d.substring(0, i) + ((j < 0) ? "" : d.substring(j));
    }

    /** Gobs within {@link #RADIUS} of the session's character, other players left out; null without a character. */
    private static Map<Long, String> near(UI ui) {
        Gob pl = player(ui);
        if (pl == null)
            return null;
        Map<Long, String> ret = new HashMap<>();
        synchronized (ui.sess.glob.oc) {
            for (Gob g : ui.sess.glob.oc) {
                if ((g == pl) || (g.rc.dist(pl.rc) > RADIUS))
                    continue;
                String name = name(g);
                if (name.startsWith("gfx/borka/"))
                    continue;
                ret.put(g.id, describe(g));
            }
        }
        return ret;
    }

    private static Gob player(UI ui) {
        NGameUI gui = (ui == null) ? null : (NGameUI) ui.gui;
        return ((gui == null) || (gui.map == null)) ? null : gui.map.player();
    }

    private static String name(Gob g) {
        return ((g.ngob != null) && (g.ngob.name != null)) ? g.ngob.name : "(loading)";
    }

    /** Res name, position in tiles, and for animals the pose and whether it reads as down or dead. */
    private static String describe(Gob g) {
        String name = name(g);
        StringBuilder sb = new StringBuilder(name).append(" at ").append(pos(g.rc));
        if (name.startsWith("gfx/kritter/")) {
            String pose = g.pose();
            sb.append(" pose=").append(pose);
            if (NAreaRad.isDownOrDead(g))
                sb.append(" (down/dead)");
        }
        return sb.toString();
    }

    private static String pos(Coord2d rc) {
        return String.format("(%.1f,%.1f)t", rc.x / MCache.tilesz.x, rc.y / MCache.tilesz.y);
    }

    /* ------------------------------------------------------------------ dumps */

    private void dumpAll(String why) {
        for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            if (ctx.ui != null)
                dump(ctx.ui, why);
        }
    }

    /** Skills, LP, equipment, belt, inventory and nearby gobs of one character, read under its UI lock. */
    private void dump(UI ui, String why) {
        synchronized (ui) {
            NGameUI gui = (NGameUI) ui.gui;
            if (gui == null) {
                line(ui, "STATE", why + ": not in the game (character select or loading)");
                return;
            }
            line(ui, "STATE", why + ": " + gui.chrid);
            if ((gui.chrwdg != null) && (gui.chrwdg.skill != null) && (gui.chrwdg.skill.skg != null)) {
                SkillWnd.SkillGrid skg = gui.chrwdg.skill.skg;
                line(ui, "SKILLS", "LP " + gui.chrwdg.exp + "; known: " + skills(skg.csk.items, false));
                line(ui, "SKILLS", "can buy: " + skills(skg.nsk.items, true));
            } else {
                line(ui, "SKILLS", "character sheet not loaded");
            }
            NEquipory eq = equipory(gui);
            if (eq != null) {
                List<String> worn = new ArrayList<>();
                for (NEquipory.Slots s : NEquipory.Slots.values()) {
                    WItem w = eq.quickslots[s.idx];
                    if (w == null)
                        continue;
                    worn.add(s + "=" + itemName(w.item));
                    if (w.item.contents != null)
                        line(ui, "CONTAINS", s + " " + itemName(w.item) + ": " + items(w.item.contents));
                }
                line(ui, "EQUIP", String.join(", ", worn));
            }
            if (gui.maininv != null)
                line(ui, "INV", items(gui.maininv));
        }
        Map<Long, String> gobs = near(ui);
        if (gobs != null) {
            line(ui, "GOBS", why + ": " + gobs.size() + " within " + (int) (RADIUS / MCache.tilesz.x) + " tiles of " + pos(player(ui).rc));
            for (Map.Entry<Long, String> e : gobs.entrySet())
                line(ui, "GOB", e.getValue() + " id=" + e.getKey());
        }
    }

    private static NEquipory equipory(NGameUI gui) {
        if (gui.equwnd == null)
            return null;
        for (Widget w = gui.equwnd.lchild; w != null; w = w.prev) {
            if (w instanceof NEquipory)
                return (NEquipory) w;
        }
        return null;
    }

    private static String skills(List<SkillWnd.Skill> list, boolean cost) {
        List<String> names = new ArrayList<>();
        for (SkillWnd.Skill s : list)
            names.add(s.nm + (cost ? " " + s.cost : ""));
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }

    /** Item names in an inventory widget, counted: "Branch x3, Stone Axe". Reads the widget tree, not NInventory. */
    private static String items(Widget inv) {
        Map<String, Integer> count = new TreeMap<>();
        for (Widget w = inv.child; w != null; w = w.next) {
            if (w instanceof WItem)
                count.merge(itemName(((WItem) w).item), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : count.entrySet())
            parts.add(e.getKey() + ((e.getValue() > 1) ? " x" + e.getValue() : ""));
        return parts.isEmpty() ? "(empty)" : String.join(", ", parts);
    }

    private static String itemName(GItem item) {
        if ((item instanceof NGItem) && (((NGItem) item).name() != null))
            return ((NGItem) item).name();
        try {
            Resource r = item.res.get();
            return (r == null) ? "(loading)" : r.name;
        } catch (Loading l) {
            return "(loading)";
        }
    }

    /* ------------------------------------------------------------------ formatting */

    private String args(Object[] args, boolean maskable) {
        StringBuilder sb = new StringBuilder();
        fmt(sb, args, maskable && !logText);
        if (sb.length() > MAXARGS) {
            sb.setLength(MAXARGS);
            sb.append("…");
        }
        return sb.toString();
    }

    private static void fmt(StringBuilder sb, Object a, boolean mask) {
        if (a instanceof Object[]) {
            sb.append('[');
            Object[] arr = (Object[]) a;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0)
                    sb.append(", ");
                fmt(sb, arr[i], mask);
            }
            sb.append(']');
        } else if (a instanceof String) {
            if (mask)
                sb.append("«text: ").append(((String) a).length()).append(" chars»");
            else
                sb.append('"').append(a).append('"');
        } else if (a instanceof byte[]) {
            sb.append("bytes[").append(((byte[]) a).length).append(']');
        } else {
            sb.append(a);
        }
    }

    private static String cls(Widget w) {
        if (w == null)
            return "null";
        String n = w.getClass().getName();
        return n.substring(n.lastIndexOf('.') + 1);
    }
}
