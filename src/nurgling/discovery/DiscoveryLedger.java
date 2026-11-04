package nurgling.discovery;

import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One character's discovery record, keyed by item name, in &lt;user&gt;_&lt;chr&gt;.discoveries.json
 * next to the character's .dat file. States only ever go up (see {@link State#rank}), except
 * through {@link #forget}, which the Atlas uses to undo a wrong entry.
 */
public final class DiscoveryLedger {
    public static final String SUFFIX = ".discoveries.json";

    public enum State {
        /** Seen in a container, never held. */
        SEEN(0, false),
        /** Received (trade, container, login) without LP: the discovery is unknown. */
        HELD(1, false),
        /** The player's own action produced it without LP: discovered before. */
        KNOWN(2, true),
        /** Imported from the old harvest-click tracking. */
        LEGACY(2, true),
        /** Marked by hand in the Atlas. */
        MANUAL(2, true),
        /** LP rose as it arrived. */
        DISCOVERED(3, true);

        public final int rank;
        public final boolean done;

        State(int rank, boolean done) {
            this.rank = rank; this.done = done;
        }
    }

    public static final class Entry {
        public final String name;
        public final State state;
        public final String res, src;
        public final int lp;
        public final boolean shared;
        public final long at;

        Entry(String name, State state, String res, int lp, boolean shared, long at, String src) {
            this.name = name; this.state = state; this.res = res;
            this.lp = lp; this.shared = shared; this.at = at; this.src = src;
        }
    }

    private final String path;
    private final Map<String, Entry> items = new HashMap<>();
    private final List<long[]> unattributed = new ArrayList<>();
    private boolean legacyImported = false;
    private volatile boolean dirty = false;
    private volatile int version = 0;

    public DiscoveryLedger(String path) {
        this.path = path;
        load();
    }

    /** The ledger path for a character whose data file is datPath (&lt;user&gt;_&lt;chr&gt;.dat). */
    public static String pathFor(String datPath) {
        return (datPath.endsWith(".dat") ? datPath.substring(0, datPath.length() - 4) : datPath) + SUFFIX;
    }

    public int version() {
        return version;
    }

    public synchronized boolean isDone(String name) {
        Entry e = items.get(name);
        return e != null && e.state.done;
    }

    public synchronized Entry get(String name) {
        return items.get(name);
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(items.values());
    }

    public synchronized int totalLp() {
        int sum = 0;
        for (Entry e : items.values())
            if (e.state == State.DISCOVERED && !e.shared)
                sum += e.lp;
        // Shared credit is one LP rise for several names: count it once per rise.
        Set<String> rises = new HashSet<>();
        for (Entry e : items.values())
            if (e.state == State.DISCOVERED && e.shared && rises.add(e.at / 1000 + ":" + e.lp))
                sum += e.lp;
        return sum;
    }

    /** Raises name to state; a lower or equal state changes nothing. Returns whether it changed. */
    public synchronized boolean record(String name, State state, String res, int lp, boolean shared, long at, String src) {
        if (name == null || name.isEmpty())
            return false;
        Entry old = items.get(name);
        if (old != null && old.state.rank >= state.rank) {
            if (old.res == null && res != null) {
                items.put(name, new Entry(name, old.state, res, old.lp, old.shared, old.at, old.src));
                dirty = true;
            }
            return false;
        }
        items.put(name, new Entry(name, state, res != null ? res : (old != null ? old.res : null), lp, shared, at, src));
        dirty = true;
        version++;
        return true;
    }

    /** Removes the entry, so the item counts as undiscovered again. */
    public synchronized void forget(String name) {
        if (items.remove(name) != null) {
            dirty = true;
            version++;
        }
    }

    public synchronized void unattributed(int lp, long at) {
        unattributed.add(new long[]{lp, at});
        while (unattributed.size() > 200)
            unattributed.remove(0);
        dirty = true;
    }

    /** The old resource → product pairs, imported once as LEGACY. */
    public synchronized void importLegacy(Map<String, ? extends Collection<String>> old) {
        if (legacyImported)
            return;
        legacyImported = true;
        dirty = true;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ? extends Collection<String>> e : old.entrySet()) {
            String src = e.getKey().startsWith("bark:") ? null : e.getKey();
            for (String name : e.getValue())
                record(name, State.LEGACY, null, 0, false, now, src);
        }
        version++;
    }

    /**
     * Writes every ledger on one background thread. A save of the whole game's items (~3,000) takes
     * 10-25 ms, which on the UI thread is a visible hitch every few seconds while items come in.
     */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscoveryLedger writer");
        t.setDaemon(true);
        return t;
    });

    /** Queues a write if anything changed. The entries are immutable, so a list copy is the whole snapshot. */
    public void saveAsync() {
        Runnable write = snapshotWrite();
        if (write != null)
            WRITER.execute(write);
    }

    /** Writes now, on the calling thread: at logout, so a daemon writer can't be cut off. */
    public void saveNow() {
        Runnable write = snapshotWrite();
        if (write != null)
            write.run();
    }

    private Runnable snapshotWrite() {
        if (!dirty)
            return null;
        List<Entry> entries;
        List<long[]> un;
        boolean legacy;
        synchronized (this) {
            dirty = false;
            entries = new ArrayList<>(items.values());
            un = new ArrayList<>(unattributed);
            legacy = legacyImported;
        }
        return () -> {
            try {
                NFileUtils.writeAtomically(path, toJson(entries, un, legacy).toString());
            } catch (IOException e) {
                System.out.println("DiscoveryLedger: could not save " + path + ": " + e.getMessage());
            }
        };
    }

    private static JSONObject toJson(List<Entry> entries, List<long[]> unattributed, boolean legacyImported) {
        JSONObject main = new JSONObject();
        main.put("v", 1);
        main.put("legacyImported", legacyImported);
        JSONObject jitems = new JSONObject();
        for (Entry e : entries) {
            JSONObject j = new JSONObject();
            j.put("st", e.state.name());
            if (e.res != null) j.put("res", e.res);
            if (e.lp != 0) j.put("lp", e.lp);
            if (e.shared) j.put("shared", true);
            j.put("at", e.at);
            if (e.src != null) j.put("src", e.src);
            jitems.put(e.name, j);
        }
        main.put("items", jitems);
        JSONArray un = new JSONArray();
        for (long[] u : unattributed)
            un.put(new JSONObject().put("lp", u[0]).put("at", u[1]));
        main.put("unattributed", un);
        return main;
    }

    private void load() {
        JSONObject main = read(path);
        if (main == null)
            return;
        legacyImported = main.optBoolean("legacyImported", false);
        JSONObject jitems = main.optJSONObject("items");
        if (jitems != null) {
            for (String name : jitems.keySet()) {
                JSONObject j = jitems.optJSONObject(name);
                if (j == null)
                    continue;
                State st;
                try {
                    st = State.valueOf(j.optString("st", "SEEN"));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                items.put(name, new Entry(name, st, j.optString("res", null), j.optInt("lp", 0),
                    j.optBoolean("shared", false), j.optLong("at", 0), j.optString("src", null)));
            }
        }
        JSONArray un = main.optJSONArray("unattributed");
        if (un != null) {
            for (int i = 0; i < un.length(); i++) {
                JSONObject u = un.optJSONObject(i);
                if (u != null)
                    unattributed.add(new long[]{u.optLong("lp"), u.optLong("at")});
            }
        }
    }

    private static JSONObject read(String path) {
        File f = new File(path);
        if (!f.isFile())
            return null;
        try {
            return new JSONObject(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            System.out.println("DiscoveryLedger: could not read " + path + ": " + e.getMessage());
            return null;
        }
    }

    /** What the other characters' ledgers say about one name. */
    public static final class Other {
        public String res;
        /** How many other characters count it as discovered. */
        public int done;
    }

    /**
     * Every name in the other characters' ledgers in this ledger's folder. Those characters held
     * or saw these items, so they are real game names, and a good list of what this one has yet
     * to find.
     */
    public Map<String, Other> others() {
        Map<String, Other> res = new HashMap<>();
        File own = new File(path).getAbsoluteFile();
        File dir = own.getParentFile();
        File[] files = dir == null ? null : dir.listFiles((d, n) -> n.endsWith(SUFFIX));
        if (files == null)
            return res;
        for (File f : files) {
            if (f.getAbsoluteFile().equals(own))
                continue;
            JSONObject main = read(f.getPath());
            JSONObject jitems = main == null ? null : main.optJSONObject("items");
            if (jitems == null)
                continue;
            for (String name : jitems.keySet()) {
                JSONObject j = jitems.optJSONObject(name);
                if (j == null)
                    continue;
                Other o = res.computeIfAbsent(name, k -> new Other());
                if (o.res == null)
                    o.res = j.optString("res", null);
                try {
                    if (State.valueOf(j.optString("st", "SEEN")).done)
                        o.done++;
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return res;
    }
}
