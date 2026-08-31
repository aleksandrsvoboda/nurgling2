package nurgling.cookbook;

import nurgling.tools.VSpec;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The set of concrete inventory items the client knows about, read out of {@link VSpec#categories}.
 *
 * <p>VSpec is the game's own craft-slot taxonomy, which makes it the closest thing this client has to
 * a complete item list - around 1200 distinct item names, each with the resource path(s) of its icon.
 * An entry carries its sprite either as a single {@code "static"} path or as a {@code "layer"} array
 * for composited icons, and that layered form is the only machine-readable link between an animal and
 * its meat: "Raw Wild Pork" is {@code meat-raw + meat-boar}, which is what lets the ingredient name
 * "Boar" - the name a cooked dish reports - be traced back to the item you actually put in the pot.
 */
public class ItemUniverse {
    /** Layer names shared by whole families of meat icons; they identify a cut, not an animal. */
    private static final Set<String> MEAT_BASE_LAYERS = new HashSet<>(java.util.Arrays.asList(
            "raw", "filet", "poultry", "weird", "testis", "crust", "dried", "smoked"));

    private final Map<String, Set<String>> resourcesByName = new HashMap<>();
    /** normalised name -> canonical item name, for case- and punctuation-insensitive lookup */
    private final Map<String, String> byNorm = new HashMap<>();
    /** animal token from a {@code gfx/invobjs/meat-<token>} layer -> the items wearing it */
    private final Map<String, Set<String>> byMeatToken = new HashMap<>();
    private final Map<String, List<String>> itemsByCategory = new HashMap<>();

    /**
     * Reads {@link VSpec#categories} into the lookups below. Cheap enough to build per index rebuild,
     * which keeps it out of process-wide state that would outlive a session.
     */
    public ItemUniverse() {
        for (Map.Entry<String, ArrayList<JSONObject>> cat : VSpec.categories.entrySet()) {
            List<String> names = new ArrayList<>();
            for (JSONObject obj : cat.getValue()) {
                String name = obj.optString("name", null);
                if (name == null || name.isEmpty())
                    continue;
                names.add(name);
                Set<String> res = resourcesByName.get(name);
                if (res == null) {
                    res = new HashSet<>();
                    resourcesByName.put(name, res);
                }
                collectResources(obj, res);
                byNorm.put(normalize(name), name);
            }
            itemsByCategory.put(cat.getKey(), names);
        }
        for (Map.Entry<String, Set<String>> e : resourcesByName.entrySet()) {
            for (String res : e.getValue()) {
                String token = meatToken(res);
                if (token == null)
                    continue;
                Set<String> items = byMeatToken.get(token);
                if (items == null) {
                    items = new HashSet<>();
                    byMeatToken.put(token, items);
                }
                items.add(e.getKey());
            }
        }
    }

    private static void collectResources(JSONObject obj, Set<String> out) {
        String stat = obj.optString("static", null);
        if (stat != null && !stat.isEmpty())
            out.add(stat);
        JSONArray layers = obj.optJSONArray("layer");
        if (layers != null) {
            for (int i = 0; i < layers.length(); i++) {
                String layer = layers.optString(i, null);
                if (layer != null && !layer.isEmpty())
                    out.add(layer);
            }
        }
    }

    private static String meatToken(String res) {
        final String prefix = "gfx/invobjs/meat-";
        if (!res.startsWith(prefix))
            return null;
        String token = res.substring(prefix.length());
        if (token.isEmpty() || token.indexOf('/') >= 0 || MEAT_BASE_LAYERS.contains(token))
            return null;
        return token;
    }

    /** Lower-case, alphanumerics only - so "Bog turtle" and "BogTurtle" collapse to one key. */
    public static String normalize(String name) {
        if (name == null)
            return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c))
                sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** The canonical item name for a normalised key, or null if no such item is known. */
    public String canonical(String normalized) {
        return byNorm.get(normalized);
    }

    public boolean isKnownItem(String name) {
        return byNorm.containsKey(normalize(name));
    }

    public Set<String> itemsWithMeatToken(String token) {
        Set<String> res = byMeatToken.get(token);
        return (res == null) ? Collections.<String>emptySet() : res;
    }

    private Set<String> resourcesOf(String itemName) {
        Set<String> res = resourcesByName.get(itemName);
        return (res == null) ? Collections.<String>emptySet() : res;
    }

    /** True when this item's icon is built from any {@code meat-*} layer. */
    public boolean isMeatItem(String itemName) {
        for (String res : resourcesOf(itemName)) {
            if (res.startsWith("gfx/invobjs/meat-"))
                return true;
        }
        return false;
    }

    public Set<String> allItemNames() {
        return Collections.unmodifiableSet(resourcesByName.keySet());
    }

    public List<String> itemsInCategory(String category) {
        List<String> res = itemsByCategory.get(category);
        return (res == null) ? Collections.<String>emptyList() : res;
    }

}
