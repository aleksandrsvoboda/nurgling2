package nurgling.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sorts stored item names into the Storage window's categories. The storage database keeps only
 * item names, so the resource comes from the offline icon catalogue ({@link ItemIcons}): its paths
 * name the family (meat-raw + meat-boar, wblock-pine, bar-tin), VSpec groups cover produce, and
 * name keywords catch items the catalogue only knows by a wiki picture.
 */
public final class StorageClassifier {
    private StorageClassifier() {}

    public enum Category {
        WILD_MEAT("wildmeat", "Raw Wild Pork"),
        FARM_MEAT("farmmeat", "Raw Beef"),
        FISH("fish", "Pike"),
        GARDEN("garden", "Carrot"),
        FORAGE("forage", "Blueberries"),
        WOOD("wood", "Block of Pine"),
        MINE("mine", "Bar of Bronze"),
        STONE("stone", "Granite"),
        HIDES("hides", "Bear Hide"),
        DAIRY("dairy", "Chicken Egg"),
        KITCHEN("kitchen", "Flour"),
        OTHER("other", "Branch");

        public final String id;
        /** Item whose icon stands for the category's tab. */
        public final String icon;

        Category(String id, String icon) {
            this.id = id;
            this.icon = icon;
        }

        public String l10nKey() {
            return "storage.cat." + id;
        }

        public static Category byId(String id) {
            for (Category c : values())
                if (c.id.equals(id))
                    return c;
            return null;
        }
    }

    /** Animals whose meat comes from the pen, by their meat-* icon layer. */
    private static final Set<String> FARM_ANIMALS = Set.of("cow", "pig", "sheep", "goat", "horse", "teimdeer", "chicken");
    /** Name fallbacks for farm meat the catalogue has no layers for. */
    private static final List<String> FARM_NAMES = List.of("raw beef", "raw pork", "raw mutton", "raw chevon",
            "raw horse", "raw tame reindeer", "chicken");

    private static final Map<String, Category> VSPEC = new HashMap<>();
    static {
        for (String c : List.of("Tuber", "Onion", "Salad Greens", "Carrot", "Beetroot", "Cucumber", "Crop Seeds",
                "Crops - other", "Millable Seed", "Malted Grains", "String"))
            VSPEC.put(c, Category.GARDEN);
        for (String c : List.of("Berry", "Fruit", "Fruit or Berry", "Nuts", "Edible Mushroom", "Forageable", "Flower",
                "Leaf", "Spices", "Snail", "Bug", "Dried Fruit", "Decent-sized Conifer Cone", "Tree Bough",
                "Thatching Material", "Giant Ant", "Royal Ant", "Olive"))
            VSPEC.put(c, Category.FORAGE);
        for (String c : List.of("Bone Material", "Finebone", "Entrails", "Intestines", "Animal Fat", "Chitin",
                "Goat Horn", "Dead Animal Carcass", "Clean Animal Carcass", "Clean Bird Carcass"))
            VSPEC.put(c, Category.HIDES);
        for (String c : List.of("Egg"))
            VSPEC.put(c, Category.DAIRY);
        for (String c : List.of("Flour", "Dough", "Pie", "Cooked Egg", "Sweetener", "Honey", "Vegetable Oil",
                "Solid Fat", "Cured Tea", "Vinegar", "Stuffing", "Gellant"))
            VSPEC.put(c, Category.KITCHEN);
        for (String c : List.of("Herring", "Edible Seashell"))
            VSPEC.put(c, Category.FISH);
    }

    private static volatile Map<String, Category> vspecByName;
    private static volatile Set<String> ores, stones;
    private static final Map<String, Category> cache = new ConcurrentHashMap<>();

    public static String normalize(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * The category of an item name. Before the icon catalogue has loaded the answer may be less
     * precise and is not cached, so callers can ask again once {@link ItemIcons#ready()}.
     */
    public static Category classify(String name) {
        String key = normalize(name);
        Category known = cache.get(key);
        if (known != null)
            return known;
        boolean ready = ItemIcons.ready();
        Category c = compute(name, key);
        if (ready)
            cache.put(key, c);
        return c;
    }

    private static Category compute(String name, String key) {
        List<String> res = resources(ItemIcons.descriptor(null, name, false));

        // Meat: the first icon layer says what was done to it, the second which animal.
        if (!res.isEmpty() && res.get(0).startsWith("gfx/invobjs/meat-")) {
            String base = res.get(0).substring("gfx/invobjs/meat-".length());
            String animal = res.size() > 1 ? res.get(1).replaceFirst("^gfx/invobjs/meat-", "") : "";
            switch (base) {
                case "raw":
                case "poultry":
                case "testis":
                    return FARM_ANIMALS.contains(animal) ? Category.FARM_MEAT : Category.WILD_MEAT;
                case "weird":
                    return Category.WILD_MEAT;
                case "roast":
                case "smoked":
                case "spitroast":
                    return Category.KITCHEN;
                default:
                    return Category.FISH; // filets, roe, crab and lobster meat
            }
        }
        for (String r : res) {
            if (r.startsWith("gfx/invobjs/fish-") || r.equals("gfx/invobjs/roe"))
                return Category.FISH;
        }

        Category vspec = vspecCategory(key);
        if (vspec != null)
            return vspec;

        for (String r : res) {
            String leaf = r.substring(r.lastIndexOf('/') + 1);
            if (leaf.startsWith("wblock-") || leaf.startsWith("board-") || leaf.startsWith("bough")
                    || leaf.startsWith("bark") || leaf.equals("branch") || leaf.startsWith("log"))
                return Category.WOOD;
            if (leaf.startsWith("bar-") || leaf.startsWith("nugget-") || leaf.equals("goldegg"))
                return Category.MINE;
            if (leaf.startsWith("clay-") || leaf.startsWith("brick-"))
                return Category.STONE;
            if (leaf.startsWith("cheese-") || leaf.startsWith("egg-"))
                return Category.DAIRY;
            if (leaf.startsWith("seed-") || leaf.startsWith("flaxfibre") || leaf.startsWith("hempfibre"))
                return Category.GARDEN;
            if (leaf.endsWith("hide") || leaf.endsWith("hide-blood") || leaf.startsWith("wool-"))
                return Category.HIDES;
            if (r.startsWith("gfx/invobjs/herbs/"))
                return Category.FORAGE;
        }

        if (ores().contains(key))
            return Category.MINE;
        if (stones().contains(key))
            return Category.STONE;
        return byKeyword(key);
    }

    private static Category byKeyword(String key) {
        if (key.startsWith("roast ") || key.startsWith("smoked ") || key.startsWith("spitroast "))
            return Category.KITCHEN;
        if (key.startsWith("raw ") || key.endsWith(" meat")) {
            for (String farm : FARM_NAMES)
                if (key.startsWith(farm))
                    return Category.FARM_MEAT;
            return Category.WILD_MEAT;
        }
        if (key.startsWith("filet of") || key.startsWith("dried filet of") || key.endsWith(" roe"))
            return Category.FISH;
        if (key.startsWith("block of") || key.startsWith("board of") || key.endsWith(" bough")
                || key.endsWith(" bark") || key.endsWith(" log"))
            return Category.WOOD;
        if (key.startsWith("bar of") || key.startsWith("nugget of") || key.endsWith(" ore") || key.endsWith("coal"))
            return Category.MINE;
        if (key.endsWith("clay") || key.endsWith(" brick") || key.endsWith(" bricks"))
            return Category.STONE;
        if (key.contains("milk") || key.equals("curd") || key.endsWith(" egg") || key.endsWith(" cheese"))
            return Category.DAIRY;
        if (key.endsWith(" hide") || key.endsWith(" fur") || key.endsWith(" pelt") || key.contains("bone")
                || key.endsWith(" wool") || key.endsWith(" leather"))
            return Category.HIDES;
        if (key.endsWith(" seeds") || key.endsWith(" seed") || key.endsWith(" fibres"))
            return Category.GARDEN;
        if (key.endsWith("flour") || key.endsWith("dough"))
            return Category.KITCHEN;
        return Category.OTHER;
    }

    private static Category vspecCategory(String key) {
        Map<String, Category> map = vspecByName;
        if (map == null) {
            map = new HashMap<>();
            for (Map.Entry<String, ArrayList<JSONObject>> e : VSpec.categories.entrySet()) {
                Category c = VSPEC.get(e.getKey().trim());
                if (c == null)
                    continue;
                for (JSONObject item : e.getValue())
                    map.putIfAbsent(normalize(item.optString("name")), c);
            }
            vspecByName = map;
        }
        return map.get(key);
    }

    private static Set<String> ores() {
        Set<String> s = ores;
        if (s == null) {
            s = new HashSet<>();
            for (String n : Container.ores.keys)
                s.add(normalize(n));
            ores = s;
        }
        return s;
    }

    private static Set<String> stones() {
        Set<String> s = stones;
        if (s == null) {
            s = new HashSet<>();
            for (String n : nurgling.actions.bots.Chipper.stones.keys)
                s.add(normalize(n));
            s.removeAll(ores());
            stones = s;
        }
        return s;
    }

    /** Resource paths of a catalogue descriptor, in layer order; empty for wiki pictures. */
    private static List<String> resources(JSONObject descriptor) {
        if (descriptor == null)
            return Collections.emptyList();
        if (descriptor.has("static"))
            return List.of(descriptor.getString("static"));
        JSONArray layers = descriptor.optJSONArray("layer");
        if (layers == null)
            return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < layers.length(); i++)
            out.add(layers.getString(i));
        return out;
    }
}
