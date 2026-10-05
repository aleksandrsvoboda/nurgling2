package nurgling.craft;

import org.json.*;
import java.util.*;
import java.math.BigDecimal;

/** Plain data: no UI, resource loads, server messages or global active session. */
public final class AtlasCatalog {
    public static final class Material {
        public final String resource, name, unit, reference;
        public final double count;
        public final boolean optional, category;
        /** One material quantity, satisfied by any single member of this explicit choice. */
        public final List<Material> choices;
        public Material(String resource, String name, int count, boolean optional, boolean category) {
            this(resource, name, count, "", optional, category, "");
        }
        public Material(String resource, String name, double count, String unit, boolean optional, boolean category, String reference) {
            if(!Double.isFinite(count) || count < -1) throw new IllegalArgumentException("Invalid material amount");
            this.resource = resource; this.name = name; this.count = count;
            this.optional = optional; this.category = category;
            this.unit = unit; this.reference = reference;
            List<Material> options = new ArrayList<>();
            if(resource.startsWith("wiki-choice:")) {
                if(resource.length() > 16384) throw new IllegalArgumentException("Choice too large");
                JSONArray encoded = new JSONArray(resource.substring("wiki-choice:".length()));
                if(encoded.length() < 2 || encoded.length() > 16) throw new IllegalArgumentException("Invalid choice size");
                for(int i = 0; i < encoded.length(); i++) {
                    JSONArray option = encoded.getJSONArray(i);
                    if(option.length() != 3 || !option.getString(0).startsWith("wiki-item:")) throw new IllegalArgumentException("Invalid choice member");
                    options.add(new Material(option.getString(0), option.getString(1), count, unit, optional, option.getBoolean(2), ""));
                }
            }
            choices = List.copyOf(options);
        }
        Material(JSONObject j) {
            this(j.getString("resource"), j.getString("name"), j.optDouble("count", -1), j.optString("unit"),
                 j.optBoolean("optional"), j.optBoolean("category"), j.optString("reference"));
        }
        JSONObject json() {
            return new JSONObject().put("resource", resource).put("name", name).put("count", count)
                .put("optional", optional).put("category", category).put("unit", unit).put("reference", reference);
        }
        public String key() { return reference.isEmpty() ? resource : reference; }
        public String quantity(int crafts) {
            if(crafts < 1 || crafts > 9999) throw new IllegalArgumentException("Craft count must be 1..9999");
            return count < 0 ? "?" : BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(crafts)).stripTrailingZeros().toPlainString()
                + (unit.isEmpty() ? "" : " " + unit);
        }
    }

    public static final class WikiInfo {
        public final String source, date, kind, actionName, inputText;
        public final int revision;
        public final boolean quantitiesKnown;
        public final List<String> requirements, formulas;
        public WikiInfo(String source, String date, String kind, String actionName, String inputText, int revision,
                        boolean quantitiesKnown, List<String> requirements, List<String> formulas) {
            this.source = source; this.date = date; this.kind = kind; this.actionName = actionName;
            this.inputText = inputText; this.revision = revision; this.quantitiesKnown = quantitiesKnown;
            this.requirements = List.copyOf(requirements); this.formulas = List.copyOf(formulas);
        }
        WikiInfo(JSONObject j) {
            this(j.getString("source"), j.getString("date"), j.getString("kind"), j.optString("actionName"), j.optString("inputText"),
                 j.getInt("revision"), j.getBoolean("quantitiesKnown"), strings(j.optJSONArray("requirements")), strings(j.optJSONArray("formulas")));
        }
        JSONObject json() {
            return new JSONObject().put("source", source).put("date", date).put("kind", kind).put("actionName", actionName)
                .put("inputText", inputText).put("revision", revision).put("quantitiesKnown", quantitiesKnown)
                .put("requirements", requirements).put("formulas", formulas);
        }
    }

    public static final class Recipe {
        public final String resource, name, group;
        public final List<Material> inputs, outputs;
        public final List<String> quality, tools;
        public final boolean recorded;
        public final WikiInfo wiki;
        public Recipe(String resource, String name, String group, List<Material> inputs, List<Material> outputs,
                      List<String> quality, List<String> tools, boolean recorded) {
            this(resource, name, group, inputs, outputs, quality, tools, recorded, null);
        }
        public Recipe(String resource, String name, String group, List<Material> inputs, List<Material> outputs,
                      List<String> quality, List<String> tools, boolean recorded, WikiInfo wiki) {
            this.resource = resource; this.name = name; this.group = group;
            this.inputs = List.copyOf(inputs); this.outputs = List.copyOf(outputs);
            this.quality = List.copyOf(quality); this.tools = List.copyOf(tools); this.recorded = recorded;
            this.wiki = wiki;
        }
        public Recipe(String resource, String name, String group) {
            this(resource, name, group, List.of(), List.of(), List.of(), List.of(), false);
        }
        Recipe(JSONObject j) {
            this(j.getString("resource"), j.getString("name"), j.optString("group"),
                 materials(j.optJSONArray("inputs")), materials(j.optJSONArray("outputs")),
                 strings(j.optJSONArray("quality")), strings(j.optJSONArray("tools")), j.optBoolean("recorded"),
                 j.has("wiki") ? new WikiInfo(j.getJSONObject("wiki")) : null);
        }
        JSONObject json() {
            JSONArray in = new JSONArray(), out = new JSONArray();
            inputs.forEach(m -> in.put(m.json())); outputs.forEach(m -> out.put(m.json()));
            return new JSONObject().put("resource", resource).put("name", name).put("group", group)
                .put("inputs", in).put("outputs", out).put("quality", quality).put("tools", tools).put("recorded", recorded)
                .put("wiki", wiki == null ? null : wiki.json());
        }
        public boolean matches(String query) {
            StringBuilder haystack = new StringBuilder(name).append(' ').append(group).append(' ').append(resource);
            for(Material m : inputs) haystack.append(' ').append(m.name);
            for(Material m : outputs) haystack.append(' ').append(m.name);
            if(wiki != null) haystack.append(' ').append(wiki.inputText).append(' ').append(wiki.actionName);
            String hay = haystack.toString().toLowerCase(Locale.ROOT);
            for(String word : query.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                if(!hay.contains(word)) return false;
            return true;
        }
    }

    private final Map<String, Recipe> recipes = new LinkedHashMap<>();
    private final Set<String> favorites = new HashSet<>();
    private final Map<String, String> wikiNames = new HashMap<>();
    private int wikiNamesVersion = -1;
    public int version;
    public Collection<Recipe> recipes() { return Collections.unmodifiableCollection(recipes.values()); }
    public Recipe get(String resource) { return recipes.get(resource); }
    public boolean favorite(String resource) { return favorites.contains(resource); }
    public void toggleFavorite(String resource) {
        if(!favorites.remove(resource)) favorites.add(resource);
        version++;
    }
    public void put(Recipe recipe) {
        Recipe old = recipes.get(recipe.resource);
        if(old != null && old.json().similar(recipe.json())) return;
        recipes.put(recipe.resource, recipe); version++;
    }
    public Recipe findWikiCraft(String name) {
        return findWikiAction(name, "craft");
    }
    public Recipe findWikiAction(String name, String kind) {
        // A menu scan can resolve hundreds of actions; normalize the wiki catalog once per revision.
        if(wikiNamesVersion != version) {
            wikiNames.clear();
            Set<String> ambiguous = new HashSet<>();
            for(Recipe recipe : recipes.values()) {
                if(recipe.wiki == null || !(recipe.wiki.kind.equals("craft") || recipe.wiki.kind.equals("build"))) continue;
                for(String label : new String[]{recipe.name, recipe.wiki.actionName}) {
                    String normalized = WikiRecipes.normalize(label);
                    if(normalized.isEmpty()) continue;
                    String key = recipe.wiki.kind + ":" + normalized;
                    String old = wikiNames.putIfAbsent(key, recipe.resource);
                    if(old != null && !old.equals(recipe.resource)) ambiguous.add(key);
                }
            }
            ambiguous.forEach(wikiNames::remove);
            wikiNamesVersion = version;
        }
        return recipes.get(wikiNames.get(kind + ":" + WikiRecipes.normalize(name)));
    }
    /** Merge a legacy character record into its unique wiki identity, retaining its favorite. */
    public Recipe migrate(String nativeId, Recipe wikiRecipe) {
        Recipe old = recipes.get(nativeId);
        if(old == null || nativeId.equals(wikiRecipe.resource)) return wikiRecipe;
        recipes.remove(nativeId);
        if(favorites.remove(nativeId)) favorites.add(wikiRecipe.resource);
        Recipe merged = old.recorded ? new Recipe(wikiRecipe.resource, wikiRecipe.name, wikiRecipe.group,
            old.inputs, old.outputs, old.quality, old.tools, true, wikiRecipe.wiki) : wikiRecipe;
        recipes.put(merged.resource, merged); version++;
        return merged;
    }
    /** Only exact resources with one known producer can be followed automatically. */
    public Recipe producer(Material material) {
        if(material.category) return null;
        Recipe found = null;
        for(Recipe recipe : recipes.values()) for(Material out : recipe.outputs) {
            if(out.key().equals(material.key())) {
                if(found != null && found != recipe) return null;
                found = recipe;
            }
        }
        return found;
    }
    public List<Recipe> uses(String resource) {
        List<Recipe> result = new ArrayList<>();
        for(Recipe recipe : recipes.values()) {
            if(recipe.inputs.stream().anyMatch(m -> m.key().equals(resource) || m.choices.stream().anyMatch(c -> c.key().equals(resource)))) result.add(recipe);
        }
        result.sort(Comparator.comparing(r -> r.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }
    public JSONObject json() {
        JSONArray array = new JSONArray(); recipes.values().forEach(r -> array.put(r.json()));
        return new JSONObject().put("schema", 1).put("recipes", array).put("favorites", favorites);
    }
    /** The shipped binary is the base; JSON stores only character observations and favorites. */
    public JSONObject journalJson() {
        JSONArray array = new JSONArray();
        recipes.values().stream().filter(r -> r.wiki == null || r.recorded).forEach(r -> array.put(r.json()));
        return new JSONObject().put("schema", 1).put("recipes", array).put("favorites", favorites);
    }
    /** Replace the wiki base while retaining the independent character journal, including removed observed recipes. */
    public void replaceWiki(Collection<Recipe> replacement) {
        AtlasCatalog next = new AtlasCatalog();
        replacement.forEach(next::put);
        next.mergeJournal(journalJson());
        recipes.clear(); recipes.putAll(next.recipes);
        favorites.clear(); favorites.addAll(next.favorites);
        version++;
    }
    public void mergeJournal(JSONObject json) {
        AtlasCatalog saved = new AtlasCatalog(); saved.load(json);
        for(Recipe recipe : saved.recipes()) {
            Recipe base = recipes.get(recipe.resource);
            if(base != null && base.wiki != null) {
                if(recipe.recorded) put(new Recipe(base.resource, base.name, base.group, recipe.inputs, recipe.outputs,
                    recipe.quality, recipe.tools, true, base.wiki));
            } else put(recipe);
        }
        favorites.addAll(saved.favorites); version++;
    }
    public void load(JSONObject json) {
        if(json.optInt("schema") != 1) throw new IllegalArgumentException("Unknown craft atlas schema");
        Map<String, Recipe> loaded = new LinkedHashMap<>();
        JSONArray array = json.getJSONArray("recipes");
        for(int i = 0; i < array.length(); i++) {
            Recipe recipe = new Recipe(array.getJSONObject(i));
            loaded.put(recipe.resource, recipe);
        }
        Set<String> starred = new HashSet<>(strings(json.optJSONArray("favorites")));
        recipes.clear(); recipes.putAll(loaded); favorites.clear(); favorites.addAll(starred); version++;
    }
    private static List<Material> materials(JSONArray array) {
        List<Material> result = new ArrayList<>();
        if(array != null) for(int i = 0; i < array.length(); i++) result.add(new Material(array.getJSONObject(i)));
        return result;
    }
    private static List<String> strings(JSONArray array) {
        List<String> result = new ArrayList<>();
        if(array != null) for(int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }
}
