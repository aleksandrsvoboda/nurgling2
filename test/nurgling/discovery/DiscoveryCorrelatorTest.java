package nurgling.discovery;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static nurgling.discovery.DiscoveryCorrelator.Kind.*;
import static nurgling.discovery.DiscoveryCorrelator.WINDOW_MS;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryCorrelatorTest {
    static final class FakeItem implements DiscoveryCorrelator.Item {
        String res, name;

        FakeItem(String res, String name) {
            this.res = res;
            this.name = name;
        }

        public String res() { return res; }
        public String name() { return name; }
    }

    private final DiscoveryCorrelator c = new DiscoveryCorrelator();
    private final Set<String> done = new HashSet<>();

    private List<DiscoveryCorrelator.Verdict> poll(long now) {
        return c.poll(now, done::contains);
    }

    private static FakeItem item(String name) {
        return new FakeItem("gfx/invobjs/" + name.toLowerCase().replace(' ', '-'), name);
    }

    @Test
    void itemThenLpIsDiscovery() {
        c.arrival(item("Fig Leaf"), 1000, true, "gfx/terobjs/trees/figtree");
        c.lp(100, 1400);
        assertTrue(poll(1400 + WINDOW_MS - 1).isEmpty(), "waits for the LP window to close");
        List<DiscoveryCorrelator.Verdict> v = poll(1400 + WINDOW_MS);
        assertEquals(1, v.size());
        assertEquals(DISCOVERED, v.get(0).kind);
        assertEquals("Fig Leaf", v.get(0).name);
        assertEquals(100, v.get(0).lp);
        assertEquals("gfx/terobjs/trees/figtree", v.get(0).src);
        assertTrue(poll(100_000).isEmpty());
    }

    @Test
    void lpThenItemIsDiscovery() {
        c.lp(100, 1000);
        c.arrival(item("Fig Leaf"), 1800, true, null);
        List<DiscoveryCorrelator.Verdict> v = poll(1000 + WINDOW_MS);
        assertEquals(1, v.size());
        assertEquals(DISCOVERED, v.get(0).kind);
    }

    @Test
    void receivedItemWithLpIsDiscovery() {
        c.arrival(item("Silk Moth"), 1000, false, null);
        c.lp(50, 1100);
        List<DiscoveryCorrelator.Verdict> v = poll(5000);
        assertEquals(1, v.size());
        assertEquals(DISCOVERED, v.get(0).kind);
    }

    @Test
    void producedWithoutLpIsKnown() {
        c.arrival(item("Branch"), 1000, true, null);
        assertTrue(poll(1000 + WINDOW_MS - 1).isEmpty());
        List<DiscoveryCorrelator.Verdict> v = poll(1000 + WINDOW_MS);
        assertEquals(1, v.size());
        assertEquals(KNOWN, v.get(0).kind);
    }

    @Test
    void receivedWithoutLpIsHeld() {
        c.arrival(item("Silk Moth"), 1000, false, null);
        List<DiscoveryCorrelator.Verdict> v = poll(5000);
        assertEquals(HELD, v.get(0).kind);
    }

    @Test
    void lpWithoutItemIsUnattributed() {
        c.lp(250, 1000);
        List<DiscoveryCorrelator.Verdict> v = poll(5000);
        assertEquals(1, v.size());
        assertEquals(UNATTRIBUTED, v.get(0).kind);
        assertEquals(250, v.get(0).lp);
    }

    @Test
    void curioFinishingIsNotADiscovery() {
        c.arrival(item("Branch"), 1000, true, null);
        c.curio(300, 1050);
        c.lp(300, 1100);
        List<DiscoveryCorrelator.Verdict> v = poll(5000);
        assertEquals(1, v.size());
        assertEquals(KNOWN, v.get(0).kind, "the curio's LP is not the item's");
    }

    @Test
    void curioAndDiscoveryTogetherKeepTheRemainder() {
        c.arrival(item("Fig Leaf"), 1000, true, null);
        c.curio(300, 1050);
        c.lp(400, 1100);
        List<DiscoveryCorrelator.Verdict> v = poll(5000);
        assertEquals(DISCOVERED, v.get(0).kind);
        assertEquals(100, v.get(0).lp);
    }

    @Test
    void curioWithUnknownLpCreditsNothing() {
        c.arrival(item("Fig Leaf"), 1000, true, null);
        c.curio(-1, 1050);
        c.lp(400, 1100);
        assertEquals(KNOWN, poll(5000).get(0).kind);
    }

    @Test
    void twoNewItemsShareOneRise() {
        c.arrival(item("Fig Leaf"), 1000, true, null);
        c.arrival(item("Fig"), 1010, true, null);
        c.lp(100, 1100);
        List<DiscoveryCorrelator.Verdict> v = poll(5000);
        assertEquals(2, v.size());
        for (DiscoveryCorrelator.Verdict x : v) {
            assertEquals(DISCOVERED, x.kind);
            assertTrue(x.shared);
        }
    }

    @Test
    void alreadyDoneNameIsNotCreditedWithTheRise() {
        done.add("Branch");
        c.arrival(item("Branch"), 1000, true, null);
        c.arrival(item("Fig Leaf"), 1000, true, null);
        c.lp(100, 1100);
        List<DiscoveryCorrelator.Verdict> v = poll(5000);
        DiscoveryCorrelator.Verdict fig = v.stream().filter(x -> x.name.equals("Fig Leaf")).findFirst().orElseThrow();
        DiscoveryCorrelator.Verdict branch = v.stream().filter(x -> x.name.equals("Branch")).findFirst().orElseThrow();
        assertEquals(DISCOVERED, fig.kind);
        assertFalse(fig.shared);
        assertEquals(KNOWN, branch.kind);
    }

    @Test
    void lateNameIsWaitedFor() {
        FakeItem it = new FakeItem("gfx/invobjs/leaf-fig", null);
        c.arrival(it, 1000, true, null);
        c.lp(100, 1100);
        assertTrue(poll(5000).isEmpty(), "no verdict before the name resolves");
        it.name = "Fig Leaf";
        List<DiscoveryCorrelator.Verdict> v = poll(6000);
        assertEquals(DISCOVERED, v.get(0).kind);
    }

    @Test
    void nameThatNeverResolvesIsDropped() {
        c.arrival(new FakeItem("gfx/invobjs/x", null), 1000, true, null);
        assertTrue(poll(1000 + DiscoveryCorrelator.NAME_TIMEOUT_MS).isEmpty());
        assertTrue(c.idle());
    }

    @Test
    void lateLpHoldsTheNoLpVerdict() {
        c.arrival(item("Fig Leaf"), 1000, true, null);
        c.lp(100, 1000 + WINDOW_MS);
        assertTrue(poll(1000 + WINDOW_MS).isEmpty(), "an LP rise in the window is still open");
        List<DiscoveryCorrelator.Verdict> v = poll(1000 + 2 * WINDOW_MS);
        assertEquals(1, v.size());
        assertEquals(DISCOVERED, v.get(0).kind);
    }

    @Test
    void lpOutsideTheWindowIsNotPaired() {
        c.arrival(item("Fig Leaf"), 1000, true, null);
        c.lp(100, 1000 + WINDOW_MS + 1);
        List<DiscoveryCorrelator.Verdict> v = poll(10_000);
        assertEquals(2, v.size());
        assertTrue(v.stream().anyMatch(x -> x.kind == UNATTRIBUTED));
        assertTrue(v.stream().anyMatch(x -> x.kind == KNOWN));
    }

    @Test
    void ownedMoveIsIgnored() {
        c.departure("gfx/invobjs/silk-moth", 990, true);
        c.arrival(item("Silk Moth"), 1000, true, null);
        assertTrue(poll(5000).isEmpty());
    }

    @Test
    void foreignTransferIsReceivedEvenAfterAGobClick() {
        c.departure("gfx/invobjs/silk-moth", 1010, false);
        c.arrival(item("Silk Moth"), 1000, true, null);
        assertEquals(HELD, poll(5000).get(0).kind);
    }

    @Test
    void oneDepartureCancelsOnlyOneArrival() {
        // A stack forming: the loose item leaves, it and the new one arrive inside the stack.
        c.departure("gfx/invobjs/fig-leaf", 1000, true);
        c.arrival(item("Fig Leaf"), 1005, true, null);
        c.arrival(item("Fig Leaf"), 1006, true, null);
        c.lp(100, 1100);
        List<DiscoveryCorrelator.Verdict> v = poll(5000);
        assertEquals(1, v.size());
        assertEquals(DISCOVERED, v.get(0).kind);
    }

    @Test
    void dropInLpIsNotAnEvent() {
        c.lp(-500, 1000);
        assertTrue(c.idle());
    }
}
