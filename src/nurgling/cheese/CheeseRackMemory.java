package nurgling.cheese;

import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * What each cheese rack's shelves held when we last saw them, keyed by the rack's ngob.hash (fixed
 * where the rack stands, the same across sessions). Saved to {@code cheese_racks.nurgling.json}.
 *
 * One per session (NCore). Several sessions or clients can share the file: a save re-reads it and
 * writes back only the racks this session changed, keeping the newer copy of each.
 *
 * Called from gob ticks, which run on a pool thread for background sessions, so every method is
 * synchronized.
 */
public class CheeseRackMemory {
    public static final String FILE_NAME = "cheese_racks.nurgling.json";
    /** At most one write this often. */
    private static final long SAVE_EVERY_MS = 5000;
    /** Racks not seen for this long are dropped. */
    private static final long FORGET_AFTER_MS = 60L * 24 * 3600 * 1000;

    /** One tray seen on a shelf: overlay id + tier. */
    public static final class Seen {
        public final int olId;
        public final int tier;

        public Seen(int olId, int tier) {
            this.olId = olId;
            this.tier = tier;
        }
    }

    static final class Rack {
        final CheeseRackShelves.Memory[] shelves = new CheeseRackShelves.Memory[CheeseRackShelves.SHELVES];
        long updated;
        long seen;
    }

    private final String path;
    private final Map<String, Rack> racks = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private long lastSave = 0;

    public CheeseRackMemory(String path) {
        this.path = path;
        load();
    }

    public String path() {
        return path;
    }

    /**
     * Records what a rack's shelves hold now.
     *
     * @param seen what is on each shelf (index 0 = bottom), null = no tray
     * @param live true when the rack has been watched long enough that a new tray means it was
     *             just put there (so its tier now is its tier at placement)
     * @return a copy of what we remember per shelf, after this observation
     */
    public synchronized CheeseRackShelves.Memory[] observe(String rack, Seen[] seen, boolean live, long now) {
        Rack r = racks.get(rack);
        boolean changed = false;
        if (r == null) {
            r = new Rack();
            racks.put(rack, r);
            changed = true;
        }
        for (int i = 0; i < CheeseRackShelves.SHELVES; i++) {
            Seen s = seen[i];
            CheeseRackShelves.Memory m = r.shelves[i];
            if (s == null) {
                if (m != null) {
                    r.shelves[i] = null;
                    changed = true;
                }
            } else if (m == null || m.olId != s.olId || s.tier < m.baseTier) {
                r.shelves[i] = new CheeseRackShelves.Memory(s.olId, s.tier, now, live);
                changed = true;
            }
        }
        r.seen = now;
        // Also rewrite a rack once a day while it is being seen, so its "seen" time never ages it out.
        if (changed || now - r.updated > 24L * 3600 * 1000) {
            r.updated = now;
            dirty.add(rack);
        }
        return copy(r);
    }

    /**
     * Records the content names read from the open rack.
     *
     * @param names per shelf: the content name, "" for an empty tray, null when not read
     */
    public synchronized void learn(String rack, String[] names, int[] tiers, long now) {
        Rack r = racks.get(rack);
        if (r == null)
            return;
        boolean changed = false;
        for (int i = 0; i < CheeseRackShelves.SHELVES; i++) {
            CheeseRackShelves.Memory m = r.shelves[i];
            if (m == null || names[i] == null || names[i].isEmpty())
                continue;
            if (!names[i].equals(m.cheese) || m.cheeseTier != tiers[i]) {
                m.cheese = names[i];
                m.cheeseTier = tiers[i];
                changed = true;
            }
        }
        if (changed) {
            r.updated = now;
            dirty.add(rack);
        }
    }

    /** Writes the racks that changed, at most every few seconds unless forced. */
    public synchronized void saveIfDue(long now, boolean force) {
        if (dirty.isEmpty() || (!force && now - lastSave < SAVE_EVERY_MS))
            return;
        lastSave = now;
        JSONObject root = readFile();
        JSONObject all = root.optJSONObject("racks");
        if (all == null)
            all = new JSONObject();
        for (String key : dirty) {
            Rack r = racks.get(key);
            if (r == null)
                continue;
            JSONObject theirs = all.optJSONObject(key);
            if (theirs != null && theirs.optLong("updated", 0) > r.updated)
                continue;
            all.put(key, toJson(r));
        }
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            JSONObject o = all.optJSONObject(it.next());
            if (o == null || now - o.optLong("seen", 0) > FORGET_AFTER_MS)
                it.remove();
        }
        root.put("racks", all);
        try {
            NFileUtils.writeAtomically(path, root.toString());
            dirty.clear();
        } catch (IOException e) {
            System.err.println("[CheeseRackMemory] Could not write " + path + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ JSON

    private JSONObject readFile() {
        String content = NFileUtils.readWithBackupFallback(path);
        if (content == null || content.isEmpty())
            return new JSONObject();
        try {
            return new JSONObject(content);
        } catch (JSONException e) {
            System.err.println("[CheeseRackMemory] Ignoring corrupt " + path + ": " + e.getMessage());
            return new JSONObject();
        }
    }

    private void load() {
        JSONObject all = readFile().optJSONObject("racks");
        if (all == null)
            return;
        for (String key : all.keySet()) {
            JSONObject o = all.optJSONObject(key);
            if (o != null)
                racks.put(key, fromJson(o));
        }
    }

    static JSONObject toJson(Rack r) {
        JSONObject o = new JSONObject();
        o.put("updated", r.updated);
        o.put("seen", r.seen);
        JSONArray arr = new JSONArray();
        for (CheeseRackShelves.Memory m : r.shelves) {
            if (m == null) {
                arr.put(JSONObject.NULL);
                continue;
            }
            JSONObject s = new JSONObject();
            s.put("ol", m.olId);
            s.put("base", m.baseTier);
            s.put("since", m.since);
            s.put("live", m.placedLive);
            if (m.cheese != null) {
                s.put("cheese", m.cheese);
                s.put("cheeseTier", m.cheeseTier);
            }
            arr.put(s);
        }
        o.put("shelves", arr);
        return o;
    }

    static Rack fromJson(JSONObject o) {
        Rack r = new Rack();
        r.updated = o.optLong("updated", 0);
        r.seen = o.optLong("seen", 0);
        JSONArray arr = o.optJSONArray("shelves");
        for (int i = 0; arr != null && i < Math.min(arr.length(), CheeseRackShelves.SHELVES); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null)
                continue;
            CheeseRackShelves.Memory m = new CheeseRackShelves.Memory(s.optInt("ol"), s.optInt("base"),
                    s.optLong("since"), s.optBoolean("live"));
            if (s.has("cheese")) {
                m.cheese = s.optString("cheese");
                m.cheeseTier = s.optInt("cheeseTier");
            }
            r.shelves[i] = m;
        }
        return r;
    }

    private static CheeseRackShelves.Memory[] copy(Rack r) {
        CheeseRackShelves.Memory[] out = new CheeseRackShelves.Memory[CheeseRackShelves.SHELVES];
        for (int i = 0; i < out.length; i++) {
            CheeseRackShelves.Memory m = r.shelves[i];
            if (m == null)
                continue;
            CheeseRackShelves.Memory c = new CheeseRackShelves.Memory(m.olId, m.baseTier, m.since, m.placedLive);
            c.cheese = m.cheese;
            c.cheeseTier = m.cheeseTier;
            out[i] = c;
        }
        return out;
    }
}
