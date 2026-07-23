package nurgling.actions;

/**
 * Contract for collecting ground items into inventory before depositing them.
 *
 * <p>A pickup is not complete merely because the ground gob disappeared. The
 * corresponding inventory item must also be visible before the next scan or the
 * final deposit may observe an empty inventory and silently leave the last item
 * behind.</p>
 */
public final class GroundItemCollectionContract {
    private GroundItemCollectionContract() {
    }

    public interface Port<T> {
        T nextGroundItem() throws InterruptedException;

        boolean inventoryMustBeDeposited() throws InterruptedException;

        boolean depositInventory() throws InterruptedException;

        void approach(T item) throws InterruptedException;

        int visibleInventoryCount() throws InterruptedException;

        void pickUp(T item) throws InterruptedException;

        void awaitInventoryCountAtLeast(int minimumCount) throws InterruptedException;
    }

    public static <T> boolean collectAndDeposit(Port<T> port) throws InterruptedException {
        T item;
        while ((item = port.nextGroundItem()) != null) {
            if (port.inventoryMustBeDeposited() && !port.depositInventory()) {
                return false;
            }

            port.approach(item);
            int previousInventoryCount = port.visibleInventoryCount();
            port.pickUp(item);
            port.awaitInventoryCountAtLeast(previousInventoryCount + 1);
        }

        return port.depositInventory();
    }
}
