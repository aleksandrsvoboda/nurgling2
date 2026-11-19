package nurgling.colony;

import haven.Coord2d;
import nurgling.NUtils;
import nurgling.sessions.squad.WorldRef;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The colony's settings, kept in {@code colony.nurgling.json} next to the other global files: where home is and how
 * far it reaches, which saved accounts take part, which may have characters created on them, and which characters may
 * spend their LP on skills. Nothing is allowed until it is ticked.
 */
public final class ColonyConfig {
    private static final String FILE = "colony.nurgling.json";
    /** Home reaches this many map grids (100 tiles each) out, unless set otherwise. */
    public static final int DEFAULT_RADIUS = 2;
    public static final int MAX_RADIUS = 10;

    private WorldRef home;
    private int radius = DEFAULT_RADIUS;
    /** Saved accounts whose characters take part. */
    private final Set<String> joins = new LinkedHashSet<>();
    /** Saved accounts the colony may create characters on. */
    private final Set<String> creates = new LinkedHashSet<>();
    /** Characters ({@link ColonistReport#key}) that may spend LP on skills. */
    private final Set<String> learns = new LinkedHashSet<>();
    /** World name ("World 16.2") per genus, learned from character select. */
    private final Map<String, String> worlds = new HashMap<>();
    /** The name the next colonist gets. */
    private String nextName = "Colonist1";

    ColonyConfig() {
        load();
    }

    public synchronized WorldRef home() {
        return home;
    }

    public synchronized void setHome(WorldRef r) {
        home = r;
        save();
    }

    public synchronized int radius() {
        return radius;
    }

    public synchronized void setRadius(int r) {
        int v = Math.max(1, Math.min(MAX_RADIUS, r));
        if (v != radius) {
            radius = v;
            save();
        }
    }

    public synchronized boolean joins(String account) {
        return account != null && joins.contains(account);
    }

    public synchronized boolean creates(String account) {
        return account != null && creates.contains(account);
    }

    public synchronized boolean learns(String characterKey) {
        return characterKey != null && learns.contains(characterKey);
    }

    public synchronized void setJoins(String account, boolean on) {
        if (on ? joins.add(account) : joins.remove(account))
            save();
    }

    public synchronized void setCreates(String account, boolean on) {
        if (on ? creates.add(account) : creates.remove(account))
            save();
    }

    public synchronized void setLearns(String characterKey, boolean on) {
        if (on ? learns.add(characterKey) : learns.remove(characterKey))
            save();
    }

    public synchronized String worldOf(String genus) {
        return (genus == null) ? null : worlds.get(genus);
    }

    public synchronized void rememberWorld(String genus, String world) {
        if (genus != null && world != null && !world.equals(worlds.put(genus, world)))
            save();
    }

    public synchronized String nextName() {
        return nextName;
    }

    public synchronized void setNextName(String name) {
        String n = (name == null) ? "" : name.trim();
        if (!n.isEmpty() && !n.equals(nextName)) {
            nextName = n;
            save();
        }
    }

    /** "Colonist7" after "Colonist6"; a name without a number gets a 2. */
    public static String following(String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1)))
            i--;
        if (i == name.length())
            return name + "2";
        return name.substring(0, i) + (Long.parseLong(name.substring(i)) + 1);
    }

    public synchronized Set<String> createAccounts() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(creates));
    }

    private void load() {
        String content = NFileUtils.readWithBackupFallback(NUtils.getDataFile(FILE));
        if (content == null || content.isEmpty())
            return;
        try {
            JSONObject main = new JSONObject(content);
            JSONObject h = main.optJSONObject("home");
            if (h != null) {
                Coord2d off = h.has("offX") ? new Coord2d(h.getDouble("offX"), h.getDouble("offY")) : null;
                Coord2d seg = h.has("segX") ? new Coord2d(h.getDouble("segX"), h.getDouble("segY")) : null;
                long gid = h.optLong("grid", -1);
                if ((gid >= 0 && off != null) || seg != null)
                    home = new WorldRef((off == null) ? -1 : gid, off, h.optLong("seg", 0), seg);
            }
            radius = Math.max(1, Math.min(MAX_RADIUS, main.optInt("radius", DEFAULT_RADIUS)));
            read(main.optJSONArray("joins"), joins);
            read(main.optJSONArray("creates"), creates);
            read(main.optJSONArray("learns"), learns);
            JSONObject w = main.optJSONObject("worlds");
            if (w != null)
                for (String k : w.keySet())
                    worlds.put(k, w.optString(k, ""));
            nextName = main.optString("nextName", nextName);
        } catch (JSONException e) {
            System.err.println("[Colony] corrupt " + FILE + ", starting empty: " + e.getMessage());
            home = null;
            joins.clear();
            creates.clear();
            learns.clear();
        }
    }

    private static void read(JSONArray a, Set<String> into) {
        if (a == null)
            return;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (!s.isEmpty())
                into.add(s);
        }
    }

    private void save() {
        JSONObject main = new JSONObject();
        main.put("version", 1);
        if (home != null) {
            JSONObject h = new JSONObject();
            if (home.gridId >= 0 && home.off != null) {
                h.put("grid", home.gridId);
                h.put("offX", home.off.x);
                h.put("offY", home.off.y);
            }
            if (home.segPos != null) {
                h.put("seg", home.segId);
                h.put("segX", home.segPos.x);
                h.put("segY", home.segPos.y);
            }
            main.put("home", h);
        }
        main.put("radius", radius);
        main.put("joins", new JSONArray(joins));
        main.put("creates", new JSONArray(creates));
        main.put("learns", new JSONArray(learns));
        main.put("worlds", new JSONObject(worlds));
        main.put("nextName", nextName);
        try {
            NFileUtils.writeAtomically(NUtils.getDataFile(FILE), main.toString());
        } catch (IOException e) {
            System.err.println("[Colony] failed to save " + FILE + ": " + e.getMessage());
        }
    }
}
