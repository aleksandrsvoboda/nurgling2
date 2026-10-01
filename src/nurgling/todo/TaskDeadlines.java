package nurgling.todo;

import haven.Utils;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.sessions.SessionManager;
import nurgling.timers.Timer;
import nurgling.timers.TimerStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ties To-Do tasks to their deadlines.
 *
 * <p>A deadline is not stored on the task: older clients rebuild a task from the fields they know whenever
 * they edit it and would wipe a new one. It is a {@link Timer.Kind#TASK} timer instead, keyed on the task
 * id, so it gets the timers' banners, sound, snooze and database sync, and older clients never touch it.
 *
 * <p>The price is two records to keep in step. This class does that from the session on screen: a timer
 * whose task was finished or deleted goes, a reassigned or renamed task carries its timer along, and a
 * repeating task's deadline restarts when the task reopens. It also raises the "assigned to you" and
 * "finished" banners, including for things that happened while the player was logged off.
 */
public class TaskDeadlines {
    private static final double INTERVAL = 1.0;
    private static final long HOUR_MS = 3_600_000L;
    /** A task finished longer ago than this is not announced to its creator any more. */
    private static final long DONE_NEWS_MS = 3L * 24 * HOUR_MS;

    private final NGameUI gui;
    private double next = 0;

    public TaskDeadlines(NGameUI gui) {
        this.gui = gui;
    }

    /** The deadline of a task, or null when it has none. */
    public static Timer find(TimerStore timers, int taskId) {
        return timers.get(Timer.taskTimerId(timers.genus(), taskId));
    }

    /**
     * Give a task a deadline {@code ms} from now, or remove it when {@code ms <= 0}. A finished repeating
     * task counts from when it reopens.
     */
    public static void setDue(NGameUI gui, TodoItem it, long ms) {
        TimerStore timers = gui.timerStore;
        String id = Timer.taskTimerId(timers.genus(), it.id);
        if(ms <= 0) {
            timers.remove(id);
            return;
        }
        long now = System.currentTimeMillis();
        long start = it.isOpen(now) ? now : reopenAt(it);
        timers.put(new Timer(id, Timer.Kind.TASK, 0, 0, 0, null, it.title, null, start, ms, 0, gui.chrid,
            it.listId != TodoList.PERSONAL, 0, 0, null, it.id, it.assignee));
        timers.rememberDuration(Timer.Kind.TASK.key(), ms);
    }

    private static long reopenAt(TodoItem it) {
        return it.doneAt + it.repeatH * HOUR_MS;
    }

    public void tick() {
        double t = Utils.rtime();
        if(t < next)
            return;
        next = t + INTERVAL;
        if(gui.todoStore == null || gui.timerStore == null || !gui.todoStore.settled())
            return;
        if(SessionManager.getInstance().getActiveUI() != gui.ui)
            return;
        TodoStore.View view = gui.todoStore.view();
        long now = System.currentTimeMillis();
        reconcile(view, now);
        announce(view, now);
    }

    /** Bring every deadline in line with its task. Idempotent, so two clients doing it at once agree. */
    private void reconcile(TodoStore.View view, long now) {
        TimerStore timers = gui.timerStore;
        /* "Missing" only means "gone" when the view is the database's: with the database off, the To-Do
         * window shows its local lists, and removing every village deadline it cannot see would delete
         * them for everyone once the database is back. An orphan elsewhere is harmless - nothing shows
         * a deadline whose task is not there. Personal tasks never leave this machine, so they are
         * always judged here. */
        boolean authoritative = gui.todoStore.mode() == TodoStore.Mode.DB;
        for(Timer d : timers.timers()) {
            if(d.kind != Timer.Kind.TASK)
                continue;
            TodoItem it = view.items.get(d.taskId);
            if(it == null || it.deleted) {
                if(authoritative || d.taskId < 0)
                    timers.remove(d.id);
            } else if(!it.isOpen(now)) {
                if(it.repeatH <= 0)
                    timers.remove(d.id);
                else if(d.startedAt != reopenAt(it))
                    timers.put(d.restarted(reopenAt(it), d.durationMs));
            } else if(!it.assignee.equals(d.assignee) || !it.title.equals(d.name)) {
                timers.put(d.withTask(it.assignee, it.title));
            }
        }
    }

    /**
     * Banners for tasks newly assigned to this player and for their own tasks someone else finished. The
     * store remembers what was already shown, across sessions and restarts.
     */
    private void announce(TodoStore.View view, long now) {
        TodoStore todo = gui.todoStore;
        boolean doneNews = (Boolean) NConfig.get(NConfig.Key.taskNotifyDone);
        Set<String> current = new HashSet<>();
        for(TodoItem it : view.items.values()) {
            if(it.isOpen(now) && todo.isMine(it.assignee))
                current.add("assigned|" + it.id + "|" + it.assignee);
            if(doneNews && it.done && todo.isMine(it.createdBy) && !todo.isMine(it.doneBy) && now - it.doneAt < DONE_NEWS_MS)
                current.add("done|" + it.id + "|" + it.doneAt);
        }
        List<TodoItem> assigned = new ArrayList<>();
        List<TodoItem> done = new ArrayList<>();
        for(String key : gui.timerStore.newlyAnnounced(current)) {
            String[] parts = key.split("\\|", 3);
            TodoItem it = view.items.get(Integer.parseInt(parts[1]));
            if(it == null)
                continue;
            if(parts[0].equals("done"))
                done.add(it);
            else if(!todo.isMine(it.touchedBy))
                assigned.add(it);   // taking a task yourself is not news
        }
        if(gui.timerBanners == null)
            return;
        if(!assigned.isEmpty())
            gui.timerBanners.postTasks(nurgling.widgets.timers.TimerBanners.Type.TASK_ASSIGNED, assigned);
        if(!done.isEmpty())
            gui.timerBanners.postTasks(nurgling.widgets.timers.TimerBanners.Type.TASK_DONE, done);
    }
}
