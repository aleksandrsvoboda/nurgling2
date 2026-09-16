package nurgling.cookbook;

import java.util.*;

/**
 * One recipe as the cookbook table shows it: its FEPs with any what-if spices applied, and the
 * figures derived from them. Everything is at quality 10, as recipes are stored.
 */
public final class CookbookRow {
    public final Recipe recipe;
    /** FEPs, highest first. */
    public final List<FepValue> feps;
    /** F: the sum of the FEPs. */
    public final double total;
    /** H: satiation. */
    public final double hunger;
    /** F/H: FEP per point of hunger. */
    public final double perHunger;
    /** %: the largest single FEP's share of the total. */
    public final double topShare;
    /** Filter keys of the FEPs present, e.g. "str2". */
    public final Set<String> codes;
    /** Ingredient and smoking-wood names, as the Ingredients filter lists them. */
    public final Set<String> sourceNames;
    /** Ingredients by share, highest first, joined for display. */
    public final String ingredientText;
    /** Smoking woods joined for display; empty when the dish is not smoked. */
    public final String woodText;

    final String nameKey;
    final Set<String> sourceKeys;
    final String haystack;

    public CookbookRow(Recipe recipe, Map<SpiceCalc.Spice, Double> spices) {
        this.recipe = recipe;
        SpiceCalc.Result res = SpiceCalc.apply(fepsOf(recipe), recipe.getHunger(), spices);
        List<FepValue> sorted = new ArrayList<>(res.feps);
        sorted.sort((a, b) -> Double.compare(b.value, a.value));
        this.feps = Collections.unmodifiableList(sorted);

        double sum = 0;
        Set<String> codes = new HashSet<>();
        for(FepValue f : sorted) {
            sum += f.value;
            if(f.attr != FepAttr.UNKNOWN)
                codes.add(f.attr.key(f.tier));
        }
        this.total = sum;
        this.hunger = res.hunger;
        this.perHunger = (hunger > 0) ? sum / hunger : 0;
        this.topShare = ((sum > 0) && !sorted.isEmpty()) ? sorted.get(0).value / sum * 100 : 0;
        this.codes = Collections.unmodifiableSet(codes);

        List<Map.Entry<String, Recipe.IngredientInfo>> ings = new ArrayList<>(recipe.getIngredients().entrySet());
        ings.sort((a, b) -> {
            int c = Double.compare(b.getValue().percentage, a.getValue().percentage);
            return (c != 0) ? c : a.getKey().compareTo(b.getKey());
        });
        Set<String> sources = new LinkedHashSet<>();
        StringBuilder it = new StringBuilder();
        for(Map.Entry<String, Recipe.IngredientInfo> e : ings) {
            if(it.length() > 0)
                it.append(", ");
            it.append(e.getKey());
            sources.add(e.getKey());
        }
        StringBuilder wt = new StringBuilder();
        for(String wood : recipe.getSmokingWoods().keySet()) {
            if(wt.length() > 0)
                wt.append(", ");
            wt.append(wood);
            sources.add(wood);
        }
        this.ingredientText = it.toString();
        this.woodText = wt.toString();
        this.sourceNames = Collections.unmodifiableSet(sources);

        Set<String> keys = new HashSet<>();
        for(String s : sources)
            keys.add(lower(s));
        this.sourceKeys = keys;
        this.nameKey = lower(name());
        this.haystack = nameKey + '\n' + lower(ingredientText) + '\n' + lower(woodText);
    }

    public String name() {
        return (recipe.getName() != null) ? recipe.getName() : "";
    }

    /** Energy in percent; quality does not affect it. */
    public int energy() {
        return recipe.getEnergy();
    }

    /** The value of one attribute and tier, 0 when the dish has none. */
    public double fepValue(FepAttr attr, int tier) {
        double v = 0;
        for(FepValue f : feps) {
            if(f.is(attr, tier))
                v += f.value;
        }
        return v;
    }

    public boolean hasFep(FepAttr attr, int tier) {
        for(FepValue f : feps) {
            if(f.is(attr, tier))
                return true;
        }
        return false;
    }

    static List<FepValue> fepsOf(Recipe recipe) {
        List<FepValue> ret = new ArrayList<>();
        for(Map.Entry<String, Recipe.Fep> e : recipe.getFeps().entrySet())
            ret.add(FepValue.of(e.getKey(), e.getValue().val));
        return ret;
    }

    static String lower(String s) {
        return (s == null) ? "" : s.toLowerCase(Locale.ROOT);
    }
}
