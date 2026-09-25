package nurgling.widgets.todo;

import haven.*;
import nurgling.todo.TodoItem;
import nurgling.todo.TodoList;
import nurgling.todo.TodoStore;
import nurgling.widgets.NTextArea;
import nurgling.widgets.cookbook.CookbookTheme;
import nurgling.widgets.cookbook.HintTextEntry;
import nurgling.widgets.cookbook.PillButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Details of the selected task, docked under the list. Every change saves at once as its own edit;
 * there is no Save button. Text fields commit on Enter, on losing focus, or after a pause in typing.
 */
public class TodoEditor extends Widget {
    public static final int H = UI.scale(236);
    private static final int LABELW = UI.scale(62);
    private static final int ROW = UI.scale(26);
    private static final long NOTES_IDLE_MS = 1500;
    static final int[] REPEAT_HOURS = {0, 6, 12, 24, 72, 168};

    public interface Host {
        TodoStore store();
        void openMenu(Widget anchor, List<String> options, boolean[] disabled, IntConsumer onPick);
        List<String> assigneeCandidates();
        void captureHere(TodoItem it);
        void showOnMap(TodoItem it);
        void deleteWithUndo(TodoItem it);
    }

    private final Host host;
    private final TexCache tc = new TexCache();
    private final HintTextEntry title;
    private final NTextArea notes;
    private final TodoChoice assignee, list, repeat;
    private final PillButton take, here, show, clearLoc, delete;
    private final UrgentBox urgent;
    private TodoItem cur = null;
    private boolean editable = true;
    private boolean syncing = false;
    private long notesEditAt = 0;
    private String locText = "";
    private String meta = "";

    public TodoEditor(int w, Host host) {
        super(Coord.of(w, H));
        this.host = host;
        int fx = LABELW + UI.scale(4);
        int fw = w - fx - UI.scale(6);
        int y = UI.scale(6);

        title = add(new HintTextEntry(fw, "Task title", null) {
            @Override
            public void activate(String text) {
                commitTitle();
            }

            @Override
            public void lostfocus() {
                super.lostfocus();
                commitTitle();
            }
        }, Coord.of(fx, y));
        y += ROW;

        notes = add(new NTextArea(Coord.of(fw, UI.scale(44)), ""), Coord.of(fx, y));
        notes.onchange = () -> {
            if (!syncing)
                notesEditAt = System.currentTimeMillis();
        };
        notes.oncommit = this::commitNotes;
        y += UI.scale(50);

        int takeW = UI.scale(64);
        assignee = add(new TodoChoice(fw - takeW - UI.scale(4), this::openAssignee), Coord.of(fx, y + UI.scale(2)));
        take = add(new PillButton("Take it", false, () -> {
            if (cur != null)
                host.store().setAssignee(cur.id, host.store().me());
        }).minWidth(takeW), Coord.of(fx + fw - takeW, y));
        y += ROW;

        list = add(new TodoChoice(fw, this::openList), Coord.of(fx, y + UI.scale(2)));
        y += ROW;

        repeat = add(new TodoChoice(fw, this::openRepeat), Coord.of(fx, y + UI.scale(2)));
        y += ROW;

        int bw = UI.scale(50);
        clearLoc = add(new PillButton("Clear", false, () -> {
            if (cur != null)
                host.store().clearLocation(cur.id);
        }).minWidth(bw), Coord.of(fx + fw - bw, y));
        show = add(new PillButton("Show", false, () -> {
            if (cur != null)
                host.showOnMap(cur);
        }).minWidth(bw), Coord.of(fx + fw - 2 * bw - UI.scale(4), y));
        here = add(new PillButton("Here", false, () -> {
            if (cur != null)
                host.captureHere(cur);
        }).minWidth(bw), Coord.of(fx + fw - 3 * bw - UI.scale(8), y));
        here.settip("Set the location to where you are standing");
        y += ROW + UI.scale(2);

        urgent = add(new UrgentBox(), Coord.of(fx, y + UI.scale(5)));
        delete = add(new PillButton("Delete", false, () -> {
            if (cur != null)
                host.deleteWithUndo(cur);
        }).minWidth(UI.scale(64)), Coord.of(fx + fw - UI.scale(64), y));
    }

    public int itemId() {
        return cur == null ? 0 : cur.id;
    }

    /** Shows a task. Fields the user is typing in are left alone unless a different task is shown. */
    public void show(TodoItem it, boolean editable, long now) {
        boolean switched = cur == null || it == null || cur.id != it.id;
        if (switched)
            flushText();
        cur = it;
        this.editable = editable;
        if (it == null)
            return;
        syncing = true;
        try {
            if (switched || (!title.hasfocus && !title.text().equals(it.title)))
                title.settext(it.title);
            if (switched || (!notes.hasfocus && notesEditAt == 0 && !notes.text().equals(it.notes)))
                notes.settext(it.notes);
        } finally {
            syncing = false;
        }
        if (switched)
            notesEditAt = 0;
        assignee.set(it.assignee.isEmpty() ? "anyone" : (it.assignee.equals(host.store().me()) ? it.assignee + " (me)" : it.assignee));
        TodoList l = host.store().view().list(it.listId);
        list.set(l == null ? "" : l.name);
        repeat.set(it.repeatH <= 0 ? "never" : "every " + repeatLabel(it.repeatH));
        locText = it.hasLoc ? "grid " + it.locGrid + " (" + it.locX + ", " + it.locY + ")" : "none";
        StringBuilder m = new StringBuilder();
        if (!it.createdBy.isEmpty())
            m.append("Created by ").append(it.createdBy).append(" · ").append(TodoWindow.ago(it.createdAt, now));
        if (!it.touchedBy.isEmpty() && it.touchedAt != it.createdAt)
            m.append(m.length() > 0 ? "     " : "").append("Last change: ").append(it.touchedBy).append(" · ").append(TodoWindow.ago(it.touchedAt, now));
        meta = m.toString();

        for (TodoChoice c : Arrays.asList(assignee, list, repeat))
            c.enabled = editable;
        take.visible = editable && !it.isAssignedTo(host.store().me());
        here.visible = editable;
        show.visible = it.hasLoc;
        clearLoc.visible = editable && it.hasLoc;
        delete.visible = editable;
        urgent.visible = true;
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (notesEditAt > 0 && System.currentTimeMillis() - notesEditAt >= NOTES_IDLE_MS)
            commitNotes();
    }

    /** Saves whatever is typed but not yet committed; before switching task or closing. */
    public void flushText() {
        commitTitle();
        commitNotes();
    }

    private void commitTitle() {
        if (cur == null || !editable || syncing)
            return;
        String t = title.text().trim();
        if (!t.isEmpty() && !t.equals(cur.title))
            host.store().setTitle(cur.id, t);
    }

    private void commitNotes() {
        notesEditAt = 0;
        if (cur == null || !editable || syncing)
            return;
        String n = notes.text();
        if (!n.equals(cur.notes))
            host.store().setNotes(cur.id, n);
    }

    private void openAssignee() {
        if (cur == null)
            return;
        List<String> names = host.assigneeCandidates();
        List<String> opts = new ArrayList<>();
        opts.add("anyone");
        opts.addAll(names);
        int id = cur.id;
        host.openMenu(assignee, opts, null, i -> host.store().setAssignee(id, i == 0 ? "" : names.get(i - 1)));
    }

    private void openList() {
        if (cur == null)
            return;
        List<TodoList> lists = host.store().view().lists;
        List<String> opts = new ArrayList<>();
        for (TodoList l : lists)
            opts.add(l.name);
        int id = cur.id;
        host.openMenu(list, opts, null, i -> host.store().moveToList(id, lists.get(i).id));
    }

    private void openRepeat() {
        if (cur == null)
            return;
        List<String> opts = new ArrayList<>();
        for (int h : REPEAT_HOURS)
            opts.add(h == 0 ? "never" : "every " + repeatLabel(h));
        int id = cur.id;
        host.openMenu(repeat, opts, null, i -> host.store().setRepeat(id, REPEAT_HOURS[i]));
    }

    static String repeatLabel(int hours) {
        if (hours >= 48 && hours % 24 == 0)
            return (hours / 24) + "d";
        return hours + "h";
    }

    @Override
    public void draw(GOut g) {
        CookbookTheme.fill(g, Coord.z, Coord.of(sz.x, 1), CookbookTheme.accent);
        String[] labels = {"Title", "Notes", "Assignee", "List", "Repeat", "Location"};
        int[] ys = {title.c.y, notes.c.y, assignee.c.y, list.c.y, repeat.c.y, here.c.y};
        for (int i = 0; i < labels.length; i++) {
            Tex t = tc.get(CookbookTheme.body, labels[i], CookbookTheme.muted);
            int rowh = i == 5 ? here.sz.y : UI.scale(19);
            g.image(t, Coord.of(UI.scale(6), ys[i] + (rowh - t.sz().y) / 2));
        }
        int locRight = here.visible ? here.c.x : (show.visible ? show.c.x : sz.x - UI.scale(6));
        Tex lt = tc.get(CookbookTheme.body,
            CookbookTheme.ellipsize(CookbookTheme.body, locText, locRight - title.c.x - UI.scale(6)),
            cur != null && cur.hasLoc ? CookbookTheme.fg : CookbookTheme.muted);
        g.image(lt, Coord.of(title.c.x, here.c.y + (here.sz.y - lt.sz().y) / 2));
        if (!meta.isEmpty()) {
            Tex mt = tc.get(CookbookTheme.small, CookbookTheme.ellipsize(CookbookTheme.small, meta, sz.x - UI.scale(12)), CookbookTheme.muted);
            g.image(mt, Coord.of(UI.scale(6), sz.y - mt.sz().y - UI.scale(4)));
        }
        super.draw(g);
    }

    @Override
    public void dispose() {
        tc.clear();
        super.dispose();
    }

    /** The "Urgent" check box. */
    private class UrgentBox extends Widget {
        private final Tex label = CookbookTheme.render(CookbookTheme.body, "Urgent", CookbookTheme.fg);

        UrgentBox() {
            super(Coord.of(CookbookTheme.BOX + UI.scale(6) + CookbookTheme.body.strsize("Urgent").x, UI.scale(14)));
        }

        @Override
        public void draw(GOut g) {
            int box = CookbookTheme.BOX;
            Coord ul = Coord.of(0, (sz.y - box) / 2);
            CookbookTheme.frame(g, ul, Coord.of(box, box), editable ? CookbookTheme.accent : CookbookTheme.outline);
            if (cur != null && cur.urgent) {
                int in = UI.scale(3);
                CookbookTheme.fill(g, ul.add(in, in), Coord.of(box - 2 * in, box - 2 * in), TodoListView.URGENT);
            }
            g.image(label, Coord.of(box + UI.scale(6), (sz.y - label.sz().y) / 2));
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1 && editable && cur != null) {
                host.store().setUrgent(cur.id, !cur.urgent);
                return true;
            }
            return super.mousedown(ev);
        }
    }
}
