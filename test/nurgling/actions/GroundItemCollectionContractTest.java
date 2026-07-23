package nurgling.actions;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroundItemCollectionContractTest {
    @Test
    @Tag("P0")
    void one_ground_product_is_visible_before_the_final_deposit() throws InterruptedException {
        FakePort port = new FakePort(8, "Hemp Fibres");

        GroundItemCollectionContract.collectAndDeposit(port);

        assertEquals(Arrays.asList("Hemp Fibres"), port.deposited);
        assertEquals(0, port.visibleInventory.size());
        assertEquals(1, port.awaitCalls);
        assertEquals(1, port.depositCalls);
    }

    @Test
    @Tag("P1")
    void every_pickup_crosses_the_visibility_barrier_and_full_inventory_is_flushed() throws InterruptedException {
        FakePort port = new FakePort(2, "Straw", "Straw", "Straw");

        GroundItemCollectionContract.collectAndDeposit(port);

        assertEquals(Arrays.asList("Straw", "Straw", "Straw"), port.deposited);
        assertEquals(3, port.awaitCalls);
        assertEquals(2, port.depositCalls);
    }

    @Test
    @Tag("P1")
    void interrupted_visibility_barrier_fails_closed() {
        FakePort port = new FakePort(8, "Flax Fibres");
        port.interruptOnAwait = true;

        assertThrows(
                InterruptedException.class,
                () -> GroundItemCollectionContract.collectAndDeposit(port)
        );
        assertEquals(0, port.depositCalls);
        assertEquals(0, port.deposited.size());
    }

    @Test
    @Tag("P1")
    void failed_deposit_is_reported_instead_of_silently_succeeding() throws InterruptedException {
        FakePort port = new FakePort(8, "Straw");
        port.depositSucceeds = false;

        assertFalse(GroundItemCollectionContract.collectAndDeposit(port));
        assertEquals(1, port.depositCalls);
        assertEquals(0, port.deposited.size());
    }

    private static final class FakePort implements GroundItemCollectionContract.Port<String> {
        private final Deque<String> ground = new ArrayDeque<>();
        private final int inventoryCapacity;
        private final List<String> visibleInventory = new ArrayList<>();
        private final List<String> deposited = new ArrayList<>();
        private String pendingPickup;
        private int awaitCalls;
        private int depositCalls;
        private boolean interruptOnAwait;
        private boolean depositSucceeds = true;

        private FakePort(int inventoryCapacity, String... groundItems) {
            this.inventoryCapacity = inventoryCapacity;
            ground.addAll(Arrays.asList(groundItems));
        }

        @Override
        public String nextGroundItem() {
            return ground.peekFirst();
        }

        @Override
        public boolean inventoryMustBeDeposited() {
            return visibleInventory.size() >= inventoryCapacity;
        }

        @Override
        public boolean depositInventory() {
            depositCalls++;
            if (!depositSucceeds) {
                return false;
            }
            deposited.addAll(visibleInventory);
            visibleInventory.clear();
            return true;
        }

        @Override
        public void approach(String item) {
        }

        @Override
        public int visibleInventoryCount() {
            return visibleInventory.size();
        }

        @Override
        public void pickUp(String item) {
            ground.removeFirst();
            pendingPickup = item;
        }

        @Override
        public void awaitInventoryCountAtLeast(int minimumCount) throws InterruptedException {
            awaitCalls++;
            if (interruptOnAwait) {
                throw new InterruptedException("pickup never became visible");
            }
            visibleInventory.add(pendingPickup);
            pendingPickup = null;
            if (visibleInventory.size() < minimumCount) {
                throw new AssertionError("visibility barrier completed too early");
            }
        }
    }
}
