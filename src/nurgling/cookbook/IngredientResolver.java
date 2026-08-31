package nurgling.cookbook;

import nurgling.NUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps an ingredient name as a cooked dish reports it onto the inventory item names that could have
 * produced it.
 *
 * <p>The two vocabularies genuinely differ. A dish names the <em>flavour source</em> - "Boar",
 * "Apple tree", "Cowsmilk" - while the pantry holds "Raw Wild Pork", "Block of Applewood", "Milk".
 * On a mature database only about a third of ingredient names match an item name outright.
 *
 * <p>Resolution returns a <em>set</em> of acceptable item names rather than one pick, because several
 * rules can legitimately fire at once ("Cherries" is both an item in its own right and the plural of
 * the item "Cherry"). Holding any one of them is enough to cook the dish, so a wider set only ever
 * costs precision, never a false "you can't make this".
 */
public class IngredientResolver {
    private static final String RESOURCE = "/nurgling/cookbook/cookbook-ingredients.json";
    private static final String OVERRIDE_FILE = "cookbook-ingredients.json";

    private final ItemUniverse uni;

    private final Map<String, List<String>> aliases = new HashMap<>();
    private final List<String> slotPriority = new ArrayList<>();
    /** VSpec category -> the slot group it belongs to */
    private final Map<String, String> groupOfCategory = new HashMap<>();
    private final Map<String, List<String>> categoriesOfGroup = new HashMap<>();
    /** item name -> slot group, resolved once through slotPriority so lookups are a single get */
    private final Map<String, String> groupOfItem = new HashMap<>();
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();

    public IngredientResolver(ItemUniverse uni) {
        this.uni = uni;
        JSONObject cfg = readConfig();
        if (cfg != null) {
            JSONObject al = cfg.optJSONObject("aliases");
            if (al != null) {
                for (Object key : al.keySet()) {
                    String name = (String) key;
                    List<String> items = new ArrayList<>();
                    JSONArray arr = al.optJSONArray(name);
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++)
                            items.add(arr.getString(i));
                    } else {
                        String single = al.optString(name, null);
                        if (single != null && !single.isEmpty())
                            items.add(single);
                    }
                    aliases.put(ItemUniverse.normalize(name), items);
                }
            }
            JSONArray prio = cfg.optJSONArray("slotPriority");
            if (prio != null) {
                for (int i = 0; i < prio.length(); i++)
                    slotPriority.add(prio.getString(i));
            }
            JSONObject groups = cfg.optJSONObject("slotGroups");
            if (groups != null) {
                for (Object key : groups.keySet()) {
                    String group = (String) key;
                    JSONArray cats = groups.optJSONArray(group);
                    List<String> members = new ArrayList<>();
                    if (cats != null) {
                        for (int i = 0; i < cats.length(); i++) {
                            String cat = cats.getString(i);
                            members.add(cat);
                            groupOfCategory.put(cat, group);
                        }
                    }
                    categoriesOfGroup.put(group, members);
                }
            }
        }
        indexItemGroups();
    }

    /**
     * Assign every item to exactly one slot group, walking slotPriority front to back so the most
     * specific category wins - a chanterelle is in both "Edible Mushroom" and "Forageable", and only
     * the first says anything useful about which slot it fills.
     */
    private void indexItemGroups() {
        for (String category : slotPriority) {
            String group = groupOfCategory.get(category);
            if (group == null)
                group = category;
            for (String item : uni.itemsInCategory(category)) {
                if (!groupOfItem.containsKey(item))
                    groupOfItem.put(item, group);
            }
        }
    }

    private static JSONObject readConfig() {
        // A user override next to the other nurgling json config wins outright.
        try {
            Path override = NUtils.getDataFilePath(OVERRIDE_FILE);
            if (Files.exists(override)) {
                try (Reader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                    return new JSONObject(new JSONTokener(r));
                }
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[cookbook] ignoring bad " + OVERRIDE_FILE + " override: " + e.getMessage());
        }
        try (InputStream is = IngredientResolver.class.getResourceAsStream(RESOURCE)) {
            if (is != null)
                return new JSONObject(new JSONTokener(new InputStreamReader(is, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            System.out.println("[cookbook] failed to read " + RESOURCE + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Every inventory item name that could satisfy this ingredient. Never null; an empty result means
     * nothing in the item universe looks like it, which the UI reports rather than silently treating
     * as "missing".
     */
    public Set<String> candidates(String ingredient) {
        if (ingredient == null || ingredient.isEmpty())
            return Collections.emptySet();
        Set<String> hit = cache.get(ingredient);
        if (hit == null) {
            hit = Collections.unmodifiableSet(derive(ingredient));
            cache.put(ingredient, hit);
        }
        return hit;
    }

    private Set<String> derive(String ingredient) {
        String norm = ItemUniverse.normalize(ingredient);
        Set<String> out = new LinkedHashSet<>();

        // An explicit alias is a correction, so it wins outright - letting the derived rules also
        // fire would put back exactly what the alias exists to remove.
        List<String> alias = aliases.get(norm);
        if (alias != null) {
            out.addAll(alias);
            out.add(ingredient);
            return out;
        }

        // The ingredient name is very often already an item name (herbs, berries, vegetables).
        String exact = uni.canonical(norm);
        out.add(exact != null ? exact : ingredient);

        // "Boar" -> "Raw Wild Pork" only works through the icon layer; the item name shares no word.
        // Roe wears the same meat-<fish> layer as the fish itself, but a perch's roe is a different
        // ingredient from a perch, so it only counts when the ingredient asked for roe.
        boolean wantsRoe = norm.endsWith("roe") || norm.equals("caviar");
        for (String item : uni.itemsWithMeatToken(norm)) {
            if (wantsRoe || !ItemUniverse.normalize(item).endsWith("roe"))
                out.add(item);
        }

        // Butchered cuts and fish name their animal directly: Raw Fox, Chicken Meat, Filet of Bass.
        addIfKnown(out, uni, "raw" + norm);
        addIfKnown(out, uni, norm + "meat");
        addIfKnown(out, uni, "raw" + norm + "meat");
        addIfKnown(out, uni, "filetof" + norm);
        addIfKnown(out, uni, "driedfiletof" + norm);

        // Smoking wood arrives as an ingredient named after the tree.
        String tree = ItemUniverse.normalize(stripTreeSuffix(ingredient));
        if (!tree.isEmpty()) {
            addIfKnown(out, uni, "blockof" + tree);
            addIfKnown(out, uni, "blockof" + tree + "wood");
        }

        // Singular/plural drift, e.g. the ingredient "Cherries" against the item "Cherry".
        addIfKnown(out, uni, norm + "s");
        addIfKnown(out, uni, norm + "es");
        if (norm.endsWith("s"))
            addIfKnown(out, uni, norm.substring(0, norm.length() - 1));
        if (norm.endsWith("ies"))
            addIfKnown(out, uni, norm.substring(0, norm.length() - 3) + "y");

        // Genuine last resort, for meats whose item name merely ends with the animal. It runs only
        // when nothing else matched, because on its own it is too loose: "Beef" also ends
        // "Raw Wild Beef", which is a different ingredient with a name of its own.
        if (out.size() == 1 && norm.length() >= 4) {
            for (String item : uni.allItemNames()) {
                if (!out.contains(item) && uni.isMeatItem(item)
                        && ItemUniverse.normalize(item).endsWith(norm))
                    out.add(item);
            }
        }
        return out;
    }

    private static String stripTreeSuffix(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(" tree"))
            return name.substring(0, name.length() - 5);
        return name;
    }

    private static void addIfKnown(Set<String> out, ItemUniverse uni, String normalized) {
        String item = uni.canonical(normalized);
        if (item != null)
            out.add(item);
    }

    /**
     * True when at least one candidate is an item the client has heard of. False means the alias file
     * needs an entry - which the cookbook surfaces rather than quietly reporting the dish uncookable.
     */
    public boolean isResolved(String ingredient) {
        for (String cand : candidates(ingredient)) {
            if (uni.isKnownItem(cand))
                return true;
        }
        return false;
    }

    /**
     * Which slot an ingredient occupies - "Meat", "Mushroom", "Seasoning". Derived from the VSpec
     * category of its resolved items, collapsed through {@code slotGroups} because the game's one
     * seasoning slot is spread across four VSpec categories (Spices, Leaf, Flower, Seed).
     * Returns null when no priority category claims the ingredient.
     */
    public String slotGroup(String ingredient) {
        for (String cand : candidates(ingredient)) {
            String group = groupOfItem.get(cand);
            if (group != null)
                return group;
        }
        // An animal whose meat item VSpec names wrongly - or not at all - still owns a
        // gfx/invobjs/meat-<animal> layer, which is enough to place it in the meat slot.
        if (!uni.itemsWithMeatToken(ItemUniverse.normalize(ingredient)).isEmpty())
            return "Meat";
        return null;
    }



    /** Diagnostic: which of these ingredient names no rule and no alias could place. */
    public List<String> unresolved(Iterable<String> ingredientNames) {
        List<String> res = new ArrayList<>();
        for (String name : ingredientNames) {
            if (!isResolved(name))
                res.add(name);
        }
        Collections.sort(res);
        return res;
    }
}
