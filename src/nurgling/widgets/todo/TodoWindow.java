package nurgling.widgets.todo;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.PeerPosition;
import nurgling.PingService;
import nurgling.todo.TodoItem;
import nurgling.todo.TodoList;
import nurgling.todo.TodoStore;
import nurgling.widgets.TextInputWindow;
import nurgling.widgets.cookbook.CookbookTheme;
import nurgling.widgets.cookbook.HintTextEntry;
import nurgling.widgets.cookbook.PillButton;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntConsumer;

/**
 * The To-Do window: lists as tabs, filters, a quick-add box, the task rows, the editor for the selected
 * task and a status line saying where the data lives. All state comes from the session's
 * {@link TodoStore}; the window re-reads it whenever the store's revision moves.
 */
public class TodoWindow extends Window implements TodoListView.Listener, TodoEditor.Host {
    private static final int W = UI.scale(420);
    private static final int H = UI.scale(560);
    private static final int GAP = UI.scale(4);
    private static final int FOOTH = UI.scale(18);
    private static final long TOAST_MS = 6000;
    private static final long REFRESH_MS = 5000;
    private static final Color OK = new Color(111, 191, 115);
    private static final Color BAD = new Color(212, 90, 74);
    private static final Color IDLE = new Color(128, 128, 128);

    private enum Filter { ALL, MINE, FREE }

    private final NGameUI gui;
    private final TodoStore store;
    private final TexCache tc = new TexCache();
    private final TodoTabs tabs;
    private final FilterBar filters;
    private final HintTextEntry search;
    private final HintTextEntry addBox;
    private final PillButton addBtn;
    private final Banner banner;
    private final TodoListView list;
    private final TodoEditor editor;
    private final Footer footer;
    private final Toast toast;
    private TodoMenu menu = null;
    /** The open name prompt, if any; there is only ever one. */
    private TextInputWindow prompt = null;

    private int curList = Integer.MIN_VALUE;
    private Filter filter = Filter.ALL;
    private boolean showDone = false;
    private int selectedId = 0;
    private int seenRev = -1;
    private long lastRefresh = 0;
    private int revealId = 0;

    /* Toast raised by this window (undo of a delete); remote ones come from the store. */
    private String undoText = null;
    private int[] undoIds = null;
    private long undoAt = 0;

    /* Map lookups resolve on the loader thread and are applied on the next tick. */
    private volatile MiniMap.Locator pendingCenter = null;
    private volatile String pendingMsg = null;

    public TodoWindow(NGameUI gui) {
        super(Coord.of(W, H), "To-Do");
        this.gui = gui;
        this.store = gui.todoStore;

        tabs = add(new TodoTabs(W, new TodoTabs.Listener() {
            public void select(int listId) {
                curList = listId;
                selectedId = 0;
                refresh();
            }

            public void context(int listId, Coord at) {
                openListMenu(listId, tabs.c.add(at));
            }

            public void addList() {
                promptText("New list", "List name:", name -> {
                    int id = store.addList(name);
                    if (id != 0) {
                        curList = id;
                        selectedId = 0;
                    }
                });
            }
        }), Coord.z);

        filters = add(new FilterBar(), Coord.z);
        search = add(new HintTextEntry(UI.scale(150), "Filter…", () -> refresh()), Coord.z);
        addBtn = add(new PillButton("Add", false, this::addFromBox).minWidth(UI.scale(56)), Coord.z);
        addBox = add(new HintTextEntry(W - addBtn.sz.x - GAP, "Add a task…  (Enter)", null) {
            @Override
            public void activate(String text) {
                addFromBox();
            }
        }, Coord.z);
        banner = add(new Banner(W), Coord.z);
        list = add(new TodoListView(Coord.of(W, UI.scale(200)), this), Coord.z);
        editor = add(new TodoEditor(W, this), Coord.z);
        toast = add(new Toast(W), Coord.z);
        footer = add(new Footer(W), Coord.z);
        editor.hide();
        banner.hide();
        toast.hide();
        refresh();
    }

    /* -------------------------------------------------------------- refresh & layout */

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (!visible())
            return;
        long now = System.currentTimeMillis();
        if (store.revision() != seenRev || now - lastRefresh > REFRESH_MS)
            refresh();
        MiniMap.Locator center = pendingCenter;
        if (center != null) {
            pendingCenter = null;
            centerMap(center);
        }
        String msg = pendingMsg;
        if (msg != null) {
            pendingMsg = null;
            gui.msg(msg, Color.YELLOW);
        }
        updateToast(now);
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        seenRev = store.revision();
        lastRefresh = now;
        TodoStore.View v = store.view();
        List<TodoList> shown = shownLists(v);

        TodoItem sel = selectedId == 0 ? null : v.items.get(selectedId);
        if (sel == null)
            selectedId = 0;
        else if (sel.listId != curList)
            curList = sel.listId;
        boolean known = false;
        for (TodoList l : shown)
            known |= l.id == curList;
        if (!known)
            curList = shown.get(0).id;

        int[] counts = new int[shown.size()];
        for (int i = 0; i < shown.size(); i++)
            for (TodoItem it : v.itemsOf(shown.get(i).id))
                if (it.isOpen(now))
                    counts[i]++;
        tabs.update(shown, counts, curList, !store.readOnly());

        String me = store.me();
        String q = search.text().trim().toLowerCase(Locale.ROOT);
        List<TodoItem> open = new ArrayList<>();
        List<TodoItem> done = new ArrayList<>();
        for (TodoItem it : v.itemsOf(curList)) {
            if (filter == Filter.MINE && !it.isAssignedTo(me))
                continue;
            if (filter == Filter.FREE && !it.assignee.isEmpty())
                continue;
            if (!q.isEmpty() && !it.title.toLowerCase(Locale.ROOT).contains(q)
                && !it.notes.toLowerCase(Locale.ROOT).contains(q))
                continue;
            (it.isOpen(now) ? open : done).add(it);
        }
        open.sort(Comparator.comparing((TodoItem it) -> !it.urgent).thenComparingDouble(it -> it.order).thenComparingInt(it -> it.id));
        done.sort(Comparator.comparingLong((TodoItem it) -> -it.doneAt));
        list.setRows(open, done, showDone, selectedId, emptyText(), now);
        if (revealId != 0) {
            list.reveal(revealId);
            revealId = 0;
        }

        editor.show(sel == null ? null : v.items.get(selectedId), sel != null && store.canEdit(sel.listId), now);
        editor.show(selectedId != 0);
        boolean canAdd = store.canEdit(curList);
        addBox.show(canAdd);
        addBtn.show(canAdd);
        banner.offer(v.uploadOffer);
        layout();
    }

    /** The lists as tabs. With no shared list yet, a "Village" tab stands in and is created on first add. */
    private List<TodoList> shownLists(TodoStore.View v) {
        List<TodoList> shown = new ArrayList<>(v.lists);
        boolean anyShared = false;
        for (TodoList l : shown)
            anyShared |= !l.isPersonal();
        if (!anyShared) {
            TodoList virt = new TodoList();
            virt.id = 0;
            virt.name = TodoStore.DEFAULT_LIST;
            shown.add(0, virt);
        }
        return shown;
    }

    private String emptyText() {
        if (curList != TodoList.PERSONAL && store.mode() == TodoStore.Mode.CONNECTING)
            return "Connecting to the database…";
        if (filter == Filter.MINE)
            return "Nothing assigned to you here.";
        if (!search.text().trim().isEmpty() || filter == Filter.FREE)
            return "No matching tasks.";
        return store.canEdit(curList) ? "Nothing to do here. Add a task above." : "Nothing to do here.";
    }

    private void layout() {
        int y = 0;
        tabs.c = Coord.of(0, y);
        y += tabs.sz.y + GAP;
        filters.c = Coord.of(0, y + (search.sz.y - filters.sz.y) / 2);
        search.c = Coord.of(W - search.sz.x, y);
        y += search.sz.y + GAP;
        if (addBox.visible) {
            addBtn.c = Coord.of(W - addBtn.sz.x, y);
            addBox.c = Coord.of(0, y + (addBtn.sz.y - addBox.sz.y) / 2);
            y += addBtn.sz.y + GAP;
        }
        if (banner.visible) {
            banner.c = Coord.of(0, y);
            y += banner.sz.y + GAP;
        }
        int bottom = H - FOOTH;
        footer.c = Coord.of(0, bottom);
        if (editor.visible) {
            bottom -= editor.sz.y;
            editor.c = Coord.of(0, bottom);
        }
        bottom -= GAP;
        int lh = Math.max(TodoListView.ROWH * 3, bottom - y);
        if (list.sz.y != lh)
            list.resize(Coord.of(W, lh));
        list.c = Coord.of(0, y);
        toast.c = Coord.of(0, list.c.y + list.sz.y - toast.sz.y);
    }

    /* -------------------------------------------------------------- actions */

    private void addFromBox() {
        String t = addBox.text().trim();
        if (t.isEmpty())
            return;
        TodoItem it = store.addItem(curList, t);
        if (it != null) {
            addBox.settext("");
            curList = it.listId;
            revealId = it.id;
            refresh();
        }
    }

    private void promptText(String title, String text, java.util.function.Consumer<String> onOk) {
        closePrompt();
        TextInputWindow w = new TextInputWindow(title, text, s -> {
            prompt = null;
            if (s != null && !s.trim().isEmpty())
                onOk.accept(s.trim());
        });
        prompt = w;
        gui.add(w, c.add(sz.sub(w.sz).div(2)));
        raisePrompt();
    }

    /** Closing runs the prompt's callback with null, which clears {@link #prompt}. */
    private void closePrompt() {
        if (prompt != null)
            prompt.wdgmsg("close");
        prompt = null;
    }

    private void raisePrompt() {
        if (prompt != null && prompt.parent != null) {
            prompt.raise();
            gui.setfocus(prompt);
        }
    }

    /* A click inside a window raises that window once its children have handled it, which would bury a
     * prompt the click just opened; put the prompt back on top afterwards. */
    @Override
    public boolean mousedown(MouseDownEvent ev) {
        boolean ret = super.mousedown(ev);
        raisePrompt();
        return ret;
    }

    private void openListMenu(int listId, Coord at) {
        TodoList l = store.view().list(listId);
        if (l == null || store.readOnly())
            return;
        int n = store.view().itemsOf(listId).size();
        openMenuAt(at, Arrays.asList("Rename…", "Delete list…"), null, i -> {
            if (i == 0) {
                promptText("Rename list", "New name for \"" + l.name + "\":", name -> store.renameList(listId, name));
            } else {
                openMenuAt(at, Arrays.asList("Delete \"" + l.name + "\" and its " + n + (n == 1 ? " task" : " tasks"), "Cancel"),
                    null, j -> {
                        if (j == 0) {
                            store.deleteList(listId);
                            selectedId = 0;
                        }
                    });
            }
        });
    }

    private void openMenuAt(Coord at, List<String> options, boolean[] disabled, IntConsumer onPick) {
        if (menu != null)
            menu.close();
        menu = new TodoMenu(options, disabled, onPick);
        Coord p = Coord.of(Utils.clip(at.x, 0, Math.max(0, W - menu.sz.x)), Utils.clip(at.y, 0, Math.max(0, H - menu.sz.y)));
        add(menu, p);
    }

    @Override
    public void openMenu(Widget anchor, List<String> options, boolean[] disabled, IntConsumer onPick) {
        Coord at = anchor.parentpos(this).add(0, anchor.sz.y);
        if (at.y + UI.scale(18) * Math.min(options.size(), 14) > H)
            at = anchor.parentpos(this).sub(0, UI.scale(18) * Math.min(options.size(), 14) + 2);
        openMenuAt(at, options, disabled, onPick);
    }

    private void updateToast(long now) {
        String text = null;
        String action = null;
        Runnable onAction = null;
        TodoStore.Event remote = store.lastToast();
        if (undoText != null && now - undoAt < TOAST_MS && (remote == null || remote.at <= undoAt)) {
            text = undoText;
            action = "Undo";
            int[] ids = undoIds;
            onAction = () -> {
                for (int id : ids)
                    store.restore(id);
                undoText = null;
            };
        } else if (remote != null && now - remote.at < TOAST_MS) {
            text = remote.toast;
            if (remote.restoreId != 0) {
                action = "Restore";
                int id = remote.restoreId;
                onAction = () -> {
                    store.restore(id);
                    store.clearToast();
                };
            }
        }
        if (text == null) {
            toast.hide();
            return;
        }
        toast.set(text, action, onAction);
        toast.show();
        toast.raise();
    }

    /* -------------------------------------------------------------- list listener */

    @Override
    public void toggle(TodoItem it) {
        store.setDone(it.id, it.isOpen(System.currentTimeMillis()));
    }

    @Override
    public void select(TodoItem it) {
        selectedId = selectedId == it.id ? 0 : it.id;
        refresh();
    }

    @Override
    public void context(TodoItem it, Coord at) {
        boolean edit = store.canEdit(it.listId);
        List<String> opts = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        String me = store.me();
        if (edit) {
            if (it.isAssignedTo(me)) {
                opts.add("Unassign");
                acts.add(() -> store.setAssignee(it.id, ""));
            } else {
                opts.add("Assign to me");
                acts.add(() -> store.setAssignee(it.id, me));
            }
            opts.add(it.urgent ? "Not urgent" : "Mark urgent");
            acts.add(() -> store.setUrgent(it.id, !it.urgent));
            opts.add("Move to…");
            Coord where = list.c.add(at);
            acts.add(() -> {
                List<TodoList> lists = shownLists(store.view());
                List<String> names = new ArrayList<>();
                boolean[] off = new boolean[lists.size()];
                for (int i = 0; i < lists.size(); i++) {
                    names.add(lists.get(i).name);
                    off[i] = lists.get(i).id == it.listId;
                }
                openMenuAt(where, names, off, i -> store.moveToList(it.id, lists.get(i).id));
            });
        }
        if (it.hasLoc) {
            opts.add("Show on map");
            acts.add(() -> showOnMap(it));
        }
        if (edit) {
            opts.add("Delete");
            acts.add(() -> deleteWithUndo(it));
        }
        if (!opts.isEmpty())
            openMenuAt(list.c.add(at), opts, null, i -> acts.get(i).run());
    }

    @Override
    public void reorder(TodoItem it, double order) {
        store.setOrder(it.id, order);
    }

    @Override
    public void toggleDoneSection() {
        showDone = !showDone;
        refresh();
    }

    @Override
    public void clearDone() {
        long now = System.currentTimeMillis();
        List<Integer> ids = new ArrayList<>();
        /* Repeating chores are never cleared: they come back on their own. */
        for (TodoItem it : store.view().itemsOf(curList))
            if (!it.isOpen(now) && it.repeatH <= 0)
                ids.add(it.id);
        if (ids.isEmpty())
            return;
        for (int id : ids)
            store.delete(id);
        raiseUndo("Cleared " + ids.size() + (ids.size() == 1 ? " finished task" : " finished tasks"),
            ids.stream().mapToInt(Integer::intValue).toArray());
    }

    @Override
    public boolean isPending(int id) {
        return store.mode() != TodoStore.Mode.LOCAL && id > 0 && store.isPending(id);
    }

    @Override
    public boolean canEdit() {
        return store.canEdit(curList);
    }

    @Override
    public String me() {
        return store.me();
    }

    /* -------------------------------------------------------------- editor host */

    @Override
    public TodoStore store() {
        return store;
    }

    @Override
    public List<String> assigneeCandidates() {
        Set<String> names = new LinkedHashSet<>();
        String me = store.me();
        if (!me.isEmpty())
            names.add(me);
        Set<String> others = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            if (gui.buddies != null) {
                for (BuddyWnd.Buddy b : gui.buddies)
                    if (b.name != null && !b.name.isEmpty())
                        others.add(b.name);
            }
        } catch (RuntimeException ignore) {
            /* The kin list is a live widget the server mutates; missing it costs names, not the menu. */
        }
        if (gui.peerPositionService != null)
            for (PeerPosition p : gui.peerPositionService.snapshot())
                if (p.charName != null && !p.charName.isEmpty())
                    others.add(p.charName);
        for (TodoItem it : store.view().items.values()) {
            for (String n : new String[] {it.assignee, it.createdBy, it.doneBy, it.touchedBy})
                if (n != null && !n.isEmpty())
                    others.add(n);
        }
        others.remove(me);
        names.addAll(others);
        return new ArrayList<>(names);
    }

    @Override
    public void captureHere(TodoItem it) {
        Gob pl = NUtils.player();
        if (pl == null || gui.map == null) {
            gui.msg("Can't tell where you are right now.", Color.YELLOW);
            return;
        }
        try {
            MCache mc = gui.map.glob.map;
            Coord tc = pl.rc.floor(MCache.tilesz);
            MCache.Grid grid = mc.getgrid(tc.div(MCache.cmaps));
            Coord off = tc.sub(grid.ul);
            store.setLocation(it.id, grid.id, off.x, off.y);
        } catch (Loading e) {
            gui.msg("The map around you is still loading; try again in a moment.", Color.YELLOW);
        }
    }

    @Override
    public void showOnMap(TodoItem it) {
        if (!it.hasLoc)
            return;
        if (gui.mmap == null || gui.mmap.file == null || gui.ui == null || gui.ui.sess == null) {
            gui.msg("The map isn't available yet.", Color.YELLOW);
            return;
        }
        long gid = it.locGrid;
        Coord off = Coord.of(it.locX, it.locY);
        if (gui.pingService != null)
            gui.pingService.add(gid, off, PingService.DEFAULT_COLOR, PingService.SELF);
        final MapFile file = gui.mmap.file;
        /* gridinfo is a disk-backed cache: look it up on the loader, never on the UI thread. */
        gui.ui.sess.glob.loader.defer(() -> {
            file.lock.readLock().lock();
            try {
                MapFile.GridInfo info = file.gridinfo.get(gid);
                if (info == null)
                    pendingMsg = "That place is not on your map yet.";
                else
                    pendingCenter = new MiniMap.SpecLocator(info.seg, info.sc.mul(MCache.cmaps).add(off));
            } finally {
                file.lock.readLock().unlock();
            }
        }, null);
    }

    private void centerMap(MiniMap.Locator loc) {
        if (gui.mapfile == null || gui.mapfile.view == null)
            return;
        if (!gui.mapfile.visible())
            gui.togglewnd(gui.mapfile);
        gui.mapfile.view.center(loc);
        gui.mapfile.view.follow(null);
    }

    @Override
    public void deleteWithUndo(TodoItem it) {
        store.delete(it.id);
        if (selectedId == it.id)
            selectedId = 0;
        raiseUndo("Deleted \"" + it.title + "\"", new int[] {it.id});
    }

    private void raiseUndo(String text, int[] ids) {
        undoText = text;
        undoIds = ids;
        undoAt = System.currentTimeMillis();
        store.clearToast();
    }

    /* -------------------------------------------------------------- window */

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (msg.equals("close")) {
            editor.flushText();
            if (menu != null)
                menu.close();
            closePrompt();
            hide();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    @Override
    public void dispose() {
        tc.clear();
        super.dispose();
    }

    /** Relative age for audit lines: "just now", "5m ago", "3h ago", "2d ago". */
    static String ago(long at, long now) {
        if (at <= 0)
            return "";
        long s = Math.max(0, (now - at) / 1000);
        if (s < 60)
            return "just now";
        if (s < 3600)
            return (s / 60) + "m ago";
        if (s < 86400)
            return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }

    /** Draws the count of open tasks assigned to the player over the menu button. */
    public static void drawBadge(GOut g, Coord sz, int n) {
        if (n <= 0)
            return;
        String s = n > 99 ? "99+" : Integer.toString(n);
        Tex t = BadgeText.get(s);
        int h = t.sz().y + UI.scale(2);
        int w = Math.max(h, t.sz().x + UI.scale(6));
        Coord ul = Coord.of(sz.x - w, 0);
        CookbookTheme.fill(g, ul, Coord.of(w, h), CookbookTheme.accent);
        g.image(t, ul.add((w - t.sz().x) / 2, (h - t.sz().y) / 2));
    }

    /** Badge numbers are few and reused forever, so they are kept rather than evicted. */
    private static final class BadgeText {
        private static final java.util.Map<String, Tex> cache = new java.util.HashMap<>();

        static synchronized Tex get(String s) {
            return cache.computeIfAbsent(s, k -> CookbookTheme.render(CookbookTheme.bold, k, CookbookTheme.ink));
        }
    }

    /* -------------------------------------------------------------- small parts */

    /** All / Mine / Unassigned. */
    private class FilterBar extends Widget {
        private final String[] names = {"All", "Mine", "Unassigned"};
        private final int[] xs = new int[names.length + 1];

        FilterBar() {
            super(Coord.z);
            int x = 0;
            for (int i = 0; i < names.length; i++) {
                xs[i] = x;
                x += CookbookTheme.body.strsize(names[i]).x + UI.scale(14) + UI.scale(4);
            }
            xs[names.length] = x;
            resize(Coord.of(x, UI.scale(19)));
        }

        @Override
        public void draw(GOut g) {
            for (int i = 0; i < names.length; i++) {
                boolean on = filter.ordinal() == i;
                Coord ul = Coord.of(xs[i], 0);
                Coord csz = Coord.of(xs[i + 1] - xs[i] - UI.scale(4), sz.y);
                CookbookTheme.frame(g, ul, csz, on ? CookbookTheme.accent : CookbookTheme.outline);
                Tex t = tc.get(CookbookTheme.body, names[i], on ? CookbookTheme.accent : CookbookTheme.muted);
                g.image(t, ul.add((csz.x - t.sz().x) / 2, (csz.y - t.sz().y) / 2));
            }
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b != 1)
                return super.mousedown(ev);
            for (int i = 0; i < names.length; i++) {
                if (ev.c.x >= xs[i] && ev.c.x < xs[i + 1]) {
                    filter = Filter.values()[i];
                    refresh();
                    return true;
                }
            }
            return true;
        }
    }

    /** Offer to upload the shared lists of the local file after the database was switched on. */
    private class Banner extends Widget {
        private final PillButton upload, dismiss;
        private String text = "";

        Banner(int w) {
            super(Coord.of(w, UI.scale(28)));
            dismiss = add(new PillButton("Dismiss", false, store::dismissUpload));
            upload = add(new PillButton("Upload", false, store::uploadLocal));
            dismiss.c = Coord.of(w - dismiss.sz.x - UI.scale(2), (sz.y - dismiss.sz.y) / 2);
            upload.c = Coord.of(dismiss.c.x - upload.sz.x - UI.scale(4), dismiss.c.y);
        }

        void offer(int n) {
            text = n + (n == 1 ? " local task isn't" : " local tasks aren't") + " in the village database yet.";
            show(n > 0);
        }

        @Override
        public void draw(GOut g) {
            CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.warnBg);
            CookbookTheme.frame(g, Coord.z, sz, CookbookTheme.accent);
            Tex t = tc.get(CookbookTheme.body, CookbookTheme.ellipsize(CookbookTheme.body, text, upload.c.x - UI.scale(12)), CookbookTheme.fg);
            g.image(t, Coord.of(UI.scale(6), (sz.y - t.sz().y) / 2));
            super.draw(g);
        }
    }

    /** One transient line over the bottom of the list, with an optional Undo / Restore. */
    private class Toast extends Widget {
        private String text = "";
        private String action = null;
        private Runnable onAction = null;
        private int actionX = Integer.MAX_VALUE;

        Toast(int w) {
            super(Coord.of(w, UI.scale(22)));
        }

        void set(String text, String action, Runnable onAction) {
            this.text = text;
            this.action = action;
            this.onAction = onAction;
        }

        @Override
        public void draw(GOut g) {
            CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.popBg);
            CookbookTheme.frame(g, Coord.z, sz, CookbookTheme.accent);
            Tex x = tc.get(CookbookTheme.bold, "x", CookbookTheme.muted);
            int right = sz.x - x.sz().x - UI.scale(8);
            g.image(x, Coord.of(right, (sz.y - x.sz().y) / 2));
            actionX = right;
            if (action != null) {
                Tex a = tc.get(CookbookTheme.bold, action, CookbookTheme.accent);
                actionX = right - a.sz().x - UI.scale(12);
                g.image(a, Coord.of(actionX, (sz.y - a.sz().y) / 2));
            }
            Tex t = tc.get(CookbookTheme.body, CookbookTheme.ellipsize(CookbookTheme.body, text, actionX - UI.scale(14)), CookbookTheme.fg);
            g.image(t, Coord.of(UI.scale(6), (sz.y - t.sz().y) / 2));
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b != 1)
                return true;
            int closeX = sz.x - UI.scale(18);
            if (ev.c.x >= closeX) {
                undoText = null;
                store.clearToast();
            } else if (onAction != null && ev.c.x >= actionX - UI.scale(4)) {
                onAction.run();
            }
            hide();
            return true;
        }
    }

    /** Where the data lives and whether it has arrived, plus who changed something last. */
    private class Footer extends Widget {
        Footer(int w) {
            super(Coord.of(w, FOOTH));
        }

        @Override
        public void draw(GOut g) {
            long now = System.currentTimeMillis();
            Color dot;
            String left;
            TodoStore.Mode mode = store.mode();
            if (curList == TodoList.PERSONAL) {
                dot = IDLE;
                left = "Personal · stays on this computer";
            } else if (mode == TodoStore.Mode.LOCAL) {
                dot = IDLE;
                left = "Local only (database off)";
            } else if (mode == TodoStore.Mode.CONNECTING) {
                dot = TodoListView.PENDING;
                left = "Connecting to the database…";
            } else if (store.readOnly()) {
                dot = IDLE;
                left = "Read-only: guest access";
            } else if (store.dbError()) {
                int n = store.pendingCount();
                dot = BAD;
                left = "Database unreachable" + (n > 0 ? " · " + n + (n == 1 ? " change" : " changes") + " waiting" : "");
            } else if (store.pendingCount() > 0) {
                dot = TodoListView.PENDING;
                left = "Saving…";
            } else {
                dot = OK;
                left = "Synced · village database";
            }
            int ds = UI.scale(8);
            CookbookTheme.fill(g, Coord.of(0, (sz.y - ds) / 2), Coord.of(ds, ds), dot);
            Tex lt = tc.get(CookbookTheme.small, left, CookbookTheme.muted);
            g.image(lt, Coord.of(ds + UI.scale(6), (sz.y - lt.sz().y) / 2));
            TodoStore.View v = store.view();
            if (v.lastChange != null && curList != TodoList.PERSONAL) {
                int room = sz.x - (ds + UI.scale(6) + lt.sz().x + UI.scale(16));
                String s = CookbookTheme.ellipsize(CookbookTheme.small, v.lastChange + " · " + ago(v.lastChangeAt, now), room);
                Tex rt = tc.get(CookbookTheme.small, s, CookbookTheme.muted);
                g.image(rt, Coord.of(sz.x - rt.sz().x, (sz.y - rt.sz().y) / 2));
            }
        }
    }
}
