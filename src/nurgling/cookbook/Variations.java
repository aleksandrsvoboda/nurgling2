package nurgling.cookbook;

import nurgling.craft.AtlasCatalog;
import nurgling.craft.WikiRecipes;

import java.util.*;

/**
 * Which ingredient choices of a food the cookbook has not recorded yet.
 *
 * <p>A recipe's "Any ..." inputs come from the offline wiki recipes, following crafted inputs down
 * (Meatpie is baked from Unbaked Meatpie, which takes the meat). What each input can take comes from
 * {@link GenericTypes}. An input counts only when the game lists one of its ingredients in the
 * tooltip of a recorded variant: the game lists exactly the ingredients that add FEPs, so inputs
 * it never lists (Nutjerky's nuts and leaves) change nothing and are left out.
 *
 * <p>Recorded ingredients also correct the wiki: one that fits none of the dish's inputs widens an
 * input to a larger type holding it ("Berry" to "Fruit or Berry"), or adds the smallest type holding
 * it (salt in a dish whose wiki recipe names no spices, or the milk inside its butter). Each
 * ingredient adds its own FEPs, so a choice counts as tried once any variant used it, in any share.
 */
public final class Variations {
    /** How many crafted inputs deep a recipe is followed. */
    private static final int DEPTH = 3;
    private static final String ROE = "Roe";

    /** One ingredient an input can take. */
    public static final class Option {
        /** The tooltip's name once tried, otherwise the wiki item's name. */
        public final String name;
        /** The wiki item's name, for the icon. */
        public final String item;
        /** The ingredient's icon resource when known (always when tried), else null. */
        public final String resource;
        /** How many recorded variants used it. */
        public final int seen;
        /** {@link GenericTypes#stockKey}s of the names its item may be stored under. */
        private final Set<String> stock;

        Option(String name, String item, String resource, int seen, Collection<String> stockNames) {
            this.name = name;
            this.item = item;
            this.resource = resource;
            this.seen = seen;
            Set<String> keys = new HashSet<>();
            for(String n : stockNames)
                keys.add(GenericTypes.stockKey(n));
            this.stock = keys;
        }

        public boolean tried() {
            return seen > 0;
        }

        /** Whether storage holds its item, given the stockKeys of the stored items' names. */
        public boolean stocked(Set<String> stored) {
            for(String k : stock) {
                if(stored.contains(k))
                    return true;
            }
            return false;
        }
    }

    /** One "Any ..." input of the dish. */
    public static final class Slot {
        /** The type or choice as the recipe names it; null for the smoking wood. */
        public final String title;
        /** How many the recipe takes; only whole counts above one are worth showing. */
        public final double count;
        public final boolean optional;
        /** Not in the wiki recipe; found from the recorded ingredients. */
        public final boolean inferred;
        /** Untried first, by name; then tried, most used first. */
        public final List<Option> options;
        /** For an optional input: whether some variant went without it. */
        public final boolean noneTried;

        Slot(String title, double count, boolean optional, boolean inferred, List<Option> options, boolean noneTried) {
            this.title = title;
            this.count = count;
            this.optional = optional;
            this.inferred = inferred;
            this.options = Collections.unmodifiableList(options);
            this.noneTried = noneTried;
        }

        public boolean wood() {
            return title == null;
        }

        public int tried() {
            int n = 0;
            for(Option o : options)
                n += o.tried() ? 1 : 0;
            return n;
        }
    }

    /** The untried variations of one food. */
    public static final class Dish {
        public final String name;
        public final int variants;
        /** Whether the wiki has a recipe for it. */
        public final boolean known;
        /** The inputs that change FEPs. */
        public final List<Slot> slots;
        /** Wiki inputs the game never lists for this food, so they do not change its FEPs. */
        public final List<String> notListed;

        Dish(String name, int variants, boolean known, List<Slot> slots, List<String> notListed) {
            this.name = name;
            this.variants = variants;
            this.known = known;
            this.slots = Collections.unmodifiableList(slots);
            this.notListed = Collections.unmodifiableList(notListed);
        }

        public int untried() {
            int n = 0;
            for(Slot s : slots)
                n += s.options.size() - s.tried();
            return n;
        }
    }

    /* An input while it is being worked out: its universe of wiki names. */
    private static final class Draft {
        String title;
        String type;
        final double count;
        final boolean optional, inferred;
        private final List<String> universe = new ArrayList<>();
        private Map<String, String> keys = null;

        Draft(String title, String type, double count, boolean optional, boolean inferred, Collection<String> universe) {
            this.title = title;
            this.type = type;
            this.count = count;
            this.optional = optional;
            this.inferred = inferred;
            this.universe.addAll(universe);
        }

        /* The smoking wood is the one input with neither a title nor a type. */
        boolean wood() {
            return (title == null) && (type == null);
        }

        /* Tooltip key -> wiki name; cached, as every recorded ingredient is looked up in every input. */
        Map<String, String> keys() {
            if(keys == null) {
                keys = new LinkedHashMap<>();
                for(String item : universe) {
                    for(String n : GenericTypes.tooltipNames(item))
                        keys.putIfAbsent(GenericTypes.key(n), item);
                }
            }
            return keys;
        }

        void setUniverse(Collection<String> items) {
            universe.clear();
            universe.addAll(items);
            keys = null;
        }
    }

    private final GenericTypes types;
    private final Map<String, List<Recipe>> byDish = new HashMap<>();
    private final Map<String, List<AtlasCatalog.Recipe>> producers = new HashMap<>();
    private final Set<String> pickled = new HashSet<>();
    private final SortedSet<String> woods = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final SortedSet<String> roes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    /* Ingredient name, lower case -> its resource, from any recorded food, for the icons of untried ones. */
    private final Map<String, String> resources = new HashMap<>();

    public Variations(Collection<Recipe> recipes, WikiRecipes wiki, GenericTypes types) {
        this(recipes, wiki.recipes, types);
    }

    Variations(Collection<Recipe> recipes, List<AtlasCatalog.Recipe> wiki, GenericTypes types) {
        this.types = types;
        for(Recipe r : recipes) {
            byDish.computeIfAbsent(GenericTypes.norm(r.getName()), k -> new ArrayList<>()).add(r);
            woods.addAll(r.getSmokingWoods().keySet());
            for(Map.Entry<String, Recipe.IngredientInfo> e : r.getIngredients().entrySet()) {
                String ing = e.getKey();
                if(ing.endsWith(" Roe") || ing.equals("Caviar"))
                    roes.add(ing);
                if(e.getValue().resourceName != null)
                    resources.putIfAbsent(ing.toLowerCase(Locale.ROOT), e.getValue().resourceName);
            }
        }
        for(AtlasCatalog.Recipe r : wiki) {
            for(AtlasCatalog.Material out : r.outputs) {
                String n = GenericTypes.norm(out.name);
                producers.computeIfAbsent(n, k -> new ArrayList<>()).add(r);
                if(n.startsWith("pickled "))
                    pickled.add(n);
            }
        }
    }

    /**
     * Whether storage holds everything a recorded variant lists, its smoking woods as blocks
     * included. A variant listing nothing has nothing to go by, so it is never cookable.
     *
     * @param stored {@link GenericTypes#stockKey}s of the stored items' names
     */
    public boolean cookable(Recipe r, Set<String> stored) {
        if(r.getIngredients().isEmpty())
            return false;
        for(String ing : r.getIngredients().keySet()) {
            if(!anyStored(types.storedAs(ing), stored))
                return false;
        }
        for(String wood : r.getSmokingWoods().keySet()) {
            if(!stored.contains(GenericTypes.stockKey("Block of " + wood)))
                return false;
        }
        return true;
    }

    private static boolean anyStored(Collection<String> names, Set<String> stored) {
        for(String n : names) {
            if(stored.contains(GenericTypes.stockKey(n)))
                return true;
        }
        return false;
    }

    /**
     * The crafting actions that may make a food, best first: its own name, then the stages it is
     * made through (Meatpie is baked from Unbaked Meatpie; a smoked sausage is smoked from the sausage).
     */
    public List<String> craftNames(String dish) {
        Set<String> ret = new LinkedHashSet<>();
        ret.add(dish);
        String n = GenericTypes.norm(dish);
        if(!producers.containsKey(n) && n.startsWith("smoked "))
            n = n.substring("smoked ".length());
        for(int i = 0; i <= DEPTH; i++) {
            List<AtlasCatalog.Recipe> c = producers.get(n);
            if(c == null)
                break;
            AtlasCatalog.Recipe r = c.get(0);
            if((r.wiki != null) && !r.wiki.actionName.isEmpty())
                ret.add(r.wiki.actionName);
            if((r.wiki == null) || r.wiki.kind.equals("craft") || (r.inputs.size() != 1) || r.inputs.get(0).category)
                break;
            ret.add(r.inputs.get(0).name);
            n = GenericTypes.norm(r.inputs.get(0).name);
        }
        return new ArrayList<>(ret);
    }

    /** The item names a recorded ingredient comes from: "Badger" is Raw Badger. */
    public List<String> itemNames(String ingredient) {
        return types.storedAs(ingredient);
    }

    /**
     * Whether a recorded ingredient can fill a crafting input of the given type ("Salad Greens"); a
     * pickled vegetable fills its vegetable's input, as recorded Moules Frites show.
     */
    public boolean fits(String inputType, String ingredient) {
        String t = types.type(inputType);
        if(t == null)
            return false;
        if(ingredient.startsWith("Pickled ") && types.contains(t, ingredient.substring("Pickled ".length())))
            return true;
        return types.contains(t, ingredient);
    }

    /** The untried variations of a food, by its cookbook name. */
    public Dish of(String name) {
        List<Recipe> variants = byDish.getOrDefault(GenericTypes.norm(name), List.of());
        List<Draft> drafts = wikiInputs(name);
        boolean known = (drafts != null);
        if(drafts == null)
            drafts = new ArrayList<>();
        int wikiCount = drafts.size();

        List<String> unplaced = new ArrayList<>();
        for(String ing : ingredientNames(variants)) {
            if(place(drafts, ing) < 0)
                infer(drafts, ing, unplaced);
        }
        if(!unplaced.isEmpty())
            System.out.println("[Cookbook] Variations of " + name + ": no ingredient type for " + String.join(", ", unplaced));
        boolean smoked = false;
        for(Recipe r : variants)
            smoked |= !r.getSmokingWoods().isEmpty();
        if(smoked)
            drafts.add(new Draft(null, null, 1, false, false, woods));

        List<Slot> slots = new ArrayList<>();
        List<String> notListed = new ArrayList<>();
        for(int i = 0; i < drafts.size(); i++) {
            Slot s = tally(drafts, i, variants);
            if(s.tried() > 0)
                slots.add(s);
            else if((i < wikiCount) && !notListed.contains(s.title))
                notListed.add(s.title);
        }
        return new Dish(name, variants.size(), known, slots, notListed);
    }

    /* ---- the wiki recipe ---- */

    /* The "Any ..." inputs of a food, or null when the wiki has no recipe for it. */
    private List<Draft> wikiInputs(String name) {
        String n = GenericTypes.norm(name);
        if(!producers.containsKey(n) && n.startsWith("smoked "))
            n = n.substring("smoked ".length());
        List<AtlasCatalog.Recipe> cands = producers.get(n);
        if(cands == null)
            return null;
        List<Draft> best = null;
        for(AtlasCatalog.Recipe r : cands) {
            List<Draft> d = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            seen.add(n);
            inputs(r, 0, seen, d);
            if((best == null) || (d.size() > best.size()))
                best = d;
        }
        return best;
    }

    private void inputs(AtlasCatalog.Recipe r, int depth, Set<String> seen, List<Draft> out) {
        for(AtlasCatalog.Material m : r.inputs) {
            String type = types.type(m.name);
            if(!m.choices.isEmpty()) {
                List<String> universe = new ArrayList<>();
                for(AtlasCatalog.Material c : m.choices) {
                    String ct = types.type(c.name);
                    universe.addAll((ct != null) ? types.members(ct) : List.of(c.name));
                }
                add(out, new Draft(m.name, null, m.count, m.optional, false, universe));
            } else if(type != null) {
                add(out, new Draft(type, type, m.count, m.optional, false, options(type)));
            } else if(GenericTypes.norm(m.name).equals("roe")) {
                add(out, new Draft(ROE, ROE, m.count, m.optional, false, roes));
            } else if((depth < DEPTH) && seen.add(GenericTypes.norm(m.name))) {
                List<AtlasCatalog.Recipe> sub = producers.get(GenericTypes.norm(m.name));
                if(sub != null)
                    inputs(sub.get(0), depth + 1, seen, out);
            }
        }
    }

    private static void add(List<Draft> out, Draft d) {
        for(Draft o : out) {
            if((o.type != null) && o.type.equals(d.type))
                return;
            if((o.type == null) && (d.type == null) && Objects.equals(o.title, d.title))
                return;
        }
        out.add(d);
    }

    /* A type's members, with the pickled form of each that the wiki has: a pickled carrot is a tuber. */
    private List<String> options(String type) {
        List<String> ret = new ArrayList<>(types.members(type));
        for(String m : types.members(type)) {
            String p = "Pickled " + m;
            if(pickled.contains(GenericTypes.norm(p)) && !ret.contains(p))
                ret.add(p);
        }
        return ret;
    }

    /* ---- recorded ingredients ---- */

    private static SortedSet<String> ingredientNames(List<Recipe> variants) {
        SortedSet<String> ret = new TreeSet<>();
        for(Recipe r : variants)
            ret.addAll(r.getIngredients().keySet());
        return ret;
    }

    /* The first input an ingredient fits, or -1. */
    private int place(List<Draft> drafts, String ing) {
        String k = GenericTypes.key(ing);
        for(int i = 0; i < drafts.size(); i++) {
            if(drafts.get(i).keys().containsKey(k))
                return i;
        }
        return -1;
    }

    /* Widens an input, or adds one, so that a recorded ingredient fits. */
    private void infer(List<Draft> drafts, String ing, List<String> unplaced) {
        List<String> cands = new ArrayList<>(types.typesOf(ing));
        if(ing.startsWith("Pickled "))
            cands.addAll(types.typesOf(ing.substring("Pickled ".length())));
        if(roes.contains(ing))
            cands.add(ROE);
        for(String c : cands) {
            for(Draft d : drafts) {
                if((d.type != null) && !d.type.equals(ROE) && !c.equals(ROE) && types.within(d.type, c)) {
                    d.type = c;
                    d.title = c;
                    d.setUniverse(options(c));
                    addPickled(d, ing);
                    return;
                }
            }
        }
        if(cands.isEmpty()) {
            unplaced.add(ing);
            return;
        }
        String c = cands.get(0);
        Draft d = new Draft(c, c, 1, false, true, c.equals(ROE) ? roes : options(c));
        addPickled(d, ing);
        drafts.add(d);
    }

    /* A pickled ingredient the wiki has no recipe for still joins its vegetable's input. */
    private static void addPickled(Draft d, String ing) {
        if(ing.startsWith("Pickled ") && !d.universe.contains(ing)) {
            List<String> items = new ArrayList<>(d.universe);
            items.add(ing);
            d.setUniverse(items);
        }
    }

    private Slot tally(List<Draft> drafts, int idx, List<Recipe> variants) {
        Draft d = drafts.get(idx);
        Map<String, String> keys = d.keys();
        Map<String, Integer> seen = new HashMap<>();
        Map<String, String> names = new HashMap<>(), res = new HashMap<>();
        boolean none = false;
        for(Recipe r : variants) {
            boolean present = false;
            if(d.wood()) {
                for(String w : r.getSmokingWoods().keySet()) {
                    String k = GenericTypes.key(w);
                    seen.merge(k, 1, Integer::sum);
                    names.put(k, w);
                    present = true;
                }
            } else {
                for(Map.Entry<String, Recipe.IngredientInfo> e : r.getIngredients().entrySet()) {
                    String k = GenericTypes.key(e.getKey());
                    /* Each ingredient counts for the first input it fits only. */
                    if(!keys.containsKey(k) || (place(drafts, e.getKey()) != idx))
                        continue;
                    seen.merge(k, 1, Integer::sum);
                    names.put(k, e.getKey());
                    res.put(k, e.getValue().resourceName);
                    present = true;
                }
            }
            none |= !present;
        }
        List<Option> untried = new ArrayList<>(), tried = new ArrayList<>();
        for(Map.Entry<String, String> e : keys.entrySet()) {
            String k = e.getKey();
            int n = seen.getOrDefault(k, 0);
            String item = e.getValue();
            if(n > 0) {
                tried.add(new Option(names.get(k), item, res.get(k), n, List.of()));
            } else {
                List<String> tn = GenericTypes.tooltipNames(item);
                /* An item the tooltip splits up is offered under its parts: "Pork bollock". */
                String shown = item;
                if(tn.size() > 1) {
                    for(String t : tn) {
                        if(GenericTypes.key(t).equals(k))
                            shown = t;
                    }
                }
                Set<String> stock = new LinkedHashSet<>();
                if(d.wood()) {
                    /* A smokehouse burns blocks of the wood. */
                    stock.add("Block of " + item);
                } else {
                    stock.addAll(GenericTypes.stockNames(item));
                    stock.addAll(GenericTypes.stockNames(shown));
                }
                /* The icon of the same ingredient in another food, else a known one; else the panel looks the name up. */
                String icon = resources.get(shown.toLowerCase(Locale.ROOT));
                if(icon == null)
                    icon = GenericTypes.icon(item);
                untried.add(new Option(shown, item, icon, 0, stock));
            }
        }
        untried.sort(Comparator.comparing(o -> o.name, String.CASE_INSENSITIVE_ORDER));
        tried.sort(Comparator.comparingInt((Option o) -> -o.seen).thenComparing(o -> o.name, String.CASE_INSENSITIVE_ORDER));
        List<Option> all = new ArrayList<>(untried);
        all.addAll(tried);
        return new Slot(d.title, d.count, d.optional, d.inferred, all, d.optional && none);
    }
}
