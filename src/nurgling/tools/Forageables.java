package nurgling.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

/** Bundled snapshot of the Ring of Brodgar foraging and season tables. */
public final class Forageables {
    private static final String RESOURCE = "/nurgling/data/forageables.json";
    private static final List<Entry> BUNDLED = loadBundled();

    private Forageables() {
    }

    public enum Visibility {
        RED, YELLOW, GREEN
    }

    public static final class Entry {
        public final String name;
        public final String icon;
        public final int first;
        public final int base;
        public final int all;
        public final List<String> terrains;
        public final String spring;
        public final String summer;
        public final String autumn;
        public final String winter;

        private Entry(JSONObject obj) {
            name = obj.optString("name", "").trim();
            icon = obj.optString("icon", "").trim();
            first = obj.optInt("first", 0);
            base = obj.optInt("base", 0);
            all = obj.optInt("all", 0);
            JSONArray terrainArray = obj.optJSONArray("terrains");
            if(terrainArray != null) {
                ArrayList<String> values = new ArrayList<>();
                for(int i = 0; i < terrainArray.length(); i++) {
                    String value = terrainArray.optString(i, "").trim();
                    if(!value.isEmpty())
                        values.add(value);
                }
                terrains = Collections.unmodifiableList(values);
            } else {
                terrains = ForageTerrain.parse(obj.optString("terrain", ""));
            }
            spring = season(obj, "spring");
            summer = season(obj, "summer");
            autumn = season(obj, "autumn");
            winter = season(obj, "winter");
        }

        public String terrainText() {
            return ForageTerrain.join(terrains);
        }

        private static String season(JSONObject obj, String key) {
            String value = obj.optString(key, "?").trim();
            return value.isEmpty() ? "?" : value;
        }
    }

    public static List<Entry> all() {
        return BUNDLED;
    }

    public static Visibility visibility(long score, int first, int all) {
        if(score < first)
            return Visibility.RED;
        if(score < all)
            return Visibility.YELLOW;
        return Visibility.GREEN;
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
            if(entry.name.isEmpty() || entry.first < 0 || entry.base < entry.first || entry.all < entry.base)
                continue;
            entries.add(entry);
        }
        return Collections.unmodifiableList(entries);
    }

    private static List<Entry> loadBundled() {
        try(InputStream in = Forageables.class.getResourceAsStream(RESOURCE)) {
            if(in == null)
                return Collections.emptyList();
            Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            return parse(scanner.hasNext() ? scanner.next() : "");
        } catch(Exception e) {
            return Collections.emptyList();
        }
    }
}
