package nurgling.actions;

import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the quality-farmer planting order from explicitly registered
 * planting materials.
 */
public final class QualityPlantingContract {
    private static final int INDIVIDUAL_ITEM = -1;

    private QualityPlantingContract() {
    }

    public static final class Material<T> {
        public final T item;
        public final String name;
        public final int amount;

        private Material(T item, String name, int amount) {
            this.item = item;
            this.name = name;
            this.amount = amount;
        }

        public static <T> Material<T> individual(T item, String name) {
            return new Material<>(item, name, INDIVIDUAL_ITEM);
        }

        public static <T> Material<T> stack(T item, String name, int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Stack amount cannot be negative");
            }
            return new Material<>(item, name, amount);
        }
    }

    public static <T> List<T> plan(
            List<Material<T>> candidates,
            NAlias allowedMaterials,
            int tilesToPlant,
            int unitsPerTile
    ) {
        if (tilesToPlant < 0) {
            throw new IllegalArgumentException("Tile count cannot be negative");
        }
        if (unitsPerTile <= 0) {
            throw new IllegalArgumentException("Units per tile must be positive");
        }

        List<T> order = new ArrayList<>();
        for (Material<T> material : candidates) {
            if (order.size() >= tilesToPlant) {
                break;
            }
            if (!allowedMaterials.matches(material.name)) {
                continue;
            }

            if (material.amount == INDIVIDUAL_ITEM) {
                order.add(material.item);
                continue;
            }

            int availableTiles = material.amount / unitsPerTile;
            for (int i = 0; i < availableTiles && order.size() < tilesToPlant; i++) {
                order.add(material.item);
            }
        }
        return order;
    }
}
