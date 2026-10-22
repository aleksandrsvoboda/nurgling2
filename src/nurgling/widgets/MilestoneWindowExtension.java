package nurgling.widgets;

import haven.*;
import nurgling.NStyle;
import nurgling.tools.RoadTags;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds a private tag column (see {@link RoadTags}) to milestone / roadsign windows. The server sends
 * one row per road - a name label and Follow / Travel buttons - and nothing that identifies the
 * road, so a tag is filed under the right-clicked post and the row's road name. A window whose post
 * cannot be told for sure gets no column at all.
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

    private static boolean isRoadButton(Widget w) {
        if (!(w instanceof Button) || ((Button) w).text == null)
            return false;
        String t = ((Button) w).text.text;
        return "Follow".equals(t) || "Travel".equals(t);
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

        Binder() {
            super(Coord.z);
        }

        private boolean accepts(Gob g) {
            return g != null && g.ngob != null && g.ngob.name != null && g.ngob.name.startsWith(POST_PREFIX);
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            if (dead)
                return;
            List<Button> buttons = roadButtons();
            if (buttons.isEmpty()) {
                if (System.currentTimeMillis() - created > WAIT_MS)
                    dead = true;
                return;
            }
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

        private List<Button> roadButtons() {
            List<Button> res = new ArrayList<>();
            for (Widget w = parent.child; w != null; w = w.next)
                if (isRoadButton(w))
                    res.add((Button) w);
            return res;
        }

        /** Rows are the labels lined up with a road button; a new row gets a cell right of the buttons. */
        private void layout(List<Button> buttons) {
            List<Label> rows = new ArrayList<>();
            for (Widget w = parent.child; w != null; w = w.next) {
                if (!(w instanceof Label) || ((Label) w).texts == null)
                    continue;
                int mid = w.c.y + w.sz.y / 2;
                for (Button b : buttons) {
                    if (mid >= b.c.y && mid < b.c.y + b.sz.y) {
                        rows.add((Label) w);
                        break;
                    }
                }
            }
            if (rows.isEmpty())
                return;
            rows.sort((a, b) -> Integer.compare(a.c.y, b.c.y));

            int right = 0;
            for (Button b : buttons)
                right = Math.max(right, b.c.x + b.sz.x);
            int x = right + GAP;

            boolean added = false;
            Map<String, Integer> seen = new HashMap<>();
            for (Label row : rows) {
                int n = seen.merge(row.texts, 1, Integer::sum) - 1;
                TagCell cell = cells.get(row);
                if (cell == null) {
                    cell = parent.add(new TagCell(postKey, RoadTags.roadKey(row.texts, n), CELL_W));
                    cells.put(row, cell);
                    added = true;
                }
                cell.move(Coord.of(x, row.c.y + (row.sz.y - cell.sz.y) / 2));
            }
            if (added) {
                Window wnd = (Window) parent;
                Coord csz = wnd.csz();
                int margin = Math.max(0, csz.x - right);
                int w = x + CELL_W + margin;
                if (w > csz.x)
                    wnd.resize(Coord.of(w, csz.y));
            }
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
