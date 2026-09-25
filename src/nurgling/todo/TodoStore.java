package nurgling.todo;

import nurgling.NConfig;
import nurgling.NCore;
import nurgling.NGameUI;
import nurgling.db.DatabaseManager;
import nurgling.db.dao.TodoDao;
import nurgling.db.service.TodoService;
import nurgling.profiles.ConfigFactory;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The To-Do lists of one session.
 *
 * <p>Shared lists live in the village database when {@link NConfig.Key#ndbenable} is on, and in a local
 * JSON file per world when it is off. The Personal list is always in that file, in both modes.
 *
 * <p>In database mode an edit applies to the view at once and waits in {@link #pending} until the sync
 * worker has written it. The view is always "last known database state + pending edits", rebuilt as an
 * immutable {@link View} on every change, so widgets read it without locking.
 */
public class TodoStore {
    public enum Mode { LOCAL, CONNECTING, DB }

    /** Name of the list created on the first add when there is no shared list yet. */
    public static final String DEFAULT_LIST = "Village";
    private static final String FILE_NAME = "todo.nurgling.json";
    private static final int FILE_VERSION = 1;
    private static final long SAVE_DEBOUNCE_MS = 1000;
    private static final long MTIME_CHECK_MS = 1000;
    private static final long TOMBSTONE_TTL_MS = 14L * 24 * 3600 * 1000;
    private static final int MAX_OCC_RETRIES = 4;
    private static final int MAX_OPS_PER_TICK = 200;

    /** Something to tell the user: a line for the system log, a toast for the window, or both. */
    public static final class Event {
        public final String chat;
        public final String toast;
        /** The task a Restore button on the toast brings back; 0 when there is none. */
        public final int restoreId;
        public final long at = System.currentTimeMillis();

        Event(String chat, String toast, int restoreId) {
            this.chat = chat;
            this.toast = toast;
            this.restoreId = restoreId;
        }
    }

    public static final class View {
        static final View EMPTY = new View(Collections.emptyList(), Collections.emptyMap(), 0, null, 0);

        /** Shared lists in tab order, then Personal. Never contains deleted lists. */
        public final List<TodoList> lists;
        /** Every non-deleted task, by id. Personal tasks have negative ids. */
        public final Map<Integer, TodoItem> items;
        /** Local shared tasks that could be uploaded to the database; 0 hides the offer. */
        public final int uploadOffer;
        /** The shared task or list touched most recently, for the "who changed what" footer line. */
        public final String lastChange;
        public final long lastChangeAt;

        View(List<TodoList> lists, Map<Integer, TodoItem> items, int uploadOffer, String lastChange, long lastChangeAt) {
            this.lists = lists;
            this.items = items;
            this.uploadOffer = uploadOffer;
            this.lastChange = lastChange;
            this.lastChangeAt = lastChangeAt;
        }

        public List<TodoItem> itemsOf(int listId) {
            List<TodoItem> out = new ArrayList<>();
            for (TodoItem it : items.values())
                if (it.listId == listId)
                    out.add(it);
            return out;
        }

        public TodoList list(int id) {
            for (TodoList l : lists)
                if (l.id == id)
                    return l;
            return null;
        }
    }

    private final String genus;
    private final String filePath;
    private final Supplier<String> me;
    private final Consumer<String> chat;
    private final Supplier<Mode> modeSource;
    private final Random rnd = new SecureRandom();
    private final Object lock = new Object();

    /* Last known state of the shared store (database rows, or the file in local mode). Objects in these
     * maps are never mutated once inserted - an edit replaces them - because views share them. */
    private final Map<Integer, TodoItem> baseItems = new HashMap<>();
    private final Map<Integer, TodoList> baseLists = new HashMap<>();
    private final Map<Integer, TodoItem> personalItems = new HashMap<>();
    private final List<TodoOp> pending = new ArrayList<>();

    /* Local-mode shared lists and tasks, kept as read from the file while in database mode: written back
     * untouched, and offered for upload. */
    private JSONArray asideLists = null;
    private JSONArray asideItems = null;
    private boolean uploadDismissed = false;

    private Mode loadedMode = null;
    /** Bumped on every mode switch, so a sync pass that started before it drops its results. */
    private int generation = 0;
    private boolean bulkLoaded = false;
    private boolean purged = false;
    private volatile boolean readOnly = false;
    private volatile boolean dbError = false;

    private boolean fileDirty = false;
    private long fileDirtyAt = 0;
    private long fileMtime = 0;
    private long lastMtimeCheck = 0;

    private volatile View view = View.EMPTY;
    private volatile int revision = 0;
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private volatile Event lastToast = null;

    private int badgeRevision = -1;
    private long badgeAt = 0;
    private int badgeCount = 0;

    public TodoStore(NGameUI gui, String genus) {
        this(ConfigFactory.getConfig(genus).getProfileAwarePath(FILE_NAME), genus,
            () -> gui.chrid == null ? "" : gui.chrid,
            line -> {
                if ((Boolean) NConfig.get(NConfig.Key.todoNotify))
                    gui.msg("[To-Do] " + line, new java.awt.Color(233, 156, 84));
            },
            TodoStore::computeMode);
    }

    /** For tests: everything the store needs from the game, as plain callbacks. */
    TodoStore(String filePath, String genus, Supplier<String> me, Consumer<String> chat, Supplier<Mode> modeSource) {
        this.filePath = filePath;
        this.genus = genus;
        this.me = me;
        this.chat = chat;
        this.modeSource = modeSource;
    }

    /* -------------------------------------------------------------- reading */

    public View view() {
        return view;
    }

    public int revision() {
        return revision;
    }

    public Mode mode() {
        Mode m = loadedMode;
        return m == null ? Mode.LOCAL : m;
    }

    public boolean readOnly() {
        return readOnly && mode() != Mode.LOCAL;
    }

    public boolean dbError() {
        return dbError && mode() == Mode.DB;
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    public boolean isPending(int id) {
        synchronized (lock) {
            for (TodoOp op : pending)
                if (!op.isList && op.id == id)
                    return true;
        }
        return false;
    }

    public Event lastToast() {
        return lastToast;
    }

    public void clearToast() {
        lastToast = null;
    }

    /** The character this client plays; used for "Mine", assignment and the audit fields. */
    public String me() {
        String m = me.get();
        return m == null ? "" : m;
    }

    /** Whether the user may change tasks in this list right now. Guests still own their Personal list. */
    public boolean canEdit(int listId) {
        return listId == TodoList.PERSONAL || !readOnly();
    }

    /** Open tasks assigned to this character, for the menu button badge. Cached; cheap per frame. */
    public int openAssignedToMe() {
        long now = System.currentTimeMillis();
        View v = view;
        if (badgeRevision != revision || now - badgeAt > 30_000) {
            int n = 0;
            String me = me();
            for (TodoItem it : v.items.values())
                if (it.isOpen(now) && it.isAssignedTo(me))
                    n++;
            badgeCount = n;
            badgeRevision = revision;
            badgeAt = now;
        }
        return badgeCount;
    }

    /* -------------------------------------------------------------- UI-thread housekeeping */

    /** Called every frame from the game UI. */
    public void tick() {
        ensureMode();
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (fileDirty && now - fileDirtyAt >= SAVE_DEBOUNCE_MS)
                saveFileLocked();
            /* Another client or session on this world may have written the file; pick its edits up. */
            if (!fileDirty && now - lastMtimeCheck >= MTIME_CHECK_MS) {
                lastMtimeCheck = now;
                long m = new File(filePath).lastModified();
                if (m != fileMtime) {
                    boolean local = loadedMode == Mode.LOCAL;
                    if (local) {
                        baseItems.clear();
                        baseLists.clear();
                    }
                    loadFileLocked(local);
                    rebuildLocked();
                }
            }
        }
        Event e;
        while ((e = events.poll()) != null) {
            if (e.chat != null)
                chat.accept(e.chat);
            if (e.toast != null)
                lastToast = e;
        }
    }

    /** Writes any unsaved file change now; for session teardown. */
    public void flushFile() {
        synchronized (lock) {
            if (fileDirty)
                saveFileLocked();
        }
    }

    private static Mode computeMode() {
        if (!(Boolean) NConfig.get(NConfig.Key.ndbenable))
            return Mode.LOCAL;
        DatabaseManager dbm = NCore.databaseManager;
        if (dbm == null || !dbm.isReady() || dbm.getTodoService() == null)
            return Mode.CONNECTING;
        return Mode.DB;
    }

    private void ensureMode() {
        Mode m = modeSource.get();
        if (m == loadedMode)
            return;
        synchronized (lock) {
            boolean wasLocal = loadedMode == null || loadedMode == Mode.LOCAL;
            if (m == Mode.LOCAL || wasLocal) {
                if (fileDirty)
                    saveFileLocked();
                generation++;
                baseItems.clear();
                baseLists.clear();
                pending.clear();
                bulkLoaded = false;
                readOnly = false;
                dbError = false;
                loadFileLocked(m == Mode.LOCAL);
            }
            /* CONNECTING <-> DB keeps everything: the database only blinked. */
            loadedMode = m;
            rebuildLocked();
        }
    }

    /* -------------------------------------------------------------- editing */

    /** Adds a task at the bottom of a list. List id 0 means "the default list", created on demand. */
    public TodoItem addItem(int listId, String title) {
        String t = clip(title, TodoItem.MAX_TITLE);
        if (t.isEmpty())
            return null;
        synchronized (lock) {
            if (!canEdit(listId))
                return null;
            if (listId == 0)
                listId = createListLocked(DEFAULT_LIST);
            long now = System.currentTimeMillis();
            TodoItem it = new TodoItem();
            it.id = (listId == TodoList.PERSONAL) ? -freshIdLocked() : freshIdLocked();
            it.listId = listId;
            it.title = t;
            it.order = maxOrderLocked(listId) + 1;
            it.createdBy = it.touchedBy = me();
            it.createdAt = it.touchedAt = now;
            putNewItemLocked(it);
            rebuildLocked();
            return it;
        }
    }

    public void setDone(int id, boolean done) {
        long now = System.currentTimeMillis();
        String me = me();
        editItem(id, EnumSet.of(TodoOp.Field.DONE), it -> {
            it.done = done;
            it.doneBy = done ? me : "";
            it.doneAt = done ? now : 0;
        });
    }

    public void setTitle(int id, String title) {
        String t = clip(title, TodoItem.MAX_TITLE);
        if (!t.isEmpty())
            editItem(id, EnumSet.of(TodoOp.Field.TITLE), it -> it.title = t);
    }

    public void setNotes(int id, String notes) {
        String n = notes == null ? "" : (notes.length() > TodoItem.MAX_NOTES ? notes.substring(0, TodoItem.MAX_NOTES) : notes);
        editItem(id, EnumSet.of(TodoOp.Field.NOTES), it -> it.notes = n);
    }

    public void setAssignee(int id, String who) {
        String w = who == null ? "" : who.trim();
        editItem(id, EnumSet.of(TodoOp.Field.ASSIGNEE), it -> it.assignee = w);
    }

    public void setUrgent(int id, boolean urgent) {
        editItem(id, EnumSet.of(TodoOp.Field.URGENT), it -> it.urgent = urgent);
    }

    public void setRepeat(int id, int hours) {
        int h = Math.max(0, hours);
        editItem(id, EnumSet.of(TodoOp.Field.REPEAT), it -> it.repeatH = h);
    }

    public void setLocation(int id, long gridId, int x, int y) {
        editItem(id, EnumSet.of(TodoOp.Field.LOC), it -> {
            it.hasLoc = true;
            it.locGrid = gridId;
            it.locX = x;
            it.locY = y;
        });
    }

    public void clearLocation(int id) {
        editItem(id, EnumSet.of(TodoOp.Field.LOC), it -> {
            it.hasLoc = false;
            it.locGrid = 0;
            it.locX = it.locY = 0;
        });
    }

    public void setOrder(int id, double order) {
        editItem(id, EnumSet.of(TodoOp.Field.ORDER), it -> it.order = order);
    }

    public void delete(int id) {
        editItem(id, EnumSet.of(TodoOp.Field.DELETED), it -> it.deleted = true);
    }

    /** Undoes a delete, whether it was ours or someone else's. */
    public void restore(int id) {
        synchronized (lock) {
            if (findItemLocked(id, true) == null)
                return;
        }
        editItem(id, EnumSet.of(TodoOp.Field.DELETED), it -> it.deleted = false);
    }

    /** Moves a task to another list. Crossing the Personal boundary makes a new row on the other side. */
    public void moveToList(int id, int target) {
        synchronized (lock) {
            TodoItem cur = findItemLocked(id, false);
            if (cur == null || cur.listId == target || !canEdit(cur.listId) || !canEdit(target))
                return;
            if (target == 0)
                target = createListLocked(DEFAULT_LIST);
            final int dest = target;
            double order = maxOrderLocked(dest) + 1;
            boolean fromPersonal = cur.listId == TodoList.PERSONAL;
            boolean toPersonal = dest == TodoList.PERSONAL;
            if (!fromPersonal && !toPersonal) {
                editItemLocked(id, EnumSet.of(TodoOp.Field.LIST, TodoOp.Field.ORDER), it -> {
                    it.listId = dest;
                    it.order = order;
                });
            } else {
                TodoItem moved = cur.copy();
                moved.id = toPersonal ? -freshIdLocked() : freshIdLocked();
                moved.listId = dest;
                moved.order = order;
                moved.version = 0;
                moved.touchedBy = me();
                moved.touchedAt = System.currentTimeMillis();
                if (fromPersonal) {
                    personalItems.remove(id);
                    markFileDirtyLocked();
                } else {
                    editItemLocked(id, EnumSet.of(TodoOp.Field.DELETED), it -> it.deleted = true);
                }
                putNewItemLocked(moved);
            }
            rebuildLocked();
        }
    }

    /** Creates a shared list and returns its id, or 0 when the user cannot write. */
    public int addList(String name) {
        String n = clip(name, TodoList.MAX_NAME);
        if (n.isEmpty())
            return 0;
        synchronized (lock) {
            if (readOnly())
                return 0;
            int id = createListLocked(n);
            rebuildLocked();
            return id;
        }
    }

    public void renameList(int id, String name) {
        String n = clip(name, TodoList.MAX_NAME);
        if (n.isEmpty() || id == TodoList.PERSONAL)
            return;
        editList(id, l -> l.name = n);
    }

    /** Deletes a shared list and every task in it. */
    public void deleteList(int id) {
        if (id == TodoList.PERSONAL)
            return;
        synchronized (lock) {
            if (readOnly())
                return;
            for (TodoItem it : view.itemsOf(id))
                editItemLocked(it.id, EnumSet.of(TodoOp.Field.DELETED), x -> x.deleted = true);
            editListLocked(id, l -> l.deleted = true);
            rebuildLocked();
        }
    }

    /** Sends the shared lists of the local file to the database, merging lists that share a name. */
    public void uploadLocal() {
        synchronized (lock) {
            if (loadedMode != Mode.DB || readOnly || asideItems == null)
                return;
            Map<Integer, Integer> listMap = new HashMap<>();
            if (asideLists != null) {
                for (int i = 0; i < asideLists.length(); i++) {
                    TodoList l = parseList(asideLists.optJSONObject(i));
                    if (l == null || l.deleted)
                        continue;
                    Integer existing = listByNameLocked(l.name);
                    listMap.put(l.id, existing != null ? existing : createListLocked(l.name));
                }
            }
            long now = System.currentTimeMillis();
            for (int i = 0; i < asideItems.length(); i++) {
                TodoItem it = parseItem(asideItems.optJSONObject(i));
                if (it == null || it.deleted)
                    continue;
                Integer dest = listMap.get(it.listId);
                if (dest == null) {
                    Integer def = listByNameLocked(DEFAULT_LIST);
                    dest = def != null ? def : createListLocked(DEFAULT_LIST);
                    listMap.put(it.listId, dest);
                }
                TodoItem n = it.copy();
                n.id = freshIdLocked();
                n.listId = dest;
                n.order = maxOrderLocked(dest) + 1;
                n.version = 0;
                n.touchedBy = me();
                n.touchedAt = now;
                pending.add(TodoOp.createItem(n));
            }
            asideLists = null;
            asideItems = null;
            markFileDirtyLocked();
            rebuildLocked();
        }
    }

    public void dismissUpload() {
        synchronized (lock) {
            uploadDismissed = true;
            rebuildLocked();
        }
    }

    private void editItem(int id, Set<TodoOp.Field> fields, Consumer<TodoItem> fn) {
        synchronized (lock) {
            editItemLocked(id, fields, fn);
            rebuildLocked();
        }
    }

    private void editItemLocked(int id, Set<TodoOp.Field> fields, Consumer<TodoItem> fn) {
        TodoItem cur = findItemLocked(id, true);
        if (cur == null || !canEdit(cur.listId))
            return;
        String me = me();
        long now = System.currentTimeMillis();
        Consumer<TodoItem> stamped = it -> {
            fn.accept(it);
            it.touchedBy = me;
            it.touchedAt = now;
        };
        if (cur.listId == TodoList.PERSONAL) {
            TodoItem n = personalItems.get(id).copy();
            stamped.accept(n);
            personalItems.put(id, n);
            markFileDirtyLocked();
        } else if (loadedMode == Mode.LOCAL) {
            TodoItem n = baseItems.get(id).copy();
            stamped.accept(n);
            baseItems.put(id, n);
            markFileDirtyLocked();
        } else {
            pending.add(TodoOp.editItem(id, fields, stamped, cur.copy()));
        }
    }

    private void editList(int id, Consumer<TodoList> fn) {
        synchronized (lock) {
            if (readOnly())
                return;
            editListLocked(id, fn);
            rebuildLocked();
        }
    }

    private void editListLocked(int id, Consumer<TodoList> fn) {
        String me = me();
        long now = System.currentTimeMillis();
        Consumer<TodoList> stamped = l -> {
            fn.accept(l);
            l.touchedBy = me;
            l.touchedAt = now;
        };
        if (loadedMode == Mode.LOCAL) {
            TodoList cur = baseLists.get(id);
            if (cur == null)
                return;
            TodoList n = cur.copy();
            stamped.accept(n);
            baseLists.put(id, n);
            markFileDirtyLocked();
        } else {
            pending.add(TodoOp.editList(id, stamped));
        }
    }

    private int createListLocked(String name) {
        TodoList l = new TodoList();
        l.id = freshIdLocked();
        l.name = name;
        l.order = maxListOrderLocked() + 1;
        l.touchedBy = me();
        l.touchedAt = System.currentTimeMillis();
        if (loadedMode == Mode.LOCAL) {
            baseLists.put(l.id, l);
            markFileDirtyLocked();
        } else {
            pending.add(TodoOp.createList(l));
        }
        return l.id;
    }

    private void putNewItemLocked(TodoItem it) {
        if (it.listId == TodoList.PERSONAL) {
            personalItems.put(it.id, it);
            markFileDirtyLocked();
        } else if (loadedMode == Mode.LOCAL) {
            baseItems.put(it.id, it);
            markFileDirtyLocked();
        } else {
            pending.add(TodoOp.createItem(it.copy()));
        }
    }

    /** The task as the user currently sees it, optionally including a deleted one. */
    private TodoItem findItemLocked(int id, boolean withDeleted) {
        TodoItem it = view.items.get(id);
        if (it != null)
            return it;
        if (!withDeleted)
            return null;
        it = id < 0 ? personalItems.get(id) : baseItems.get(id);
        return it;
    }

    private Integer listByNameLocked(String name) {
        for (TodoList l : view.lists)
            if (!l.isPersonal() && l.name.equalsIgnoreCase(name))
                return l.id;
        for (TodoOp op : pending)
            if (op.newList != null && op.newList.name.equalsIgnoreCase(name))
                return op.newList.id;
        return null;
    }

    private double maxOrderLocked(int listId) {
        double max = 0;
        for (TodoItem it : view.items.values())
            if (it.listId == listId)
                max = Math.max(max, it.order);
        for (TodoOp op : pending)
            if (op.newItem != null && op.newItem.listId == listId)
                max = Math.max(max, op.newItem.order);
        return max;
    }

    private double maxListOrderLocked() {
        double max = 0;
        for (TodoList l : view.lists)
            if (!l.isPersonal())
                max = Math.max(max, l.order);
        for (TodoOp op : pending)
            if (op.newList != null)
                max = Math.max(max, op.newList.order);
        return max;
    }

    /** A random positive id no row or pending create is using. Personal tasks use its negation. */
    private int freshIdLocked() {
        while (true) {
            int id = rnd.nextInt(Integer.MAX_VALUE - 2) + 2;
            if (baseItems.containsKey(id) || baseLists.containsKey(id) || personalItems.containsKey(-id))
                continue;
            boolean used = false;
            for (TodoOp op : pending)
                used |= op.id == id;
            if (!used)
                return id;
        }
    }

    private static String clip(String s, int max) {
        if (s == null)
            return "";
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max) : t;
    }

    /* -------------------------------------------------------------- view */

    private void rebuildLocked() {
        Map<Integer, TodoList> lists = new HashMap<>(baseLists);
        Map<Integer, TodoItem> items = new HashMap<>(baseItems);
        for (TodoOp op : pending) {
            if (op.isList) {
                if (op.newList != null) {
                    lists.put(op.id, op.newList);
                } else {
                    TodoList l = lists.get(op.id);
                    if (l != null) {
                        l = l.copy();
                        op.listFn.accept(l);
                        lists.put(op.id, l);
                    }
                }
            } else {
                if (op.newItem != null) {
                    items.put(op.id, op.newItem);
                } else {
                    TodoItem it = items.get(op.id);
                    if (it != null) {
                        it = it.copy();
                        op.itemFn.accept(it);
                        items.put(op.id, it);
                    }
                }
            }
        }
        String lastChange = null;
        long lastAt = 0;
        List<TodoList> ordered = new ArrayList<>();
        Set<Integer> live = new HashSet<>();
        for (TodoList l : lists.values()) {
            if (l.touchedAt > lastAt && !l.touchedBy.isEmpty()) {
                lastAt = l.touchedAt;
                lastChange = l.touchedBy + (l.deleted ? " deleted list \"" : " changed list \"") + l.name + "\"";
            }
            if (!l.deleted) {
                ordered.add(l);
                live.add(l.id);
            }
        }
        ordered.sort((a, b) -> a.order != b.order ? Double.compare(a.order, b.order) : a.name.compareToIgnoreCase(b.name));
        TodoList personal = new TodoList();
        personal.id = TodoList.PERSONAL;
        personal.name = "Personal";
        ordered.add(personal);

        Map<Integer, TodoItem> visible = new HashMap<>();
        for (TodoItem it : items.values()) {
            if (it.touchedAt > lastAt && !it.touchedBy.isEmpty()) {
                lastAt = it.touchedAt;
                lastChange = it.touchedBy + (it.deleted ? " deleted \"" : (it.done && it.doneAt == it.touchedAt ? " finished \"" : " changed \"")) + it.title + "\"";
            }
            if (!it.deleted && live.contains(it.listId))
                visible.put(it.id, it);
        }
        for (TodoItem it : personalItems.values())
            if (!it.deleted)
                visible.put(it.id, it);

        int offer = 0;
        if (loadedMode == Mode.DB && bulkLoaded && !uploadDismissed && !readOnly && asideItems != null) {
            for (int i = 0; i < asideItems.length(); i++) {
                JSONObject o = asideItems.optJSONObject(i);
                if (o != null && !o.optBoolean("deleted", false))
                    offer++;
            }
        }
        view = new View(Collections.unmodifiableList(ordered), Collections.unmodifiableMap(visible), offer, lastChange, lastAt);
        revision++;
    }

    /* -------------------------------------------------------------- database sync (sync worker thread) */

    /** One sync pass: first-time load, push pending edits, pull what others changed. */
    public void syncOnce(TodoService svc) {
        int gen;
        boolean needBulk;
        synchronized (lock) {
            if (loadedMode != Mode.DB)
                return;
            gen = generation;
            needBulk = !bulkLoaded;
        }
        String profile = TodoDao.profileFor(genus);
        try {
            if (needBulk) {
                boolean writable = svc.canWrite();
                List<TodoDao.Row> rows = svc.loadAll(profile);
                synchronized (lock) {
                    if (gen != generation)
                        return;
                    readOnly = !writable;
                    if (readOnly)
                        pending.clear();
                    applyRowsLocked(rows, Collections.emptySet(), true);
                    bulkLoaded = true;
                    rebuildLocked();
                }
                if (!purged && writable) {
                    purged = true;
                    svc.purgeTombstones(profile, System.currentTimeMillis() - TOMBSTONE_TTL_MS);
                }
            }
            if (!readOnly)
                flush(svc, profile, gen);
            poll(svc, profile, gen);
            dbError = false;
        } catch (SQLException e) {
            if ("42501".equals(e.getSQLState())) {
                /* Permission denied: this login is a guest. Drop what cannot be written and reload. */
                synchronized (lock) {
                    readOnly = true;
                    pending.clear();
                    bulkLoaded = false;
                    rebuildLocked();
                }
                dbError = false;
            } else {
                if (!dbError)
                    System.err.println("Todo sync: " + e.getMessage());
                dbError = true;
            }
        }
    }

    private void flush(TodoService svc, String profile, int gen) throws SQLException {
        for (int n = 0; n < MAX_OPS_PER_TICK; n++) {
            TodoOp op;
            synchronized (lock) {
                if (gen != generation || pending.isEmpty())
                    return;
                op = pending.get(0);
            }
            if (op.isCreate())
                pushCreate(svc, profile, gen, op);
            else
                pushEdit(svc, profile, gen, op);
        }
    }

    private void pushCreate(TodoService svc, String profile, int gen, TodoOp op) throws SQLException {
        String path = op.isList ? TodoDao.PATH_LIST : TodoDao.PATH_ITEM;
        for (int attempt = 0; attempt < MAX_OCC_RETRIES; attempt++) {
            int id;
            String name, data;
            synchronized (lock) {
                id = op.id;
                name = op.isList ? op.newList.name : op.newItem.title;
                data = (op.isList ? op.newList.toJson() : op.newItem.toJson()).toString();
            }
            if (svc.insert(profile, id, path, name, data)) {
                synchronized (lock) {
                    if (gen != generation)
                        return;
                    if (op.isList) {
                        TodoList l = op.newList.copy();
                        l.version = 1;
                        baseLists.put(id, l);
                    } else {
                        TodoItem it = op.newItem.copy();
                        it.version = 1;
                        baseItems.put(id, it);
                    }
                    pending.remove(op);
                    rebuildLocked();
                }
                return;
            }
            /* The id is taken: either our own insert whose reply was lost, or a real clash. */
            TodoDao.Row row = svc.loadOne(profile, id);
            synchronized (lock) {
                if (gen != generation)
                    return;
                if (row != null && isOwnCreate(op, row)) {
                    applyRowsLocked(Collections.singletonList(row), Collections.emptySet(), false);
                    pending.remove(op);
                    rebuildLocked();
                    return;
                }
                remapLocked(op, freshIdLocked());
            }
        }
        synchronized (lock) {
            pending.remove(op);
            rebuildLocked();
        }
    }

    private static boolean isOwnCreate(TodoOp op, TodoDao.Row row) {
        try {
            JSONObject o = new JSONObject(row.data == null ? "{}" : row.data);
            if (op.isList)
                return row.isList() && op.newList.name.equals(row.name) && o.optLong("touchedAt") == op.newList.touchedAt;
            return !row.isList() && op.newItem.createdBy.equals(o.optString("createdBy"))
                && o.optLong("createdAt") == op.newItem.createdAt;
        } catch (JSONException e) {
            return false;
        }
    }

    private void remapLocked(TodoOp op, int fresh) {
        int old = op.id;
        for (TodoOp o : pending) {
            if (o.isList == op.isList && o.id == old)
                o.id = fresh;
            if (op.isList && o.newItem != null && o.newItem.listId == old)
                o.newItem.listId = fresh;
        }
        if (op.isList)
            op.newList.id = fresh;
        else
            op.newItem.id = fresh;
        rebuildLocked();
    }

    private void pushEdit(TodoService svc, String profile, int gen, TodoOp op) throws SQLException {
        for (int attempt = 0; attempt < MAX_OCC_RETRIES; attempt++) {
            String name, data;
            int expected;
            TodoItem nextItem = null;
            TodoList nextList = null;
            synchronized (lock) {
                if (gen != generation)
                    return;
                if (op.isList) {
                    TodoList base = baseLists.get(op.id);
                    if (base == null) {
                        pending.remove(op);
                        rebuildLocked();
                        return;
                    }
                    nextList = base.copy();
                    op.listFn.accept(nextList);
                    name = nextList.name;
                    data = nextList.toJson().toString();
                    expected = base.version;
                } else {
                    TodoItem base = baseItems.get(op.id);
                    if (base == null) {
                        pending.remove(op);
                        rebuildLocked();
                        return;
                    }
                    nextItem = base.copy();
                    op.itemFn.accept(nextItem);
                    name = nextItem.title;
                    data = nextItem.toJson().toString();
                    expected = base.version;
                }
            }
            int nv = svc.update(profile, op.id, name, data, expected);
            if (nv > 0) {
                synchronized (lock) {
                    if (gen != generation)
                        return;
                    if (op.isList) {
                        nextList.version = nv;
                        baseLists.put(op.id, nextList);
                    } else {
                        nextItem.version = nv;
                        baseItems.put(op.id, nextItem);
                    }
                    pending.remove(op);
                    rebuildLocked();
                }
                return;
            }
            /* Someone else wrote first: take their row and re-apply our edit on top of it. */
            TodoDao.Row row = svc.loadOne(profile, op.id);
            synchronized (lock) {
                if (gen != generation)
                    return;
                if (row == null) {
                    baseItems.remove(op.id);
                    baseLists.remove(op.id);
                    pending.remove(op);
                    rebuildLocked();
                    return;
                }
                if (!op.isList) {
                    TodoItem remote = parseRow(row);
                    if (remote != null && remote.deleted && !op.fields.contains(TodoOp.Field.DELETED)) {
                        baseItems.put(op.id, remote);
                        pending.remove(op);
                        events.add(new Event(null, who(remote.touchedBy) + " deleted \"" + remote.title + "\"", remote.id));
                        rebuildLocked();
                        return;
                    }
                    if (remote != null && op.collidesWith(remote) && !me().equals(remote.touchedBy))
                        events.add(new Event(null, who(remote.touchedBy) + " also changed \"" + remote.title + "\"", 0));
                }
                applyRowsLocked(Collections.singletonList(row), Collections.emptySet(), false);
            }
        }
        System.err.println("Todo sync: gave up on an edit of " + op.id + " after " + MAX_OCC_RETRIES + " conflicts");
        synchronized (lock) {
            pending.remove(op);
            rebuildLocked();
        }
    }

    private void poll(TodoService svc, String profile, int gen) throws SQLException {
        Map<Integer, Integer> versions = svc.versions(profile);
        Set<Integer> fetch = new HashSet<>();
        Set<Integer> removed = new HashSet<>();
        synchronized (lock) {
            if (gen != generation)
                return;
            for (Map.Entry<Integer, Integer> e : versions.entrySet()) {
                TodoItem it = baseItems.get(e.getKey());
                TodoList l = baseLists.get(e.getKey());
                int local = it != null ? it.version : (l != null ? l.version : 0);
                if (e.getValue() > local)
                    fetch.add(e.getKey());
            }
            for (Integer id : baseItems.keySet())
                if (!versions.containsKey(id))
                    removed.add(id);
            for (Integer id : baseLists.keySet())
                if (!versions.containsKey(id))
                    removed.add(id);
        }
        List<TodoDao.Row> rows = fetch.isEmpty() ? Collections.emptyList() : svc.load(profile, fetch);
        if (rows.isEmpty() && removed.isEmpty())
            return;
        synchronized (lock) {
            if (gen != generation)
                return;
            applyRowsLocked(rows, removed, false);
            rebuildLocked();
        }
    }

    private void applyRowsLocked(List<TodoDao.Row> rows, Set<Integer> removed, boolean full) {
        if (full) {
            baseItems.clear();
            baseLists.clear();
        }
        for (TodoDao.Row row : rows) {
            if (row.isList()) {
                TodoList l = parseListRow(row);
                if (l != null)
                    baseLists.put(row.id, l);
            } else {
                TodoItem it = parseRow(row);
                if (it == null)
                    continue;
                TodoItem old = baseItems.put(row.id, it);
                if (!full)
                    notifyChange(old, it);
            }
        }
        for (Integer id : removed) {
            baseItems.remove(id);
            baseLists.remove(id);
        }
    }

    /** Queues a system-log line when someone else assigns us a task or finishes one we created. */
    private void notifyChange(TodoItem old, TodoItem now) {
        String me = me();
        if (me.isEmpty() || now.deleted || me.equals(now.touchedBy))
            return;
        if (now.isAssignedTo(me) && (old == null || !old.isAssignedTo(me)) && now.isOpen(System.currentTimeMillis()))
            events.add(new Event(who(now.touchedBy) + " assigned you: " + now.title, null, 0));
        if (now.done && old != null && (!old.done || old.doneAt != now.doneAt) && me.equals(now.createdBy)
            && !me.equals(now.doneBy))
            events.add(new Event(who(now.doneBy) + " finished: " + now.title, null, 0));
    }

    private static String who(String name) {
        return (name == null || name.isEmpty()) ? "Someone" : name;
    }

    private static TodoItem parseRow(TodoDao.Row row) {
        try {
            TodoItem it = TodoItem.fromJson(row.id, row.name, new JSONObject(row.data == null ? "{}" : row.data));
            it.version = row.version;
            return it;
        } catch (JSONException e) {
            System.err.println("Todo sync: unreadable task row " + row.id + ": " + e.getMessage());
            return null;
        }
    }

    private static TodoList parseListRow(TodoDao.Row row) {
        try {
            TodoList l = TodoList.fromJson(row.id, row.name, new JSONObject(row.data == null ? "{}" : row.data));
            l.version = row.version;
            return l;
        } catch (JSONException e) {
            System.err.println("Todo sync: unreadable list row " + row.id + ": " + e.getMessage());
            return null;
        }
    }

    /* -------------------------------------------------------------- file */

    private void markFileDirtyLocked() {
        fileDirty = true;
        fileDirtyAt = System.currentTimeMillis();
    }

    /** Reads the Personal list, plus the shared lists (into the store, or set aside in database mode). */
    private void loadFileLocked(boolean includeShared) {
        personalItems.clear();
        asideLists = null;
        asideItems = null;
        fileMtime = new File(filePath).lastModified();
        String content = NFileUtils.readWithBackupFallback(filePath);
        if (content == null || content.isEmpty())
            return;
        try {
            JSONObject main = new JSONObject(content);
            JSONArray personal = main.optJSONArray("personal");
            if (personal != null) {
                for (int i = 0; i < personal.length(); i++) {
                    TodoItem it = parseItem(personal.optJSONObject(i));
                    if (it == null || it.deleted)
                        continue;
                    it.listId = TodoList.PERSONAL;
                    if (it.id >= 0)
                        it.id = -(Math.abs(it.id) + 2);
                    personalItems.put(it.id, it);
                }
            }
            JSONArray lists = main.optJSONArray("lists");
            JSONArray items = main.optJSONArray("items");
            if (includeShared) {
                if (lists != null)
                    for (int i = 0; i < lists.length(); i++) {
                        TodoList l = parseList(lists.optJSONObject(i));
                        if (l != null && !l.deleted)
                            baseLists.put(l.id, l);
                    }
                if (items != null)
                    for (int i = 0; i < items.length(); i++) {
                        TodoItem it = parseItem(items.optJSONObject(i));
                        if (it != null && !it.deleted && it.id > 0)
                            baseItems.put(it.id, it);
                    }
            } else {
                asideLists = (lists != null && lists.length() > 0) ? lists : null;
                asideItems = (items != null && items.length() > 0) ? items : null;
            }
        } catch (JSONException e) {
            System.err.println("[TodoStore] ignoring unreadable " + filePath + ": " + e.getMessage());
        }
    }

    private void saveFileLocked() {
        fileDirty = false;
        JSONObject main = new JSONObject();
        main.put("version", FILE_VERSION);
        JSONArray personal = new JSONArray();
        for (TodoItem it : personalItems.values())
            if (!it.deleted)
                personal.put(itemJson(it));
        main.put("personal", personal);
        if (loadedMode == Mode.LOCAL) {
            JSONArray lists = new JSONArray();
            for (TodoList l : baseLists.values())
                if (!l.deleted)
                    lists.put(listJson(l));
            JSONArray items = new JSONArray();
            for (TodoItem it : baseItems.values())
                if (!it.deleted)
                    items.put(itemJson(it));
            main.put("lists", lists);
            main.put("items", items);
        } else {
            if (asideLists != null)
                main.put("lists", asideLists);
            if (asideItems != null)
                main.put("items", asideItems);
        }
        try {
            NFileUtils.writeAtomically(filePath, main.toString());
            fileMtime = new File(filePath).lastModified();
        } catch (IOException e) {
            System.err.println("[TodoStore] save failed, will retry: " + e.getMessage());
            markFileDirtyLocked();
        }
    }

    private static JSONObject itemJson(TodoItem it) {
        JSONObject o = it.toJson();
        o.put("id", it.id);
        o.put("title", it.title);
        return o;
    }

    private static JSONObject listJson(TodoList l) {
        JSONObject o = l.toJson();
        o.put("id", l.id);
        o.put("name", l.name);
        return o;
    }

    private static TodoItem parseItem(JSONObject o) {
        if (o == null || !o.has("id"))
            return null;
        return TodoItem.fromJson(o.optInt("id"), o.optString("title", ""), o);
    }

    private static TodoList parseList(JSONObject o) {
        if (o == null || !o.has("id"))
            return null;
        return TodoList.fromJson(o.optInt("id"), o.optString("name", ""), o);
    }
}
