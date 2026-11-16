package nurgling.leveling;

import haven.Coord;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every leveling plan of one world, shared by all sessions logged into it
 * ({@link nurgling.sessions.SessionManager#levelPlans}). Local only: the file is the whole store.
 *
 * <p>Reads are lock-free: {@link #plans()} is an immutable snapshot of immutable plans, swapped on
 * every change. Each map grid has a revision, bumped whenever a plan's tiles or target there change,
 * so the map only redraws the grids an edit touched. File writes are coalesced onto a background
 * thread, because painting happens on the UI thread.
 */
public class LevelPlans {
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Level-Plans-Writer");
        t.setDaemon(true);
        return t;
    });

    private final String path;
    private final Executor writer;
    private volatile List<LevelPlan> plans = Collections.emptyList();
    private final ConcurrentHashMap<Long, Long> gridRevs = new ConcurrentHashMap<>();
    private volatile long revision = 0;
    /** Survey units per height unit, as the server last sent it with a Land Survey; NaN until one is seen. */
    private volatile double gran = Double.NaN;

    private final Object saveLock = new Object();
    private final AtomicBoolean saveQueued = new AtomicBoolean(false);

    public LevelPlans(String path) {
        this(path, WRITER);
    }

    /** With the file writes run on {@code writer}; tests pass one that runs them in place. */
    LevelPlans(String path, Executor writer) {
        this.path = path;
        this.writer = writer;
        load();
    }

    /** Every plan, oldest first. Safe to hold: neither the list nor the plans ever change. */
    public List<LevelPlan> plans() {return(plans);}

    public LevelPlan get(String id) {
        if(id == null)
            return(null);
        for(LevelPlan p : plans) {
            if(p.id.equals(id))
                return(p);
        }
        return(null);
    }

    /** Bumped on every change to any plan. */
    public long revision() {return(revision);}

    /** Bumped whenever what a plan draws on this grid changes. */
    public long gridRevision(long gid) {
        Long r = gridRevs.get(gid);
        return((r == null) ? 0 : r);
    }

    /** The plan a tile belongs to, or null. A tile belongs to at most one plan. */
    public LevelPlan planAt(long gid, int x, int y) {
        for(LevelPlan p : plans) {
            if(p.contains(gid, x, y))
                return(p);
        }
        return(null);
    }

    public boolean touches(long gid) {
        for(LevelPlan p : plans) {
            if(p.masks.containsKey(gid))
                return(true);
        }
        return(false);
    }

    public double gran() {return(gran);}

    public void recordGran(double g) {
        if((g > 0) && (g != gran)) {
            gran = g;
            changed(Collections.emptySet());
        }
    }

    // -------------------- Edits --------------------

    public LevelPlan create(String name) {
        LevelPlan p = LevelPlan.create(name);
        synchronized(this) {
            List<LevelPlan> np = new ArrayList<>(plans);
            np.add(p);
            plans = Collections.unmodifiableList(np);
        }
        changed(Collections.emptySet());
        return(p);
    }

    public void delete(String id) {
        Set<Long> touched;
        synchronized(this) {
            LevelPlan old = get(id);
            if(old == null)
                return;
            touched = new HashSet<>(old.masks.keySet());
            List<LevelPlan> np = new ArrayList<>(plans);
            np.remove(old);
            plans = Collections.unmodifiableList(np);
        }
        changed(touched);
    }

    /** Replace a plan with an edited copy of itself (same id). Its tiles are kept as they are in the store. */
    public void update(LevelPlan edited) {
        Set<Long> touched = new HashSet<>();
        synchronized(this) {
            List<LevelPlan> np = new ArrayList<>(plans);
            int i = indexOf(np, edited.id);
            if(i < 0)
                return;
            LevelPlan old = np.get(i);
            /* Only the settings come from the caller: the tiles may have been painted since it read the plan. */
            LevelPlan merged = old.withName(edited.name).withMode(edited.mode).withBudget(edited.budget)
                .withCustom(edited.custom).withTarget(edited.target);
            merged = edited.locked ? merged.lock(edited.baseline) : merged.unlock();
            if(!same(old.target, merged.target))
                touched.addAll(old.masks.keySet());
            np.set(i, merged);
            plans = Collections.unmodifiableList(np);
        }
        changed(touched);
    }

    /**
     * Add tiles to a plan, or take them out. Tiles added to a plan leave any other plan.
     *
     * @param tiles per grid id, tile offsets inside that grid, 0 to 99
     */
    public void paint(String id, Map<Long, ? extends Iterable<Coord>> tiles, boolean add) {
        Set<Long> touched = new HashSet<>();
        synchronized(this) {
            List<LevelPlan> np = new ArrayList<>(plans);
            int pi = indexOf(np, id);
            if(pi < 0)
                return;
            for(Map.Entry<Long, ? extends Iterable<Coord>> e : tiles.entrySet()) {
                if(paint(np, pi, e.getKey(), e.getValue(), add))
                    touched.add(e.getKey());
            }
            if(!touched.isEmpty())
                plans = Collections.unmodifiableList(np);
        }
        if(!touched.isEmpty())
            changed(touched);
    }

    /** Paints one grid into the working list {@code np}; true if anything changed. */
    private static boolean paint(List<LevelPlan> np, int pi, long gid, Iterable<Coord> locals, boolean add) {
        boolean any = false;
        for(int i = 0; i < np.size(); i++) {
            LevelPlan p = np.get(i);
            boolean self = (i == pi);
            if(!self && !add)
                continue;
            long[] cur = p.masks.get(gid);
            if(!self && (cur == null))
                continue;
            long[] m = (cur == null) ? TileMask.create() : cur.clone();
            boolean on = self && add, ch = false;
            for(Coord c : locals) {
                if((c.x < 0) || (c.y < 0) || (c.x >= TileMask.SIZE) || (c.y >= TileMask.SIZE))
                    continue;
                if(TileMask.get(m, c.x, c.y) != on) {
                    TileMask.put(m, c.x, c.y, on);
                    ch = true;
                }
            }
            if(ch) {
                np.set(i, p.withMask(gid, m));
                any = true;
            }
        }
        return(any);
    }

    private static int indexOf(List<LevelPlan> l, String id) {
        for(int i = 0; i < l.size(); i++) {
            if(l.get(i).id.equals(id))
                return(i);
        }
        return(-1);
    }

    private static boolean same(double a, double b) {
        return((Double.isNaN(a) && Double.isNaN(b)) || (a == b));
    }

    // -------------------- Persistence --------------------

    private void changed(Set<Long> grids) {
        for(Long gid : grids)
            gridRevs.merge(gid, 1L, Long::sum);
        synchronized(this) {
            revision++;
        }
        if(saveQueued.compareAndSet(false, true)) {
            writer.execute(() -> {
                /* Cleared before writing, so a change made during the write queues the next one. */
                saveQueued.set(false);
                save();
            });
        }
    }

    /** Write the file now; used on shutdown. */
    public void flush() {
        save();
    }

    private void save() {
        if(path == null)
            return;
        synchronized(saveLock) {
            try {
                NFileUtils.writeAtomically(path, serialize());
            } catch(IOException e) {
                System.err.println("[Leveling] could not save " + path + ": " + e.getMessage());
            }
        }
    }

    private String serialize() {
        JSONObject main = new JSONObject();
        main.put("version", 1);
        if(!Double.isNaN(gran))
            main.put("gran", gran);
        JSONArray arr = new JSONArray();
        for(LevelPlan p : plans)
            arr.put(p.toJson());
        main.put("plans", arr);
        return(main.toString());
    }

    private void load() {
        if(path == null)
            return;
        String content = NFileUtils.readWithBackupFallback(path);
        if((content == null) || content.isEmpty())
            return;
        try {
            JSONObject main = new JSONObject(content);
            gran = main.optDouble("gran", Double.NaN);
            List<LevelPlan> np = new ArrayList<>();
            JSONArray arr = main.optJSONArray("plans");
            if(arr != null) {
                for(int i = 0; i < arr.length(); i++)
                    np.add(LevelPlan.fromJson(arr.getJSONObject(i)));
            }
            plans = Collections.unmodifiableList(np);
        } catch(JSONException e) {
            System.err.println("[Leveling] could not read " + path + ": " + e.getMessage());
        }
    }
}
