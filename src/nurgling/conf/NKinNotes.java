package nurgling.conf;

import nurgling.NConfig;
import nurgling.profiles.ConfigFactory;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Free-text notes on memorised kin, stored per world.
 * <p>
 * A kin list belongs to a character, but a note like "scammer" is about the person, so the
 * scope is the world profile rather than the character. Entries carry both the kin id and the
 * name: lookup tries the id first and falls back to the name, which keeps a note attached both
 * across an id reshuffle and across a nickname change.
 */
public class NKinNotes {
    private static final String FILE = "kin_notes.nurgling.json";
    private static final Map<String, NKinNotes> insts = new HashMap<>();

    private final String path;
    private final List<Entry> notes = new ArrayList<>();

    private static class Entry {
        int id;
        String name;
        String note;

        Entry(int id, String name, String note) {
            this.id = id;
            this.name = name;
            this.note = note;
        }
    }

    /** One instance per world, shared by every session on it. */
    public static synchronized NKinNotes get(String genus) {
        NKinNotes ret = insts.get(genus == null ? "" : genus);
        if (ret == null)
            insts.put(genus == null ? "" : genus, ret = new NKinNotes(genus));
        return (ret);
    }

    private NKinNotes(String genus) {
        NConfig cfg = ConfigFactory.getConfig(genus);
        this.path = cfg.getProfileAwarePath(FILE);
        load();
    }

    private void load() {
        String content = NFileUtils.readWithBackupFallback(path);
        if ((content == null) || content.isEmpty())
            return;
        try {
            JSONArray arr = new JSONObject(content).optJSONArray("notes");
            if (arr == null)
                return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null)
                    continue;
                String note = o.optString("note", "");
                if (note.isEmpty())
                    continue;
                notes.add(new Entry(o.optInt("id", -1), o.optString("name", ""), note));
            }
        } catch (org.json.JSONException e) {
            System.err.println("[NKinNotes] corrupt " + FILE + ", starting empty: " + e.getMessage());
            notes.clear();
        }
    }

    private void save() {
        JSONArray arr = new JSONArray();
        for (Entry e : notes) {
            JSONObject o = new JSONObject();
            o.put("id", e.id);
            o.put("name", e.name);
            o.put("note", e.note);
            arr.put(o);
        }
        JSONObject main = new JSONObject();
        main.put("version", 1);
        main.put("notes", arr);
        try {
            NFileUtils.writeAtomically(path, main.toString());
        } catch (IOException e) {
            System.err.println("[NKinNotes] failed to save " + FILE + ": " + e.getMessage());
        }
    }

    private Entry find(int id, String name) {
        for (Entry e : notes) {
            if (e.id == id)
                return (e);
        }
        if ((name != null) && !name.isEmpty()) {
            for (Entry e : notes) {
                if (name.equals(e.name)) {
                    /* Same person, new id - re-anchor so the id path works from now on. */
                    e.id = id;
                    return (e);
                }
            }
        }
        return (null);
    }

    public synchronized String get(int id, String name) {
        Entry e = find(id, name);
        return ((e == null) ? "" : e.note);
    }

    public synchronized boolean has(int id, String name) {
        return (!get(id, name).isEmpty());
    }

    public synchronized void set(int id, String name, String note) {
        String n = (note == null) ? "" : note;
        String nm = (name == null) ? "" : name;
        Entry e = find(id, nm);
        if (e == null) {
            if (n.isEmpty())
                return;
            notes.add(new Entry(id, nm, n));
        } else {
            if (n.equals(e.note) && nm.equals(e.name))
                return;
            if (n.isEmpty()) {
                notes.remove(e);
            } else {
                e.note = n;
                e.name = nm;
            }
        }
        save();
    }
}
