package nurgling.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

/**
 * Bundled snapshot of Ring of Brodgar {@code Category:Trees} terrain lists.
 * <p>
 * Wiki gaps kept in the snapshot: Mallorn Tree and Whitewillow Tree have empty
 * {@code terrain} on RoB. Charred Tree, Dragon Tree, Mallorn Tree, Southron Pine
 * Tree, and Whitewillow Tree have no {@code gfx/terobjs/trees/*} mapping in
 * {@link VSpec}, so Pick/Bring of their products cannot resolve a resource path.
 */
public final class Trees {
    private static final String RESOURCE = "/nurgling/data/trees.json";
    private static final List<Entry> BUNDLED = loadBundled();
    private static final Map<String, Entry> BY_NAME = indexByName(BUNDLED);
    private static final Map<String, Entry> BY_COMPACT = indexByCompact(BUNDLED);
    private static final Map<String, Entry> BY_RESOURCE = indexByResource(BUNDLED);

    private Trees() {
    }

    public static final class Entry {
        public final String name;
        public final String resource;
        public final List<String> terrains;

        private Entry(JSONObject obj) {
            name = obj.optString("name", "").trim();
            resource = obj.optString("resource", "").trim();
            JSONArray terrainArray = obj.optJSONArray("terrains");
            if(terrainArray != null) {
                ArrayList<String> values = new ArrayList<>();
                for(int i = 0; i < terrainArray.length(); i++) {
                    String value = terrainArray.optString(i, "").trim();
                    if(!value.isEmpty())
                        values.addAll(ForageTerrain.parse(value));
                }
                terrains = Collections.unmodifiableList(distinct(values));
            } else {
                terrains = ForageTerrain.parse(obj.optString("terrain", ""));
            }
        }
    }

    public static List<Entry> all() {
        return BUNDLED;
    }

    public static Entry find(String name) {
        if(name == null)
            return null;
        String normalized = normalize(name);
        if(normalized.isEmpty())
            return null;
        Entry entry = BY_NAME.get(normalized);
        if(entry != null)
            return entry;
        if(normalized.endsWith(" tree"))
            entry = BY_NAME.get(normalized.substring(0, normalized.length() - 5).trim());
        else
            entry = BY_NAME.get(normalized + " tree");
        if(entry != null)
            return entry;
        String compact = compact(normalized);
        entry = BY_COMPACT.get(compact);
        if(entry != null)
            return entry;
        if(compact.endsWith("tree"))
            return BY_COMPACT.get(compact.substring(0, compact.length() - 4));
        return BY_COMPACT.get(compact + "tree");
    }

    public static Entry findByResource(String resource) {
        if(resource == null)
            return null;
        String key = resource.trim();
        return key.isEmpty() ? null : BY_RESOURCE.get(key);
    }

    public static List<Entry> parse(String json) {
        if(json == null || json.isEmpty())
            return Collections.emptyList();
        JSONArray array = new JSONArray(json);
        List<Entry> entries = new ArrayList<>();
        for(int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if(obj == null)
                continue;
            Entry entry = new Entry(obj);
            if(entry.name.isEmpty())
                continue;
            entries.add(entry);
        }
        return Collections.unmodifiableList(entries);
    }

    private static List<Entry> loadBundled() {
        try(InputStream in = Trees.class.getResourceAsStream(RESOURCE)) {
            if(in == null)
                return Collections.emptyList();
            Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            return parse(scanner.hasNext() ? scanner.next() : "");
        } catch(Exception e) {
            return Collections.emptyList();
        }
    }

    private static Map<String, Entry> indexByName(List<Entry> entries) {
        Map<String, Entry> index = new LinkedHashMap<>();
        for(Entry entry : entries) {
            putIfAbsent(index, normalize(entry.name), entry);
            String withoutTree = stripTreeSuffix(normalize(entry.name));
            putIfAbsent(index, withoutTree, entry);
            putIfAbsent(index, withoutTree + " tree", entry);
        }
        return Collections.unmodifiableMap(index);
    }

    private static Map<String, Entry> indexByCompact(List<Entry> entries) {
        Map<String, Entry> index = new LinkedHashMap<>();
        for(Entry entry : entries) {
            String compact = compact(entry.name);
            putIfAbsent(index, compact, entry);
            if(compact.endsWith("tree"))
                putIfAbsent(index, compact.substring(0, compact.length() - 4), entry);
            else
                putIfAbsent(index, compact + "tree", entry);
        }
        return Collections.unmodifiableMap(index);
    }

    private static Map<String, Entry> indexByResource(List<Entry> entries) {
        Map<String, Entry> index = new LinkedHashMap<>();
        for(Entry entry : entries) {
            if(!entry.resource.isEmpty())
                putIfAbsent(index, entry.resource, entry);
        }
        return Collections.unmodifiableMap(index);
    }

    private static void putIfAbsent(Map<String, Entry> index, String key, Entry entry) {
        if(key != null && !key.isEmpty())
            index.putIfAbsent(key, entry);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String compact(String value) {
        return normalize(value).replaceAll("[^a-z0-9]", "");
    }

    private static String stripTreeSuffix(String normalized) {
        return normalized.endsWith(" tree") ? normalized.substring(0, normalized.length() - 5).trim() : normalized;
    }

    private static List<String> distinct(List<String> values) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(values));
    }
}
