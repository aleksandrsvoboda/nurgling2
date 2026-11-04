package nurgling.discovery;

import nurgling.craft.AtlasCatalog;
import nurgling.tools.ItemResources;
import nurgling.tools.VSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every item the Discovery Atlas lists, merged from: this character's ledger, the other
 * characters' ledgers, the gob products in VSpec.object, the craft atlas's recorded recipes and,
 * only when asked, the offline icon catalogue and wiki recipes, whose names are not all real.
 */
public final class DiscoveryCatalog {
    public enum Category { TREE, BUSH, STONE, CRAFTED, OTHER }

    public static final class Row {
        public final String name;
        public String res;
        public Category category = Category.OTHER;
        /** Gob resources that yield it. */
        public final Set<String> gobs = new TreeSet<>();
        /** Recipes that make it. */
        public final Set<String> recipes = new TreeSet<>();
        /** How many other characters count it as discovered. */
        public int others;
        /** Only from a source whose names may not be real game names. */
        public boolean unverified = true;

        Row(String name) {
            this.name = name;
        }
    }

    private DiscoveryCatalog() {
    }

    public static List<Row> build(DiscoveryLedger ledger, Map<String, DiscoveryLedger.Other> others,
                                  Collection<AtlasCatalog.Recipe> recipes, boolean unverified) {
        Map<String, Row> rows = new HashMap<>();

        for (Map.Entry<String, ArrayList<String>> e : VSpec.object.entrySet()) {
            Category cat = categoryOf(e.getKey());
            for (String product : e.getValue()) {
                Row r = verified(rows, product);
                r.gobs.add(e.getKey());
                if (r.category == Category.OTHER)
                    r.category = cat;
            }
        }

        if (recipes != null) {
            for (AtlasCatalog.Recipe recipe : recipes) {
                if (!recipe.recorded && !unverified)
                    continue;
                for (AtlasCatalog.Material out : recipe.outputs) {
                    if (out.category || out.name == null || out.name.isEmpty())
                        continue;
                    Row r = recipe.recorded ? verified(rows, out.name) : rows.computeIfAbsent(out.name, Row::new);
                    r.recipes.add(recipe.name);
                    if (r.category == Category.OTHER)
                        r.category = Category.CRAFTED;
                    if (r.res == null && out.resource != null && out.resource.startsWith("gfx/"))
                        r.res = out.resource;
                }
            }
        }

        for (Map.Entry<String, DiscoveryLedger.Other> e : others.entrySet()) {
            Row r = verified(rows, e.getKey());
            r.others = e.getValue().done;
            if (r.res == null)
                r.res = e.getValue().res;
        }

        for (DiscoveryLedger.Entry e : ledger.all()) {
            Row r = verified(rows, e.name);
            if (e.res != null)
                r.res = e.res;
        }

        if (unverified) {
            Set<String> known = new java.util.HashSet<>();
            for (String name : rows.keySet())
                known.add(name.toLowerCase(Locale.ROOT));
            for (String lower : ItemResources.catalogueNames()) {
                if (known.add(lower))
                    rows.put(lower, new Row(titleCase(lower)));
            }
        }

        List<Row> list = new ArrayList<>(rows.values());
        list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        return list;
    }

    private static Row verified(Map<String, Row> rows, String name) {
        Row r = rows.computeIfAbsent(name, Row::new);
        r.unverified = false;
        return r;
    }

    public static Category categoryOf(String gobRes) {
        if (gobRes.startsWith("gfx/terobjs/trees"))
            return Category.TREE;
        if (gobRes.startsWith("gfx/terobjs/bushes"))
            return Category.BUSH;
        if (gobRes.startsWith("gfx/terobjs/bumlings"))
            return Category.STONE;
        return Category.OTHER;
    }

    /** "alder bough" -> "Alder Bough"; the catalogue keeps only lower-case names. */
    private static String titleCase(String lower) {
        StringBuilder sb = new StringBuilder(lower.length());
        boolean start = true;
        for (char c : lower.toCharArray()) {
            sb.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-';
        }
        return sb.toString();
    }

    /** A short readable name for a gob resource: "gfx/terobjs/trees/figtree" -> "figtree". */
    public static String gobLabel(String res) {
        int i = res.lastIndexOf('/');
        return i < 0 ? res : res.substring(i + 1);
    }
}
