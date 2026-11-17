package nurgling.actions.bots.largefields;

import nurgling.tools.NAlias;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a large-field farmer needs to know about one crop. The areas are the same ones the
 * regular farmer for that crop uses (see nurgling.actions.bots.farmers), so an existing field
 * setup works unchanged:
 * <ul>
 *   <li>crop area "crop/&lt;subtype&gt;" - the field (any number of them, all farmed);</li>
 *   <li>seed area "seed/&lt;subtype&gt;" - barrels for seeds, stockpiles for vegetables;</li>
 *   <li>trough area - seed overflow (optional);</li>
 *   <li>put areas for the by-products ("Straw", "Flax Fibres", ...) and, for carrot, turnip,
 *       leek and radish, for the vegetable itself.</li>
 * </ul>
 * Products and stages come from CropRegistry, as for the regular farmers.
 */
final class LargeFieldCrop {
    /** Something the harvest leaves on the ground (or puts in the inventory) that goes to its own put area. */
    static final class ByProduct {
        /** Matches the dropped object's resource; null for a piece that only ever appears in the inventory. */
        final NAlias alias;
        /** Exact inventory name, and the name of its put area. */
        final String name;
        /** Straw: skipped entirely while "Ignore straw in farmers" is on. */
        final boolean straw;

        ByProduct(NAlias alias, String name, boolean straw) {
            this.alias = alias;
            this.name = name;
            this.straw = straw;
        }
    }

    /**
     * A whole head or fruit the harvest leaves on the ground (lettuce, pumpkin, watermelon). It is
     * picked up and cut in the inventory into seeds and a piece, as
     * nurgling.actions.LettucePumpkinAndWatermelonCollector does.
     */
    static final class Whole {
        /** Matches the object on the ground and the uncut item in the inventory. */
        final NAlias alias;
        /** Flower-menu verb that cuts one item. */
        final String verb;
        /** Lettuce heads are small and many: cut at half full. A pumpkin is cut once nothing else fits. */
        final boolean cutWhenHalfFull;
        /** The piece is required to have a put area (the regular pumpkin/watermelon farmers refuse to run without one). */
        final boolean pieceRequired;
        /**
         * Inventory cells one uncut item takes, as (rows, columns) - the order
         * NInventory.getNumberFreeCoord(Coord) takes: lettuce 1 row x 2 columns, pumpkin and
         * watermelon 2x2. Used until one is carried and can be measured.
         */
        final haven.Coord size;

        Whole(NAlias alias, String verb, boolean cutWhenHalfFull, boolean pieceRequired, haven.Coord size) {
            this.alias = alias;
            this.verb = verb;
            this.cutWhenHalfFull = cutWhenHalfFull;
            this.pieceRequired = pieceRequired;
            this.size = size;
        }
    }

    final String id;
    final String subtype;
    final NAlias plant;
    final List<ByProduct> byProducts;
    /** Put area of the vegetable when it isn't kept in the seed area (dual-product crops), else null. */
    final String vegOut;
    /** Whole heads to pick up and cut, or null. */
    Whole whole = null;
    /** Champignons: the seed area is a container store (nurgling.actions.AreaContainerStore). */
    boolean containerStore = false;

    private LargeFieldCrop(String id, String subtype, String plant, String vegOut, ByProduct... byProducts) {
        this.id = id;
        this.subtype = subtype;
        this.plant = new NAlias(plant);
        this.vegOut = vegOut;
        this.byProducts = Collections.unmodifiableList(Arrays.asList(byProducts));
    }

    private static ByProduct straw() {
        return new ByProduct(new NAlias("straw", "Straw"), "Straw", true);
    }

    private static ByProduct item(String res, String name) {
        return new ByProduct(new NAlias(res, name), name, false);
    }

    /** id -> crop, in menu order. */
    static final Map<String, LargeFieldCrop> ALL = new LinkedHashMap<>();

    private static void add(LargeFieldCrop c) {
        ALL.put(c.id, c);
    }

    private static LargeFieldCrop whole(String id, String subtype, String plant, Whole whole, String piece) {
        LargeFieldCrop c = new LargeFieldCrop(id, subtype, plant, null, new ByProduct(null, piece, false));
        c.whole = whole;
        return c;
    }

    static {
        add(new LargeFieldCrop("barley", "Barley", "plants/barley", null, straw()));
        add(new LargeFieldCrop("wheat", "Wheat", "plants/wheat", null, straw()));
        add(new LargeFieldCrop("millet", "Millet", "plants/millet", null, straw()));
        add(new LargeFieldCrop("flax", "Flax", "plants/flax", null, item("flaxfibre", "Flax Fibres")));
        add(new LargeFieldCrop("hemp", "Hemp", "plants/hemp", null, item("hempfibre", "Hemp Fibres")));
        add(new LargeFieldCrop("poppy", "Poppy", "plants/poppy", null, item("flower-poppy", "Poppy Flower")));
        add(new LargeFieldCrop("pipeweed", "Pipeweed", "plants/pipeweed", null, item("tobacco-fresh", "Fresh Leaf of Pipeweed")));
        add(new LargeFieldCrop("green_kale", "Green Kale", "plants/greenkale", null, item("items/greenkale", "Green Kale")));
        add(new LargeFieldCrop("turnip", "Turnip", "plants/turnip", "Turnip"));
        add(new LargeFieldCrop("carrot", "Carrot", "plants/carrot", "Carrot"));
        add(new LargeFieldCrop("leek", "Leek", "plants/leek", "Leek"));
        add(new LargeFieldCrop("radish", "Radish", "plants/radish", "Radish"));
        add(new LargeFieldCrop("beetroot", "Beetroot", "plants/beet", null, item("beetleaves", "Beetroot Leaves")));
        add(new LargeFieldCrop("red_onion", "Red Onion", "plants/redonion", null));
        add(new LargeFieldCrop("yellow_onion", "Yellow Onion", "plants/yellowonion", null));
        add(new LargeFieldCrop("white_onion", "White Onion", "plants/whiteonion", null));
        add(new LargeFieldCrop("garlic", "Garlic", "plants/garlic", null));
        add(whole("lettuce", "Lettuce", "plants/lettuce",
                new Whole(new NAlias("items/lettucehead", "Head of Lettuce"), "Split", true, false, new haven.Coord(1, 2)), "Lettuce Leaf"));
        add(whole("pumpkin", "Pumpkin", "plants/pumpkin",
                new Whole(new NAlias(Arrays.asList("items/pumpkin", "Pumpkin"), Arrays.asList("plants", "seed", "flesh")), "Slice", false, true, new haven.Coord(2, 2)), "Pumpkin Flesh"));
        add(whole("watermelon", "Watermelon", "plants/watermelon",
                new Whole(new NAlias(Arrays.asList("items/watermelon", "Watermelon"), Arrays.asList("plants", "seed", "slice")), "Slice", false, true, new haven.Coord(2, 2)), "Watermelon Slice"));
        LargeFieldCrop champignon = new LargeFieldCrop("champignon", "Champignon", "plants/champignon", null);
        champignon.containerStore = true;
        add(champignon);
    }
}
