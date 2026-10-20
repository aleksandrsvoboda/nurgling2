package nurgling.tools;

import nurgling.cookbook.GenericTypes;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.*;

/**
 * The items a make-window category input offers: VSpec's list for the input's name, completed with
 * every member of the wiki generic type the input stands for (GenericTypes, which the cookbook lists
 * untried ingredients from). VSpec.categories itself stays as it is: StackSupporter and the storage
 * code read it for stack sizes and mixed piles, which a wider list would change.
 */
public final class CraftCategories {
    private CraftCategories() {}

    /**
     * Input names the game gives a generic type other than the type's own. A meat input is named
     * from its one layer: meat-raw "Raw %" gives "Raw ", meat-weird "% Meat" gives " Meat".
     */
    private static final Map<String, String> TYPES = Map.ofEntries(
        Map.entry("Raw ", "Raw Meat"),
        Map.entry(" Meat", "Weird Meat"),
        Map.entry("Filet of ", "Filet of Any Fish"),
        Map.entry("Onion", "Any Onion"),
        Map.entry("Flour", "Any Flour"),
        Map.entry("Snail", "Any Snail"),
        Map.entry("Stuffing", "Any Stuffing"),
        Map.entry("Pigment", "Any Pigment"),
        Map.entry("Bar of Any Common Metal", "Bar of Common Metal"),
        Map.entry("Bar of Bronze, Iron or Steel", "Bar of Hard Metal"),
        Map.entry("Nugget of Any Common Metal", "Nugget of Common Metal"),
        Map.entry("Nugget of Bronze, Iron or Steel", "Nugget of Hard Metal"),
        Map.entry("Nugget of a Precious Metal", "Nugget of Precious Metal"));

    private static final Map<String, ArrayList<JSONObject>> complete = new HashMap<>();
    private static Map<String, JSONObject> byName;
    private static GenericTypes types;
    private static boolean typesFailed = false;

    /** Whether an input name is a category input. */
    public static boolean has(String input) {
        return input != null && (VSpec.categories.containsKey(input) || !members(input).isEmpty()
                || !ItemVariants.craftOptions(input).isEmpty());
    }

    /** The options of a category input, or null when the name is not one. */
    public static synchronized ArrayList<JSONObject> options(String input) {
        if(input == null)
            return null;
        ArrayList<JSONObject> ret = complete.get(input);
        if(ret != null)
            return ret;
        List<String> members = members(input);
        ArrayList<JSONObject> own = VSpec.categories.get(input);
        List<JSONObject> kinds = ItemVariants.craftOptions(input);
        if(own == null && members.isEmpty() && kinds.isEmpty())
            return null;
        ret = (own == null) ? new ArrayList<>() : new ArrayList<>(own);
        Set<String> names = new HashSet<>(), icons = new HashSet<>();
        for(JSONObject o : ret)
            seen(o, names, icons);
        /* The catalog loads in the background; until then a member it alone knows is left out. */
        boolean resolved = true;
        for(String m : members) {
            ArrayList<JSONObject> family = VSpec.categories.get(m);
            if((family != null) && family.stream().noneMatch(o -> m.equals(o.optString("name")))) {
                /* A member that is a family of items, as "Bollock" or "Clay". */
                for(JSONObject o : family)
                    add(ret, o, names, icons);
                continue;
            }
            if(names.contains(GenericTypes.norm(m)))
                continue;
            for(String item : GenericTypes.itemNames(m)) {
                JSONObject icon = icon(item, m);
                if(icon == null)
                    resolved &= ItemIcons.ready();
                else
                    add(ret, icon.put("name", item), names, icons);
            }
        }
        /* Every kind of milk and of butter shares one icon, so these are told apart by name alone. */
        for(JSONObject o : kinds) {
            if(names.add(GenericTypes.norm(o.optString("name"))))
                ret.add(o);
        }
        if(resolved)
            complete.put(input, ret);
        return ret;
    }

    private static List<String> members(String input) {
        GenericTypes t = types();
        return (t == null) ? List.of() : t.members(TYPES.getOrDefault(input, input));
    }

    private static synchronized GenericTypes types() {
        if((types == null) && !typesFailed) {
            try {
                types = GenericTypes.bundled();
            } catch(IOException e) {
                typesFailed = true;
                System.err.println("[CraftCategories] " + e.getMessage());
            }
        }
        return types;
    }

    /** One item of each name and each icon: the wiki may name an item VSpec already has otherwise. */
    private static void add(List<JSONObject> list, JSONObject o, Set<String> names, Set<String> icons) {
        if(names.contains(GenericTypes.norm(o.optString("name"))) || icons.contains(ItemIcons.key(o)))
            return;
        list.add(o);
        seen(o, names, icons);
    }

    private static void seen(JSONObject o, Set<String> names, Set<String> icons) {
        names.add(GenericTypes.norm(o.optString("name")));
        icons.add(ItemIcons.key(o));
    }

    /** A sprite descriptor for an item: VSpec's, a checked resource, or the catalog's; null if none. */
    private static JSONObject icon(String item, String member) {
        JSONObject own = vspec().get(item.toLowerCase(Locale.ROOT));
        if(own != null)
            return new JSONObject(own.toString());
        String res = GenericTypes.icon(member);
        if(res == null)
            res = GenericTypes.icon(item);
        if(res != null) {
            String[] layers = res.split("\\+");
            return (layers.length == 1) ? new JSONObject().put("static", res) : new JSONObject().put("layer", new JSONArray(layers));
        }
        if(!ItemIcons.ready())
            return null;
        /* Only a game resource draws in the picker, not a wiki image. */
        JSONObject found = ItemIcons.descriptor(null, item, false);
        return ((found != null) && (found.has("static") || found.has("layer"))) ? found : null;
    }

    private static Map<String, JSONObject> vspec() {
        if(byName == null) {
            Map<String, JSONObject> index = new HashMap<>();
            for(ArrayList<JSONObject> list : VSpec.categories.values()) {
                for(JSONObject o : list)
                    index.putIfAbsent(o.optString("name").toLowerCase(Locale.ROOT), o);
            }
            byName = index;
        }
        return byName;
    }
}
