package nurgling.cookbook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The wiki's generic ingredient types ("Edible Mushroom", "Raw Meat", "Any Flour", ...) with their
 * members, from generic-types.json (tools/wiki-recipes/fetch_generics.py), plus the corrections
 * needed to compare wiki item names with the names a food's tooltip lists its ingredients under.
 *
 * <p>The tooltip names some ingredients after their source rather than the item: meat after the
 * animal ("Badger" for Raw Badger), a fish filet after the fish ("Perch"), milk after its kind
 * ("Cowsmilk", which reaches a dish through its butter). {@link #key} reduces both sides to one form.
 */
public final class GenericTypes {
    /** Wiki names that {@link #key} cannot turn into the tooltip's name, lower case -> tooltip names. */
    private static final Map<String, List<String>> ALIASES = Map.of(
        "raw wild pork", List.of("Boar"),
        "raw tame reindeer", List.of("Tame Reindeer Venison"),
        "raw wildhorse", List.of("Wild Horse"),
        "watermelon slice", List.of("Watermelon"),
        "bollock", List.of("Pork bollock", "Beef bollock", "Mutton bollock", "Goat bollock", "Horse bollock", "Reindeer bollock"));

    /** Names of options whose item is stored under another name, lower case -> stored item names. */
    private static final Map<String, List<String>> STOCK_NAMES = Map.ofEntries(
        Map.entry("raw duck meat", List.of("Duck Meat")),
        Map.entry("mammoth meat", List.of("Raw Mammoth")),
        Map.entry("goat bollock", List.of("Chevon Bollock")),
        Map.entry("reindeer bollock", List.of("Tame Reindeer Bollock")),
        Map.entry("black pepper", List.of("Dried Pepper Drupe")),
        Map.entry("almond", List.of("Almonds")),
        /* The tooltips of the resources in ICONS. */
        Map.entry("birdcherries", List.of("Bird Cherries")),
        Map.entry("black poplar catkin", List.of("Blackpoplar Catkin")),
        Map.entry("boar hide", List.of("Boarhide")),
        Map.entry("elm seedling", List.of("Elm Seeds")),
        Map.entry("flax fibre", List.of("Flax Fibres")),
        Map.entry("hemp fibre", List.of("Hemp Fibres")),
        Map.entry("hornbeam catkin", List.of("Hornbeam Catkins")),
        Map.entry("mallorn tree seeds", List.of("Mallorn Nuts")),
        Map.entry("malted millet", List.of("Malted Millet Seeds")),
        Map.entry("otter hide", List.of("Otterhide")),
        Map.entry("seeds of green kale", List.of("Green Kale Seeds")),
        Map.entry("southron pine cone", List.of("Southron Pinecone")),
        Map.entry("strawberry", List.of("Strawberries")),
        Map.entry("whitebeam fruit", List.of("Whitebeam Fruits")),
        Map.entry("wild kale seeds", List.of("Wildkale Seeds")));

    /**
     * Icons of items the client has no icon for under their name, checked on the resource server
     * (each resource's tooltip is the name, or the game's spelling: "Almonds", "Woodgrouse Egg").
     */
    private static final Map<String, String> ICONS = Map.ofEntries(
        Map.entry("Brown Kelp", "gfx/invobjs/brownkelp"),
        Map.entry("Fairy Mushroom", "gfx/invobjs/fairyshroom"),
        Map.entry("Indigo Cap", "gfx/invobjs/herbs/indigocap"),
        Map.entry("Black Truffles", "gfx/invobjs/herbs/truffle-black"),
        Map.entry("White Truffles", "gfx/invobjs/herbs/truffle-white"),
        Map.entry("Blue Truffles", "gfx/invobjs/herbs/truffle-blue"),
        Map.entry("Almond", "gfx/invobjs/almond"),
        Map.entry("Withercorn", "gfx/invobjs/seed-witherstand"),
        Map.entry("Arrowwood Berries", "gfx/invobjs/seed-arrowwood"),
        Map.entry("Caprifole Berries", "gfx/invobjs/seed-caprifole"),
        Map.entry("Gorse", "gfx/invobjs/seed-gorse"),
        Map.entry("Tibast Berries", "gfx/invobjs/seed-tibast"),
        Map.entry("Tundra Rose Fruit", "gfx/invobjs/seed-tundrarose"),
        Map.entry("Black Henbane", "gfx/invobjs/herbs/henbane"),
        Map.entry("Crocus", "gfx/invobjs/herbs/crocus"),
        Map.entry("Edelweiß", "gfx/invobjs/herbs/edelweiss"),
        Map.entry("Crane Fly", "gfx/invobjs/cranefly"),
        Map.entry("Dead Wood Scorpion", "gfx/invobjs/woodscorpion-dead"),
        Map.entry("Dumbledore", "gfx/invobjs/dumbledore"),
        Map.entry("Upside-Downbeldore", "gfx/invobjs/dumbledoreo"),
        Map.entry("Crying White Onion", "gfx/invobjs/whiteonion-crying"),
        Map.entry("Wild Tuber", "gfx/invobjs/pretuber"),
        Map.entry("Wood Grouse Egg", "gfx/invobjs/egg-woodgrouse"),
        Map.entry("Pickled Radish", "gfx/invobjs/pickledradish"),
        Map.entry("Acacia Pod", "gfx/invobjs/seed-acacia"),
        Map.entry("Alder Catkin", "gfx/invobjs/seed-alder"),
        Map.entry("Ash Samaras", "gfx/invobjs/seed-ash"),
        Map.entry("Aspen Catkin", "gfx/invobjs/seed-aspen"),
        Map.entry("Bay Willow Catkins", "gfx/invobjs/seed-baywillow"),
        Map.entry("Birch Catkin", "gfx/invobjs/seed-birch"),
        Map.entry("Birdcherries", "gfx/invobjs/birdcherry"),
        Map.entry("Bittersweet Nightshade Berries", "gfx/invobjs/seed-bsnightshade"),
        Map.entry("Black Poplar Catkin", "gfx/invobjs/seed-blackpoplar"),
        Map.entry("Black Trumpets", "gfx/invobjs/herbs/blacktrumpet"),
        Map.entry("Boar hide", "gfx/invobjs/boarhide"),
        Map.entry("Bog-Myrtle Cones", "gfx/invobjs/seed-bogmyrtle"),
        Map.entry("Boxwood Seeds", "gfx/invobjs/seed-boxwood"),
        Map.entry("Buckthorn Drupes", "gfx/invobjs/seed-buckthorn"),
        Map.entry("Checker Tree Fruits", "gfx/invobjs/seed-checkertree"),
        Map.entry("Cherries", "gfx/invobjs/cherry"),
        Map.entry("Cherry Kernels", "gfx/invobjs/seed-cherry"),
        Map.entry("Cleaned Crane", "gfx/invobjs/crane-cleaned"),
        Map.entry("Cleaned Goshawk", "gfx/invobjs/goshawk-cleaned"),
        Map.entry("Conker", "gfx/invobjs/seed-conkertree"),
        Map.entry("Crampbark Berries", "gfx/invobjs/seed-crampbark"),
        Map.entry("Crane Feather", "gfx/invobjs/feather-crane"),
        Map.entry("Cypress Cone", "gfx/invobjs/seed-cypress"),
        Map.entry("Direlily Seeds", "gfx/invobjs/seed-direlily"),
        Map.entry("Dogwood Seeds", "gfx/invobjs/seed-dogwood"),
        Map.entry("Dragon Tree Seeds", "gfx/invobjs/seed-dragontree"),
        Map.entry("Elm Seedling", "gfx/invobjs/seed-elm"),
        Map.entry("Flax Fibre", "gfx/invobjs/flaxfibre"),
        Map.entry("Fly Woodbine Berries", "gfx/invobjs/seed-woodbine"),
        Map.entry("Garefowl Feather", "gfx/invobjs/feather-garefowl"),
        Map.entry("Gloomcap Spores", "gfx/invobjs/seed-gloomcap"),
        Map.entry("Gnome's Cap Spore", "gfx/invobjs/seed-gnomeshat"),
        Map.entry("Goldenchain Seeds", "gfx/invobjs/seed-goldenchain"),
        Map.entry("Goshawk Feather", "gfx/invobjs/feather-goshawk"),
        Map.entry("Gray Alder Cones", "gfx/invobjs/seed-grayalder"),
        Map.entry("Hawthorn Fruits", "gfx/invobjs/seed-hawthorn"),
        Map.entry("Hemp Fibre", "gfx/invobjs/hempfibre"),
        Map.entry("Hoarwithy Berries", "gfx/invobjs/seed-hoarwithy"),
        Map.entry("Hollyberries", "gfx/invobjs/seed-holly"),
        Map.entry("Hornbeam Catkin", "gfx/invobjs/seed-hornbeam"),
        Map.entry("Korund", "gfx/invobjs/corund"),
        Map.entry("Larch Cones", "gfx/invobjs/seed-larch"),
        Map.entry("Laurel Seeds", "gfx/invobjs/seed-laurel"),
        Map.entry("Lemon Seed", "gfx/invobjs/seed-lemontree"),
        Map.entry("Linden Fruits", "gfx/invobjs/seed-linden"),
        Map.entry("Lote Tree Drupes", "gfx/invobjs/seed-lotetree"),
        Map.entry("Mallorn Tree Seeds", "gfx/invobjs/seed-mallorn"),
        Map.entry("Malted Millet", "gfx/invobjs/seed-milletmalt"),
        Map.entry("Mastic Fruit", "gfx/invobjs/masticfruit"),
        Map.entry("Mayflower Pomes", "gfx/invobjs/seed-mayflower"),
        Map.entry("Mouflon Horn", "gfx/invobjs/mouflonhorn"),
        Map.entry("Mound Beans", "gfx/invobjs/seed-moundtree"),
        Map.entry("Moundwood Bough", "gfx/invobjs/bough-moundtree"),
        Map.entry("Narwhal Tusk", "gfx/invobjs/narwhaltusk"),
        Map.entry("Olive Kernel", "gfx/invobjs/seed-olive"),
        Map.entry("Orange Seed", "gfx/invobjs/seed-orangetree"),
        Map.entry("Osier Catkin", "gfx/invobjs/seed-osier"),
        Map.entry("Otter Hide", "gfx/invobjs/otterhide"),
        Map.entry("Pear Core", "gfx/invobjs/seed-pear"),
        Map.entry("Pearl Oyster", "gfx/invobjs/herbs/pearloyster"),
        Map.entry("Plane Seedpods", "gfx/invobjs/seed-planetree"),
        Map.entry("Plum Kernel", "gfx/invobjs/seed-plum"),
        Map.entry("Poplar Catkin", "gfx/invobjs/seed-poplar"),
        Map.entry("Quince Core", "gfx/invobjs/quincecore"),
        Map.entry("Rat Cloth", "gfx/invobjs/ratcloth"),
        Map.entry("Rat Fibre", "gfx/invobjs/ratfibre"),
        Map.entry("Raw Garefowl", "gfx/invobjs/meat-raw+gfx/invobjs/meat-garefowl"),
        Map.entry("Rowan Berries", "gfx/invobjs/rowanberry"),
        Map.entry("Sallow Catkin", "gfx/invobjs/seed-sallow"),
        Map.entry("Seeds of Green Kale", "gfx/invobjs/seed-greenkale"),
        Map.entry("Southron Pine Cone", "gfx/invobjs/seed-southronpine"),
        Map.entry("Spindleberries", "gfx/invobjs/seed-spindlebush"),
        Map.entry("Strawberry", "gfx/invobjs/herbs/strawberry"),
        Map.entry("Stuffed Bird", "gfx/invobjs/stuffedbird"),
        Map.entry("Stuffed Puff", "gfx/invobjs/stuffedpuff"),
        Map.entry("Sweetgum Seedpod", "gfx/invobjs/seed-sweetgum"),
        Map.entry("Sycamore Seed", "gfx/invobjs/seed-sycamore"),
        Map.entry("Tamarisk Seeds", "gfx/invobjs/seed-tamarisk"),
        Map.entry("Teabush Seedpod", "gfx/invobjs/seed-teabush"),
        Map.entry("Towercap Spore", "gfx/invobjs/seed-towercap"),
        Map.entry("Tree Heath Seed", "gfx/invobjs/seed-treeheath"),
        Map.entry("Trombone Chantrelle Spore", "gfx/invobjs/seed-trombonechantrelle"),
        Map.entry("Warty Birch Catkin", "gfx/invobjs/seed-wartybirch"),
        Map.entry("Whitebeam Fruit", "gfx/invobjs/whitebeamfruit"),
        Map.entry("Wild Kale Seeds", "gfx/invobjs/seed-brassica"),
        Map.entry("Wildflower Seeds", "gfx/invobjs/seed-flower"),
        Map.entry("Wildkale Leaf", "gfx/invobjs/leaf-brassica"),
        Map.entry("Wych Elm Samara", "gfx/invobjs/seed-wychelm"),
        Map.entry("Zelkova Catkin", "gfx/invobjs/seed-zelkova"),
        /* Not checked: no squab meat resource was found; this is the adult rock dove's meat. */
        Map.entry("Squab Meat", "gfx/invobjs/meat-rockdove"),
        /* The milks share the game's one Milk icon. */
        Map.entry("Cowsmilk", "gfx/invobjs/milk"),
        Map.entry("Goatsmilk", "gfx/invobjs/milk"),
        Map.entry("Sheepsmilk", "gfx/invobjs/milk"));

    /** Wiki pages named differently from the game's item. */
    private static final Map<String, String> RENAMES = Map.of(
        "Laurel Leaf", "Laurel Leaves");

    /** Members the wiki lists that the game does not take, by type. */
    private static final Map<String, List<String>> EXCLUDED = Map.of(
        /* A block of wood, not a mushroom. */
        "Edible Mushroom", List.of("Block of Trombone Chantrelle"),
        "Any Mushroom", List.of("Block of Trombone Chantrelle"));

    /** Members the wiki lacks, by type. */
    private static final Map<String, List<String>> EXTRA = Map.of(
        "Raw Meat", List.of("Raw Wildhorse"),
        "Any Flour", List.of("Corn Grass Flour"));

    /** Types that accept each other's members: the wiki notes the game makes no distinction. */
    private static final List<List<String>> SAME = List.of(
        List.of("Edible Mushroom", "Any Mushroom"));

    /** Types the wiki has no member list for, made of other types. */
    private static final Map<String, List<String>> UNIONS = Map.of(
        "Fruit or Berry", List.of("Fruit", "Berry"));

    /** Types the wiki has no page for, but a tooltip lists: milk comes in through butter and batter. */
    public static final String MILK = "Milk";
    private static final Map<String, List<String>> SYNTHETIC = Map.of(
        MILK, List.of("Cowsmilk", "Goatsmilk", "Sheepsmilk"));

    private static GenericTypes bundled;

    private final Map<String, List<String>> members = new LinkedHashMap<>();
    private final Map<String, String> byKey = new HashMap<>();
    private final Map<String, Set<String>> memberKeys = new HashMap<>();
    /* Tooltip key -> the items a food lists under that name. */
    private final Map<String, Set<String>> itemsByKey = new HashMap<>();

    GenericTypes(Map<String, List<String>> raw) {
        for(String type : raw.keySet()) {
            Set<String> flat = new LinkedHashSet<>();
            flatten(type, raw, flat, new HashSet<>());
            members.put(type, new ArrayList<>(flat));
        }
        for(Map.Entry<String, List<String>> e : EXTRA.entrySet())
            members.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        for(List<String> same : SAME) {
            Set<String> all = new LinkedHashSet<>();
            for(String type : same)
                all.addAll(members.getOrDefault(type, List.of()));
            for(String type : same)
                members.put(type, new ArrayList<>(all));
        }
        for(Map.Entry<String, List<String>> e : EXCLUDED.entrySet())
            members.getOrDefault(e.getKey(), new ArrayList<>()).removeAll(e.getValue());
        for(Map.Entry<String, List<String>> e : UNIONS.entrySet()) {
            Set<String> all = new LinkedHashSet<>();
            for(String type : e.getValue())
                all.addAll(members.getOrDefault(type, List.of()));
            members.put(e.getKey(), new ArrayList<>(all));
        }
        members.putAll(SYNTHETIC);
        members.values().removeIf(List::isEmpty);
        for(Map.Entry<String, List<String>> e : members.entrySet()) {
            byKey.put(norm(e.getKey()), e.getKey());
            Set<String> keys = new HashSet<>();
            for(String m : e.getValue()) {
                List<String> tn = tooltipNames(m);
                for(String n : tn) {
                    keys.add(key(n));
                    /* An item the tooltip splits up exists as each part: "Pork Bollock". */
                    itemsByKey.computeIfAbsent(key(n), k -> new LinkedHashSet<>()).add((tn.size() > 1) ? n : m);
                }
            }
            memberKeys.put(e.getKey(), keys);
        }
    }

    /**
     * The item names a food's tooltip ingredient may be stored under: "Badger" is stored as Raw
     * Badger, "Perch" as Filet of Perch. A name no type knows is taken to be the item's own.
     */
    public List<String> storedAs(String ingredient) {
        List<String> ret = new ArrayList<>();
        for(String item : itemsByKey.getOrDefault(key(ingredient), Set.of(ingredient)))
            ret.addAll(stockNames(item));
        return ret;
    }

    private static void flatten(String type, Map<String, List<String>> raw, Set<String> out, Set<String> seen) {
        if(!seen.add(type))
            return;
        for(String m : raw.getOrDefault(type, List.of())) {
            if(raw.containsKey(m))
                flatten(m, raw, out, seen);
            /* Yesteryear's items act as the regular one once processed, so a dish lists the regular one. */
            else if(!m.startsWith("Yesteryear's "))
                out.add(RENAMES.getOrDefault(m, m));
        }
    }

    /** The types shipped with the client. */
    public static synchronized GenericTypes bundled() throws IOException {
        if(bundled == null) {
            try(InputStream in = GenericTypes.class.getResourceAsStream("generic-types.json")) {
                if(in == null)
                    throw new FileNotFoundException("Missing generic-types.json");
                JSONObject types = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONObject("types");
                Map<String, List<String>> raw = new LinkedHashMap<>();
                for(String type : types.keySet()) {
                    JSONArray a = types.getJSONArray(type);
                    List<String> list = new ArrayList<>();
                    for(int i = 0; i < a.length(); i++)
                        list.add(a.getString(i));
                    raw.put(type, list);
                }
                bundled = new GenericTypes(raw);
            }
        }
        return bundled;
    }

    /** The type a recipe input names ("Any Flour", "Edible Mushroom"), or null when it is not one. */
    public String type(String inputName) {
        return byKey.get(norm(inputName));
    }

    /** The members of a type, by wiki name. */
    public List<String> members(String type) {
        return members.getOrDefault(type, List.of());
    }

    /** Whether a tooltip ingredient belongs to a type. */
    public boolean contains(String type, String ingredient) {
        return memberKeys.getOrDefault(type, Set.of()).contains(key(ingredient));
    }

    /** Every type holding a tooltip ingredient, smallest first. */
    public List<String> typesOf(String ingredient) {
        String k = key(ingredient);
        List<String> ret = new ArrayList<>();
        for(Map.Entry<String, Set<String>> e : memberKeys.entrySet()) {
            if(e.getValue().contains(k))
                ret.add(e.getKey());
        }
        ret.sort(Comparator.comparingInt((String t) -> memberKeys.get(t).size()).thenComparing(t -> t));
        return ret;
    }

    /** Whether every member of {@code small} is a member of {@code big}. */
    public boolean within(String small, String big) {
        return memberKeys.getOrDefault(big, Set.of()).containsAll(memberKeys.getOrDefault(small, Set.of()));
    }

    /** The names a food's tooltip may list a wiki item under; most items keep their own name. */
    public static List<String> tooltipNames(String wikiName) {
        return ALIASES.getOrDefault(wikiName.toLowerCase(Locale.ROOT), List.of(wikiName));
    }

    /** A known icon resource for an item the client may have no icon for, or null. */
    public static String icon(String item) {
        return ICONS.get(item);
    }

    /** The names an option's item may be stored under: its own, and any the game uses instead. */
    public static List<String> stockNames(String name) {
        List<String> ret = new ArrayList<>();
        ret.add(name);
        ret.addAll(STOCK_NAMES.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()));
        return ret;
    }

    /** The game's names for a wiki item: the names it is stored under instead, else its own. */
    public static List<String> itemNames(String name) {
        return STOCK_NAMES.getOrDefault(name.toLowerCase(Locale.ROOT), List.of(name));
    }

    /**
     * One form for a stored item's name and an option's. Storage keeps the game's item names, so
     * unlike {@link #key} nothing is stripped (a whole Ptarmigan is not Ptarmigan Meat), except
     * Yesteryear's, and spaces, as the game writes some names together ("Woodgrouse Egg").
     */
    public static String stockKey(String name) {
        return norm(name).replaceFirst("^yesteryears ", "").replace(" ", "");
    }

    /**
     * One form for a wiki item and the tooltip ingredient it becomes: "Raw Badger", "Badger Meat"
     * and "Badger" all give "badger", "Filet of Perch" and "Perch" give "perch".
     */
    public static String key(String name) {
        String n = norm(name);
        n = n.replaceFirst("^yesteryears ", "");
        n = n.replaceFirst("^smoked ", "");
        n = n.replaceFirst("^filet of ", "");
        n = n.replaceFirst("^raw ", "");
        n = n.replaceFirst(" meat$", "");
        return n;
    }

    /**
     * Lower case letters, digits and single spaces only, so names compare across punctuation and
     * accents: "Surf 'n' Turf" and "Surf'n'Turf", "Liver Paté" and the database's "Liver Pat?".
     */
    public static String norm(String name) {
        if(name == null)
            return "";
        /* Non-ASCII letters are dropped, not folded: the database already lost them to "?". */
        String n = name.toLowerCase(Locale.ROOT).replace("'n'", " n ").replaceAll("[^a-z0-9 ]", "");
        return n.trim().replaceAll("\\s+", " ");
    }
}
