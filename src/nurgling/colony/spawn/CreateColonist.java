package nurgling.colony.spawn;

import haven.*;
import nurgling.NGameUI;
import nurgling.colony.ColonyWaits;
import nurgling.plugins.UiEvents;
import nurgling.widgets.charsel.NCharselScreen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Creates one character on a session waiting at character select, with a spawn secret, and leaves it in the world.
 *
 * <p>Copied from the Character Maker plugin (nurgling-plugins, charmaker/CreateCharacters.java) for the colony, and
 * reachable only from the Commander Colony panel; the plugin stays as it is. Differences: one character, it stays in
 * the world (no log-out at the end), and waits poll from the colony's run thread rather than one session's NCore.
 *
 * <p>Steps, each checked against the server's answer: "New character" on the selection screen; the walk through the
 * creation area ({@link CreationPath}); the Wizard ({@code ui/chnm}: {@code srv <world id>}, then {@code nm <name>});
 * the charter pole ("Spawn Charter" window, the secret goes in with {@code activate}); the fire, after which the
 * character lands in the world thousands of tiles away.
 */
public final class CreateColonist implements UiEvents.Listener {
    /** A step that could not be done; the message says what and why. */
    public static final class Failed extends Exception {
        Failed(String msg) {
            super(msg);
        }
    }

    private static final double TILE = MCache.tilesz.x;
    private static final double ARRIVE = 2.0;
    private static final long STALL_MS = 2000;
    private static final int NAME_TRIES = 10;
    /** Landing in the world moves the character thousands of tiles; the creation area is ~150 across. */
    private static final double LANDED = 500 * TILE;

    private final UI ui;
    private final String world;
    private final String wantedName;
    private final String secret;
    private final Consumer<String> status;

    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<Object[]> worlds = Collections.synchronizedList(new ArrayList<>());
    private final Set<Widget> destroyed = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private volatile Widget wizard;

    /**
     * @param world  short world name as character select shows it ("World 16.2")
     * @param name   the name to ask for; "Name2", "Name3"... are tried when it is taken
     * @param status gets a line for each step
     */
    public CreateColonist(UI ui, String world, String name, String secret, Consumer<String> status) {
        this.ui = ui;
        this.world = world;
        this.wantedName = name;
        this.secret = secret;
        this.status = status;
    }

    /** Runs the whole creation; returns the name the character got. */
    public String run() throws Failed, InterruptedException {
        UiEvents.addListener(this);
        try {
            Coord2d anchor = enterCreation();
            String name = null;
            for (CreationPath.Step s : CreationPath.STEPS) {
                Coord2d target = world(s.t, anchor);
                if (s.walk()) {
                    walkTo(target, s.t);
                    continue;
                }
                switch (s.role) {
                    case WIZARD:
                        status.accept("wizard");
                        name = wizard(target);
                        break;
                    case CHARTER:
                        status.accept("charter");
                        charter(target);
                        break;
                    case FIRE:
                        status.accept("fire");
                        fire(target, anchor);
                        break;
                }
            }
            return name;
        } finally {
            UiEvents.removeListener(this);
        }
    }

    /* ---------------------------------------------------------------- screens */

    private NCharselScreen charsel() {
        for (Widget w = ui.root.child; w != null; w = w.next)
            if (w instanceof NCharselScreen)
                return (NCharselScreen) w;
        return null;
    }

    private boolean inGame() {
        GameUI g = ui.gui;
        return g != null && g.parent != null;
    }

    private Gob player() {
        GameUI g = ui.gui;
        MapView map = (g == null) ? null : g.map;
        return (map == null) ? null : map.player();
    }

    private Coord2d enterCreation() throws Failed, InterruptedException {
        status.accept("charsel");
        if (!ColonyWaits.until(90000, () -> !inGame() && charsel() != null && charsel().newCharButton() != null))
            throw new Failed("character selection did not show");
        GameUI prev = ui.gui;
        status.accept("enter");
        synchronized (ui) {
            charsel().newCharButton().wdgmsg("activate");
        }
        if (!ColonyWaits.until(60000, () -> inGame() && ui.gui != prev && player() != null))
            throw new Failed("the creation area did not load");
        Gob pl = player();
        if (!ColonyWaits.until(10000, () -> "gfx/borka/wisp".equals(name(pl))))
            throw new Failed("\"New character\" did not open the creation area (player is " + name(pl) + ")");
        return pl.rc;
    }

    /* ---------------------------------------------------------------- walking */

    private void walkTo(Coord2d target, Coord2d t) throws Failed, InterruptedException {
        status.accept("walk");
        for (int attempt = 0; attempt < 2; attempt++) {
            synchronized (ui) {
                ui.gui.map.wdgmsg("click", Coord.z, target.floor(OCache.posres), 1, 0);
            }
            long deadline = System.currentTimeMillis() + 30000;
            Coord2d[] last = {null};
            long[] moved = {System.currentTimeMillis()};
            boolean[] arrived = {false};
            ColonyWaits.until(31000, () -> {
                Gob pl = player();
                long now = System.currentTimeMillis();
                if (pl == null)
                    return now > deadline;
                if (pl.rc.dist(target) < ARRIVE) {
                    arrived[0] = true;
                    return true;
                }
                if (last[0] == null || pl.rc.dist(last[0]) > 0.05) {
                    last[0] = pl.rc;
                    moved[0] = now;
                }
                return now - moved[0] > STALL_MS || now > deadline;
            });
            if (arrived[0])
                return;
        }
        throw new Failed(String.format("stuck walking to (%.1f, %.1f) tiles from spawn", t.x, t.y));
    }

    /* ---------------------------------------------------------------- the three stations */

    private void use(CreationPath.Role role, Coord2d at) throws Failed, InterruptedException {
        Gob[] g = {null};
        if (!ColonyWaits.until(10000, () -> (g[0] = nearest(role.res, at, 3 * TILE)) != null))
            throw new Failed("no " + role.label + " where the route expects it");
        Coord mc = g[0].rc.floor(OCache.posres);
        synchronized (ui) {
            ui.gui.map.wdgmsg("click", Coord.z, mc, 3, 0, 0, (int) g[0].id, mc, 0, -1);
        }
    }

    private String wizard(Coord2d at) throws Failed, InterruptedException {
        wizard = null;
        worlds.clear();
        use(CreationPath.Role.WIZARD, at);
        if (!ColonyWaits.until(20000, () -> wizard != null))
            throw new Failed("the wizard did not answer");
        Widget dlg = wizard;
        ColonyWaits.until(3000, () -> !worlds.isEmpty());
        Object id = pickWorld();
        synchronized (ui) {
            dlg.wdgmsg("srv", id);
        }
        for (int k = 0; k < NAME_TRIES; k++) {
            String name = (k == 0) ? wantedName : wantedName + (k + 1);
            int seen = errors.size();
            synchronized (ui) {
                dlg.wdgmsg("nm", name);
            }
            if (!ColonyWaits.until(15000, () -> destroyed.contains(dlg) || errors.size() > seen))
                throw new Failed("the wizard did not answer the name \"" + name + "\"");
            if (destroyed.contains(dlg))
                return name;
            String err = errors.get(errors.size() - 1);
            String e = err.toLowerCase();
            if (!(e.contains("name") && (e.contains("already") || e.contains("taken"))))
                throw new Failed("the wizard refused \"" + name + "\": " + err);
        }
        throw new Failed(NAME_TRIES + " names in a row were taken");
    }

    /** The wizard's world whose short name ("World 16.2" of "Alternate world: World 16.2") is the colony's. */
    private Object pickWorld() throws Failed {
        List<Object[]> ws;
        synchronized (worlds) {
            ws = new ArrayList<>(worlds);
        }
        if (ws.isEmpty())
            throw new Failed("the wizard offered no worlds");
        List<String> names = new ArrayList<>();
        for (Object[] w : ws) {
            String n = (String) w[0];
            int i = n.indexOf(": ");
            String shortName = (i < 0) ? n : n.substring(i + 2);
            names.add(shortName);
            if (shortName.equals(world))
                return w[1];
        }
        throw new Failed("the wizard does not offer " + world + " (it offers " + String.join(", ", names) + ")");
    }

    private void charter(Coord2d at) throws Failed, InterruptedException {
        use(CreationPath.Role.CHARTER, at);
        TextEntry[] te = {null};
        if (!ColonyWaits.until(20000, () -> (te[0] = charterField()) != null))
            throw new Failed("the charter pole did not open");
        Widget wnd = te[0].parent;
        int seen = errors.size();
        synchronized (ui) {
            te[0].wdgmsg("activate", secret);
        }
        if (!ColonyWaits.until(15000, () -> destroyed.contains(wnd) || errors.size() > seen))
            throw new Failed("the charter pole did not take the secret");
        if (!destroyed.contains(wnd))
            throw new Failed("the charter pole refused the secret: " + errors.get(errors.size() - 1));
    }

    private TextEntry charterField() {
        GameUI g = ui.gui;
        Window wnd = (g instanceof NGameUI) ? ((NGameUI) g).getWindow("Spawn Charter") : null;
        if (wnd == null)
            return null;
        for (Widget c = wnd.child; c != null; c = c.next)
            if (c instanceof TextEntry)
                return (TextEntry) c;
        return null;
    }

    private void fire(Coord2d at, Coord2d anchor) throws Failed, InterruptedException {
        use(CreationPath.Role.FIRE, at);
        if (!ColonyWaits.until(60000, () -> {
            Gob pl = player();
            return pl != null && pl.rc.dist(anchor) > LANDED;
        }))
            throw new Failed("the fire did not send the character into the world");
    }

    /* ---------------------------------------------------------------- server events */

    @Override
    public void onNewWidget(UI ui, int id, String type, Widget wdg, Object[] cargs) {
        if (ui == this.ui && type.startsWith("ui/chnm"))
            wizard = wdg;
    }

    @Override
    public void onUiMsg(UI ui, int id, Widget wdg, String msg, Object[] args) {
        if (ui != this.ui)
            return;
        if (wdg == ui.root && "err".equals(msg) && args.length > 0)
            errors.add(String.valueOf(args[0]));
        else if (wdg == wizard && "srv".equals(msg) && args.length >= 2)
            worlds.add(new Object[]{String.valueOf(args[0]), args[1]});
    }

    @Override
    public void onDestroyWidget(UI ui, int id, Widget wdg) {
        if (ui == this.ui)
            destroyed.add(wdg);
    }

    /* ---------------------------------------------------------------- gobs */

    private Gob nearest(String res, Coord2d c, double radius) {
        Gob pl = player();
        Gob best = null;
        OCache oc = ui.sess.glob.oc;
        synchronized (oc) {
            for (Gob g : oc) {
                if (g instanceof OCache.Virtual || g == pl || g.rc.dist(c) > radius || !res.equals(name(g)))
                    continue;
                if (best == null || g.rc.dist(c) < best.rc.dist(c))
                    best = g;
            }
        }
        return best;
    }

    private static String name(Gob g) {
        if (g == null)
            return "(none)";
        if (g.ngob != null && g.ngob.name != null)
            return g.ngob.name;
        Drawable d = g.getattr(Drawable.class);
        if (d == null)
            return "(no drawable)";
        try {
            Resource r = d.getres();
            return (r == null) ? "(no res)" : r.name;
        } catch (Loading l) {
            return "(loading)";
        }
    }

    private static Coord2d world(Coord2d t, Coord2d anchor) {
        return Coord2d.of(anchor.x + t.x * TILE, anchor.y + t.y * TILE);
    }
}
