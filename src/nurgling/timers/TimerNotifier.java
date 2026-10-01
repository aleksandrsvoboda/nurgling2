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
import nurgling.todo.TodoItem;
import nurgling.todo.TodoStore;
import nurgling.widgets.timers.TimerBanners;

import java.util.ArrayList;
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
            attention = !openTasksOnly(store.due(ms), ms).isEmpty();
            return;
        }
        attention = false;
        if((Boolean) NConfig.get(NConfig.Key.timerCombatQuiet) && inCombat())
            return;   // the banners wait for the fight to end

        // A heads-up is quiet: no sound, no flashing, just the banner.
        List<Timer> soon = openTasksOnly(store.dueSoon(ms, headsUpMs()), ms);
        if(!soon.isEmpty()) {
            store.markSoon(soon);
            if(gui.timerBanners != null)
                gui.timerBanners.postDeadlines(TimerBanners.Type.TASK_SOON, soon, false);
        }

        List<Timer> due = openTasksOnly(store.due(ms), ms);
        if(due.isEmpty()) {
            firstPass = false;
            return;
        }
        store.markNotified(due);
        boolean away = firstPass && due.stream().allMatch(t -> t.readyAt() < loginAt);
        firstPass = false;

        if(gui.timerBanners != null) {
            List<Timer> tasks = new ArrayList<>();
            List<Timer> others = new ArrayList<>();
            for(Timer t : due)
                (t.kind == Timer.Kind.TASK ? tasks : others).add(t);
            if(!others.isEmpty())
                gui.timerBanners.post(others, away);
            if(!tasks.isEmpty())
                gui.timerBanners.postDeadlines(TimerBanners.Type.TASK_DUE, tasks, away);
        }
        playSound(due);
        if((Boolean) NConfig.get(NConfig.Key.timerFlashTaskbar))
            requestAttention();
    }

    /**
     * Drop task deadlines whose task is no longer open - finished a moment ago, before the deadline was
     * cleaned up - and all of them while the To-Do list has not finished loading.
     */
    private List<Timer> openTasksOnly(List<Timer> timers, long now) {
        TodoStore todo = gui.todoStore;
        boolean settled = todo != null && todo.settled();
        List<Timer> out = new ArrayList<>();
        for(Timer t : timers) {
            if(t.kind != Timer.Kind.TASK) {
                out.add(t);
                continue;
            }
            TodoItem it = settled ? todo.view().items.get(t.taskId) : null;
            if(it != null && it.isOpen(now))
                out.add(t);
        }
        return out;
    }

    private static long headsUpMs() {
        Object v = NConfig.get(NConfig.Key.taskHeadsUpMinutes);
        return (v instanceof Number) ? ((Number) v).longValue() * 60_000L : 0;
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
