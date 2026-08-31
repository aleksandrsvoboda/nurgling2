package nurgling.cookbook;

import nurgling.db.DatabaseManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An immutable answer to "what can I cook right now, and which variants have I never tried".
 *
 * <p>Computed in one pass on the database worker and then read-only, because
 * {@link DatabaseManager} work blocks the UI thread - the widget must never resolve an ingredient
 * or touch a connection while drawing. Use {@link Builder} to produce one.
 */
public class CookbookIndex {
    /** What one recipe needs and whether the pantry covers it. */
    public static class RecipeStatus {
        public final boolean cookable;
        /** ingredients with nothing in stock that could satisfy them */
        public final List<String> missing;
        public final List<Held> ingredients;

        RecipeStatus(boolean cookable, List<String> missing, List<Held> ingredients) {
            this.cookable = cookable;
            this.missing = Collections.unmodifiableList(missing);
            this.ingredients = Collections.unmodifiableList(ingredients);
        }
    }

    /** One ingredient of a recipe, paired with the stocked item that satisfies it. */
    public static class Held {
        public final String ingredient;
        /** the item name found in stock, or null when none is */
        public final String item;
        public final int count;
        /** best quality held of {@link #item}, 0 when none is held */
        public final double quality;
        /** false when no rule or alias could map the ingredient onto any known item at all */
        public final boolean resolved;

        Held(String ingredient, String item, int count, double quality, boolean resolved) {
            this.ingredient = ingredient;
            this.item = item;
            this.count = count;
            this.quality = quality;
            this.resolved = resolved;
        }
    }

    /**
     * One variable slot of a dish - its meat slot, its mushroom slot - and how much of that slot you
     * have explored.
     *
     * <p>The domain is deliberately evidence-based: it holds only ingredients the game has actually
     * accepted in this slot somewhere in your cookbook. Enumerating a whole VSpec category instead
     * would multiply out to thousands of combinations, most of which the recipe would refuse.
     */
    public static class SlotCoverage {
        public final String group;
        public final List<String> tried;
        public final List<String> untried;
        public final List<String> untriedInStock;

        SlotCoverage(String group, List<String> tried, List<String> untried, List<String> untriedInStock) {
            this.group = group;
            this.tried = Collections.unmodifiableList(tried);
            this.untried = Collections.unmodifiableList(untried);
            this.untriedInStock = Collections.unmodifiableList(untriedInStock);
        }

        public int domainSize() {
            return tried.size() + untried.size();
        }
    }

    public static class DishCoverage {
        public final String dish;
        public final int variants;
        public final List<SlotCoverage> slots;

        DishCoverage(String dish, int variants, List<SlotCoverage> slots) {
            this.dish = dish;
            this.variants = variants;
            this.slots = Collections.unmodifiableList(slots);
        }

        public int untriedInStockCount() {
            int n = 0;
            for (SlotCoverage slot : slots)
                n += slot.untriedInStock.size();
            return n;
        }
    }

    /**
     * How many different ingredients a dish must have been seen with in one slot before that slot
     * counts as varied. Two is not enough: Haven renames a dish as its ingredients change, so two
     * meats from the same family can land on one output name ("Smoked Piglet Wursts" is boar and
     * pork; beaver would have come out as a "Smoked Beaver Dog"). Three distinct values is the
     * point where a slot is demonstrably free rather than an artefact of the naming.
     */
    private static final int VARIED_SLOT_MIN = 3;

    public static final CookbookIndex EMPTY = new CookbookIndex(Pantry.empty(),
            new HashMap<String, RecipeStatus>(), new LinkedHashMap<String, DishCoverage>(),
            new ArrayList<String>(), 0, 0);

    private final Pantry pantry;
    private final Map<String, RecipeStatus> byHash;
    private final Map<String, DishCoverage> coverage;
    private final List<String> unresolvedIngredients;
    private final int recipeCount;
    private final int cookableCount;

    private CookbookIndex(Pantry pantry, Map<String, RecipeStatus> byHash,
                          Map<String, DishCoverage> coverage, List<String> unresolved,
                          int recipeCount, int cookableCount) {
        this.pantry = pantry;
        this.byHash = Collections.unmodifiableMap(byHash);
        this.coverage = Collections.unmodifiableMap(coverage);
        this.unresolvedIngredients = Collections.unmodifiableList(unresolved);
        this.recipeCount = recipeCount;
        this.cookableCount = cookableCount;
    }

    public RecipeStatus status(String recipeHash) {
        return byHash.get(recipeHash);
    }

    public boolean isCookable(String recipeHash) {
        RecipeStatus st = byHash.get(recipeHash);
        return (st != null) && st.cookable;
    }

    public Pantry pantry() {
        return pantry;
    }

    public Collection<DishCoverage> dishes() {
        return coverage.values();
    }


    /** Ingredient names no rule could place - the alias file needs an entry for each. */
    public List<String> unresolvedIngredients() {
        return unresolvedIngredients;
    }

    public int recipeCount() {
        return recipeCount;
    }

    public int cookableCount() {
        return cookableCount;
    }

    public boolean isEmpty() {
        return recipeCount == 0;
    }

    /**
     * Loads the whole corpus and the pantry, then computes cookability and slot coverage.
     * Submit to {@link DatabaseManager#submitTask}; poll {@link #ready} and take {@link #result()}.
     */
    public static class Builder implements Runnable {
        public final AtomicBoolean ready = new AtomicBoolean(false);
        private final DatabaseManager databaseManager;
        private volatile CookbookIndex result = EMPTY;

        public Builder(DatabaseManager databaseManager) {
            this.databaseManager = databaseManager;
        }

        public CookbookIndex result() {
            return result;
        }

        @Override
        public void run() {
            try {
                result = databaseManager.executeOperation(adapter -> {
                    // dish -> hash -> ingredient names. One query for the whole corpus: a few
                    // thousand rows, far cheaper than a lookup per displayed row.
                    Map<String, String> dishOfHash = new LinkedHashMap<>();
                    Map<String, List<String>> ingredientsOfHash = new LinkedHashMap<>();
                    String sql = "SELECT r.recipe_hash, r.item_name, i.name AS ing_name "
                            + "FROM recipes r LEFT JOIN ingredients i ON i.recipe_hash = r.recipe_hash";
                    try (ResultSet rs = adapter.executeQuery(sql)) {
                        while (rs.next()) {
                            String hash = rs.getString("recipe_hash");
                            if (hash == null)
                                continue;
                            dishOfHash.put(hash, rs.getString("item_name"));
                            List<String> ings = ingredientsOfHash.get(hash);
                            if (ings == null) {
                                ings = new ArrayList<>();
                                ingredientsOfHash.put(hash, ings);
                            }
                            String ing = rs.getString("ing_name");
                            if (ing != null && !ing.isEmpty() && !ings.contains(ing))
                                ings.add(ing);
                        }
                    }
                    return build(Pantry.load(adapter), dishOfHash, ingredientsOfHash);
                });
            } catch (SQLException | RuntimeException e) {
                System.out.println("[cookbook] failed to build index: " + e.getMessage());
                result = EMPTY;
            } finally {
                ready.set(true);
            }
        }
    }

    public static CookbookIndex build(Pantry pantry, Map<String, String> dishOfHash,
                               Map<String, List<String>> ingredientsOfHash) {
        IngredientResolver resolver = new IngredientResolver(new ItemUniverse());

        Map<String, RecipeStatus> byHash = new HashMap<>();
        Set<String> unmapped = new TreeSet<>();
        int cookable = 0;
        for (Map.Entry<String, List<String>> e : ingredientsOfHash.entrySet()) {
            List<String> ings = e.getValue();
            List<String> missing = new ArrayList<>();
            List<Held> held = new ArrayList<>();
            for (String ing : ings) {
                Set<String> cands = resolver.candidates(ing);
                String item = pantry.firstHeld(cands);
                // Holding it is proof enough that the name was placed correctly, even for the
                // handful of real items VSpec has never heard of.
                boolean resolved = (item != null) || resolver.isResolved(ing);
                held.add(new Held(ing, item, (item == null) ? 0 : pantry.count(item),
                        (item == null) ? 0 : pantry.bestQuality(item), resolved));
                if (item == null)
                    missing.add(ing);
                if (!resolved)
                    unmapped.add(ing);
            }
            // A recipe with no recorded ingredients is a raw food, not something you cook - it is
            // never "cookable", so it stays out of the have-ingredients filter entirely.
            boolean ok = !ings.isEmpty() && missing.isEmpty();
            if (ok)
                cookable++;
            byHash.put(e.getKey(), new RecipeStatus(ok, missing, held));
        }

        // What the game has been seen to accept in each slot, pooled across every dish. This is the
        // domain an untried variant is drawn from.
        Map<String, Set<String>> domainOfGroup = new HashMap<>();
        for (List<String> ings : ingredientsOfHash.values()) {
            for (String ing : ings) {
                String group = groupOf(resolver, ing);
                Set<String> domain = domainOfGroup.get(group);
                if (domain == null) {
                    domain = new TreeSet<>();
                    domainOfGroup.put(group, domain);
                }
                domain.add(ing);
            }
        }

        // dish -> group -> ingredients tried in that dish
        Map<String, Map<String, Set<String>>> triedByDish = new LinkedHashMap<>();
        Map<String, Integer> variantsByDish = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : dishOfHash.entrySet()) {
            String dish = e.getValue();
            List<String> ings = ingredientsOfHash.get(e.getKey());
            if (dish == null || ings == null || ings.isEmpty())
                continue;
            Integer n = variantsByDish.get(dish);
            variantsByDish.put(dish, (n == null) ? 1 : n + 1);
            Map<String, Set<String>> groups = triedByDish.get(dish);
            if (groups == null) {
                groups = new LinkedHashMap<>();
                triedByDish.put(dish, groups);
            }
            for (String ing : ings) {
                String group = groupOf(resolver, ing);
                Set<String> tried = groups.get(group);
                if (tried == null) {
                    tried = new TreeSet<>();
                    groups.put(group, tried);
                }
                tried.add(ing);
            }
        }

        Map<String, DishCoverage> coverage = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Set<String>>> e : triedByDish.entrySet()) {
            String dish = e.getKey();
            List<SlotCoverage> slots = new ArrayList<>();
            for (Map.Entry<String, Set<String>> ge : e.getValue().entrySet()) {
                String group = ge.getKey();
                Set<String> tried = ge.getValue();
                Set<String> domain = domainOfGroup.get(group);
                List<String> untried = new ArrayList<>();
                List<String> inStock = new ArrayList<>();
                // A slot the dish has only ever been seen with one value in is not a slot you can
                // vary - it is either genuinely fixed, or it is the slot that names the output.
                // Haven renames as ingredients change ("Smoked Piglet Wursts" is always Boar;
                // swapping in beaver gives you a "Smoked Beaver Dog", not another Wurst), so
                // offering substitutes there would be advice for a dish that cannot exist.
                if (domain != null && tried.size() >= VARIED_SLOT_MIN) {
                    for (String cand : domain) {
                        if (tried.contains(cand))
                            continue;
                        untried.add(cand);
                        if (pantry.firstHeld(resolver.candidates(cand)) != null)
                            inStock.add(cand);
                    }
                }
                slots.add(new SlotCoverage(group, new ArrayList<>(tried), untried, inStock));
            }
            Collections.sort(slots, (a, b) -> {
                int cmp = Integer.compare(b.untriedInStock.size(), a.untriedInStock.size());
                return (cmp != 0) ? cmp : a.group.compareTo(b.group);
            });
            Integer variants = variantsByDish.get(dish);
            coverage.put(dish, new DishCoverage(dish, (variants == null) ? 0 : variants, slots));
        }

        List<String> ordered = new ArrayList<>(coverage.keySet());
        Collections.sort(ordered, (a, b) -> Integer.compare(
                coverage.get(b).untriedInStockCount(), coverage.get(a).untriedInStockCount()));
        Map<String, DishCoverage> sorted = new LinkedHashMap<>();
        for (String dish : ordered)
            sorted.put(dish, coverage.get(dish));

        return new CookbookIndex(pantry, byHash, sorted, new ArrayList<>(unmapped),
                byHash.size(), cookable);
    }

    private static String groupOf(IngredientResolver resolver, String ingredient) {
        String group = resolver.slotGroup(ingredient);
        return (group == null) ? "Other" : group;
    }
}
