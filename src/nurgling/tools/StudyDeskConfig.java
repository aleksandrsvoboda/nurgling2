package nurgling.tools;

import haven.Drawable;
import haven.Gob;
import nurgling.NConfig;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared read/write/migration logic for the study desk layout config.
 * <p>
 * Storage is a single flat, global map keyed by study desk gob hash (not by character),
 * since any character can now place items into any study desk:
 * <pre>
 * studyDeskLayout: {
 *   "desks": {
 *     "&lt;gobHash&gt;": { "label": "Desk 1", "layout": { "x,y": {...} } },
 *     ...
 *   }
 * }
 * </pre>
 * Older configs saved one desk per character name (no "desks" key) and are migrated into this
 * shape the first time they're loaded.
 */
public class StudyDeskConfig {

    private StudyDeskConfig() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static final String DESKS_KEY = "desks";
    private static final String LABEL_KEY = "label";
    private static final String LAYOUT_KEY = "layout";
    private static final String GOB_HASH_KEY = "gobHash";
    private static final String OWNER_KEY = "owner";

    /**
     * Load every configured desk, migrating legacy per-character config on the fly.
     * @return map of gobHash -> desk entry ({"label": ..., "layout": {...}})
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> allDesks() {
        Object existingData = NConfig.get(NConfig.Key.studyDeskLayout);

        Map<String, Object> raw;
        if (existingData instanceof Map) {
            raw = (Map<String, Object>) existingData;
        } else if (existingData instanceof String && !((String) existingData).isEmpty()) {
            raw = new JSONObject((String) existingData).toMap();
        } else {
            return new HashMap<>();
        }

        Object desksObj = raw.get(DESKS_KEY);
        if (desksObj instanceof Map) {
            return new HashMap<>((Map<String, Object>) desksObj);
        }

        // Legacy shape: Map<charName, {gobHash, layout}> with no top-level "desks" key.
        // Flatten every character's single desk into the new per-hash map.
        Map<String, Object> migrated = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> charData = (Map<String, Object>) entry.getValue();
            Object hashObj = charData.get(GOB_HASH_KEY);
            Object layoutObj = charData.get(LAYOUT_KEY);
            if (!(hashObj instanceof String) || !(layoutObj instanceof Map)) {
                continue;
            }
            String hash = (String) hashObj;
            if (migrated.containsKey(hash)) {
                continue; // already captured under another character's legacy entry
            }
            Map<String, Object> deskEntry = new HashMap<>();
            deskEntry.put(LABEL_KEY, "Desk (" + entry.getKey() + ")");
            deskEntry.put(LAYOUT_KEY, layoutObj);
            migrated.put(hash, deskEntry);
        }

        if (!migrated.isEmpty()) {
            saveDesks(migrated);
        }
        return migrated;
    }

    /**
     * @return the desk entry for the given hash, or null if none is saved.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getDesk(String hash) {
        if (hash == null) {
            return null;
        }
        Object entry = allDesks().get(hash);
        return (entry instanceof Map) ? (Map<String, Object>) entry : null;
    }

    /**
     * Save (or update) a single desk's label/layout, keeping every other desk untouched.
     * @param label new label, or null to keep whatever label is already saved for this desk
     * @param layout the planned layout map (position key -> item data), never null
     * @param owner the saving character's chrid, claiming this desk as theirs (see
     *              {@link #findOwnedDeskHash}), or null to leave ownership as it already was.
     */
    @SuppressWarnings("unchecked")
    public static void putDesk(String hash, String label, Map<String, Object> layout, String owner) {
        Map<String, Object> desks = allDesks();
        Object existing = desks.get(hash);

        Map<String, Object> deskEntry = new HashMap<>();
        String resolvedLabel = orExisting(existing, LABEL_KEY, label);
        if (resolvedLabel != null) {
            deskEntry.put(LABEL_KEY, resolvedLabel);
        }
        deskEntry.put(LAYOUT_KEY, layout);
        String resolvedOwner = orExisting(existing, OWNER_KEY, owner);
        if (resolvedOwner != null) {
            deskEntry.put(OWNER_KEY, resolvedOwner);
        }
        desks.put(hash, deskEntry);

        // A character owns at most one desk at a time - claiming this one clears their tag from
        // any other desk they'd previously claimed, so findOwnedDeskHash() always has exactly one
        // unambiguous answer instead of an arbitrary pick among several (HashMap iteration order).
        if (owner != null) {
            for (Map.Entry<String, Object> entry : desks.entrySet()) {
                if (entry.getKey().equals(hash) || !(entry.getValue() instanceof Map)) {
                    continue;
                }
                Map<String, Object> other = (Map<String, Object>) entry.getValue();
                if (owner.equals(other.get(OWNER_KEY))) {
                    other.remove(OWNER_KEY);
                }
            }
        }

        saveDesks(desks);
    }

    /** {@code key}'s value from {@code existing} if {@code provided} is null, else {@code provided}. */
    @SuppressWarnings("unchecked")
    private static String orExisting(Object existing, String key, String provided) {
        if (provided != null) {
            return provided;
        }
        if (existing instanceof Map) {
            Object v = ((Map<String, Object>) existing).get(key);
            if (v instanceof String) {
                return (String) v;
            }
        }
        return null;
    }

    /**
     * The hash of the desk the given character last saved a plan for (see {@link #putDesk}), or
     * null if they don't own one. Used by "Fill Study Desk" to target their own desk specifically.
     * At most one desk can carry a given owner at a time (putDesk clears it from any other), so
     * this always has exactly one unambiguous answer.
     */
    @SuppressWarnings("unchecked")
    public static String findOwnedDeskHash(String chrid) {
        if (chrid == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : allDesks().entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Object owner = ((Map<String, Object>) entry.getValue()).get(OWNER_KEY);
            if (chrid.equals(owner)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Update just a desk's label, leaving its saved layout untouched (an empty layout is
     * created if this desk has never been saved before). Unlike {@link #putDesk}, this never
     * writes whatever is currently on-screen in the planner, so renaming can't silently
     * persist an in-progress, not-yet-saved layout edit.
     */
    @SuppressWarnings("unchecked")
    public static void renameDesk(String hash, String label) {
        Map<String, Object> desks = allDesks();

        Map<String, Object> deskEntry;
        Object existing = desks.get(hash);
        if (existing instanceof Map) {
            deskEntry = new HashMap<>((Map<String, Object>) existing);
        } else {
            deskEntry = new HashMap<>();
            deskEntry.put(LAYOUT_KEY, new HashMap<>());
        }
        deskEntry.put(LABEL_KEY, label);

        desks.put(hash, deskEntry);
        saveDesks(desks);
    }

    /**
     * Persist the full flat desk map back to config.
     */
    public static void saveDesks(Map<String, Object> desks) {
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put(DESKS_KEY, desks);
        NConfig.set(NConfig.Key.studyDeskLayout, wrapper);
    }

    /**
     * The container cap name ("Study Desk" / "Fine Study Desk" / "Grand Study Desk") for a
     * study desk gob, or null if the gob isn't a recognized study desk variant.
     */
    public static String capFor(Gob gob) {
        Drawable drawable = gob.getattr(Drawable.class);
        if (drawable == null || drawable.getres() == null) {
            return null;
        }
        String resName = drawable.getres().name;
        if ("gfx/terobjs/studydesk-big".equals(resName)) {
            return "Fine Study Desk";
        } else if ("gfx/terobjs/grandstudydesk".equals(resName)) {
            return "Grand Study Desk";
        } else if ("gfx/terobjs/studydesk".equals(resName)) {
            return "Study Desk";
        }
        return null;
    }
}
