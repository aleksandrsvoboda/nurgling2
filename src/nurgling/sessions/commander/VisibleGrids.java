package nurgling.sessions.commander;

import haven.error.FileLogger;
import nurgling.NUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * The map grids the player marked "keep visible": their remembered ground shows in Commander mode even when no
 * character is near (a base, a field, a road). Kept by global grid id, which never changes, so a mark survives the map
 * file joining or re-basing segments. Saved to {@code commander_visible.nurgling.json} in the data folder.
 */
public final class VisibleGrids {
    private static final String FILE = "commander_visible.nurgling.json";

    private final Set<Long> ids = new HashSet<>();
    private volatile int version = 0;
    private boolean loaded = false;
    private boolean dirty = false;

    private synchronized void load() {
        if (loaded)
            return;
        loaded = true;
        try {
            File f = new File(NUtils.getDataFile(FILE));
            if (!f.exists())
                return;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONArray a = o.optJSONArray("grids");
            if (a != null)
                for (int i = 0; i < a.length(); i++)
                    ids.add(a.getLong(i));
        } catch (java.io.IOException | org.json.JSONException e) {
            FileLogger.log("[Commander] cannot read " + FILE + ": " + e);
        }
        version++;
    }

    public synchronized boolean contains(long gridId) {
        load();
        return ids.contains(gridId);
    }

    public synchronized boolean set(long gridId, boolean on) {
        load();
        boolean ch = on ? ids.add(gridId) : ids.remove(gridId);
        if (ch) {
            dirty = true;
            version++;
        }
        return ch;
    }

    public synchronized Set<Long> snapshot() {
        load();
        return new HashSet<>(ids);
    }

    public int version() {
        return version;
    }

    public synchronized int size() {
        load();
        return ids.size();
    }

    /** Write the marks out if they changed; called when a paint stroke ends. */
    public void save() {
        JSONArray a;
        synchronized (this) {
            if (!dirty)
                return;
            dirty = false;
            a = new JSONArray();
            for (Long id : ids)
                a.put(id);
        }
        JSONObject o = new JSONObject();
        o.put("version", 1);
        o.put("grids", a);
        byte[] data = o.toString().getBytes(StandardCharsets.UTF_8);
        Thread t = new Thread(() -> {
            try {
                File f = new File(NUtils.getDataFile(FILE));
                File tmp = new File(f.getPath() + ".tmp");
                Files.write(tmp.toPath(), data);
                Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                           java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException e) {
                FileLogger.log("[Commander] cannot write " + FILE + ": " + e);
            }
        }, "Commander visible save");
        t.setDaemon(true);
        t.start();
    }
}
