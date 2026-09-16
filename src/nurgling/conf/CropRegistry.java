package nurgling.conf;

import nurgling.tools.NAlias;

import java.util.*;

public class CropRegistry {

    public enum StorageBehavior { BARREL, STOCKPILE, CONTAINER }

    public static class CropStage {
        public final int stage;
        public final NAlias result;
        public final StorageBehavior storageBehavior;
        public final boolean isHybridTrellis;
        // Whether the product can be put back in the ground. Seeds always can, and so can
        // most vegetables - but not all: radishes are grown from Radish Seeds only.
        public final boolean plantable;

        public CropStage(int stage, NAlias result, StorageBehavior storageBehavior) {
            this(stage, result, storageBehavior, false);
        }

        public CropStage(int stage, NAlias result, StorageBehavior storageBehavior, boolean isHybridTrellis) {
            this(stage, result, storageBehavior, isHybridTrellis, true);
        }

        public CropStage(int stage, NAlias result, StorageBehavior storageBehavior, boolean isHybridTrellis, boolean plantable) {
            this.stage = stage;
            this.result = result;
            this.storageBehavior = storageBehavior;
            this.isHybridTrellis = isHybridTrellis;
            this.plantable = plantable;
        }
    }

    public static final Map<NAlias, List<CropStage>> HARVESTABLE = new HashMap<>();

    /**
     * Crop zone subtype -> plant, for the crops grown on open fields by HarvestCrop (the
     * regular and quality farmers). Trellis crops are left out: their bots don't read a
     * per-field harvest stage.
     */
    private static final Map<String, NAlias> FIELD_CROPS = new HashMap<>();

    /** The plant grown on a crop field of this subtype, or null if it isn't a field crop. */
    public static NAlias getFieldCrop(String subtype) {
        return subtype == null ? null : FIELD_CROPS.get(subtype);
    }

    /** The distinct stages a crop is harvested at, lowest first. */
    public static List<Integer> getHarvestStages(NAlias crop) {
        TreeSet<Integer> stages = new TreeSet<>();
        for (CropStage stage : getStages(crop))
            stages.add(stage.stage);
        return new ArrayList<>(stages);
    }

    /** The products a harvest at this stage yields, e.g. "Radish Seeds, Radish". */
    public static String describeStage(NAlias crop, int stage) {
        LinkedHashSet<String> products = new LinkedHashSet<>();
        for (CropStage cs : getStages(crop)) {
            if (cs.stage == stage)
                products.addAll(cs.result.keys);
        }
        return String.join(", ", products);
    }

    /** All harvest stages registered for a crop (empty if unknown). */
    public static List<CropStage> getStages(NAlias crop) {
        return HARVESTABLE.getOrDefault(crop, Collections.emptyList());
    }

    /**
     * The plantable harvest product for a crop with the given storage behavior, or null
     * if the crop has none. Used to derive planting material per storage location
     * (BARREL = stacked seeds, STOCKPILE = vegetables); a product that can't be planted
     * is never returned, so it can never become a planting source.
     */
    public static CropStage getPlantingMaterial(NAlias crop, StorageBehavior behavior) {
        for (CropStage stage : getStages(crop)) {
            if (stage.storageBehavior == behavior && stage.plantable)
                return stage;
        }
        return null;
    }

    static {
        FIELD_CROPS.put("Flax", new NAlias("plants/flax"));
        FIELD_CROPS.put("Turnip", new NAlias("plants/turnip"));
        FIELD_CROPS.put("Carrot", new NAlias("plants/carrot"));
        FIELD_CROPS.put("Hemp", new NAlias("plants/hemp"));
        FIELD_CROPS.put("Millet", new NAlias("plants/millet"));
        FIELD_CROPS.put("Wheat", new NAlias("plants/wheat"));
        FIELD_CROPS.put("Barley", new NAlias("plants/barley"));
        FIELD_CROPS.put("Poppy", new NAlias("plants/poppy"));
        FIELD_CROPS.put("Beetroot", new NAlias("plants/beet"));
        FIELD_CROPS.put("Red Onion", new NAlias("plants/redonion"));
        FIELD_CROPS.put("Yellow Onion", new NAlias("plants/yellowonion"));
        FIELD_CROPS.put("White Onion", new NAlias("plants/whiteonion"));
        FIELD_CROPS.put("Garlic", new NAlias("plants/garlic"));
        FIELD_CROPS.put("Pipeweed", new NAlias("plants/pipeweed"));
        FIELD_CROPS.put("Lettuce", new NAlias("plants/lettuce"));
        FIELD_CROPS.put("Pumpkin", new NAlias("plants/pumpkin"));
        FIELD_CROPS.put("Watermelon", new NAlias("plants/watermelon"));
        FIELD_CROPS.put("Green Kale", new NAlias("plants/greenkale"));
        FIELD_CROPS.put("Leek", new NAlias("plants/leek"));
        FIELD_CROPS.put("Radish", new NAlias("plants/radish"));
        FIELD_CROPS.put("String Grass", new NAlias("plants/stringgrass"));
        FIELD_CROPS.put("Wild Kale", new NAlias("plants/wildbrassica"));
        FIELD_CROPS.put("Wild Onion", new NAlias("plants/wildonion"));
        FIELD_CROPS.put("Wild Tuber", new NAlias("plants/tuber"));
        FIELD_CROPS.put("Wild Flower", new NAlias("plants/wildflower"));

        // Turnip
        HARVESTABLE.put(
                new NAlias("plants/turnip"),
                Arrays.asList(
                        new CropStage(1, new NAlias("Turnip Seeds"), StorageBehavior.BARREL),
                        new CropStage(3, new NAlias("Turnip"), StorageBehavior.STOCKPILE)
                )
        );

        // Carrot
        HARVESTABLE.put(
                new NAlias("plants/carrot"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Carrot Seeds"), StorageBehavior.BARREL),
                        new CropStage(4, new NAlias("Carrot"), StorageBehavior.STOCKPILE)
                )
        );

        // Beetroot
        HARVESTABLE.put(
                new NAlias("plants/beet"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Beetroot"), StorageBehavior.STOCKPILE)
                )
        );

        // Red Onion
        HARVESTABLE.put(
                new NAlias("plants/redonion"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Red Onion"), StorageBehavior.STOCKPILE)
                )
        );

        // Yellow Onion
        HARVESTABLE.put(
                new NAlias("plants/yellowonion"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Yellow Onion"), StorageBehavior.STOCKPILE)
                )
        );

        // White Onion
        HARVESTABLE.put(
                new NAlias("plants/whiteonion"),
                Arrays.asList(
                        new CropStage(3, new NAlias("White Onion"), StorageBehavior.STOCKPILE)
                )
        );

        // Garlic
        HARVESTABLE.put(
                new NAlias("plants/garlic"),
                Arrays.asList(
                        new CropStage(4, new NAlias("Garlic"), StorageBehavior.STOCKPILE)
                )
        );

        // Hemp
        HARVESTABLE.put(
                new NAlias("plants/hemp"),
                Arrays.asList(
                        new CropStage(4, new NAlias("Hemp Seeds"), StorageBehavior.BARREL)
                )
        );

        // Flax
        HARVESTABLE.put(
                new NAlias("plants/flax"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Flax Seeds"), StorageBehavior.BARREL)
                )
        );

        // Lettuce
        HARVESTABLE.put(
                new NAlias("plants/lettuce"),
                Arrays.asList(
                        new CropStage(4, new NAlias("Lettuce Seeds"), StorageBehavior.BARREL)
                )
        );

        // Green Kale
        HARVESTABLE.put(
                new NAlias("plants/greenkale"),
                Arrays.asList(
                        new CropStage(4, new NAlias("Green Kale Seeds"), StorageBehavior.BARREL)
                )
        );

        // Leek
        HARVESTABLE.put(
                new NAlias("plants/leek"),
                Arrays.asList(
                        new CropStage(2, new NAlias("Leek Seeds"), StorageBehavior.BARREL),
                        new CropStage(4, new NAlias("Leek"), StorageBehavior.STOCKPILE)
                )
        );

        // Radish - a radish can't be planted, the field is resown from Radish Seeds only.
        // Harvested at stages 3 and 4 only (the wiki's "Stage 4"/"Stage 5" - it counts from 1),
        // the two that yield seeds as well as radishes: 5-8 / 10-15 seeds against the 5 a tile
        // takes to resow. Stage 2 gives radishes alone and would starve the seed barrel.
        HARVESTABLE.put(
                new NAlias("plants/radish"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Radish Seeds"), StorageBehavior.BARREL),
                        new CropStage(3, new NAlias("Radish"), StorageBehavior.STOCKPILE, false, false),
                        new CropStage(4, new NAlias("Radish Seeds"), StorageBehavior.BARREL),
                        new CropStage(4, new NAlias("Radish"), StorageBehavior.STOCKPILE, false, false)
                )
        );

        // Pumpkin
        HARVESTABLE.put(
                new NAlias("plants/pumpkin"),
                Arrays.asList(
                        new CropStage(4, new NAlias("Pumpkin Seeds"), StorageBehavior.BARREL)
                )
        );

        // Watermelon (harvest yields seeds; the melons themselves drop as gobs and are
        // picked up and sliced by LettucePumpkinAndWatermelonCollector, like pumpkins)
        HARVESTABLE.put(
                new NAlias("plants/watermelon"),
                Arrays.asList(
                        new CropStage(4, new NAlias("Watermelon Seeds"), StorageBehavior.BARREL)
                )
        );

        // Barley
        HARVESTABLE.put(
                new NAlias("plants/barley"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Barley Seeds"), StorageBehavior.BARREL)
                )
        );

        // Millet
        HARVESTABLE.put(
                new NAlias("plants/millet"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Millet Seeds"), StorageBehavior.BARREL)
                )
        );

        // Wheat
        HARVESTABLE.put(
                new NAlias("plants/wheat"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Wheat Seeds"), StorageBehavior.BARREL)
                )
        );

        // Poppy
        HARVESTABLE.put(
                new NAlias("plants/poppy"),
                Arrays.asList(
                        new CropStage(4, new NAlias("Poppy Seeds"), StorageBehavior.BARREL)
                )
        );

        // Pipeweed
        HARVESTABLE.put(
                new NAlias("plants/pipeweed"),
                Arrays.asList(
                        new CropStage(4, new NAlias("Pipeweed Seeds"), StorageBehavior.BARREL)
                )
        );

        // Grape (Trellis crop)
        HARVESTABLE.put(
                new NAlias("plants/wine"),
                Arrays.asList(
                        new CropStage(6, new NAlias("Grapes"), StorageBehavior.STOCKPILE)
                )
        );

        // Hops (Trellis crop - Multiple harvest results)
        HARVESTABLE.put(
                new NAlias("plants/hops"),
                Arrays.asList(
                        new CropStage(6, new NAlias("Unusually Large Hop Cone"), StorageBehavior.STOCKPILE),
                        new CropStage(6, new NAlias("Hop Cones"), StorageBehavior.STOCKPILE)
                )
        );

        // Peppercorn (Trellis crop - Container storage)
        HARVESTABLE.put(
                new NAlias("plants/pepper"),
                Arrays.asList(
                        new CropStage(6, new NAlias("Peppercorn"), StorageBehavior.CONTAINER)
                )
        );

        // Pea (Hybrid Trellis crop - plant disappears after harvest)
        HARVESTABLE.put(
                new NAlias("plants/pea"),
                Arrays.asList(
                        new CropStage(4, new NAlias("Peapods"), StorageBehavior.STOCKPILE, true)
                )
        );

        // Cucumber (Hybrid Trellis crop - plant disappears after harvest, Mixed storage: barrel + stockpile)
        HARVESTABLE.put(
                new NAlias("plants/cucumber"),
                Arrays.asList(
                        new CropStage(4, new NAlias("Seeds of Cucumber"), StorageBehavior.BARREL, true),
                        new CropStage(4, new NAlias("Cucumbers"), StorageBehavior.STOCKPILE, true)
                )
        );

        // String Grass
        HARVESTABLE.put(
                new NAlias("plants/stringgrass"),
                Arrays.asList(
                        new CropStage(3, new NAlias("String Grass Seeds"), StorageBehavior.BARREL)
                )
        );

        // Wild Kale
        HARVESTABLE.put(
                new NAlias("plants/wildbrassica"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Wild Kale Seeds"), StorageBehavior.BARREL)
                )
        );

        // Wild Onion
        HARVESTABLE.put(
                new NAlias("plants/wildonion"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Wild Onion"), StorageBehavior.STOCKPILE)
                )
        );

        HARVESTABLE.put(
                new NAlias("plants/gourd"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Wild Gourd"), StorageBehavior.STOCKPILE,true)
                )
        );


        // Wild Tuber
        HARVESTABLE.put(
                new NAlias("plants/tuber"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Wild Tuber"), StorageBehavior.STOCKPILE)
                )
        );

        // Wild Gourd (Hybrid Trellis crop - Mixed storage: barrel + stockpile)
        HARVESTABLE.put(
                new NAlias("plants/wildgourd"),
                Arrays.asList(
                        new CropStage(4, new NAlias("Wild Gourd Seeds"), StorageBehavior.BARREL, true),
                        new CropStage(4, new NAlias("Wild Gourd"), StorageBehavior.STOCKPILE, true)
                )
        );

        // Wild Flower
        HARVESTABLE.put(
                new NAlias("plants/wildflower"),
                Arrays.asList(
                        new CropStage(3, new NAlias("Wild Flower Seeds"), StorageBehavior.BARREL)
                )
        );
    }
}
