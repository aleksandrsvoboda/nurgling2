package nurgling.timers;

import haven.Utils;
import haven.iosys.tk.AWTToolkit;
import haven.iosys.tk.IndirectToolkit;
import haven.iosys.tk.Windeye;
import nurgling.NAlarmManager;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

import java.util.List;

/**
 * Turns ready timers into banners, a sound and - when the game is in the background - a flashing taskbar
 * button. One per session; only the session on screen announces anything.
 *
 * <p>The store is shared by every session on a world and records which cycle has been announced, so a
 * timer raises one banner however many characters are logged in. A background session whose world has
 * nobody on screen keeps its due timers until the player switches to it, and marks its tab meanwhile
 * ({@link #needsAttention()}).
 */
public class TimerNotifier {
    private static final double INTERVAL = 0.5;
    private static final double MAINTENANCE = 30;

    public static final String SOUND_NONE = "none";
    /** Alarm sounds the client ships that suit a timer; see resources/src/alarm. */
    public static final String[] SOUNDS = {"alarm/question", "alarm/quest", "alarm/curio", "alarm/white", "alarm/alarm", SOUND_NONE};

    private final NGameUI gui;
    private final long loginAt = System.currentTimeMillis();
    private double next = 0;
    private double nextMaintenance = 0;
    /** The first announcement of a session gathers what became ready while the player was logged off. */
    private boolean firstPass = true;
    private volatile boolean attention = false;

    public TimerNotifier(NGameUI gui) {
        this.gui = gui;
    }

    public void tick() {
        double now = Utils.rtime();
        if(now < next)
            return;
        next = now + INTERVAL;
        TimerStore store = gui.timerStore;
        if(store == null)
            return;
        long ms = System.currentTimeMillis();

        if(now >= nextMaintenance) {
            nextMaintenance = now + MAINTENANCE;
            store.prune(ms);
            if(gui.mmap != null)
                store.convertLegacy(gui.mmap.file);
        }

        boolean onScreen = SessionManager.getInstance().getActiveUI() == gui.ui;
        if(!onScreen) {
            attention = !store.due(ms).isEmpty();
            return;
        }
        attention = false;
        if((Boolean) NConfig.get(NConfig.Key.timerCombatQuiet) && inCombat())
            return;   // the banners wait for the fight to end

        List<Timer> due = store.due(ms);
        if(due.isEmpty()) {
            firstPass = false;
            return;
        }
        store.markNotified(due);
        boolean away = firstPass && due.stream().allMatch(t -> t.readyAt() < loginAt);
        firstPass = false;

        if(gui.timerBanners != null)
            gui.timerBanners.post(due, away);
        playSound(due);
        if((Boolean) NConfig.get(NConfig.Key.timerFlashTaskbar))
            requestAttention();
    }

    /** Whether this session's tab should carry the timer dot. */
    public boolean needsAttention() {
        return attention;
    }

    private boolean inCombat() {
        SessionContext ctx = SessionManager.getInstance().findByUI(gui.ui);
        return ctx != null && ctx.isInCombat();
    }

    /** One sound per banner, chosen by the kind of the first timer in it. */
    private static void playSound(List<Timer> due) {
        String res = soundFor(due.get(0).kind);
        if(res != null)
            NAlarmManager.play(res);
    }

    /** The configured alarm resource for a kind, or null for silence. */
    public static String soundFor(Timer.Kind kind) {
        NConfig.Key key;
        switch(kind) {
            case RESOURCE: key = NConfig.Key.timerSoundResource; break;
            case PIN: key = NConfig.Key.timerSoundPin; break;
            default: key = NConfig.Key.timerSoundReminder; break;
        }
        Object v = NConfig.get(key);
        if(!(v instanceof String) || ((String) v).isEmpty() || v.equals(SOUND_NONE))
            return null;
        return (String) v;
    }

    /**
     * Flash the game's taskbar button when the window is not focused. Only the AWT-based toolkits give
     * us a frame to flash; on any other window this does nothing.
     */
    private void requestAttention() {
        Windeye wnd = gui.ui.wnd;
        if(wnd == null || wnd.focused())
            return;
        while(wnd instanceof IndirectToolkit.IndirectWindow)
            wnd = ((IndirectToolkit.IndirectWindow) wnd).bk;
        if(!(wnd instanceof AWTToolkit.AWTWindow))
            return;
        java.awt.Frame frame = ((AWTToolkit.AWTWindow) wnd).frame;
        if(!java.awt.Taskbar.isTaskbarSupported())
            return;
        java.awt.Taskbar tb = java.awt.Taskbar.getTaskbar();
        if(tb.isSupported(java.awt.Taskbar.Feature.USER_ATTENTION_WINDOW))
            tb.requestWindowUserAttention(frame);
    }
}
