package nurgling.cookbook.connection;

import nurgling.cookbook.GenericTypes;
import nurgling.cookbook.Recipe;
import nurgling.cookbook.Stock;
import nurgling.cookbook.Variations;
import nurgling.craft.WikiRecipes;
import nurgling.db.DatabaseAdapter;
import nurgling.db.DatabaseManager;
import nurgling.db.dao.StorageItemDao;
import nurgling.widgets.storage.VillageStorage;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loads every recipe for the cookbook window, off the UI thread.
 *
 * <p>Three plain queries on one connection, joined in Java. A single join of feps and
 * ingredients would return feps x ingredients rows per recipe; this returns their sum. Nothing
 * the user types is ever part of the SQL -- the cookbook filters in memory.
 */
public class RecipeCatalogLoader implements Runnable {
    private final DatabaseManager databaseManager;
    public final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile List<Recipe> recipes = Collections.emptyList();
    private volatile String error = null;
    private volatile Variations variations = null;
    private final VillageStorage storage;
    /** Whether the village could be worked out when this load started. */
    public final boolean villageKnowable;
    private volatile Stock stock = null;
    private volatile Set<String> cookable = Collections.emptySet();

    /** @param storage which storage counts as the village's, read on the UI thread */
    public RecipeCatalogLoader(DatabaseManager databaseManager, VillageStorage storage) {
        this.databaseManager = databaseManager;
        this.storage = storage;
        this.villageKnowable = storage.knowable();
    }

    @Override
    public void run() {
        try {
            recipes = databaseManager.executeOperation(this::load);
            variations = variations(recipes);
            stock = loadStock();
            cookable = cookable(recipes, variations, (stock != null) ? stock.keys() : Set.of());
        } catch (SQLException e) {
            error = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("[Cookbook] Failed to load recipes: " + error);
        } finally {
            ready.set(true);
        }
    }

    /** The loaded recipes; empty until {@link #ready} and after a failure. */
    public List<Recipe> recipes() {
        return recipes;
    }

    /** The untried variations of the loaded recipes; null until {@link #ready} and when the offline data is missing. */
    public Variations variations() {
        return variations;
    }

    /**
     * The items in storage, limited to the village exactly as the Storage Items window does; null
     * until {@link #ready} and when storage could not be read.
     */
    public Stock stock() {
        return stock;
    }

    /** Hashes of the recipes whose every listed ingredient is in the village's storage; empty until {@link #ready}. */
    public Set<String> cookable() {
        return cookable;
    }

    private static Set<String> cookable(List<Recipe> recipes, Variations variations, Set<String> stored) {
        Set<String> ret = new HashSet<>();
        if (variations != null) {
            for (Recipe r : recipes)
                if (variations.cookable(r, stored))
                    ret.add(r.getHash());
        }
        return ret;
    }

    private Stock loadStock() {
        try {
            List<StorageItemDao.StorageItemData> items = VillageStorage.valid(
                    databaseManager.executeOperation(adapter -> new StorageItemDao().loadAllStorageItems(adapter)));
            int all = items.size();
            if (VillageStorage.onlyVillage()) {
                Set<Long> village = storage.village();
                items = VillageStorage.inVillage(items, village);
                System.out.println("[Cookbook] Storage: " + items.size() + " of " + all + " items in the village (" + village.size() + " chunks"
                        + (village.isEmpty() ? ", none found, so all count" : "") + ")");
            }
            return new Stock(items);
        } catch (SQLException e) {
            /* The recipes loaded; only the storage frames and counts are missing. */
            System.err.println("[Cookbook] Failed to load storage items: " + e.getMessage());
            return null;
        }
    }

    private static Variations variations(List<Recipe> recipes) {
        try {
            return new Variations(recipes, WikiRecipes.bundled(), GenericTypes.bundled());
        } catch (IOException | org.json.JSONException e) {
            System.err.println("[Cookbook] Untried variations unavailable: " + e.getMessage());
            return null;
        }
    }

    /** Why loading failed, or null. */
    public String error() {
        return error;
    }

    private List<Recipe> load(DatabaseAdapter adapter) throws SQLException {
        Map<String, Recipe> byHash = new LinkedHashMap<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT r.recipe_hash, r.item_name, r.resource_name, r.hunger, r.energy, fav.recipe_hash AS fav_hash " +
                "FROM recipes r LEFT JOIN favorite_recipes fav ON r.recipe_hash = fav.recipe_hash")) {
            while (rs.next()) {
                String hash = rs.getString("recipe_hash");
                if (byHash.containsKey(hash))
                    continue;
                Recipe recipe = new Recipe(hash, rs.getString("item_name"), rs.getString("resource_name"),
                        rs.getDouble("hunger"), rs.getInt("energy"),
                        new HashMap<>(), new HashMap<>(), new HashMap<>());
                recipe.setFavorite(rs.getString("fav_hash") != null);
                byHash.put(hash, recipe);
            }
        }
        try (ResultSet rs = adapter.executeQuery("SELECT recipe_hash, name, value, weight FROM feps")) {
            while (rs.next()) {
                Recipe recipe = byHash.get(rs.getString("recipe_hash"));
                String name = rs.getString("name");
                if ((recipe != null) && (name != null))
                    recipe.getFeps().put(name, new Recipe.Fep(rs.getDouble("value"), rs.getDouble("weight")));
            }
        }
        try (ResultSet rs = adapter.executeQuery("SELECT recipe_hash, name, percentage, resource_name FROM ingredients")) {
            while (rs.next()) {
                Recipe recipe = byHash.get(rs.getString("recipe_hash"));
                String name = rs.getString("name");
                if ((recipe != null) && (name != null))
                    recipe.addIngredientRow(name, rs.getDouble("percentage"), rs.getString("resource_name"));
            }
        }
        return new ArrayList<>(byHash.values());
    }
}
