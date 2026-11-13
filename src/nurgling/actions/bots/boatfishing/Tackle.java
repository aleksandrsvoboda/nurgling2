package nurgling.actions.bots.boatfishing;

import haven.Coord;
import haven.res.ui.stackinv.ItemStack;
import haven.WItem;
import haven.Widget;
import nurgling.NGItem;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.tools.NAlias;
import nurgling.widgets.NEquipory;

import java.util.ArrayList;
import java.util.List;

/** What the player carries: equipped creels (pouch slots, 0-2 of them) and the main inventory.
 *  Everything is read fresh on each call. Methods marked UI-safe only walk widgets and may run inside an NTask. */
final class Tackle {
    /** Pouch slots; NEquipory calls them LFOOT/RFOOT. */
    private static final int[] POUCHES = {NEquipory.Slots.LFOOT.idx, NEquipory.Slots.RFOOT.idx};

    private Tackle() {}

    static final class Found {
        final WItem item;
        final NInventory from;

        Found(WItem item, NInventory from) {
            this.item = item;
            this.from = from;
        }
    }

    /** Equipped creels' inventories. UI-safe. */
    static List<NInventory> creels() {
        List<NInventory> res = new ArrayList<>();
        NEquipory eq = NUtils.getEquipment();
        if (eq == null)
            return res;
        for (int idx : POUCHES) {
            WItem w = eq.quickslots[idx];
            if (w == null)
                continue;
            String n = ((NGItem) w.item).name();
            if (n != null && n.equals("Creel") && w.item.contents instanceof NInventory)
                res.add((NInventory) w.item.contents);
        }
        return res;
    }

    /** Creels first, then the main inventory. UI-safe. */
    static List<NInventory> all() {
        List<NInventory> res = creels();
        NInventory inv = NUtils.getGameUI().getInventory();
        if (inv != null)
            res.add(inv);
        return res;
    }

    /** First item matching {@code alias} in the creels or the inventory, or null. */
    static Found find(NAlias alias) throws InterruptedException {
        for (NInventory inv : all()) {
            WItem w = inv.getItem(alias);
            if (w != null)
                return new Found(w, inv);
        }
        return null;
    }

    /** Items called {@code name} in the main inventory only (bait is only taken from there). */
    static int countInInventory(String name) throws InterruptedException {
        return NUtils.getGameUI().getInventory().getItems(new NAlias(name)).size();
    }

    /** Room for one more item of {@code size} in any creel or the inventory. UI-safe. */
    static boolean hasRoom(Coord size) {
        for (NInventory inv : all()) {
            if (inv.calcNumberFreeCoord(size) > 0)
                return true;
        }
        return false;
    }

    /** A number that changes whenever an item arrives anywhere, stacked items included. UI-safe. */
    static int itemCount() {
        int n = 0;
        for (NInventory inv : all()) {
            for (Widget w = inv.child; w != null; w = w.next) {
                if (w instanceof WItem) {
                    WItem wi = (WItem) w;
                    if (wi.item.contents instanceof ItemStack)
                        n += ((ItemStack) wi.item.contents).wmap.size();
                    else
                        n++;
                }
            }
        }
        return n;
    }

    /** Puts whatever is in hand into {@code preferred}, else any other carried container. True when the hand is empty. */
    static boolean emptyHand(NInventory preferred) throws InterruptedException {
        if (NUtils.getGameUI().vhand == null)
            return true;
        List<NInventory> order = new ArrayList<>();
        if (preferred != null)
            order.add(preferred);
        for (NInventory inv : all())
            if (inv != preferred)
                order.add(inv);
        for (NInventory inv : order) {
            WItem hand = NUtils.getGameUI().vhand;
            if (hand == null)
                return true;
            Coord dc = inv.findFreeCoord(hand);
            if (dc != null)
                inv.dropOn(dc);
        }
        return NUtils.getGameUI().vhand == null;
    }
}
