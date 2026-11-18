package nurgling.sessions.commander;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.sessions.SessionContext;
import nurgling.sessions.ThreadLocalUI;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The roster's portraits: every character's own portrait widget (in its own session's game UI, hidden in Commander
 * mode) drawn into its card. They are drawn right after the frame's interface, on the render thread, each under its
 * session's lock - the same lock its own background tick holds - so a portrait never reads a session that is changing.
 */
public final class CommanderPortraits {
    static final class Slot {
        final SessionContext ctx;
        final Area a;

        Slot(SessionContext ctx, Area a) {
            this.ctx = ctx;
            this.a = a;
        }
    }

    private static volatile List<Slot> want = Collections.emptyList();
    private static volatile UI wantUi;
    private static volatile long wantAt;
    private static final Map<NGameUI, Avaview> found = new WeakHashMap<>();

    private CommanderPortraits() {}

    /** The roster of {@code ui} drew its cards this frame: the portraits go there. */
    static void want(UI ui, List<Slot> slots) {
        /* A background session's roster drawn off screen (CommanderPrime) must not take them: they would go to its
         * session, not the one on screen, and blink. */
        if (CommanderPrime.priming)
            return;
        want = slots;
        wantUi = ui;
        wantAt = System.nanoTime();
    }

    public static void frame(UI onScreen, GOut g) {
        List<Slot> s = want;
        if (wantUi != onScreen || s.isEmpty() || System.nanoTime() - wantAt > 250_000_000L)
            return;
        for (Slot sl : s) {
            NUI ui = sl.ctx.ui;
            if (ui == null || ui.gui == null)
                continue;
            Avaview av = portraitOf(ui.gui);
            if (av == null)
                continue;
            NUI prev = ThreadLocalUI.get();
            ThreadLocalUI.set(ui);
            try {
                synchronized (ui) {
                    av.draw(g.reclip(sl.a.ul, sl.a.sz()));
                }
            } finally {
                if (prev != null)
                    ThreadLocalUI.set(prev);
                else
                    ThreadLocalUI.clear();
            }
        }
    }

    /** The roster's portrait view of this game UI, or null before its session made it. */
    private static Avaview portraitOf(NGameUI gui) {
        synchronized (found) {
            Avaview av = found.get(gui);
            return (av != null && av.parent != null) ? av : null;
        }
    }

    /**
     * Called in every session's own tick in Commander mode: its roster portrait, a small view of its character made
     * the first time (the game's own portrait has a fixed size), kept pointed at its character.
     */
    static void ensure(NGameUI gui) {
        long plgob = (gui.map == null) ? -1 : gui.map.plgob;
        Avaview av;
        synchronized (found) {
            av = found.get(gui);
        }
        if (av == null || av.parent == null) {
            int p = Roster.portrait();
            av = gui.add(new Avaview(Coord.of(p, p), plgob, "avacam"));
            av.hide();
            synchronized (found) {
                found.put(gui, av);
            }
        } else if (av.avagob != plgob && plgob >= 0) {
            av.avagob = plgob;
        }
    }
}
