package nurgling.widgets.todo;

import haven.*;
import nurgling.todo.TodoList;
import nurgling.widgets.cookbook.CookbookTheme;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** One tab per list, with its count of open tasks, and a "+" tab for a new list. */
public class TodoTabs extends Widget {
    private static final int PAD = UI.scale(7);
    private static final int GAP = UI.scale(2);
    private static final int MAXNAME = UI.scale(96);
    /** The window body colour, so the selected tab reads as part of the page below it. */
    private static final Color PAGE = new Color(40, 52, 54);

    public interface Listener {
        void select(int listId);
        void context(int listId, Coord at);
        void addList();
    }

    private static final class Tab {
        final int id;
        final String label;
        final String count;
        final boolean plus;
        int x, w;

        Tab(int id, String label, String count, boolean plus) {
            this.id = id;
            this.label = label;
            this.count = count;
            this.plus = plus;
        }
    }

    private final Listener listener;
    private final TexCache tc = new TexCache();
    private final List<Tab> tabs = new ArrayList<>();
    private int selected;
    private Tab hover = null;

    public TodoTabs(int w, Listener listener) {
        super(Coord.of(w, UI.scale(22)));
        this.listener = listener;
    }

    /** {@code counts[i]} is the open-task count of {@code lists.get(i)}. */
    public void update(List<TodoList> lists, int[] counts, int selected, boolean canAdd) {
        this.selected = selected;
        tabs.clear();
        hover = null;
        int x = 0;
        for (int i = 0; i < lists.size(); i++) {
            TodoList l = lists.get(i);
            String name = CookbookTheme.ellipsize(CookbookTheme.bold, l.name, MAXNAME);
            String count = counts[i] > 0 ? Integer.toString(counts[i]) : null;
            Tab t = new Tab(l.id, name, count, false);
            t.x = x;
            t.w = CookbookTheme.bold.strsize(name).x + 2 * PAD
                + (count != null ? CookbookTheme.small.strsize(count).x + UI.scale(4) : 0);
            x += t.w + GAP;
            tabs.add(t);
        }
        if (canAdd) {
            Tab plus = new Tab(0, "+", null, true);
            plus.x = x;
            plus.w = CookbookTheme.bold.strsize("+").x + 2 * PAD;
            tabs.add(plus);
        }
    }

    @Override
    public void draw(GOut g) {
        int base = sz.y - 1;
        CookbookTheme.fill(g, Coord.of(0, base), Coord.of(sz.x, 1), CookbookTheme.outline);
        for (Tab t : tabs) {
            boolean on = !t.plus && t.id == selected;
            Coord ul = Coord.of(t.x, on ? 0 : UI.scale(2));
            Coord tsz = Coord.of(t.w, sz.y - ul.y);
            CookbookTheme.fill(g, ul, tsz, on ? PAGE : (hover == t ? CookbookTheme.hover : CookbookTheme.bg));
            CookbookTheme.frame(g, ul, Coord.of(tsz.x, tsz.y + 1), on ? CookbookTheme.accent : CookbookTheme.outline);
            if (on)
                CookbookTheme.fill(g, Coord.of(t.x + 1, base), Coord.of(t.w - 2, 1), PAGE);
            Tex label = tc.get(CookbookTheme.bold, t.label, t.plus ? CookbookTheme.accent : CookbookTheme.fg);
            g.image(label, Coord.of(t.x + PAD, ul.y + (tsz.y - label.sz().y) / 2));
            if (t.count != null) {
                Tex count = tc.get(CookbookTheme.small, t.count, CookbookTheme.accent);
                g.image(count, Coord.of(t.x + PAD + label.sz().x + UI.scale(4), ul.y + (tsz.y - count.sz().y) / 2));
            }
        }
    }

    private Tab at(Coord c) {
        if (c.y < 0 || c.y >= sz.y)
            return null;
        for (Tab t : tabs)
            if (c.x >= t.x && c.x < t.x + t.w)
                return t;
        return null;
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        Tab t = at(ev.c);
        if (t == null)
            return super.mousedown(ev);
        if (t.plus) {
            if (ev.b == 1)
                listener.addList();
        } else if (ev.b == 1) {
            listener.select(t.id);
        } else if (ev.b == 3 && t.id != TodoList.PERSONAL && t.id != 0) {
            listener.context(t.id, Coord.of(t.x, sz.y));
        }
        return true;
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        hover = at(ev.c);
        super.mousemove(ev);
    }

    @Override
    public void dispose() {
        tc.clear();
        super.dispose();
    }
}
