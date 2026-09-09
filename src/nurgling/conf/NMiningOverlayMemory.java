package nurgling.conf;

import haven.Coord;
import haven.MCache;
import nurgling.NConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-character minesweeper overlay memory: revealed floor numbers and confirmed-safe
 * (green) wall tiles. Keys are {@code gridId + local tile} so the same cave survives
 * relog; session world coords shift with the first loaded grid and are not usable here.
 * Different caves sit on different grids, so they do not overwrite each other.
 * Red danger marks are not stored — the solver recomputes them after restore.
 */
public class NMiningOverlayMemory implements JConf {
    public static final int MAX_NUMBERS = 4096;
    public static final int MAX_GREENS = 4096;
    static final long SAVE_DEBOUNCE_MS = 1000;

    private final String username;
    private final String chrid;
    private final LinkedHashMap<TileRef, Integer> numbers = new LinkedHashMap<>();
    private final LinkedHashMap<TileRef, Boolean> greens = new LinkedHashMap<>();
    private transient boolean dirty;
    private transient long lastFlushMs;

    public static final class TileRef {
        public final long gridId;
        public final int lx;
        public final int ly;

        public TileRef(long gridId, int lx, int ly) {
            this.gridId = gridId;
            this.lx = lx;
            this.ly = ly;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TileRef)) {
                return false;
            }
            TileRef other = (TileRef) o;
            return gridId == other.gridId && lx == other.lx && ly == other.ly;
        }

        @Override
        public int hashCode() {
            return Objects.hash(gridId, lx, ly);
        }

        @Override
        public String toString() {
            return gridId + ":" + lx + "," + ly;
        }
    }

    public NMiningOverlayMemory(String username, String chrid) {
        this.username = username;
        this.chrid = chrid;
    }

    public NMiningOverlayMemory(Map<String, Object> values) {
        this.username = (String) values.get("username");
        this.chrid = (String) values.get("chrid");
        loadTiles(values.get("numbers"), true);
        loadTiles(values.get("greens"), false);
    }

    private void loadTiles(Object raw, boolean asNumbers) {
        if (!(raw instanceof List<?>)) {
            return;
        }
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) item;
            TileRef ref = tileFromRow(row);
            if (ref == null) {
                continue;
            }
            if (asNumbers) {
                Integer v = intOf(row.get("v"));
                if (v != null) {
                    numbers.put(ref, v);
                }
            } else {
                greens.put(ref, Boolean.TRUE);
            }
        }
        evict(numbers, MAX_NUMBERS);
        evict(greens, MAX_GREENS);
    }

    public String username() {
        return username;
    }

    public String chrid() {
        return chrid;
    }

    public Integer getNumber(TileRef ref) {
        return numbers.get(ref);
    }

    public boolean isGreen(TileRef ref) {
        return greens.containsKey(ref);
    }

    public int numberCount() {
        return numbers.size();
    }

    public int greenCount() {
        return greens.size();
    }

    public Map<TileRef, Integer> numbers() {
        return new LinkedHashMap<>(numbers);
    }

    public Set<TileRef> greens() {
        return new LinkedHashSet<>(greens.keySet());
    }

    public boolean putNumber(TileRef ref, int value) {
        Integer prev = numbers.get(ref);
        if (prev != null && prev == value) {
            return false;
        }
        // Re-insert so the eviction order stays least-recently-written.
        numbers.remove(ref);
        numbers.put(ref, value);
        evict(numbers, MAX_NUMBERS);
        dirty = true;
        return true;
    }

    public boolean putGreen(TileRef ref) {
        if (greens.containsKey(ref)) {
            return false;
        }
        greens.put(ref, Boolean.TRUE);
        evict(greens, MAX_GREENS);
        dirty = true;
        return true;
    }

    public boolean removeGreen(TileRef ref) {
        if (greens.remove(ref) == null) {
            return false;
        }
        dirty = true;
        return true;
    }

    /** Persist at most once per {@link #SAVE_DEBOUNCE_MS}, and only when something changed. */
    public void maybeFlush(long nowMs) {
        if (!dirty) {
            return;
        }
        if (lastFlushMs != 0 && nowMs - lastFlushMs < SAVE_DEBOUNCE_MS) {
            return;
        }
        set(this);
        dirty = false;
        lastFlushMs = nowMs;
    }

    public static TileRef ofWorld(MCache map, Coord worldTile) {
        if (map == null || worldTile == null) {
            return null;
        }
        MCache.Grid g;
        synchronized (map.grids) {
            g = map.grids.get(worldTile.div(MCache.cmaps));
        }
        if (g == null) {
            return null;
        }
        Coord local = worldTile.sub(g.ul);
        return new TileRef(g.id, local.x, local.y);
    }

    public static Coord toWorld(MCache map, TileRef ref) {
        if (map == null || ref == null) {
            return null;
        }
        MCache.Grid g = map.findGrid(ref.gridId);
        if (g == null) {
            return null;
        }
        return g.ul.add(ref.lx, ref.ly);
    }

    public static void set(NMiningOverlayMemory mem) {
        NConfig.set(NConfig.Key.miningoverlaymemory, replace(stored(), mem));
    }

    static ArrayList<NMiningOverlayMemory> replace(ArrayList<NMiningOverlayMemory> props, NMiningOverlayMemory mem) {
        ArrayList<NMiningOverlayMemory> next = new ArrayList<>(props);
        for (Iterator<NMiningOverlayMemory> i = next.iterator(); i.hasNext(); ) {
            NMiningOverlayMemory old = i.next();
            if (Objects.equals(old.username, mem.username) && Objects.equals(old.chrid, mem.chrid)) {
                i.remove();
                break;
            }
        }
        next.add(mem);
        return next;
    }

    @Override
    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        j.put("type", "NMiningOverlayMemory");
        j.put("username", username);
        j.put("chrid", chrid);
        JSONArray nums = new JSONArray();
        for (Map.Entry<TileRef, Integer> e : numbers.entrySet()) {
            nums.put(tileToJson(e.getKey(), e.getValue()));
        }
        j.put("numbers", nums);
        JSONArray gs = new JSONArray();
        for (TileRef ref : greens.keySet()) {
            gs.put(tileToJson(ref, null));
        }
        j.put("greens", gs);
        return j;
    }

    @Override
    public String toString() {
        return "NMiningOverlayMemory[" + username + "|" + chrid + "]";
    }

    public static NMiningOverlayMemory get(String username, String chrid) {
        return find(stored(), username, chrid);
    }

    static NMiningOverlayMemory find(ArrayList<NMiningOverlayMemory> props, String username, String chrid) {
        for (NMiningOverlayMemory mem : props) {
            if (Objects.equals(mem.username, username) && Objects.equals(mem.chrid, chrid)) {
                return mem;
            }
        }
        return new NMiningOverlayMemory(username, chrid);
    }

    static ArrayList<NMiningOverlayMemory> stored() {
        return listFromRaw(NConfig.getGlobal(NConfig.Key.miningoverlaymemory));
    }

    @SuppressWarnings("unchecked")
    static ArrayList<NMiningOverlayMemory> listFromRaw(Object raw) {
        ArrayList<NMiningOverlayMemory> result = new ArrayList<>();
        if (!(raw instanceof List<?>)) {
            return result;
        }
        for (Object item : (List<?>) raw) {
            if (item instanceof NMiningOverlayMemory) {
                result.add((NMiningOverlayMemory) item);
            } else if (item instanceof Map<?, ?>) {
                result.add(new NMiningOverlayMemory((Map<String, Object>) item));
            }
        }
        return result;
    }

    /* Grid ids are 64-bit and JSON numbers are not, so they round-trip as strings. */
    private static JSONObject tileToJson(TileRef ref, Integer value) {
        JSONObject o = new JSONObject();
        o.put("g", Long.toString(ref.gridId));
        o.put("x", ref.lx);
        o.put("y", ref.ly);
        if (value != null) {
            o.put("v", value);
        }
        return o;
    }

    private static TileRef tileFromRow(Map<String, Object> row) {
        Long g = longOf(row.get("g"));
        Integer x = intOf(row.get("x"));
        Integer y = intOf(row.get("y"));
        if (g == null || x == null || y == null) {
            return null;
        }
        return new TileRef(g, x, y);
    }

    private static Long longOf(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String) {
            try {
                return Long.parseLong((String) raw);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer intOf(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw instanceof String) {
            try {
                return Integer.parseInt((String) raw);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void evict(LinkedHashMap<?, ?> map, int max) {
        while (map.size() > max) {
            map.remove(map.keySet().iterator().next());
        }
    }
}
