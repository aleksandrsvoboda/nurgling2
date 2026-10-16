package nurgling.actions.bots.blueprint;

import haven.Coord;
import nurgling.conf.ConstructionMaterialsRegistry;
import nurgling.craft.AtlasCatalog;
import nurgling.craft.WikiRecipes;
import nurgling.tools.NAlias;
import nurgling.tools.VSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a building costs and how its materials are matched in the inventory.
 * <p>
 * The bundled wiki catalog gives an estimate per build button name, used to plan trips before a
 * sign exists. The construction window is the truth: once the bot has seen one, the exact required
 * counts per gob resource replace the estimate for the rest of the session.
 */
public final class BuildRecipes
{
    /** Material display name to required count, in window order. Count < 0 = unknown. */
    public static final class Recipe
    {
        public final Map<String, Integer> counts = new LinkedHashMap<>();
        public final boolean fromWindow;

        Recipe(boolean fromWindow) { this.fromWindow = fromWindow; }

        public boolean hasUnknownCounts()
        {
            for (int c : counts.values()) if (c < 0) return true;
            return counts.isEmpty();
        }
    }

    private static final Map<String, Recipe> seen = new ConcurrentHashMap<>();
    private static final Map<String, Coord> shapes = new ConcurrentHashMap<>();
    private static final Map<String, Coord> knownShapes = new LinkedHashMap<>();
    /** Wiki spellings of metal choices that VSpec names differently (or not at all). */
    private static final Map<String, String[]> wikiChoices = new LinkedHashMap<>();

    static
    {
        // Same footprints the Build* bots pass to getNumberFreeCoord.
        knownShapes.put("Board", new Coord(4, 1));
        knownShapes.put("Block", new Coord(1, 2));
        knownShapes.put("Bough", new Coord(2, 1));
        knownShapes.put("Rope", new Coord(1, 2));

        wikiChoices.put("Nugget of Common Metal", VSpec.getNamesInCategory("Nugget of Any Common Metal").getKeys().toArray(new String[0]));
        wikiChoices.put("Bar of Common Metal", VSpec.getNamesInCategory("Bar of Any Common Metal").getKeys().toArray(new String[0]));
        wikiChoices.put("Nugget of Hard Metal", VSpec.getNamesInCategory("Nugget of Bronze, Iron or Steel").getKeys().toArray(new String[0]));
        wikiChoices.put("Bar of Hard Metal", VSpec.getNamesInCategory("Bar of Bronze, Iron or Steel").getKeys().toArray(new String[0]));
        wikiChoices.put("Nugget of Precious Metal", VSpec.getNamesInCategory("Nugget of a Precious Metal").getKeys().toArray(new String[0]));
        wikiChoices.put("Bar of Precious Metal", new String[] {"Bar of Gold", "Bar of Rose Gold", "Bar of Silver"});
    }

    private BuildRecipes() {}

    /** Recipe for a gob resource: the one read from a window if any, else the wiki estimate for the button. */
    public static Recipe forBuilding(String gobRes, String buttonName)
    {
        Recipe r = seen.get(gobRes);
        if (r != null) return r;
        r = (buttonName == null) ? null : wikiEstimate(buttonName);
        return (r != null) ? r : forGhost(gobRes);
    }

    private static final Map<String, java.util.Optional<Recipe>> byGhost = new ConcurrentHashMap<>();

    /**
     * Recipe for a planner ghost, button or not: the one read from a window if any, else the wiki
     * estimate under the building's own name (the first that has one). Null when unknown.
     */
    public static Recipe forGhost(String gobRes)
    {
        Recipe r = seen.get(gobRes);
        if (r != null) return r;
        return byGhost.computeIfAbsent(gobRes, res -> {
            for (String name : BuildingNames.of(res))
            {
                Recipe w = wikiEstimate(name);
                if (w != null) return java.util.Optional.of(w);
            }
            return java.util.Optional.empty();
        }).orElse(null);
    }

    /** "35 Clay, 4 Board" ("?" for a count the wiki doesn't give); null when unknown. */
    public static String describe(Recipe r)
    {
        if (r == null || r.counts.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : r.counts.entrySet())
        {
            if (b.length() > 0) b.append(", ");
            b.append(e.getValue() < 0 ? "?" : String.valueOf(e.getValue())).append(' ').append(e.getKey());
        }
        return b.toString();
    }

    /** Material totals for a list of planner ghosts; -1 where a count is unknown. Ghosts with no known recipe are skipped. */
    public static Map<String, Integer> totals(Iterable<String> gobRes)
    {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String res : gobRes)
        {
            Recipe r = forGhost(res);
            if (r == null) continue;
            for (Map.Entry<String, Integer> e : r.counts.entrySet())
                out.merge(e.getKey(), e.getValue(), (a, b) -> (a < 0 || b < 0) ? -1 : a + b);
        }
        return out;
    }

    /** Remember the exact recipe read from a construction window (required = in sign + still needed). */
    public static void rememberFromWindow(String gobRes, Map<String, Integer> required)
    {
        if (required.isEmpty()) return;
        Recipe r = new Recipe(true);
        r.counts.putAll(required);
        seen.put(gobRes, r);
    }

    public static Recipe wikiEstimate(String buttonName)
    {
        WikiRecipes wiki;
        try
        {
            wiki = WikiRecipes.bundled();
        } catch (IOException e)
        {
            return null;
        }
        String key = WikiRecipes.normalize(buttonName);
        for (AtlasCatalog.Recipe recipe : wiki.recipes)
        {
            if (recipe.wiki == null || !"build".equals(recipe.wiki.kind)) continue;
            if (!WikiRecipes.normalize(recipe.name).equals(key)) continue;
            Recipe r = new Recipe(false);
            for (AtlasCatalog.Material m : recipe.inputs)
            {
                if (m.optional) continue;
                int count = (m.count < 0) ? -1 : (int) Math.ceil(m.count);
                r.counts.merge(m.name, count, (a, b) -> (a < 0 || b < 0) ? -1 : a + b);
            }
            return r;
        }
        return null;
    }

    /**
     * Inventory alias for a material name from the wiki or a construction window: a VSpec
     * category ("Thatching Material", "Bar of Any Metal") expands to its members, a "X, Y or Z"
     * choice to its parts, anything else matches by name.
     */
    public static NAlias aliasFor(String materialName)
    {
        String[] choice = wikiChoices.get(materialName);
        if (choice != null) return new NAlias(choice);
        if (VSpec.categories.containsKey(materialName))
        {
            NAlias members = VSpec.getNamesInCategory(materialName);
            if (!members.getKeys().isEmpty()) return members;
        }
        if (materialName.contains(" or "))
        {
            List<String> keys = new ArrayList<>();
            for (String part : materialName.split(",| or "))
            {
                String p = part.trim();
                if (p.isEmpty()) continue;
                keys.addAll(aliasFor(p).getKeys());
            }
            if (!keys.isEmpty()) return new NAlias(keys.toArray(new String[0]));
        }
        return new NAlias(materialName);
    }

    /** The construction-materials zone type that stores this material, or null. */
    public static ConstructionMaterialsRegistry.MaterialType zoneTypeFor(String materialName)
    {
        ConstructionMaterialsRegistry.MaterialType t = ConstructionMaterialsRegistry.getMaterialType(new NAlias(materialName));
        if (t == null) t = ConstructionMaterialsRegistry.getMaterialType(aliasFor(materialName));
        // "Tree Bough", "Alder Bough": the registry only matches a material word at the start
        for (String word : materialName.split(" "))
            if (t == null && !word.isEmpty() && Character.isUpperCase(word.charAt(0)))
                t = ConstructionMaterialsRegistry.getMaterialType(new NAlias(word));
        return t;
    }

    /** Inventory footprint of one item of the material: observed, known, or 1x1. */
    public static Coord shapeOf(String materialName)
    {
        Coord s = shapes.get(materialName);
        if (s != null) return s;
        for (Map.Entry<String, Coord> e : knownShapes.entrySet())
            if (materialName.contains(e.getKey())) return e.getValue();
        return new Coord(1, 1);
    }

    public static void observeShape(String materialName, Coord shape)
    {
        if (shape != null) shapes.put(materialName, shape);
    }
}
