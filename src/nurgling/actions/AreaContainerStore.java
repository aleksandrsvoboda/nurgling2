package nurgling.actions;

import haven.Gob;
import haven.WItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The containers of a storage area that may be far from where the bot works (e.g. the
 * champignon store across the base). Every trip goes there by global navigation and comes
 * back to the work area afterwards, so callers never touch the store's gobs themselves.
 *
 * Kinds are passed in preference order: the first kind is the first one taken out and the
 * first one kept back from a deposit.
 */
public class AreaContainerStore {
    private final NArea store;
    private final NArea home;

    public AreaContainerStore(NArea store, NArea home) {
        this.store = store;
        this.home = home;
    }

    /** How many of these kinds the player holds (stack members counted one by one). */
    public static int count(NGameUI gui, List<NAlias> kinds) throws InterruptedException {
        int n = 0;
        for (NAlias kind : kinds)
            n += gui.getInventory().getItems(kind).size();
        return n;
    }

    /**
     * Puts every item of these kinds into the store, except {@code keep} of them, which stay
     * in the inventory - taken from the first kinds first. Makes no trip when there is
     * nothing to put away. Stops the bot if the store is unreachable or full.
     */
    public void deposit(NGameUI gui, List<NAlias> kinds, int keep) throws InterruptedException {
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        int[] keepOf = splitKeep(gui, kinds, keep);
        if (remaining(gui, kinds, keepOf) == 0)
            return;

        goTo(gui, store);
        for (Container container : containers(gui)) {
            if (remaining(gui, kinds, keepOf) == 0)
                break;
            // Whole kinds first; at most one kind is partly kept back, and moving it closes
            // the container, so it goes last to keep this to a single open.
            for (int k = 0; k < kinds.size(); k++) {
                if (keepOf[k] == 0 && !gui.getInventory().getItems(kinds.get(k)).isEmpty())
                    new TransferToContainer(container, kinds.get(k)).run(gui);
            }
            for (int k = 0; k < kinds.size(); k++) {
                if (keepOf[k] == 0)
                    continue;
                ArrayList<WItem> held = gui.getInventory().getItems(kinds.get(k));
                int toPut = held.size() - keepOf[k];
                if (toPut > 0)
                    new TransferWItemsToContainer(container, new ArrayList<>(held.subList(0, toPut))).run(gui);
            }
            new CloseTargetContainer(container).run(gui);
        }
        if (remaining(gui, kinds, keepOf) > 0) {
            gui.error("The containers in " + store.name + " are full");
            throw new InterruptedException();
        }
        goTo(gui, home);
    }

    /**
     * Takes up to {@code wanted} items from the store, first kinds first, as far as the
     * inventory has room. Returns how many were taken (0 when the store has none).
     */
    public int take(NGameUI gui, List<NAlias> kinds, int wanted) throws InterruptedException {
        if (wanted <= 0)
            return 0;
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        int before = count(gui, kinds);

        goTo(gui, store);
        for (Container container : containers(gui)) {
            int left = wanted - (count(gui, kinds) - before);
            if (left <= 0 || gui.getInventory().getFreeSpace() <= 0)
                break;
            if (Container.pathTo(gui, container) == null)
                continue;
            new OpenTargetContainer(container).run(gui);
            for (NAlias kind : kinds) {
                left = wanted - (count(gui, kinds) - before);
                if (left <= 0 || gui.getInventory().getFreeSpace() <= 0)
                    break;
                new TakeAvailableItemsFromContainer(container, kind, left).run(gui);
            }
            new CloseTargetContainer(container).run(gui);
        }
        goTo(gui, home);
        return count(gui, kinds) - before;
    }

    /** How many of each kind to keep back so that {@code keep} items stay, first kinds first. */
    private static int[] splitKeep(NGameUI gui, List<NAlias> kinds, int keep) throws InterruptedException {
        int[] keepOf = new int[kinds.size()];
        for (int k = 0; k < kinds.size() && keep > 0; k++) {
            keepOf[k] = Math.min(keep, gui.getInventory().getItems(kinds.get(k)).size());
            keep -= keepOf[k];
        }
        return keepOf;
    }

    private static int remaining(NGameUI gui, List<NAlias> kinds, int[] keepOf) throws InterruptedException {
        int n = 0;
        for (int k = 0; k < kinds.size(); k++)
            n += Math.max(0, gui.getInventory().getItems(kinds.get(k)).size() - keepOf[k]);
        return n;
    }

    // A failed navigation must not turn into "no containers here" (the area just isn't
    // loaded), so it stops the bot instead.
    private static void goTo(NGameUI gui, NArea area) throws InterruptedException {
        if (!NUtils.navigateToArea(area, true)) {
            gui.error("Can't reach " + area.name);
            throw new InterruptedException();
        }
    }

    private ArrayList<Container> containers(NGameUI gui) throws InterruptedException {
        ArrayList<Gob> gobs = Finder.findGobs(store.getRCArea(), new NAlias(new ArrayList<>(NContext.contcaps.keySet())));
        Gob player = NUtils.player();
        if (player != null)
            gobs.sort(Comparator.comparingDouble(g -> g.rc.dist(player.rc)));
        ArrayList<Container> result = new ArrayList<>();
        for (Gob gob : gobs) {
            Container container = new Container(gob, NContext.contcaps.get(gob.ngob.name), store);
            container.initattr(Container.Space.class);
            result.add(container);
        }
        if (result.isEmpty()) {
            gui.error("No containers found in " + store.name);
            throw new InterruptedException();
        }
        return result;
    }
}
