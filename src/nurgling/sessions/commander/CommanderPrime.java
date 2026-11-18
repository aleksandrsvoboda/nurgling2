package nurgling.sessions.commander;

import haven.Coord;
import haven.GOut;
import haven.UI;
import nurgling.NGameUI;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.ThreadLocalUI;

import java.util.ArrayList;
import java.util.List;

/**
 * Off-screen drawing of warm background sessions, on the render thread after the frame on screen. A background
 * session's view has never been drawn, or not for a while: shown cold, it would come up black, fill in over a second
 * as its textures and meshes load, and its auto-exposure would adapt from a black first frame. Drawing it where nobody
 * sees it (far outside the window) while the camera is near its character does all of that before the switch.
 *
 * <p>A session not yet ready (never drawn enough, or with draw slots still to build) is drawn every frame; ready ones
 * every few frames, round robin, to keep up with what changes around them. Drawn under the session's UI lock, which
 * its own background tick holds too, so the two never run at once.
 */
public final class CommanderPrime {
    /** Frames a view must have been drawn before it counts as ready: textures, meshes and exposure settle. */
    private static final int READY_FRAMES = 45;
    /** A ready session is drawn every this many frames. */
    private static final int KEEP_EVERY = 4;

    private static long frames;
    /** True while a background session's widgets are drawn off screen (they draw as if on screen). */
    static volatile boolean priming;
    private static int rr;

    private CommanderPrime() {}

    public static void frame(UI onScreen, GOut g) {
        SessionManager sm = SessionManager.getInstance();
        if (!sm.squad().commander())
            return;
        List<SessionContext> warm = new ArrayList<>();
        for (SessionContext c : sm.getAllSessions()) {
            if (c.camTarget != null && c.isHeadless() && c.ui != null && c.ui != onScreen && c.ui.gui != null
                && c.ui.gui.map != null)
                warm.add(c);
        }
        if (warm.isEmpty())
            return;
        frames++;
        SessionContext pick = null;
        for (SessionContext c : warm) {
            if (!ready(c.ui.gui)) {
                pick = c;
                break;
            }
        }
        if (pick == null) {
            if (frames % KEEP_EVERY != 0)
                return;
            pick = warm.get((rr++) % warm.size());
        }
        draw(pick, g);
    }

    static boolean ready(NGameUI gui) {
        int n = gui.map.primed();
        /* Some slots can wait for good (something that never loads): past a point, enough is enough. */
        return n >= READY_FRAMES && (!gui.map.drawPending() || n >= 2 * READY_FRAMES);
    }

    private static void draw(SessionContext c, GOut g) {
        NGameUI gui = c.ui.gui;
        nurgling.NUI prev = ThreadLocalUI.get();
        ThreadLocalUI.set(c.ui);
        try {
            synchronized (c.ui) {
                if (!c.isHeadless())
                    return;
                /* Far outside the window, not merely clipped: some drawing (outlines) ignores the clip, and drawn at
                 * the corner it showed as blinking boxes there. Offset like this, all of it falls off the viewport. */
                GOut clip = g.reclipl(Coord.of(-40000, -40000), Coord.of(1, 1));
                gui.map.prime(clip);
                /* Its other widgets too (minimap, Commander panels): their first draw makes their textures, and a
                 * first frame that does all that at once is slow enough to show half drawn through the frame
                 * bridge. The map view itself is not drawn this way: that would go to the scene on screen. */
                priming = true;
                try {
                    for (haven.Widget w = gui.child; w != null; w = w.next) {
                        if (w.visible && w != gui.map)
                            w.draw(clip);
                    }
                } finally {
                    priming = false;
                }
            }
        } finally {
            if (prev != null)
                ThreadLocalUI.set(prev);
            else
                ThreadLocalUI.clear();
        }
        boolean r = ready(gui);
        if (r && !c.primeReady)
            haven.error.FileLogger.log(String.format("[Commander] %s ready to show after %d frames off screen%s",
                                                     c.getDisplayName(), gui.map.primed(),
                                                     gui.map.drawPending() ? " (some slots still loading)" : ""));
        c.primeReady = r;
    }
}
