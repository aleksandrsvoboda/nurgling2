package nurgling.cookbook.connection;

import nurgling.cookbook.Recipe;
import nurgling.db.DatabaseAdapter;
import nurgling.db.DatabaseManager;

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

    public RecipeCatalogLoader(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public void run() {
        try {
            recipes = databaseManager.executeOperation(this::load);
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
