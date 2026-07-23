package nurgling.tasks;

import nurgling.tools.NAlias;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitMoreItemsContractTest {
    @Test
    @Tag("P0")
    void nested_stack_items_count_as_visible_inventory_items() {
        FakeItem stack = new FakeItem(
                "Hemp Fibres",
                new FakeItem("Hemp Fibres"),
                new FakeItem("Hemp Fibres")
        );
        List<FakeItem> visible = new ArrayList<>();

        boolean ready = InventoryItemTree.collectMatching(
                Collections.singletonList(stack),
                new NAlias("Hemp Fibres"),
                FakeItem.ADAPTER,
                visible
        );

        assertTrue(ready);
        assertEquals(2, visible.size());
    }

    @Test
    @Tag("P1")
    void bounded_wait_completes_when_required_visibility_arrives() {
        AtomicInteger visible = new AtomicInteger(0);
        WaitMoreItems wait = new WaitMoreItems(visible::get, 1, 2);

        assertFalse(wait.baseCheck());
        visible.set(1);
        assertTrue(wait.baseCheck());
        assertFalse(wait.criticalExit);
    }

    @Test
    @Tag("P1")
    void bounded_wait_fails_closed_after_its_check_budget() {
        WaitMoreItems wait = new WaitMoreItems(() -> 0, 1, 2);

        assertFalse(wait.baseCheck());
        assertFalse(wait.baseCheck());
        assertTrue(wait.baseCheck());
        assertTrue(wait.criticalExit);
    }

    private static final class FakeItem {
        private static final InventoryItemTree.Adapter<FakeItem> ADAPTER =
                new InventoryItemTree.Adapter<FakeItem>() {
                    @Override
                    public String name(FakeItem item) {
                        return item.name;
                    }

                    @Override
                    public Iterable<FakeItem> children(FakeItem item) {
                        return item.children;
                    }
                };

        private final String name;
        private final List<FakeItem> children;

        private FakeItem(String name, FakeItem... children) {
            this.name = name;
            this.children = children.length == 0
                    ? null
                    : Arrays.asList(children);
        }
    }
}
