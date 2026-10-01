package nurgling.widgets;

import haven.*;
import nurgling.widgets.cookbook.CookbookTheme;
import nurgling.widgets.cookbook.HintTextEntry;
import nurgling.widgets.timers.TimerIcons;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A drop-down for choosing a character, with a search box. Type to filter - names that start with the
 * text come first, then names that contain it - and pick with the mouse or ↑/↓ and Enter. When the text
 * matches nobody exactly, a last row offers that exact name, for a character no list knows yet.
 *
 * <p>Like the To-Do window's other menus it grabs the mouse while open, so a click anywhere else closes it.
 */
public class PersonPicker extends Widget {
    /** Where a name came from; also the order of the groups. */
    public enum Group {
        ME("Me"), ONLINE("Online now"), VILLAGE("Village"), KIN("Kin"), SEEN("Seen in tasks");

        final String label;

        Group(String label) {
            this.label = label;
        }
    }

    /** One character to offer. */
    public static final class Person {
        public final String name;
        public final Group group;
        /** Short hint on the right: "kin", "2 days ago". */
        public final String note;
        public final boolean online;

        public Person(String name, Group group, String note, boolean online) {
            this.name = name;
            this.group = group;
            this.note = (note == null) ? "" : note;
            this.online = online;
        }
    }

    private static final int W = UI.scale(250);
    private static final int ROWH = UI.scale(18);
    private static final int MAXROWS = 12;
    private static final int PAD = UI.scale(6);
    private static final Color ONLINE = new Color(122, 209, 122);
    private static final Color OFFLINE = new Color(96, 96, 96);

    /** A drawn line: a group header, a person, "anyone", or the exact-name row. */
    private static final class Row {
        final String header;
        final Person person;
        final String pick;      // what choosing the row hands back; null for headers

        Row(String header, Person person, String pick) {
            this.header = header;
            this.person = person;
            this.pick = pick;
        }
    }

    private final List<Person> people;
    private final String anyoneLabel;
    private final Consumer<String> onPick;
    private final HintTextEntry search;
    private final List<Row> rows = new ArrayList<>();
    private String query = "";
    private int sel = -1;
    private int scroll = 0;
    private UI.Grab grab = null;
    private boolean closed = false;

    /**
     * @param anyoneLabel first row that unassigns (hands back ""), or null for none
     * @param onPick      called with the chosen name, or "" for {@code anyoneLabel}
     */
    public PersonPicker(List<Person> people, String anyoneLabel, Consumer<String> onPick) {
        super(Coord.z);
        this.people = people;
        this.anyoneLabel = anyoneLabel;
        this.onPick = onPick;
        search = add(new HintTextEntry(W - 2 * PAD, "Search…", this::refilter) {
            @Override
            public boolean keydown(KeyDownEvent ev) {
                if(handleKey(ev))
                    return true;
                return super.keydown(ev);
            }
        }, Coord.of(PAD, PAD));
        refilter();
    }

    private int listTop() {
        return search.c.y + search.sz.y + PAD;
    }

    private void refilter() {
        query = (search == null) ? "" : search.text().trim();
        String q = query.toLowerCase(Locale.ROOT);
        rows.clear();
        if(q.isEmpty()) {
            if(anyoneLabel != null)
                rows.add(new Row(null, null, ""));
            Group last = null;
            for(Person p : sorted(people, "")) {
                if(p.group != last) {
                    rows.add(new Row(p.group.label, null, null));
                    last = p.group;
                }
                rows.add(new Row(null, p, p.name));
            }
        } else {
            boolean exact = false;
            for(Person p : sorted(people, q)) {
                String n = p.name.toLowerCase(Locale.ROOT);
                if(!n.contains(q))
                    continue;
                exact |= n.equals(q);
                rows.add(new Row(null, p, p.name));
            }
            if(!exact)
                rows.add(new Row(null, null, query));
        }
        sel = firstPickable(0, 1);
        scroll = 0;
        int shown = Math.min(Math.max(rows.size(), 1), MAXROWS);
        resize(Coord.of(W, listTop() + shown * ROWH + PAD));
    }

    /** Group order then name; while searching, names that start with the text before the rest. */
    private static List<Person> sorted(List<Person> people, String q) {
        List<Person> out = new ArrayList<>(people);
        Comparator<Person> byGroup = Comparator.comparing(p -> p.group);
        Comparator<Person> order = q.isEmpty() ? byGroup
            : Comparator.<Person, Boolean>comparing(p -> !p.name.toLowerCase(Locale.ROOT).startsWith(q)).thenComparing(byGroup);
        out.sort(order.thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private int firstPickable(int from, int dir) {
        for(int i = from; i >= 0 && i < rows.size(); i += dir) {
            if(rows.get(i).pick != null)
                return i;
        }
        return -1;
    }

    private boolean handleKey(KeyDownEvent ev) {
        if(ev.code == KeyEvent.VK_ESCAPE) {
            close();
            return true;
        }
        if(ev.code == KeyEvent.VK_DOWN || ev.code == KeyEvent.VK_UP) {
            int dir = (ev.code == KeyEvent.VK_DOWN) ? 1 : -1;
            int n = firstPickable(sel + dir, dir);
            if(n >= 0) {
                sel = n;
                if(sel < scroll)
                    scroll = sel;
                else if(sel >= scroll + MAXROWS)
                    scroll = sel - MAXROWS + 1;
            }
            return true;
        }
        if(ev.code == KeyEvent.VK_ENTER) {
            if(sel >= 0)
                pick(rows.get(sel));
            return true;
        }
        return false;
    }

    private void pick(Row r) {
        if(r.pick == null)
            return;
        close();
        onPick.accept(r.pick);
    }

    @Override
    protected void added() {
        super.added();
        grab = ui.grabmouse(this);
        raise();
        parent.setfocus(this);
        setfocus(search);
    }

    public void close() {
        if(closed)
            return;
        closed = true;
        if(grab != null) {
            grab.remove();
            grab = null;
        }
        destroy();
    }

    @Override
    public void draw(GOut g) {
        CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.popBg);
        super.draw(g);
        int top = listTop();
        int shown = Math.min(rows.size() - scroll, MAXROWS);
        for(int r = 0; r < shown; r++)
            drawRow(g, rows.get(r + scroll), r + scroll == sel, top + r * ROWH);
        if(rows.isEmpty()) {
            Tex t = TimerIcons.text(CookbookTheme.small, "Nobody to show", CookbookTheme.muted);
            g.image(t, Coord.of(PAD, top + (ROWH - t.sz().y) / 2));
        }
        CookbookTheme.frame(g, Coord.z, sz, CookbookTheme.accent);
    }

    private void drawRow(GOut g, Row row, boolean selected, int y) {
        if(row.header != null) {
            Tex t = TimerIcons.text(CookbookTheme.small, row.header.toUpperCase(), CookbookTheme.accent);
            g.image(t, Coord.of(PAD, y + (ROWH - t.sz().y) / 2));
            return;
        }
        if(selected)
            CookbookTheme.fill(g, Coord.of(1, y), Coord.of(sz.x - 2, ROWH), CookbookTheme.hover);
        int x = PAD;
        int cy = y + ROWH / 2;
        if(row.person == null) {
            String label = row.pick.isEmpty() ? anyoneLabel : "Assign to \"" + row.pick + "\" exactly";
            Tex t = TimerIcons.text(CookbookTheme.body, label, row.pick.isEmpty() ? CookbookTheme.fg : CookbookTheme.muted);
            g.image(t, Coord.of(x + UI.scale(12), cy - t.sz().y / 2));
            return;
        }
        Person p = row.person;
        TimerIcons.dot(g, Coord.of(x + UI.scale(4), cy), UI.scale(8), p.online ? ONLINE : OFFLINE);
        x += UI.scale(12);
        x = drawName(g, p.name, x, cy);
        if(!p.note.isEmpty()) {
            Tex n = TimerIcons.text(CookbookTheme.small, p.note, CookbookTheme.muted);
            g.image(n, Coord.of(sz.x - PAD - n.sz().x, cy - n.sz().y / 2));
        }
    }

    /** The name with the part that matches the search in the accent colour. */
    private int drawName(GOut g, String name, int x, int cy) {
        int at = query.isEmpty() ? -1 : name.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        String[] parts = (at < 0) ? new String[] {name, "", ""}
            : new String[] {name.substring(0, at), name.substring(at, at + query.length()), name.substring(at + query.length())};
        Color[] cols = {CookbookTheme.fg, CookbookTheme.accent, CookbookTheme.fg};
        for(int i = 0; i < 3; i++) {
            if(parts[i].isEmpty())
                continue;
            Tex t = TimerIcons.text(i == 1 ? CookbookTheme.bold : CookbookTheme.body, parts[i], cols[i]);
            g.image(t, Coord.of(x, cy - t.sz().y / 2));
            x += t.sz().x;
        }
        return x;
    }

    private int rowAt(Coord c) {
        int top = listTop();
        if(c.x < 0 || c.x >= sz.x || c.y < top || c.y >= sz.y - PAD)
            return -1;
        int i = (c.y - top) / ROWH + scroll;
        return i < rows.size() ? i : -1;
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        int i = rowAt(ev.c);
        if(i >= 0 && rows.get(i).pick != null)
            sel = i;
        super.mousemove(ev);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(!ev.c.isect(Coord.z, sz)) {
            close();
            return true;
        }
        int i = rowAt(ev.c);
        if(i >= 0) {
            if(ev.b == 1)
                pick(rows.get(i));
            return true;
        }
        return super.mousedown(ev);
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        scroll = Utils.clip(scroll + ev.a, 0, Math.max(0, rows.size() - MAXROWS));
        return true;
    }
}
