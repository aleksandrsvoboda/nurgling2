package nurgling.cookbook;

import java.util.*;

/**
 * Everything the cookbook window shows, minus the drawing: the loaded recipes, the search, the
 * filters, the what-if spices and the sort, and the list of rows they produce.
 *
 * <p>It has no UI dependencies so it can be tested without a client. The window reads
 * {@link #view()} and redraws whenever {@link #version()} moves. Not thread-safe: it lives on the
 * UI thread, and recipes arrive through {@link #setRecipes}.
 */
public class CookbookModel {
    /** How a filter's ticked boxes combine. An empty selection lets every row through. */
    public enum Mode {
        ANY, ALL, NONE;

        public boolean matches(Set<String> selected, Set<String> present) {
            if(selected.isEmpty())
                return true;
            int n = 0;
            for(String s : selected) {
                if(present.contains(s))
                    n++;
            }
            switch(this) {
                case ALL:
                    return n == selected.size();
                case NONE:
                    return n == 0;
                default:
                    return n > 0;
            }
        }
    }

    /** The sortable table columns. Text columns sort A to Z first, numbers highest first. */
    public enum Column {
        NAME(true), INGREDIENTS(true), F(false), H(false), FH(false), TOP(false), ENERGY(false);

        public final boolean textual;

        Column(boolean textual) {
            this.textual = textual;
        }
    }

    private List<Recipe> recipes = Collections.emptyList();
    private List<CookbookRow> rows = Collections.emptyList();
    private List<String> ingredientNames = Collections.emptyList();
    private final EnumMap<SpiceCalc.Spice, Double> spices = new EnumMap<>(SpiceCalc.Spice.class);

    private String search = "";
    private SearchQuery query = SearchQuery.parse("");
    private final Set<String> fepCodes = new HashSet<>();
    private Mode fepMode = Mode.ANY;
    private final Set<String> ingredients = new HashSet<>();
    private Mode ingredientMode = Mode.ANY;
    private boolean favoritesFirst = true;
    private boolean favoritesOnly = false;

    private Column sortColumn = Column.FH;
    /* When set, the table is sorted by this FEP instead of by sortColumn. */
    private FepAttr sortAttr = null;
    private int sortTier = 2;
    private boolean descending = true;
    private int statTier = 2;

    private List<CookbookRow> view = null;
    private int version = 0;
    private int queryVersion = 0;

    /* ---- data ---- */

    public void setRecipes(List<Recipe> recipes) {
        this.recipes = new ArrayList<>(recipes);
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.<String>naturalOrder()));
        for(Recipe r : this.recipes) {
            names.addAll(r.getIngredients().keySet());
            names.addAll(r.getSmokingWoods().keySet());
        }
        this.ingredientNames = Collections.unmodifiableList(new ArrayList<>(names));
        rebuild();
    }

    /** How many recipes are loaded, filtered or not. */
    public int total() {
        return recipes.size();
    }

    /** Every ingredient and smoking wood of the loaded recipes, alphabetically. */
    public List<String> ingredientNames() {
        return ingredientNames;
    }

    /** Call after changing a loaded recipe's favourite flag. Re-sorts without counting as a new query. */
    public void favoritesChanged() {
        view = null;
        version++;
    }

    /** Moves whenever anything the window shows changes. */
    public int version() {
        return version;
    }

    /**
     * Moves when the search, a filter, a spice, the sort or the recipes change -- the changes after
     * which the table should start again from the top. Favourites do not move it.
     */
    public int queryVersion() {
        return queryVersion;
    }

    /* ---- search and filters ---- */

    public String search() {
        return search;
    }

    public void setSearch(String text) {
        String t = (text == null) ? "" : text;
        if(t.equals(search))
            return;
        search = t;
        query = SearchQuery.parse(t);
        changed();
    }

    public Set<String> fepCodes() {
        return Collections.unmodifiableSet(fepCodes);
    }

    public void toggleFep(String code) {
        if(!fepCodes.remove(code))
            fepCodes.add(code);
        changed();
    }

    public void setFepCodes(Collection<String> codes) {
        fepCodes.clear();
        fepCodes.addAll(codes);
        changed();
    }

    public Mode fepMode() {
        return fepMode;
    }

    public void setFepMode(Mode mode) {
        fepMode = mode;
        changed();
    }

    public Set<String> ingredients() {
        return Collections.unmodifiableSet(ingredients);
    }

    public void toggleIngredient(String name) {
        if(!ingredients.remove(name))
            ingredients.add(name);
        changed();
    }

    public void setIngredients(Collection<String> names) {
        ingredients.clear();
        ingredients.addAll(names);
        changed();
    }

    public Mode ingredientMode() {
        return ingredientMode;
    }

    public void setIngredientMode(Mode mode) {
        ingredientMode = mode;
        changed();
    }

    public boolean favoritesFirst() {
        return favoritesFirst;
    }

    public void setFavoritesFirst(boolean on) {
        favoritesFirst = on;
        changed();
    }

    public boolean favoritesOnly() {
        return favoritesOnly;
    }

    public void setFavoritesOnly(boolean on) {
        favoritesOnly = on;
        changed();
    }

    /* ---- spices ---- */

    /** The what-if quality of a spice; 0 when it is off. */
    public double spice(SpiceCalc.Spice s) {
        Double q = spices.get(s);
        return (q == null) ? 0 : q;
    }

    public void setSpice(SpiceCalc.Spice s, double quality) {
        if(spice(s) == Math.max(quality, 0))
            return;
        if(quality > 0)
            spices.put(s, quality);
        else
            spices.remove(s);
        rebuild();
    }

    /** The spices currently applied, with their qualities, in application order. */
    public Map<SpiceCalc.Spice, Double> activeSpices() {
        return Collections.unmodifiableMap(spices);
    }

    /** Whether anything but the sort narrows or changes the rows. */
    public boolean hasActiveFilters() {
        return !query.isEmpty() || !fepCodes.isEmpty() || !ingredients.isEmpty() || favoritesOnly || !spices.isEmpty();
    }

    /** Resets the search, every filter and the spices; the sort stays. */
    public void clearFilters() {
        search = "";
        query = SearchQuery.parse("");
        fepCodes.clear();
        ingredients.clear();
        fepMode = Mode.ANY;
        ingredientMode = Mode.ANY;
        favoritesOnly = false;
        spices.clear();
        rebuild();
    }

    /* ---- sort ---- */

    /** The column sorted by, or null while sorting by a FEP. */
    public Column sortColumn() {
        return (sortAttr == null) ? sortColumn : null;
    }

    /** The attribute sorted by, or null while sorting by a column. */
    public FepAttr sortAttr() {
        return sortAttr;
    }

    public int sortTier() {
        return sortTier;
    }

    public boolean descending() {
        return descending;
    }

    /** Sorts by a column; choosing the current one again reverses it. */
    public void sortBy(Column c) {
        if((sortAttr == null) && (sortColumn == c)) {
            descending = !descending;
        } else {
            sortColumn = c;
            sortAttr = null;
            descending = !c.textual;
        }
        changed();
    }

    /** Sorts by one FEP, highest first; choosing the current one again reverses it. */
    public void sortByFep(FepAttr attr, int tier) {
        if((sortAttr == attr) && (sortTier == tier)) {
            descending = !descending;
        } else {
            sortAttr = attr;
            sortTier = tier;
            descending = true;
        }
        changed();
    }

    /** A stat icon: sorts by that attribute at the tier chosen on the +1/+2 switch. */
    public void sortByStat(FepAttr attr) {
        sortByFep(attr, statTier);
    }

    /** The tier the stat icons sort by. */
    public int statTier() {
        return statTier;
    }

    /** Flips the +1/+2 switch. A FEP sort in progress moves to the new tier of the same attribute. */
    public void setStatTier(int tier) {
        if(tier == statTier)
            return;
        statTier = tier;
        if((sortAttr != null) && (sortTier != tier)) {
            sortTier = tier;
            descending = true;
        }
        changed();
    }

    /* ---- the result ---- */

    /** The rows that pass every filter, in sort order. */
    public List<CookbookRow> view() {
        if(view == null) {
            List<CookbookRow> out = new ArrayList<>();
            for(CookbookRow r : rows) {
                if(favoritesOnly && !r.recipe.isFavorite())
                    continue;
                if(!query.test(r))
                    continue;
                if(!fepMode.matches(fepCodes, r.codes))
                    continue;
                if(!ingredientMode.matches(ingredients, r.sourceNames))
                    continue;
                out.add(r);
            }
            out.sort(this::compare);
            view = Collections.unmodifiableList(out);
        }
        return view;
    }

    private int compare(CookbookRow a, CookbookRow b) {
        if(favoritesFirst && (a.recipe.isFavorite() != b.recipe.isFavorite()))
            return a.recipe.isFavorite() ? -1 : 1;
        int c = compareKey(a, b);
        if(c != 0)
            return descending ? -c : c;
        c = a.name().compareToIgnoreCase(b.name());
        if(c != 0)
            return c;
        return String.valueOf(a.recipe.getHash()).compareTo(String.valueOf(b.recipe.getHash()));
    }

    private int compareKey(CookbookRow a, CookbookRow b) {
        if(sortAttr != null)
            return Double.compare(a.fepValue(sortAttr, sortTier), b.fepValue(sortAttr, sortTier));
        switch(sortColumn) {
            case NAME:
                return a.name().compareToIgnoreCase(b.name());
            case INGREDIENTS:
                return a.ingredientText.compareToIgnoreCase(b.ingredientText);
            case F:
                return Double.compare(a.total, b.total);
            case H:
                return Double.compare(a.hunger, b.hunger);
            case TOP:
                return Double.compare(a.topShare, b.topShare);
            case ENERGY:
                return Integer.compare(a.energy(), b.energy());
            default:
                return Double.compare(a.perHunger, b.perHunger);
        }
    }

    private void rebuild() {
        List<CookbookRow> next = new ArrayList<>(recipes.size());
        for(Recipe r : recipes)
            next.add(new CookbookRow(r, spices));
        rows = next;
        changed();
    }

    private void changed() {
        view = null;
        version++;
        queryVersion++;
    }
}
