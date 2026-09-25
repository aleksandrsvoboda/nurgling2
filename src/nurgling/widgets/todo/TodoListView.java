package nurgling.widgets.todo;

import haven.*;
import nurgling.todo.TodoItem;
import nurgling.widgets.cookbook.CookbookTheme;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The rows of one list: open tasks (urgent first, then in the order people dragged them), then a
 * collapsible "Done" section. Drawn by hand like the cookbook table, so a sync update is only a repaint.
 */
public class TodoListView extends Widget implements Scrollable {
    static final int ROWH = UI.scale(20);
    static final Color URGENT = new Color(224, 106, 79);
    static final Color PENDING = new Color(232, 163, 61);
    private static final int BOXX = UI.scale(6);
    private static final int TEXTX = UI.scale(30);
    private static final int PINW = UI.scale(16);
    private static final int DRAGTHRESH = UI.scale(5);

    public interface Listener {
        void toggle(TodoItem it);
        void select(TodoItem it);
        void context(TodoItem it, Coord at);
        void showOnMap(TodoItem it);
        void reorder(TodoItem it, double order);
        void toggleDoneSection();
        void clearDone();
        boolean isPending(int id);
        boolean canEdit();
        String me();
    }

    /** One drawn line: a task, or the Done header. */
    private static final class Row {
        final TodoItem item;
        final boolean open;
        Row(TodoItem item, boolean open) {
            this.item = item;
            this.open = open;
        }
    }

    private final Listener listener;
    private final TexCache tc = new TexCache();
    private final Scrollbar sb;
    private final List<Row> rows = new ArrayList<>();
    private final List<TodoItem> open = new ArrayList<>();
    private int doneCount = 0;
    private boolean showDone = false;
    private int selectedId = 0;
    private String empty = "";
    private int hover = -1;
    private int scroll = 0;
    private long now = System.currentTimeMillis();

    private TodoItem dragging = null;
    private boolean dragged = false;
    private int dragY = 0;
    private int dropIdx = -1;
    private UI.Grab dgrab = null;

    public TodoListView(Coord sz, Listener listener) {
        super(sz);
        this.listener = listener;
        sb = add(new Scrollbar(sz.y, this));
        sb.c = Coord.of(sz.x - sb.sz.x, 0);
    }

    @Override
    public void resize(Coord nsz) {
        super.resize(nsz);
        sb.resize(Coord.of(sb.sz.x, nsz.y));
        sb.c = Coord.of(nsz.x - sb.sz.x, 0);
        scroll = Utils.clip(scroll, 0, scrollmax());
    }

    public void setRows(List<TodoItem> openItems, List<TodoItem> doneItems, boolean showDone, int selectedId,
                        String empty, long now) {
        if (dragging != null)
            return;
        this.now = now;
        this.showDone = showDone;
        this.selectedId = selectedId;
        this.empty = empty;
        open.clear();
        open.addAll(openItems);
        doneCount = doneItems.size();
        rows.clear();
        for (TodoItem it : openItems)
            rows.add(new Row(it, true));
        if (doneCount > 0) {
            rows.add(new Row(null, false));
            if (showDone)
                for (TodoItem it : doneItems)
                    rows.add(new Row(it, false));
        }
        scroll = Utils.clip(scroll, 0, scrollmax());
    }

    /** Scrolls so the given task is visible. */
    public void reveal(int id) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.item != null && r.item.id == id) {
                int y = i * ROWH;
                if (y < scroll)
                    scroll = y;
                else if (y + ROWH > scroll + sz.y)
                    scroll = y + ROWH - sz.y;
                scroll = Utils.clip(scroll, 0, scrollmax());
                return;
            }
        }
    }

    private int listW() {
        return sb.vis() ? sz.x - sb.sz.x - UI.scale(2) : sz.x;
    }

    @Override
    public void draw(GOut g) {
        CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.bg);
        int w = listW();
        String me = listener.me();
        int first = scroll / ROWH;
        for (int i = first; i < rows.size(); i++) {
            int y = i * ROWH - scroll;
            if (y >= sz.y)
                break;
            Row r = rows.get(i);
            if (r.item == null) {
                drawHeader(g, y, w);
                continue;
            }
            if (i % 2 == 1)
                CookbookTheme.fill(g, Coord.of(0, y), Coord.of(w, ROWH), CookbookTheme.hover);
            if (i == hover || r.item == dragging)
                CookbookTheme.fill(g, Coord.of(0, y), Coord.of(w, ROWH), new Color(233, 156, 84, 26));
            if (r.item.id == selectedId)
                CookbookTheme.fill(g, Coord.of(0, y), Coord.of(UI.scale(2), ROWH), CookbookTheme.accent);
            drawItem(g, r, y, w, me);
        }
        if (rows.isEmpty() && !empty.isEmpty()) {
            Tex t = tc.get(CookbookTheme.body, empty, CookbookTheme.muted);
            g.image(t, Coord.of((w - t.sz().x) / 2, UI.scale(14)));
        }
        if (dragged && dropIdx >= 0) {
            int y = dropIdx * ROWH - scroll;
            CookbookTheme.fill(g, Coord.of(0, y - 1), Coord.of(w, UI.scale(2)), CookbookTheme.accent);
        }
        super.draw(g);
    }

    private void drawHeader(GOut g, int y, int w) {
        CookbookTheme.fill(g, Coord.of(0, y), Coord.of(w, 1), CookbookTheme.outline);
        Tex t = tc.get(CookbookTheme.small, (showDone ? "Hide done (" : "Show done (") + doneCount + ")", CookbookTheme.muted);
        g.image(t, Coord.of(UI.scale(6), y + (ROWH - t.sz().y) / 2));
        if (showDone && listener.canEdit()) {
            Tex c = tc.get(CookbookTheme.small, "Clear done", CookbookTheme.accent);
            g.image(c, Coord.of(w - c.sz().x - UI.scale(6), y + (ROWH - c.sz().y) / 2));
        }
    }

    private void drawItem(GOut g, Row r, int y, int w, String me) {
        TodoItem it = r.item;
        boolean shownDone = !r.open;
        int box = CookbookTheme.BOX;
        Coord bul = Coord.of(BOXX, y + (ROWH - box) / 2);
        CookbookTheme.frame(g, bul, Coord.of(box, box), listener.canEdit() ? CookbookTheme.accent : CookbookTheme.outline);
        if (shownDone) {
            int in = UI.scale(3);
            CookbookTheme.fill(g, bul.add(in, in), Coord.of(box - 2 * in, box - 2 * in),
                listener.canEdit() ? CookbookTheme.accent : CookbookTheme.muted);
        }
        if (it.urgent && !shownDone) {
            Tex u = tc.get(CookbookTheme.bold, "!", URGENT);
            g.image(u, Coord.of(BOXX + box + UI.scale(4), y + (ROWH - u.sz().y) / 2));
        }

        /* Right side, from the edge inwards: pin, assignee, pending marker. */
        int right = w - UI.scale(4);
        if (it.hasLoc) {
            int cx = right - PINW / 2, cy = y + ROWH / 2;
            int s = UI.scale(7);
            CookbookTheme.frame(g, Coord.of(cx - s / 2, cy - s / 2), Coord.of(s, s), CookbookTheme.accent);
            CookbookTheme.fill(g, Coord.of(cx - 1, cy - 1), Coord.of(UI.scale(3), UI.scale(3)), CookbookTheme.accent);
        }
        right -= PINW;
        String whoText;
        Color whoCol;
        if (shownDone) {
            whoText = it.doneBy.isEmpty() ? "" : it.doneBy;
            whoCol = CookbookTheme.muted;
        } else if (it.assignee.isEmpty()) {
            whoText = "anyone";
            whoCol = new Color(120, 132, 130);
        } else {
            whoText = "@" + it.assignee;
            whoCol = it.assignee.equals(me) ? CookbookTheme.accent : CookbookTheme.muted;
        }
        if (!whoText.isEmpty()) {
            Tex wt = tc.get(CookbookTheme.small,
                CookbookTheme.ellipsize(CookbookTheme.small, whoText, UI.scale(90)), whoCol);
            right -= wt.sz().x;
            g.image(wt, Coord.of(right, y + (ROWH - wt.sz().y) / 2));
            right -= UI.scale(6);
        }
        if (listener.isPending(it.id)) {
            int s = UI.scale(5);
            right -= s;
            CookbookTheme.fill(g, Coord.of(right, y + (ROWH - s) / 2), Coord.of(s, s), PENDING);
            right -= UI.scale(5);
        }

        int tx = TEXTX;
        String extra = it.repeatH > 0 ? " every " + TodoEditor.repeatLabel(it.repeatH) : "";
        Tex extraTex = extra.isEmpty() ? null : tc.get(CookbookTheme.small, extra, CookbookTheme.muted);
        int room = right - tx - (extraTex != null ? extraTex.sz().x : 0);
        Tex tt = tc.get(CookbookTheme.body, CookbookTheme.ellipsize(CookbookTheme.body, it.title, room),
            shownDone ? CookbookTheme.muted : CookbookTheme.fg);
        int ty = y + (ROWH - tt.sz().y) / 2;
        g.image(tt, Coord.of(tx, ty));
        if (shownDone)
            CookbookTheme.fill(g, Coord.of(tx, y + ROWH / 2), Coord.of(tt.sz().x, 1), CookbookTheme.muted);
        if (extraTex != null)
            g.image(extraTex, Coord.of(tx + tt.sz().x, y + (ROWH - extraTex.sz().y) / 2 + UI.scale(1)));
    }

    private int rowAt(Coord c) {
        if (c.x < 0 || c.y < 0 || c.x >= listW() || c.y >= sz.y)
            return -1;
        int i = (c.y + scroll) / ROWH;
        return i < rows.size() ? i : -1;
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (sb.vis() && ev.c.x >= sz.x - sb.sz.x)
            return super.mousedown(ev);
        int i = rowAt(ev.c);
        if (i < 0)
            return super.mousedown(ev);
        Row r = rows.get(i);
        if (r.item == null) {
            if (ev.b == 1) {
                Tex c = tc.get(CookbookTheme.small, "Clear done", CookbookTheme.accent);
                if (showDone && listener.canEdit() && ev.c.x >= listW() - c.sz().x - UI.scale(10))
                    listener.clearDone();
                else
                    listener.toggleDoneSection();
            }
            return true;
        }
        if (ev.b == 3) {
            listener.context(r.item, ev.c);
            return true;
        }
        if (ev.b != 1)
            return true;
        if (ev.c.x < TEXTX - UI.scale(4)) {
            if (listener.canEdit())
                listener.toggle(r.item);
            return true;
        }
        if (r.item.hasLoc && ev.c.x >= listW() - UI.scale(4) - PINW) {
            listener.showOnMap(r.item);
            return true;
        }
        listener.select(r.item);
        if (r.open && listener.canEdit()) {
            dragging = r.item;
            dragged = false;
            dragY = ev.c.y;
            if (dgrab == null)
                dgrab = ui.grabmouse(this);
        }
        return true;
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        hover = rowAt(ev.c);
        if (dragging != null) {
            if (!dragged && Math.abs(ev.c.y - dragY) >= DRAGTHRESH)
                dragged = true;
            if (dragged) {
                if (ev.c.y < ROWH / 2)
                    scroll = Math.max(0, scroll - UI.scale(4));
                else if (ev.c.y > sz.y - ROWH / 2)
                    scroll = Math.min(scrollmax(), scroll + UI.scale(4));
                dropIdx = Utils.clip((ev.c.y + scroll + ROWH / 2) / ROWH, 0, open.size());
            }
        }
        super.mousemove(ev);
    }

    @Override
    public boolean mouseup(MouseUpEvent ev) {
        if (ev.b == 1 && dragging != null) {
            if (dgrab != null) {
                dgrab.remove();
                dgrab = null;
            }
            TodoItem it = dragging;
            boolean moved = dragged;
            int idx = dropIdx;
            dragging = null;
            dragged = false;
            dropIdx = -1;
            if (moved && idx >= 0)
                drop(it, idx);
            return true;
        }
        return super.mouseup(ev);
    }

    /** Gives the dragged task an order between its new neighbours; only that one row is written. */
    private void drop(TodoItem it, int idx) {
        int cur = open.indexOf(it);
        if (cur < 0 || idx == cur || idx == cur + 1)
            return;
        TodoItem prev = idx > 0 ? open.get(idx - 1) : null;
        TodoItem next = idx < open.size() ? open.get(idx) : null;
        double order;
        if (prev == null && next == null)
            return;
        else if (prev == null)
            order = next.order - 1;
        else if (next == null)
            order = prev.order + 1;
        else
            order = (prev.order + next.order) / 2;
        listener.reorder(it, order);
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        scroll = Utils.clip(scroll + ev.a * ROWH * 2, 0, scrollmax());
        return true;
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        int i = rowAt(c);
        if (i < 0 || rows.get(i).item == null)
            return super.tooltip(c, prev);
        TodoItem it = rows.get(i).item;
        StringBuilder sb = new StringBuilder(it.title);
        if (!it.notes.isEmpty())
            sb.append("\n").append(it.notes.length() > 200 ? it.notes.substring(0, 200) + "…" : it.notes);
        if (!it.createdBy.isEmpty())
            sb.append("\nCreated by ").append(it.createdBy).append(" · ").append(TodoWindow.ago(it.createdAt, now));
        if (!it.touchedBy.isEmpty() && it.touchedAt != it.createdAt)
            sb.append("\nLast change: ").append(it.touchedBy).append(" · ").append(TodoWindow.ago(it.touchedAt, now));
        return sb.toString();
    }

    @Override
    public void dispose() {
        tc.clear();
        super.dispose();
    }

    @Override
    public int scrollmin() {
        return 0;
    }

    @Override
    public int scrollmax() {
        return Math.max(0, rows.size() * ROWH - sz.y);
    }

    @Override
    public int scrollval() {
        return scroll;
    }

    @Override
    public void scrollval(int val) {
        scroll = Utils.clip(val, 0, scrollmax());
    }
}
