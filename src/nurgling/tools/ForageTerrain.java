package nurgling.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared vocabulary for wiki forage terrain names and map tile resources. */
public final class ForageTerrain {
    private static final Map<String, Terrain> BY_NAME = new LinkedHashMap<>();
    private static final Map<String, Terrain> BY_KEY = new LinkedHashMap<>();
    private static final List<String> LONGEST_NAMES;

    static {
        add("Acre Clay Field");
        add("Badlands");
        add("Beach");
        add("Beech Grove");
        add("Black Wood");
        add("Blue Sod");
        add("Bog");
        add("Bounty Acre");
        add("Cave");
        add("Cloud Range");
        add("Deep Tangle");
        add("Dry Flat");
        add("Dry Weald");
        add("Fen");
        add("Flower Meadow");
        add("Grass");
        add("Green Brake", "greenbrake");
        add("Greens Ward", "greensward");
        add("Grove");
        add("Hard Steppe");
        add("Heath");
        add("Highground");
        add("Leaf");
        add("Leaf Detritus");
        add("Leaf Patch");
        add("Lichen Wold");
        add("Lush Field");
        add("Moor");
        add("Moss Brush");
        add("Mountain", "mountain", "mountain2", "mountain3");
        add("Oak Wilds");
        add("Ox Pasture");
        add("Peat Moss");
        add("Pine Barren");
        add("Red Plain");
        add("Root Bosk");
        add("Sand Cliff");
        add("Scrub Veld");
        add("Shady Copse");
        add("Skargard");
        add("Sombre Bramble");
        add("Sour Timber");
        add("Swamp");
        add("Tidepool");
        add("Timber Land");
        add("Wald");
        add("Wild Moor");
        add("Wild Turf");
        add("Ashland");
        add("Gleam Grotto");
        add("Warm Depth");
        add("Wild Cavern");

        group("Forest", "Beech Grove", "Black Wood", "Deep Tangle", "Dry Weald", "Green Brake", "Grove",
                "Leaf", "Leaf Detritus", "Leaf Patch", "Lichen Wold", "Moss Brush", "Oak Wilds",
                "Pine Barren", "Root Bosk", "Shady Copse", "Sombre Bramble", "Sour Timber",
                "Timber Land", "Wald");
        group("Grassland", "Blue Sod", "Bounty Acre", "Cloud Range", "Dry Flat", "Flower Meadow",
                "Grass", "Greens Ward", "Hard Steppe", "Highground", "Lush Field", "Moor",
                "Ox Pasture", "Red Plain", "Wild Moor", "Wild Turf");
        add("Shallow Water", "water", "owater");
        add("Water Terrain", "water", "owater", "deep", "odeep", "vdeep", "ovdeep");

        ArrayList<String> names = new ArrayList<>();
        for(Terrain terrain : BY_NAME.values())
            names.add(terrain.name);
        names.sort(Comparator.comparingInt(String::length).reversed());
        LONGEST_NAMES = Collections.unmodifiableList(names);
    }

    private ForageTerrain() {
    }

    public static List<String> parse(String value) {
        if(value == null || value.trim().isEmpty())
            return Collections.emptyList();
        String trimmed = value.trim();
        if(trimmed.indexOf(',') >= 0) {
            ArrayList<String> result = new ArrayList<>();
            for(String part : trimmed.split(",")) {
                String name = canonicalName(part);
                if(!name.isEmpty())
                    result.add(name);
            }
            return immutableDistinct(result);
        }

        ArrayList<String> result = new ArrayList<>();
        int offset = 0;
        while(offset < trimmed.length()) {
            while(offset < trimmed.length() && Character.isWhitespace(trimmed.charAt(offset)))
                offset++;
            if(offset >= trimmed.length())
                break;
            String match = matchAt(trimmed, offset);
            if(match == null) {
                if(offset == 0)
                    return Collections.singletonList(canonicalName(trimmed));
                return Collections.singletonList(trimmed);
            }
            result.add(match);
            offset += match.length();
        }
        return immutableDistinct(result);
    }

    public static String join(Collection<String> names) {
        return String.join(", ", names == null ? Collections.emptyList() : names);
    }

    public static Set<String> resourceNames(Collection<String> names) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if(names == null)
            return result;
        for(String name : names) {
            Terrain terrain = lookup(name);
            if(terrain != null)
                result.addAll(terrain.resources);
        }
        return result;
    }

    public static Set<String> resourceNames(String nameOrKey) {
        return resourceNames(Collections.singletonList(nameOrKey));
    }

    public static boolean known(String nameOrKey) {
        return lookup(nameOrKey) != null;
    }

    private static String matchAt(String value, int offset) {
        for(String name : LONGEST_NAMES) {
            int end = offset + name.length();
            if(end <= value.length() && value.regionMatches(true, offset, name, 0, name.length())
                    && (end == value.length() || Character.isWhitespace(value.charAt(end))))
                return name;
        }
        return null;
    }

    private static String canonicalName(String value) {
        String trimmed = value == null ? "" : value.trim();
        Terrain terrain = lookup(trimmed);
        return terrain == null ? trimmed : terrain.name;
    }

    private static Terrain lookup(String nameOrKey) {
        if(nameOrKey == null)
            return null;
        String key = nameOrKey.trim().toLowerCase(Locale.ROOT);
        Terrain terrain = BY_NAME.get(key);
        return terrain != null ? terrain : BY_KEY.get(compact(key));
    }

    private static void add(String name, String... tileKeys) {
        List<String> keys = tileKeys.length == 0 ? Collections.singletonList(compact(name))
                : Arrays.asList(tileKeys);
        ArrayList<String> resources = new ArrayList<>();
        for(String key : keys)
            resources.add("gfx/tiles/" + key);
        register(new Terrain(name, compact(name), resources));
    }

    private static void group(String name, String... members) {
        LinkedHashSet<String> resources = new LinkedHashSet<>();
        for(String member : members) {
            Terrain terrain = lookup(member);
            if(terrain != null)
                resources.addAll(terrain.resources);
        }
        register(new Terrain(name, compact(name), new ArrayList<>(resources)));
    }

    private static void register(Terrain terrain) {
        BY_NAME.put(terrain.name.toLowerCase(Locale.ROOT), terrain);
        BY_KEY.put(terrain.key, terrain);
    }

    private static String compact(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static List<String> immutableDistinct(Collection<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(values)));
    }

    private static final class Terrain {
        final String name;
        final String key;
        final List<String> resources;

        Terrain(String name, String key, List<String> resources) {
            this.name = name;
            this.key = key;
            this.resources = Collections.unmodifiableList(resources);
        }
    }
}
