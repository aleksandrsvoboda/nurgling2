package nurgling.actions.bots.areamover;

import haven.Coord2d;
import haven.Gob;
import haven.Pair;
import haven.WItem;
import nurgling.NGameUI;
import nurgling.NISBox;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.CloseTargetContainer;
import nurgling.actions.CloseTargetWindow;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.PathFinder;
import nurgling.actions.PileMaker;
import nurgling.actions.Results;
import nurgling.actions.TransferToContainer;
import nurgling.areas.NArea;
import nurgling.tasks.WaitFreeHand;
import nurgling.actions.WaitStockpile;
import nurgling.tasks.WaitTargetSize;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.StackSupporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One unloading leg of the Area Mover: walk to Y and put the cargo into piles that already hold
 * the same item, then into storage containers, then into new piles. TransferToPiles is not used:
 * it puts into every stockpile in the area, and a pile of another item rejects the put and leaves
 * it waiting forever, so this side checks what a pile holds before putting anything in.
 */
class UnloadToArea implements Action {
    /** Model attribute of a stockpile with no room left. */
    private static final int PILE_FULL = 31;

    private final NArea area;
    private final Cargo cargo;
    private final Set<String> stockpileable;
    private final boolean createPiles;

    /** What each pile in Y holds (item resource name), learned on first open or when we build it. */
    private final Map<Long, String> pileContents = new HashMap<>();
    /** Containers found full this run, by gob hash. */
    private final Set<String> full = new HashSet<>();
    /** Set once Y has no free spot for another pile. */
    private boolean noPileSpace = false;

    int pilesBuilt = 0;

    UnloadToArea(NArea area, Cargo cargo, Set<String> stockpileable, boolean createPiles) {
        this.area = area;
        this.cargo = cargo;
        this.stockpileable = stockpileable;
        this.createPiles = createPiles;
    }

    /** @return SUCCESS when everything carried was put away, FAIL when Y ran out of room. */
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // Ensure presence: new pile spots are chosen from what is loaded, so all of Y must be.
        Pair<Coord2d, Coord2d> rc = AreaScan.reach(area, true);
        if (rc == null)
            return Results.ERROR("Area Mover: could not reach destination area " + area.name);

        for (Gob pile : AreaScan.piles(rc)) {
            if (cargo.total(gui) == 0)
                break;
            putIntoExistingPile(gui, pile);
        }

        for (Gob gob : AreaScan.storage(rc)) {
            if (cargo.total(gui) == 0)
                break;
            if (!full.contains(gob.ngob.hash) && !gob.ngob.isContainerFull())
                putIntoContainer(gui, gob);
        }

        if (createPiles) {
            for (String name : new ArrayList<>(cargo.current(gui).keySet())) {
                if (noPileSpace)
                    break;
                if (stockpileable.contains(name))
                    putIntoNewPiles(gui, rc, name);
            }
        }

        return cargo.total(gui) == 0 ? Results.SUCCESS() : Results.FAIL();
    }

    private void putIntoExistingPile(NGameUI gui, Gob cached) throws InterruptedException {
        Gob gob = Finder.findGob(cached.id);
        if (gob == null || gob.ngob.getModelAttribute() == PILE_FULL)
            return;
        String known = pileContents.get(gob.id);
        if (known != null && carriedNameFor(gui, known) == null)
            return;
        // Skip what we cannot walk to: clicking it from afar opens nothing, and the open waits forever.
        if (!PathFinder.isAvailable(gob) || !new PathFinder(gob).run(gui).IsSuccess())
            return;
        new OpenTargetContainer("Stockpile", gob).run(gui);
        NISBox box = gui.getStockpile();
        if (box != null) {
            String holds = Cargo.waitResName(box.itemres);
            pileContents.put(gob.id, holds);
            String name = (holds == null) ? null : carriedNameFor(gui, holds);
            if (name != null)
                fillOpenPile(gui, name);
        }
        closeStockpile(gui);
    }

    private void putIntoContainer(NGameUI gui, Gob gob) throws InterruptedException {
        if (!PathFinder.isAvailable(gob))
            return;
        if (!new PathFinder(gob).run(gui).IsSuccess())
            return;
        Container container = new Container(gob, AreaScan.cap(gob), area);
        container.initattr(Container.Space.class);
        new OpenTargetContainer(container).run(gui);

        NInventory target = gui.getInventory(container.cap);
        if (target != null) {
            for (Map.Entry<String, Integer> e : cargo.current(gui).entrySet()) {
                // Only the carried count of a name goes in, so same-name items the player
                // started with stay in the inventory.
                int left = e.getValue();
                while (left > 0) {
                    ArrayList<WItem> items = Cargo.items(gui, e.getKey());
                    if (items.isEmpty() || target.getNumberFreeCoord(items.get(0)) <= 0)
                        break;
                    int moved = TransferToContainer.transfer(items.get(0), target, left);
                    if (moved <= 0)
                        break;
                    left -= moved;
                }
            }
            container.update();
            if (target.getFreeSpace() == 0)
                full.add(gob.ngob.hash);
        }
        new CloseTargetContainer(container).run(gui);
    }

    private void putIntoNewPiles(NGameUI gui, Pair<Coord2d, Coord2d> rc, String name) throws InterruptedException {
        while (cargo.count(gui, name) > 0) {
            ArrayList<WItem> items = Cargo.items(gui, name);
            if (items.isEmpty())
                return;
            String res = Cargo.resName(items.get(0));

            closeStockpile(gui);
            PileMaker maker = new PileMaker(rc, name, new NAlias("stockpile"), 0);
            if (!maker.run(gui).IsSuccess()) {
                abandonPlacement(gui);
                noPileSpace = true;
                return;
            }
            Gob pile = maker.getPile();
            pilesBuilt++;
            if (res != null)
                pileContents.put(pile.id, res);
            // Placing the pile opens it. Fill it; every pass placed at least one item, so this ends.
            if (gui.getStockpile() != null)
                fillOpenPile(gui, name);
            closeStockpile(gui);
        }
    }

    /** Puts as many carried items of this name as the open pile takes. @return items put. */
    private int fillOpenPile(NGameUI gui, String name) throws InterruptedException {
        NUtils.addTask(new WaitStockpile(true));
        NISBox box = gui.getStockpile();
        int n = Math.min(cargo.count(gui, name), box.getFreeSpace());
        if (n <= 0)
            return 0;
        int fullSize = gui.getInventory().getItems().size();
        if (StackSupporter.isSameExistExact(name, gui.getInventory())) {
            // The batched put lets the server pick which items leave; with a sibling of the same
            // stacking category aboard it could pick the wrong one, so go one item at a time.
            for (int i = 0; i < n; i++) {
                ArrayList<WItem> items = Cargo.items(gui, name);
                if (items.isEmpty())
                    break;
                NUtils.takeItemToHand(items.get(0));
                gui.getStockpile().wdgmsg("drop");
                NUtils.addTask(new WaitFreeHand());
            }
        } else {
            box.put(n);
        }
        NUtils.getUI().core.addTask(new WaitTargetSize(gui.getInventory(), fullSize - n));
        return n;
    }

    /** The carried item name whose resource is this one, or null if none. */
    private String carriedNameFor(NGameUI gui, String resName) throws InterruptedException {
        for (String name : cargo.current(gui).keySet()) {
            ArrayList<WItem> items = Cargo.items(gui, name);
            if (!items.isEmpty() && resName.equals(Cargo.resName(items.get(0))))
                return name;
        }
        return null;
    }

    /** PileMaker leaves the placement ghost up and the item in hand when it finds no spot. */
    private static void abandonPlacement(NGameUI gui) throws InterruptedException {
        if (gui.map.placing != null) {
            gui.map.placing.cancel();
            gui.map.placing = null;
        }
        if (gui.vhand != null) {
            NUtils.dropToInv();
            NUtils.addTask(new WaitFreeHand());
        }
    }

    private static void closeStockpile(NGameUI gui) throws InterruptedException {
        if (gui.getStockpile() != null)
            new CloseTargetWindow(gui.getWindow("Stockpile")).run(gui);
    }
}
