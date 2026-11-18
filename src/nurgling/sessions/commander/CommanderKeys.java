package nurgling.sessions.commander;

import haven.KeyBinding;
import haven.KeyMatch;
import nurgling.NGameUI;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionContext;
import nurgling.sessions.squad.MemberReport;
import nurgling.sessions.squad.Squad;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Commander-mode camera and attention keys, checked with the squad's (after focused widgets, before global keys).
 * They are key bindings, so the player can move them in the key settings.
 */
public final class CommanderKeys {
    public static final KeyBinding kb_alert = KeyBinding.get("commander-alert", KeyMatch.forcode(KeyEvent.VK_SPACE, 0));
    public static final KeyBinding kb_center = KeyBinding.get("commander-center", KeyMatch.forcode(KeyEvent.VK_C, 0));
    public static final KeyBinding kb_paint = KeyBinding.get("commander-paint", KeyMatch.forcode(KeyEvent.VK_V, 0));
    public static final KeyBinding kb_theater = KeyBinding.get("commander-theater", KeyMatch.forcode(KeyEvent.VK_TAB, 0));
    public static final KeyBinding kb_idle = KeyBinding.get("commander-idle", KeyMatch.forcode(KeyEvent.VK_I, 0));
    public static final KeyBinding kb_build = KeyBinding.get("commander-build", KeyMatch.forcode(KeyEvent.VK_B, 0));

    private CommanderKeys() {}

    public static boolean key(Commander cm, Squad sq, NGameUI gui, KeyEvent ev) {
        int code = ev.getKeyCode();
        if (kb_alert.key().match(ev)) {
            Alerts.Alert a = cm.jumpToAlert();
            say(gui, (a == null) ? L10n.get("commander.msg.no_alert") : a.text);
            return true;
        }
        if (kb_center.key().match(ev)) {
            if (cm.doubled(code))
                cm.followSelection();
            else
                cm.centerSelection();
            return true;
        }
        if (kb_paint.key().match(ev)) {
            cm.setPainting(!cm.painting());
            return true;
        }
        if (kb_theater.key().match(ev)) {
            cm.nextTheater(gui);
            return true;
        }
        if (kb_build.key().match(ev)) {
            cm.setBuildArmed(!cm.buildArmed());
            if (cm.buildArmed())
                say(gui, L10n.get("commander.msg.build_arm"));
            return true;
        }
        if (kb_idle.key().match(ev)) {
            selectIdle(cm, sq, gui, cm.doubled(code));
            return true;
        }
        /* Shift+1..0: the Commander quick bar's slots. */
        if (ev.isShiftDown() && !ev.isControlDown() && !ev.isAltDown() && code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) {
            int slot = (code == KeyEvent.VK_0) ? 9 : code - KeyEvent.VK_1;
            QuickBar.use(slot, gui);
            return true;
        }
        if (code >= KeyEvent.VK_F5 && code <= KeyEvent.VK_F8 && !ev.isAltDown() && !ev.isShiftDown()) {
            int i = code - KeyEvent.VK_F5;
            if (ev.isControlDown()) {
                cm.mark(i, gui);
                say(gui, L10n.get("commander.msg.mark_set", i + 5));
            } else if (!cm.recallMark(i)) {
                say(gui, L10n.get("commander.msg.mark_empty", i + 5));
            }
            return true;
        }
        return false;
    }

    /** I: select the idle characters of the camera's theater (all theaters when it has none); twice: look at them. */
    static void selectIdle(Commander cm, Squad sq, NGameUI gui, boolean look) {
        Theater t = cm.current(gui);
        List<SessionContext> pool = (t == null) ? sq.candidates() : t.members;
        List<SessionContext> idle = new ArrayList<>();
        for (SessionContext c : pool) {
            MemberReport r = c.squadReport;
            if (r != null && r.status == MemberReport.Status.IDLE)
                idle.add(c);
        }
        if (idle.isEmpty()) {
            say(gui, L10n.get("commander.msg.no_idle"));
            return;
        }
        sq.select(idle, false);
        if (look)
            cm.centerSelection();
        else
            say(gui, L10n.get("commander.msg.idle", idle.size()));
    }

    private static void say(NGameUI gui, String s) {
        if (gui != null && s != null)
            gui.msg(s);
    }
}
