package nurgling.sessions.commander;

import haven.UI;
import haven.Utils;
import haven.error.FileLogger;
import nurgling.NGameUI;
import nurgling.sessions.SessionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Times every session switch from the request until the new session's ground is all on screen, so we know what the
 * blink is made of. Each step of the switch path marks itself ({@link #mark}); every frame drawn meanwhile is counted
 * as showing the target's game screen or something else (the login screen the switch passes through), and the target
 * map's live terrain is checked until every cut around the camera is in the scene. One summary per switch goes to the
 * log ({@code [Commander switch]}); with {@code :commander trace} it is also printed in the chat.
 */
public final class SwitchTrace {
    /** Give up on a switch whose ground never fills. */
    private static final double TIMEOUT = 15;
    private static final int HISTORY = 20;

    private static volatile Trace cur;
    private static volatile boolean chat = false;
    private static final List<String> history = Collections.synchronizedList(new ArrayList<>());

    private SwitchTrace() {}

    private static final class Trace {
        final SessionContext target;
        final String name;
        final double t0 = Utils.rtime();
        final StringBuilder marks = new StringBuilder();
        double lastFrame = -1, maxGap = 0, firstGame = -1, firstFill = -1, lastGain = -1;
        int frames, otherFrames, gameFrames;
        int fillAtFirstGame = -1, wantedAtFirstGame = -1, lastDrawn, lastWanted;

        Trace(SessionContext from, SessionContext target) {
            this.target = target;
            this.name = ((from == null) ? "-" : from.getDisplayName()) + " -> " + target.getDisplayName();
        }

        void mark(String what, double now) {
            marks.append(String.format(" %s@%d", what, ms(now)));
        }

        long ms(double now) {
            return Math.round((now - t0) * 1000);
        }
    }

    public static boolean chat() {
        return chat;
    }

    public static void chat(boolean on) {
        chat = on;
    }

    public static List<String> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /** A switch from {@code from} (may be null) to {@code to} was asked for. */
    public static void begin(SessionContext from, SessionContext to) {
        Trace old = cur;
        if (old != null)
            finish(old, Utils.rtime(), "superseded");
        cur = new Trace(from, to);
    }

    /** A step of the switch path happened. */
    public static void mark(String what) {
        Trace t = cur;
        if (t != null) {
            synchronized (t) {
                t.mark(what, Utils.rtime());
            }
        }
    }

    /** One frame was drawn with {@code ui} on screen. Called on the UI loop's thread. */
    static void frame(UI ui, double now) {
        Trace t = cur;
        if (t == null)
            return;
        synchronized (t) {
            t.frames++;
            if (t.lastFrame >= 0)
                t.maxGap = Math.max(t.maxGap, now - t.lastFrame);
            t.lastFrame = now;
            boolean game = (ui != null) && (ui == t.target.ui);
            if (!game) {
                t.otherFrames++;
            } else {
                t.gameFrames++;
                NGameUI gui = t.target.getGameUI();
                int[] fill = (gui == null || gui.map == null) ? null : gui.map.terrainfill();
                if (fill != null) {
                    if (fill[0] > t.lastDrawn)
                        t.lastGain = now;
                    t.lastDrawn = fill[0];
                    t.lastWanted = fill[1];
                }
                if (t.firstGame < 0) {
                    t.firstGame = now;
                    t.mark("game-frame", now);
                    if (fill != null) {
                        t.fillAtFirstGame = fill[0];
                        t.wantedAtFirstGame = fill[1];
                    }
                }
                if (fill != null && fill[1] > 0 && fill[0] >= fill[1]) {
                    t.firstFill = now;
                    finish(t, now, null);
                    return;
                }
            }
            if (now - t.t0 > TIMEOUT)
                finish(t, now, "timed out");
        }
    }

    private static void finish(Trace t, double now, String why) {
        if (cur == t)
            cur = null;
        String line = String.format("switch %s: %s; frames before the switch %d, game frames %d, longest frame %d ms; " +
                                    "first game frame +%s ms with %s cuts; all %d cuts %s;%s",
                                    t.name, (why == null) ? "done" : why, t.otherFrames, t.gameFrames,
                                    Math.round(t.maxGap * 1000),
                                    (t.firstGame < 0) ? "-" : String.valueOf(t.ms(t.firstGame)),
                                    (t.wantedAtFirstGame < 0) ? "?" : (t.fillAtFirstGame + "/" + t.wantedAtFirstGame),
                                    t.lastWanted,
                                    (t.firstFill < 0)
                                        ? ("never (" + t.lastDrawn + "/" + t.lastWanted + ", last cut +"
                                           + ((t.lastGain < 0) ? "-" : String.valueOf(t.ms(t.lastGain))) + " ms)")
                                        : ("+" + t.ms(t.firstFill) + " ms"),
                                    t.marks);
        synchronized (history) {
            history.add(line);
            while (history.size() > HISTORY)
                history.remove(0);
        }
        FileLogger.log("[Commander switch] " + line);
        if (chat) {
            NGameUI gui = t.target.getGameUI();
            if (gui != null)
                gui.msg("[switch] " + line);
        }
    }
}
