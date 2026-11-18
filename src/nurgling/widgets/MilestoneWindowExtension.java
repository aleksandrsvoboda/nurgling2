package nurgling.widgets;

import haven.*;
import nurgling.NStyle;
import nurgling.sessions.SessionManager;
import nurgling.tools.RoadTags;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds a private tag column (see {@link RoadTags}) to milestone / roadsign windows. The server sends
 * one row per road - a name label and Follow / Travel buttons - and nothing that identifies the
 * road, so a tag is filed under the right-clicked post and the row's road name. A window whose post
 * cannot be told for sure gets no column at all.
 *
 * A road button pressed here is also handed to the squad (see Squad.onRoadButton), so every character that opened
 * the same post for a squad order presses the same road.
 */
public final class MilestoneWindowExtension {

    private static final String POST_PREFIX = "gfx/terobjs/road/milestone-";

    private MilestoneWindowExtension() {
    }

    /** Called for every window added to the game UI. */
    public static void attach(Window wnd) {
        if (wnd != null)
            wnd.add(new Binder(), Coord.z);
    }

    /** Whether a gob resource is a milestone post. */
    public static boolean isPost(String res) {
        return res != null && res.startsWith(POST_PREFIX);
    }

    private static boolean isRoadButton(Widget w) {
        if (!(w instanceof Button) || ((Button) w).text == null)
            return false;
        String t = ((Button) w).text.text;
        return "Follow".equals(t) || "Travel".equals(t);
    }

    /** A road row: the road's name and which of the rows with that name it is (0 = the topmost). */
    public static final class Road {
        public final String name;
        public final int n;

        Road(String name, int n) {
            this.name = name;
            this.n = n;
        }
    }

    /** Whether this window lists roads, i.e. has a Follow or Travel button. */
    public static boolean isRoadWindow(Widget wnd) {
        return !roadButtons(wnd).isEmpty();
    }

    private static List<Button> roadButtons(Widget wnd) {
        List<Button> res = new ArrayList<>();
        for (Widget w = wnd.child; w != null; w = w.next)
            if (isRoadButton(w))
                res.add((Button) w);
        return res;
    }

    /** The road-name labels, top to bottom: labels lined up with a road button. */
    private static List<Label> rows(Widget wnd, List<Button> buttons) {
        List<Label> rows = new ArrayList<>();
        for (Widget w = wnd.child; w != null; w = w.next) {
            if (!(w instanceof Label) || ((Label) w).texts == null)
                continue;
            for (Button b : buttons) {
                if (inRow(b, (Label) w)) {
                    rows.add((Label) w);
                    break;
                }
            }
        }
        rows.sort((a, b) -> Integer.compare(a.c.y, b.c.y));
        return rows;
    }

    /** A label is on a button's row when its middle lies within the button's height. */
    private static boolean inRow(Widget b, Label row) {
        int mid = row.c.y + row.sz.y / 2;
        return mid >= b.c.y && mid < b.c.y + b.sz.y;
    }

    /** The road a button belongs to, or null when it is on no road row. */
    public static Road roadOf(Button b) {
        Widget wnd = b.parent;
        if (wnd == null)
            return null;
        Map<String, Integer> seen = new HashMap<>();
        for (Label row : rows(wnd, roadButtons(wnd))) {
            int n = seen.merge(row.texts, 1, Integer::sum) - 1;
            if (inRow(b, row))
                return new Road(row.texts, n);
        }
        return null;
    }

    /** The button with this text on the given road's row, or null. */
    public static Button button(Widget wnd, String road, int n, String text) {
        List<Button> buttons = roadButtons(wnd);
        Map<String, Integer> seen = new HashMap<>();
        for (Label row : rows(wnd, buttons)) {
            if (seen.merge(row.texts, 1, Integer::sum) - 1 != n || !row.texts.equals(road))
                continue;
            for (Button b : buttons)
                if (text.equals(b.text.text) && inRow(b, row))
                    return b;
            return null;
        }
        return null;
    }

    private static class Binder extends Widget {
        /** How long a window may take to show road buttons, and then to be tied to its post. */
        private static final long WAIT_MS = 5_000;
        private static final int GAP = UI.scale(10);
        private static final int CELL_W = UI.scale(150);

        private final long created = System.currentTimeMillis();
        private boolean dead = false;
        private Gob post = null;
        private String postKey = null;
        private final Map<Label, TagCell> cells = new HashMap<>();
        /** Road buttons whose press is already passed on to the squad. */
        private final java.util.Set<Button> hooked = Collections.newSetFromMap(new IdentityHashMap<>());
        /** The window's right margin past its buttons, measured once before the column widens it. */
        private int margin = -1;

        Binder() {
            super(Coord.z);
        }

        private boolean accepts(Gob g) {
            return g != null && g.ngob != null && isPost(g.ngob.name);
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            if (dead)
                return;
            List<Button> buttons = roadButtons(parent);
            if (buttons.isEmpty()) {
                if (System.currentTimeMillis() - created > WAIT_MS)
                    dead = true;
                return;
            }
            for (Button b : buttons)
                if (hooked.add(b))
                    hook(b);
            if (postKey == null) {
                if (post == null)
                    post = ui.core.takeClickedGob(this::accepts);
                if (post != null)
                    postKey = RoadTags.postKey(post);
                if (postKey == null) {
                    // Better no column than tags filed under the wrong post.
                    if (System.currentTimeMillis() - created > WAIT_MS)
                        dead = true;
                    return;
                }
            }
            layout(buttons);
        }

        /** Tell the squad about a press before the server hears it, so the squad's characters press it too. */
        private void hook(Button b) {
            Runnable press = b.action;
            b.action = () -> {
                Road r = roadOf(b);
                if (r != null && ui != null)
                    SessionManager.getInstance().squad().onRoadButton(ui, r.name, r.n, b.text.text);
                if (press != null)
                    press.run();
            };
        }

        /** Rows are the labels lined up with a road button; a new row gets a cell right of the buttons. */
        private void layout(List<Button> buttons) {
            List<Label> rows = rows(parent, buttons);
            if (rows.isEmpty())
                return;

            // Every button on a road row, not just Follow / Travel: some posts add Extend / Connect.
            int right = 0;
            for (Widget w = parent.child; w != null; w = w.next) {
                if (w instanceof Button && onRow(w, rows))
                    right = Math.max(right, w.c.x + w.sz.x);
            }
            int x = right + GAP;

            Map<String, Integer> seen = new HashMap<>();
            for (Label row : rows) {
                int n = seen.merge(row.texts, 1, Integer::sum) - 1;
                TagCell cell = cells.get(row);
                if (cell == null) {
                    cell = parent.add(new TagCell(postKey, RoadTags.roadKey(row.texts, n), CELL_W));
                    cells.put(row, cell);
                }
                cell.move(Coord.of(x, row.c.y + (row.sz.y - cell.sz.y) / 2));
            }

            // Buttons can arrive after the first layout, so widen whenever the column no longer fits,
            // keeping the window's own right margin as it was before the column was added.
            Window wnd = (Window) parent;
            Coord csz = wnd.csz();
            if (margin < 0)
                margin = Math.max(0, csz.x - right);
            int w = x + CELL_W + margin;
            if (w > csz.x)
                wnd.resize(Coord.of(w, csz.y));
        }

        private static boolean onRow(Widget w, List<Label> rows) {
            for (Label row : rows) {
                if (w.c.y < row.c.y + row.sz.y && w.c.y + w.sz.y > row.c.y)
                    return true;
            }
            return false;
        }
    }

    /** The tag (or a faint "add tag"); a click swaps in an entry, Enter saves, Esc cancels. */
    private static class TagCell extends Widget {
        private static final Color EMPTY = new Color(111, 124, 120);
        private final String postKey;
        private final String roadKey;
        private String tag;
        private Text rendered;
        private Entry entry = null;
        private boolean closing = false;
        private boolean entryFocused = false;

        TagCell(String postKey, String roadKey, int w) {
            super(Coord.of(w, Text.std.height()));
            this.postKey = postKey;
            this.roadKey = roadKey;
            this.tag = RoadTags.get(postKey, roadKey);
            render();
        }

        private void render() {
            boolean has = tag != null && !tag.isEmpty();
            rendered = Text.std.render(has ? tag : "add tag", has ? NStyle.border : EMPTY);
            settip(has ? tag : "Private tag for this road, only you see it");
        }

        @Override
        public void draw(GOut g) {
            if (entry == null) {
                g.reclip(Coord.z, sz).image(rendered.tex(), Coord.z);
            }
            super.draw(g);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (entry == null && ev.b == 1) {
                entry = add(new Entry(sz.x, tag == null ? "" : tag), Coord.z);
                entryFocused = false;
                setfocus(entry);
                return true;
            }
            return super.mousedown(ev);
        }

        /** Saves (unless save is null) and drops the entry on the next tick, not inside its own key event. */
        private void close(String save) {
            if (save != null) {
                RoadTags.set(postKey, roadKey, save);
                tag = RoadTags.get(postKey, roadKey);
                render();
            }
            closing = true;
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            if (entry == null)
                return;
            // Once the entry has had the focus, clicking elsewhere in the window cancels the edit.
            if (entry.hasfocus)
                entryFocused = true;
            if (closing || (entryFocused && !entry.hasfocus)) {
                entry.destroy();
                entry = null;
                closing = false;
            }
        }

        private class Entry extends TextEntry {
            Entry(int w, String text) {
                super(w, text);
            }

            @Override
            public void activate(String text) {
                close(text);
            }

            @Override
            public boolean keydown(KeyDownEvent ev) {
                if (ev.code == KeyEvent.VK_ESCAPE) {
                    close(null);
                    return true;
                }
                return super.keydown(ev);
            }
        }
    }
}
