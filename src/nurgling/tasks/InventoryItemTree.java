package nurgling.tasks;

import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.List;

/**
 * Traverses inventory items without treating a stack wrapper as one item.
 */
final class InventoryItemTree {
    private InventoryItemTree() {
    }

    interface Adapter<T> {
        String name(T item);

        /**
         * Returns nested stack items, or {@code null} when this is an item leaf.
         */
        Iterable<T> children(T item);
    }

    static <T> boolean collectMatching(
            Iterable<T> roots,
            NAlias name,
            Adapter<T> adapter,
            List<T> result
    ) {
        for (T item : roots) {
            String itemName = adapter.name(item);
            if (itemName == null) {
                return false;
            }

            if (name == null || NParser.checkName(itemName, name)) {
                Iterable<T> children = adapter.children(item);
                if (name != null && children != null) {
                    if (!collectMatching(children, name, adapter, result)) {
                        return false;
                    }
                } else {
                    result.add(item);
                }
            }
        }
        return true;
    }
}
