package nurgling.widgets.timers;

import haven.*;
import nurgling.NGameUI;
import nurgling.i18n.L10n;
import nurgling.timers.Timer;
import nurgling.timers.TimerDurations;
import nurgling.timers.TimerPlacement;
import nurgling.timers.TimerStore;
import nurgling.todo.TodoItem;
import nurgling.todo.TodoList;
import nurgling.todo.TodoStore;
import nurgling.widgets.cookbook.CookbookTheme;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Banners that drop in at the top of the screen, phone-notification style: timers that became ready, and
 * To-Do news - a task assigned to you, a deadline coming up or reached, a task of yours someone finished.
 *
 * <p>The newest banner shows in full with the older ones peeking out underneath; clicking the peek expands
 * the stack. A banner stays until the player clicks one of its buttons, or until what it is about is dealt
 * with somewhere else - a timer dismissed in the timers panel, a task ticked off in the To-Do window.
 *
 * <p>The widget is only as big as the banners it shows, so everywhere else clicks reach the map as usual.
 */
public class TimerBanners extends Widget {
    private static final int W = UI.scale(310);
    private static final int PAD = UI.scale(8);
    private static final int GAP = UI.scale(5);
    private static final int BTN_H = UI.scale(20);
    private static final int PEEK = UI.scale(7);
    private static final int TOP = UI.scale(46);
    private static final int MAX_EXPANDED = 5;
    private static final long TASK_SNOOZE_MS = 60 * 60 * 1000L;
    private static final long TIMER_SNOOZE_MS = 15 * 60 * 1000L;
    private static final Color BG = new Color(0x1C, 0x25, 0x26, 0xF4);
    private static final Color DUE_FRAME = new Color(224, 87, 76);
    private static final Color DUE_TEXT = new Color(255, 138, 128);

    /** What a banner is about. */
    public enum Type {
        /** Timers that became ready; ids are timer ids. */
        TIMERS,
        /** Task deadlines that were reached; ids are timer ids. */
        TASK_DUE,
        /** Task deadlines coming up; ids are timer ids. */
        TASK_SOON,
        /** Tasks newly assigned to this player; ids are task ids. */
        TASK_ASSIGNED,
        /** This player's tasks that someone else finished; ids are task ids. */
        TASK_DONE
    }

    private static final class Banner {
        final Type type;
        final List<String> ids;
        final boolean away;

        Banner(Type type, List<String> ids, boolean away) {
            this.type = type;
            this.ids = ids;
            this.away = away;
        }
    }

    /** A clickable rectangle laid out during the last draw. */
    private static final class Hit {
        final Coord ul, sz;
        final Runnable action;

        Hit(Coord ul, Coord sz, Runnable action) {
            this.ul = ul;
            this.sz = sz;
            this.action = action;
        }
    }

    /** One button on a banner. */
    private static final class Action {
        final String label;
        final boolean primary;
        final Runnable run;

        Action(String label, boolean primary, Runnable run) {
            this.label = label;
            this.primary = primary;
            this.run = run;
        }
    }

    /** What a banner says right now, worked out from the live timers and tasks on every draw. */
    private static final class Content {
        String kind;
        Color kindColor = CookbookTheme.muted;
        Color frame = CookbookTheme.accent;
        Timer iconTimer;
        long since;
        String title;
        String sub;
        final List<Action> actions = new ArrayList<>();
    }

    private final List<Banner> banners = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private boolean expanded = false;

    public TimerBanners() {
        super(Coord.z);
    }

    private NGameUI gui() {
        return (NGameUI) parent;
    }

    private TimerStore store() {
        return gui().timerStore;
    }

    /** Raise a banner for timers that just became ready. */
    public void post(List<Timer> due, boolean away) {
        add(Type.TIMERS, idsOf(due), away);
    }

    /** Raise a banner for task deadlines that were reached ({@link Type#TASK_DUE}) or are close ({@link Type#TASK_SOON}). */
    public void postDeadlines(Type type, List<Timer> deadlines, boolean away) {
        add(type, idsOf(deadlines), away);
    }

    /** Raise a banner about tasks: newly assigned to this player, or theirs and finished by someone else. */
    public void postTasks(Type type, List<TodoItem> tasks) {
        List<String> ids = new ArrayList<>();
        for(TodoItem it : tasks)
            ids.add(Integer.toString(it.id));
        add(type, ids, false);
    }

    private static List<String> idsOf(List<Timer> timers) {
        List<String> ids = new ArrayList<>();
        for(Timer t : timers)
            ids.add(t.id);
        return ids;
    }

    private void add(Type type, List<String> ids, boolean away) {
        banners.add(0, new Banner(type, ids, away));
        expanded = false;
        raise();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        long ms = System.currentTimeMillis();
        TimerStore store = store();
        TodoStore todo = gui().todoStore;
        TodoStore.View view = (todo == null) ? null : todo.view();
        for(java.util.Iterator<Banner> it = banners.iterator(); it.hasNext(); ) {
            Banner b = it.next();
            // Dealt with somewhere else - the timers panel, the To-Do window, another banner, a villager.
            b.ids.removeIf(id -> stale(b.type, id, store, todo, view, ms));
            if(b.ids.isEmpty())
                it.remove();
        }
        if(banners.size() <= 1)
            expanded = false;
        resize(Coord.of(W, banners.isEmpty() ? 0 : height()));
        if(parent != null)
            c = Coord.of((parent.sz.x - W) / 2, TOP);
    }

    private static boolean stale(Type type, String id, TimerStore store, TodoStore todo, TodoStore.View view, long ms) {
        switch(type) {
            case TASK_ASSIGNED: {
                TodoItem it = (view == null) ? null : view.items.get(Integer.parseInt(id));
                return it == null || !it.isOpen(ms) || !todo.isMine(it.assignee);
            }
            case TASK_DONE:
                return view == null || !view.items.containsKey(Integer.parseInt(id));
            default: {
                Timer t = store.get(id);
                if(t == null)
                    return true;
                TimerStore.Local l = store.local(id);
                if(l.ackedStart == t.startedAt || l.snoozeUntil > ms)
                    return true;
                if(t.kind == Timer.Kind.TASK) {
                    TodoItem it = (view == null) ? null : view.items.get(t.taskId);
                    if(it == null || !it.isOpen(ms))
                        return true;
                }
                // A heads-up gives way to the due banner; a ready banner to a restart.
                return (type == Type.TASK_SOON) == t.isReady(ms);
            }
        }
    }

    private int height() {
        if(expanded) {
            int h = 0;
            for(int i = 0; i < Math.min(banners.size(), MAX_EXPANDED); i++)
                h += bannerHeight() + GAP;
            return h;
        }
        int peeks = Math.min(banners.size() - 1, 2);
        int h = bannerHeight() + peeks * PEEK;
        if(banners.size() > 1)
            h += CookbookTheme.small.height() + UI.scale(4);
        return h;
    }

    private static int bannerHeight() {
        return PAD + CookbookTheme.small.height() + UI.scale(3) + CookbookTheme.bold.height()
            + CookbookTheme.small.height() + UI.scale(6) + BTN_H + PAD;
    }

    @Override
    public void draw(GOut g) {
        hits.clear();
        if(banners.isEmpty())
            return;
        if(expanded) {
            int y = 0;
            for(int i = 0; i < Math.min(banners.size(), MAX_EXPANDED); i++) {
                drawBanner(g, banners.get(i), y);
                y += bannerHeight() + GAP;
            }
            return;
        }
        int peeks = Math.min(banners.size() - 1, 2);
        int bh = bannerHeight();
        for(int i = peeks; i >= 1; i--) {
            int inset = UI.scale(6) * i;
            Coord ul = Coord.of(inset, bh + (i - 1) * PEEK - UI.scale(2));
            Coord sz = Coord.of(W - inset * 2, PEEK + UI.scale(2));
            CookbookTheme.fill(g, ul, sz, BG);
            CookbookTheme.frame(g, ul, sz, new Color(233, 156, 84, 150 - i * 40));
        }
        drawBanner(g, banners.get(0), 0);
        if(banners.size() > 1) {
            String more = L10n.get("timers.banner.more", banners.size() - 1);
            Tex t = TimerIcons.text(CookbookTheme.small, more, CookbookTheme.muted);
            int y = bh + peeks * PEEK + UI.scale(2);
            g.image(t, Coord.of((W - t.sz().x) / 2, y));
            hits.add(new Hit(Coord.of(0, bh), Coord.of(W, sz.y - bh), () -> expanded = true));
        }
    }

    private void drawBanner(GOut g, Banner b, int y) {
        long now = System.currentTimeMillis();
        Content c = content(b, now);
        if(c == null)
            return;
        int h = bannerHeight();
        CookbookTheme.fill(g, Coord.of(0, y), Coord.of(W, h), BG);
        CookbookTheme.frame(g, Coord.of(0, y), Coord.of(W, h), c.frame);

        // Header: icon, kind, age, close
        int iy = y + PAD;
        int icon = CookbookTheme.small.height() + UI.scale(2);
        Coord iul = Coord.of(PAD, iy - UI.scale(1));
        if(c.iconTimer != null)
            TimerIcons.drawKindIcon(g, c.iconTimer, iul, icon);
        else
            TimerIcons.drawTaskIcon(g, iul, icon);
        g.image(TimerIcons.text(CookbookTheme.small, c.kind.toUpperCase(), c.kindColor), Coord.of(PAD + icon + UI.scale(5), iy));
        Tex close = TimerIcons.text(CookbookTheme.bold, "✕", CookbookTheme.fg);
        Coord cul = Coord.of(W - PAD - close.sz().x, iy - UI.scale(2));
        g.image(close, cul);
        hits.add(new Hit(cul.sub(UI.scale(3), UI.scale(3)), close.sz().add(UI.scale(6), UI.scale(6)), () -> dismissAll(b)));
        if(c.since >= 0) {
            Tex ageTex = TimerIcons.text(CookbookTheme.small, L10n.get("timers.ago", TimerDurations.format(c.since)), CookbookTheme.muted);
            g.image(ageTex, Coord.of(cul.x - UI.scale(8) - ageTex.sz().x, iy));
        }

        // Title and detail line
        int ty = iy + CookbookTheme.small.height() + UI.scale(3);
        g.image(TimerIcons.text(CookbookTheme.bold, CookbookTheme.ellipsize(CookbookTheme.bold, c.title, W - PAD * 2), CookbookTheme.fg),
            Coord.of(PAD, ty));
        int sy = ty + CookbookTheme.bold.height();
        g.image(TimerIcons.text(CookbookTheme.small, CookbookTheme.ellipsize(CookbookTheme.small, c.sub, W - PAD * 2), CookbookTheme.muted),
            Coord.of(PAD, sy));

        // Actions
        int by = sy + CookbookTheme.small.height() + UI.scale(6);
        int bx = PAD;
        for(Action a : c.actions)
            bx = button(g, bx, by, a);
    }

    /** What to show for a banner, or null when nothing it was about is left. */
    private Content content(Banner b, long now) {
        switch(b.type) {
            case TIMERS: return timersContent(b, now);
            case TASK_DUE: return dueContent(b, now);
            case TASK_SOON: return soonContent(b, now);
            default: return taskNewsContent(b, now);
        }
    }

    private List<Timer> timersOf(Banner b) {
        List<Timer> out = new ArrayList<>();
        for(String id : b.ids) {
            Timer t = store().get(id);
            if(t != null)
                out.add(t);
        }
        return out;
    }

    private Content timersContent(Banner b, long now) {
        List<Timer> timers = timersOf(b);
        if(timers.isEmpty())
            return null;
        TimerStore store = store();
        Timer first = timers.get(0);
        Content c = new Content();
        c.iconTimer = first;
        c.since = now - first.readyAt();
        if(timers.size() == 1) {
            Timer t = first;
            c.kind = L10n.get("timers.banner.kind_" + t.kind.key());
            c.title = L10n.get("timers.banner.ready", displayName(t));
            c.sub = (b.away ? L10n.get("timers.banner.away_one") + " · " : "") + detailLine(t, now);
            if(t.hasLocation())
                c.actions.add(new Action(L10n.get("timers.action.show_on_map"), true, () -> {
                    if(!TimerPlacement.showOnMap(gui(), t))
                        gui().msg(L10n.get("timers.not_on_map"));
                }));
            c.actions.add(new Action(L10n.get("timers.action.restart", TimerDurations.formatShort(t.durationMs)), !t.hasLocation(), () -> {
                store.restart(t.id, System.currentTimeMillis());
                store.rememberDuration(TimerStore.durationKey(t.kind, t.resType), t.durationMs);
            }));
            c.actions.add(new Action(L10n.get("timers.action.snooze"), false,
                () -> store.snooze(t.id, System.currentTimeMillis() + TIMER_SNOOZE_MS)));
            c.actions.add(new Action(L10n.get("timers.action.dismiss"), false, () -> dismissAll(b)));
        } else {
            c.kind = L10n.get("timers.banner.kind_many");
            c.title = b.away ? L10n.get("timers.banner.away", timers.size()) : L10n.get("timers.banner.ready_many", timers.size());
            c.sub = joinNames(timers);
            c.actions.add(new Action(L10n.get("timers.action.open"), true, () -> gui().showTimersPanel()));
            c.actions.add(new Action(L10n.get("timers.action.dismiss_all"), false, () -> dismissAll(b)));
        }
        return c;
    }

    private Content dueContent(Banner b, long now) {
        List<Timer> due = timersOf(b);
        if(due.isEmpty())
            return null;
        Timer first = due.get(0);
        Content c = new Content();
        c.kind = L10n.get("tasks.banner.kind_due");
        c.kindColor = DUE_TEXT;
        c.frame = DUE_FRAME;
        c.since = now - first.readyAt();
        if(due.size() == 1) {
            Timer d = first;
            TodoItem it = task(d.taskId);
            String title = (it == null) ? d.name : it.title;
            boolean nobody = d.assignee.isEmpty();
            c.title = nobody ? L10n.get("tasks.banner.due_nobody", title) : L10n.get("tasks.banner.due", title);
            c.sub = (b.away ? L10n.get("timers.banner.away_one") + " · " : "") + taskOrigin(it);
            TodoStore todo = gui().todoStore;
            boolean editable = it != null && todo.canEdit(it.listId);
            if(nobody && editable)
                c.actions.add(new Action(L10n.get("tasks.action.take"), true, () -> todo.setAssignee(d.taskId, todo.me())));
            else if(editable)
                c.actions.add(new Action(L10n.get("tasks.action.done"), true, () -> todo.setDone(d.taskId, true)));
            c.actions.add(new Action(L10n.get("timers.action.open"), !editable, () -> gui().openTodoTask(d.taskId)));
            c.actions.add(new Action(L10n.get("tasks.action.snooze"), false,
                () -> store().snooze(d.id, System.currentTimeMillis() + TASK_SNOOZE_MS)));
            c.actions.add(new Action(L10n.get("timers.action.dismiss"), false, () -> dismissAll(b)));
        } else {
            c.title = L10n.get("tasks.banner.due_many", due.size());
            c.sub = joinTaskTitles(due);
            c.actions.add(new Action(L10n.get("timers.action.open"), true, () -> gui().openTodoTask(first.taskId)));
            c.actions.add(new Action(L10n.get("timers.action.dismiss_all"), false, () -> dismissAll(b)));
        }
        return c;
    }

    private Content soonContent(Banner b, long now) {
        List<Timer> soon = timersOf(b);
        if(soon.isEmpty())
            return null;
        Timer first = soon.get(0);
        Content c = new Content();
        c.kind = L10n.get("tasks.banner.kind_soon");
        c.since = -1;
        if(soon.size() == 1) {
            TodoItem it = task(first.taskId);
            c.title = L10n.get("tasks.banner.soon", (it == null) ? first.name : it.title, TimerDurations.format(first.remaining(now)));
            c.sub = L10n.get("timers.at", TimerDurations.formatClock(first.readyAt(), L10n.get("timers.tomorrow")))
                + " · " + taskOrigin(it);
        } else {
            c.title = L10n.get("tasks.banner.soon_many", soon.size());
            c.sub = joinTaskTitles(soon);
        }
        c.actions.add(new Action(L10n.get("timers.action.open"), true, () -> gui().openTodoTask(first.taskId)));
        c.actions.add(new Action(L10n.get("timers.action.dismiss"), false, () -> banners.remove(b)));
        return c;
    }

    private Content taskNewsContent(Banner b, long now) {
        List<TodoItem> tasks = new ArrayList<>();
        for(String id : b.ids) {
            TodoItem it = task(Integer.parseInt(id));
            if(it != null)
                tasks.add(it);
        }
        if(tasks.isEmpty())
            return null;
        TodoItem first = tasks.get(0);
        boolean assigned = b.type == Type.TASK_ASSIGNED;
        Content c = new Content();
        c.kind = L10n.get(assigned ? "tasks.banner.kind_assigned" : "tasks.banner.kind_done");
        c.since = now - (assigned ? first.touchedAt : first.doneAt);
        if(tasks.size() == 1) {
            if(assigned) {
                c.title = L10n.get("tasks.banner.assigned", who(first.touchedBy), first.title);
                Timer d = nurgling.todo.TaskDeadlines.find(store(), first.id);
                String due = (d == null) ? L10n.get("tasks.no_deadline")
                    : L10n.get("tasks.due_in", TimerDurations.format(d.remaining(now)),
                        TimerDurations.formatClock(d.readyAt(), L10n.get("timers.tomorrow")));
                c.sub = listName(first) + " · " + due;
            } else {
                c.title = L10n.get("tasks.banner.done", who(first.doneBy), first.title);
                c.sub = listName(first);
            }
        } else {
            c.title = L10n.get(assigned ? "tasks.banner.assigned_many" : "tasks.banner.done_many", tasks.size());
            List<String> titles = new ArrayList<>();
            for(TodoItem it : tasks)
                titles.add(it.title);
            c.sub = String.join(", ", titles);
        }
        c.actions.add(new Action(L10n.get("timers.action.open"), true, () -> gui().openTodoTask(first.id)));
        c.actions.add(new Action(L10n.get("timers.action.dismiss"), false, () -> banners.remove(b)));
        return c;
    }

    private TodoItem task(int id) {
        TodoStore todo = gui().todoStore;
        return (todo == null) ? null : todo.view().items.get(id);
    }

    private String listName(TodoItem it) {
        TodoList l = gui().todoStore.view().list(it.listId);
        return (l == null) ? "" : l.name;
    }

    /** "from Olga · Village" */
    private String taskOrigin(TodoItem it) {
        if(it == null)
            return "";
        return L10n.get("tasks.from", who(it.createdBy)) + " · " + listName(it);
    }

    private String who(String name) {
        return TimerStore.isLocalCharacter(name) ? L10n.get("timers.by_you") : name;
    }

    private String joinTaskTitles(List<Timer> deadlines) {
        List<String> titles = new ArrayList<>();
        for(Timer d : deadlines) {
            TodoItem it = task(d.taskId);
            titles.add((it == null) ? d.name : it.title);
        }
        return String.join(", ", titles);
    }

    private static String joinNames(List<Timer> timers) {
        List<String> names = new ArrayList<>();
        for(Timer t : timers)
            names.add(displayName(t));
        return String.join(", ", names);
    }

    private int button(GOut g, int x, int y, Action a) {
        Tex t = TimerIcons.text(CookbookTheme.small, a.label, a.primary ? CookbookTheme.ink : CookbookTheme.accent);
        Coord sz = Coord.of(t.sz().x + UI.scale(14), BTN_H);
        Coord ul = Coord.of(x, y);
        if(a.primary)
            CookbookTheme.fill(g, ul, sz, CookbookTheme.accent);
        CookbookTheme.frame(g, ul, sz, CookbookTheme.accent);
        g.image(t, ul.add((sz.x - t.sz().x) / 2, (sz.y - t.sz().y) / 2));
        hits.add(new Hit(ul, sz, a.run));
        return x + sz.x + UI.scale(6);
    }

    /** The ✕ and Dismiss: timers and deadlines are dismissed for this player; task news just goes away. */
    private void dismissAll(Banner b) {
        if(b.type == Type.TIMERS || b.type == Type.TASK_DUE) {
            long now = System.currentTimeMillis();
            for(String id : new ArrayList<>(b.ids))
                store().dismiss(id, now);
        }
        banners.remove(b);
    }

    static String displayName(Timer t) {
        if(!t.name.isEmpty())
            return t.name;
        return L10n.get("timers.banner.kind_" + t.kind.key());
    }

    /** "set by Olga 6h ago" for one timer. */
    static String detailLine(Timer t, long now) {
        String who = TimerStore.isLocalCharacter(t.setBy) ? L10n.get("timers.by_you") : t.setBy;
        return L10n.get("timers.set_ago", who, TimerDurations.format(now - t.startedAt));
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        for(int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if(ev.c.isect(h.ul, h.sz)) {
                h.action.run();
                return true;
            }
        }
        // A click on the banner itself is swallowed so it never walks the character somewhere.
        return ev.c.isect(Coord.z, sz);
    }
}
