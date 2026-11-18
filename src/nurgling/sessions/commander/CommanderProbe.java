package nurgling.sessions.commander;

import haven.*;
import haven.error.FileLogger;
import nurgling.NGameUI;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.MemberReport;
import nurgling.sessions.squad.SquadExecutor;
import nurgling.sessions.squad.WorldRef;

import java.util.*;

/**
 * {@code :commander} - phase 0 measurements for the commander camera (plan: .lavish/commander-canvas-plan.html).
 * <ul>
 *   <li>{@code trace} - toggles printing every session switch's timing in the chat (always in the log).</li>
 *   <li>{@code switches} - prints the last switches' timings.</li>
 *   <li>{@code far <tiles> [deg]} - moves the camera centre that far from the player; reports at +1, +3, +10 s.</li>
 *   <li>{@code pan <tiles> <seconds> [deg]} - pans out and back and reports frame times, heap and GC.</li>
 *   <li>{@code home} - camera back on the player.</li>
 *   <li>{@code status} - live terrain, remembered ground and height fallbacks right now.</li>
 *   <li>{@code segs} - which map segment (would-be theater) every character is in, and a cross-check for the
 *   characters this view can see.</li>
 * </ul>
 */
public final class CommanderProbe {
    private static final String USAGE = "Usage: commander trace | switches | far <tiles> [deg] | pan <tiles> <seconds> [deg] | home | status | segs";

    private CommanderProbe() {}

    /** One frame was drawn with {@code ui} on screen. Called by the UI loop after every frame. */
    public static void frame(UI ui, double now) {
        SwitchTrace.frame(ui, now);
        CameraProbe.frame(ui, now);
        CommanderRun.frame(now);
    }

    public static void run(NGameUI gui, String[] args) {
        if (args.length < 2) {
            gui.msg(USAGE);
            return;
        }
        switch (args[1]) {
            case "trace":
                SwitchTrace.chat(!SwitchTrace.chat());
                gui.msg("commander: switch timings in the chat " + (SwitchTrace.chat() ? "on" : "off"));
                break;
            case "switches":
                List<String> h = SwitchTrace.history();
                if (h.isEmpty())
                    gui.msg("commander: no switch timed yet");
                for (String line : h)
                    gui.msg("[switch] " + line);
                break;
            case "far":
                if (args.length < 3) {
                    gui.msg(USAGE);
                    return;
                }
                CameraProbe.far(gui, Double.parseDouble(args[2]), (args.length > 3) ? Double.parseDouble(args[3]) : 0);
                break;
            case "pan":
                if (args.length < 4) {
                    gui.msg(USAGE);
                    return;
                }
                CameraProbe.pan(gui, Double.parseDouble(args[2]), Double.parseDouble(args[3]),
                                (args.length > 4) ? Double.parseDouble(args[4]) : 0);
                break;
            case "home":
                CameraProbe.home(gui);
                break;
            case "status":
                CameraProbe.say(gui, CameraProbe.status(gui, gui.map.ccfallback));
                break;
            case "segs":
                segs(gui);
                break;
            default:
                gui.msg(USAGE);
        }
    }

    /**
     * Groups the characters by the map segment their own session places them in: each group is what would be one
     * theater. For every other character this view can see, it also checks that this session puts it in the same
     * segment at the same spot as its own session does.
     */
    private static void segs(NGameUI vp) {
        MiniMap.Location sl = SquadExecutor.sessloc(vp);
        Map<String, List<String>> theaters = new LinkedHashMap<>();
        List<String> checks = new ArrayList<>();
        for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            MemberReport r = ctx.squadReport;
            String who = ctx.getDisplayName();
            String key = (r == null) ? "no report yet"
                       : (r.pos == null || r.pos.segPos == null) ? "not on the map yet"
                       : String.format("segment %x", r.pos.segId);
            theaters.computeIfAbsent(key, k -> new ArrayList<>()).add(who + ((ctx.ui == vp.ui) ? " (on screen)" : ""));
            if (r == null || r.pos == null || r.pos.segPos == null || ctx.ui == vp.ui || sl == null)
                continue;
            Gob g = vp.map.glob.oc.getgob(r.plgob);
            if (g == null || g.ngob == null || !SquadExecutor.PLAYER_RES.equals(g.ngob.name))
                continue;
            WorldRef here = WorldRef.of(vp.map.glob.map, sl, g.rc);
            if (here == null || here.segPos == null) {
                checks.add(who + ": seen here, but not placed in this session's segment");
            } else if (here.segId != r.pos.segId) {
                checks.add(String.format("%s: seen here in segment %x, its own session says %x", who, here.segId, r.pos.segId));
            } else {
                checks.add(String.format("%s: same segment, %.1f tiles apart", who,
                                         here.segPos.dist(r.pos.segPos) / MCache.tilesz.x));
            }
        }
        say(vp, String.format("%d theater(s), on screen in segment %s", theaters.size(),
                              (sl == null) ? "?" : String.format("%x", sl.seg.id)));
        for (Map.Entry<String, List<String>> e : theaters.entrySet())
            say(vp, e.getKey() + ": " + String.join(", ", e.getValue()));
        for (String c : checks)
            say(vp, "check " + c);
    }

    private static void say(NGameUI gui, String line) {
        FileLogger.log("[Commander segs] " + line);
        gui.msg("[commander] " + line);
    }
}
