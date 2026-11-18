package nurgling.sessions.commander;

import haven.Coord2d;
import haven.MiniMap;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.SquadExecutor;

/**
 * The session side of the commander camera, run in every session's own {@code NCore.tick}. A headless session the
 * viewpoint asked to be warm ({@link SessionContext#camTarget}) points its own map view at that spot and keeps its
 * ground meshes built there, so switching to it shows ready ground; it reports when it is ({@link
 * SessionContext#warmReady}). A few seconds after the camera has left, it lets the meshes go again: they are what makes
 * a background session cost memory.
 */
public final class CommanderTick {
    /** How long a session stays warm after the camera has gone. */
    private static final long LINGER_MS = 4000;

    private CommanderTick() {}

    public static void tick(NUI ui) {
        if (ui == null || ui.sess == null)
            return;
        NGameUI gui = ui.gui;
        if (gui == null || gui.map == null)
            return;
        SessionContext ctx = SessionManager.getInstance().findBySession(ui.sess);
        if (ctx == null)
            return;
        if (SessionManager.getInstance().squad().commander())
            CommanderPortraits.ensure(gui);
        if (!ctx.isHeadless()) {
            /* The viewpoint: its camera is driven by CommanderView. */
            ctx.warmReady = false;
            ctx.warmedMeshes = false;
            return;
        }
        long now = System.currentTimeMillis();
        Focus t = ctx.camTarget;
        if (t != null) {
            MiniMap.Location sl = SquadExecutor.sessloc(gui);
            Coord2d local = t.localIn(sl);
            if (local != null)
                gui.map.camfocus = local;
            if (!gui.map.glob.map.meshes()) {
                gui.map.glob.map.meshes(true);
                ctx.warmedMeshes = true;
            }
            int[] fill = gui.map.terrainfill();
            ctx.warmReady = (local != null) && fill != null && fill[1] > 0 && fill[0] >= fill[1];
            ctx.warmUntil = now + LINGER_MS;
        } else {
            ctx.warmReady = false;
            if (now > ctx.warmUntil) {
                if (gui.map.camfocus != null)
                    gui.map.camfocus = null;
                if (ctx.warmedMeshes) {
                    ctx.warmedMeshes = false;
                    gui.map.glob.map.meshes(false);
                }
            }
        }
    }
}
