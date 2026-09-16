package nurgling.cookbook;

import java.util.*;

public class Recipe {
    /**
     * resource_name of an ingredients-table row that holds a smoking wood rather than an ingredient.
     * It names the game resource behind the "Smoked with ..." tooltip line, so no real ingredient can
     * carry it, and clients that predate it simply list the wood among the ingredients.
     */
    public static final String SMOKE_RESOURCE = "ui/tt/smoked";

    private final String hash;
    private final String name;
    private final String resourceName;
    private final double hunger;
    private final int energy;
    private final Map<String, IngredientInfo> ingredients; // Ingredient name -> info (percent + resource)
    private final Map<String, Double> smokingWoods;        // Wood name -> share of the smoke in percent, sorted by name
    private final Map<String, Fep> feps;         // FEP name -> value
    private boolean isFavorite;

    public static class IngredientInfo {
        public final double percentage;
        public final String resourceName; // Composite resource name (e.g., "gfx/invobjs/meat-raw+gfx/invobjs/meat-fox")

        public IngredientInfo(double percentage, String resourceName) {
            this.percentage = percentage;
            this.resourceName = resourceName;
        }

        public IngredientInfo(double percentage) {
            this(percentage, null);
        }
    }

    public Recipe(String hash, String name, String resourceName,
                  double hunger, int energy,
                  Map<String, IngredientInfo> ingredients,
                  Map<String, Double> smokingWoods,
                  Map<String, Fep> feps) {
        this.hash = hash;
        this.name = name;
        this.resourceName = resourceName;
        this.hunger = hunger;
        this.energy = energy;
        this.ingredients = ingredients;
        this.smokingWoods = new TreeMap<>(smokingWoods);
        this.feps = feps;
    }

    // Геттеры
    public String getHash() {
        return hash;
    }

    public String getName() {
        return name;
    }

    public String getResourceName() {
        return resourceName;
    }

    public double getHunger() {
        return hunger;
    }

    public int getEnergy() {
        return energy;
    }

    public Map<String, IngredientInfo> getIngredients() {
        return ingredients;
    }

    public Map<String, Double> getSmokingWoods() {
        return smokingWoods;
    }

    /** Files a row of the ingredients table as either an ingredient or a smoking wood. */
    public void addIngredientRow(String name, double percentage, String resourceName) {
        if (SMOKE_RESOURCE.equals(resourceName)) {
            smokingWoods.put(name, percentage);
        } else {
            ingredients.put(name, new IngredientInfo(percentage, resourceName));
        }
    }

    public static class Fep
    {
        public Fep(double val, double weigth) {
            this.val = val;
            this.weigth = weigth;
        }

        public double val;
        public double weigth;
    }

    public Map<String, Fep> getFeps() {
        return feps;
    }

    public boolean isFavorite() {
        return isFavorite;
    }

    public void setFavorite(boolean favorite) {
        isFavorite = favorite;
    }

    @Override
    public String toString() {
        return "Recipe{" +
                "hash='" + hash + '\'' +
                ", name='" + name + '\'' +
                ", resourceName='" + resourceName + '\'' +
                ", hunger=" + hunger +
                ", energy=" + energy +
                ", ingredients=" + ingredients +
                ", smokingWoods=" + smokingWoods +
                ", feps=" + feps.toString() +
                '}';
    }
}
