package nurgling.colony.actions;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.i18n.L10n;
import nurgling.sessions.squad.SquadExecutor;
import nurgling.sessions.squad.WorldRef;
import nurgling.tasks.NTask;

/**
 * Lights the colony's Wilderness Beacon with a spawn secret. Measured in game (2026-11-18): right-clicking a built
 * beacon opens a window "Wilderness Beacon" with "Enter spawn secret:" and a text field, no flower menu; the window
 * closes once the secret is taken. The beacon then burns for an hour or until one character spawns by it.
 */
public final class LightBeacon implements Action {
    private static final String WINDOW = "Wilderness Beacon";
    private static final long WAIT_MS = 10000;

    private final WorldRef site;
    private final String secret;
    public volatile String problem;

    public LightBeacon(WorldRef site, String secret) {
        this.site = site;
        this.secret = secret;
    }

    private Results fail(String key, Object... args) {
        problem = L10n.get(key, args);
        return Results.FAIL();
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Coord2d at = site.localIn(gui.map.glob.map, SquadExecutor.sessloc(gui));
        Gob beacon = (at == null) ? null : nearest(gui, at);
        if (beacon == null)
            return fail("colony.err.nobeacon");
        PathFinder pf = new PathFinder(beacon);
        pf.isHardMode = true;
        if (!pf.run(gui).IsSuccess())
            return fail("colony.err.reach");
        gui.ui.dropLastError();
        NUtils.rclickGob(beacon);
        TextEntry[] te = {null};
        long deadline = System.currentTimeMillis() + WAIT_MS;
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                te[0] = field(gui);
                return te[0] != null || gui.ui.getLastError() != null || System.currentTimeMillis() > deadline;
            }
        });
        if (te[0] == null) {
            String err = gui.ui.getLastError();
            return (err != null) ? fail("colony.err.light", err) : fail("colony.err.nolight");
        }
        Widget wnd = te[0].parent;
        gui.ui.dropLastError();
        te[0].wdgmsg("activate", secret);
        long until = System.currentTimeMillis() + WAIT_MS;
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return wnd.parent == null || gui.ui.getLastError() != null || System.currentTimeMillis() > until;
            }
        });
        if (wnd.parent != null) {
            String err = gui.ui.getLastError();
            wnd.wdgmsg("close");
            return (err != null) ? fail("colony.err.light", err) : fail("colony.err.nolight");
        }
        return Results.SUCCESS();
    }

    private static Gob nearest(NGameUI gui, Coord2d at) {
        Gob best = null;
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob g : gui.ui.sess.glob.oc) {
                if (g.ngob == null || !BuildBeacon.BEACON.equals(g.ngob.name) || g.rc.dist(at) > 3 * MCache.tilesz.x)
                    continue;
                if (best == null || g.rc.dist(at) < best.rc.dist(at))
                    best = g;
            }
        }
        return best;
    }

    /** The secret's text field, once the beacon's window is open. */
    private static TextEntry field(NGameUI gui) {
        Window w = gui.getWindow(WINDOW);
        if (w == null)
            return null;
        for (Widget c = w.child; c != null; c = c.next)
            if (c instanceof TextEntry)
                return (TextEntry) c;
        return null;
    }
}
