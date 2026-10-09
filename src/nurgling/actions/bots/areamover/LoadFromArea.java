package nurgling.actions.bots.areamover;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Inventory;
import haven.Pair;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NISBox;
import nurgling.NInventory;
import nurgling.actions.Action;
import nurgling.actions.CloseTargetContainer;
import nurgling.actions.CloseTargetWindow;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.actions.TakeItemsFromContainer;
import nurgling.actions.TakeItemsFromPile;
import nurgling.areas.NArea;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.StackSupporter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * One loading leg of the Area Mover: walk to X and fill the inventory from its stockpiles and
 * storage containers, nearest first. State that must survive between trips (what is finished,
 * what can never be taken, which items came out of piles) lives in this object, so the bot keeps
 * one instance for the whole run.
 */
class LoadFromArea implements Action {
    private final NArea area;
    private final Cargo cargo;

    /** Containers emptied this run, by gob hash. */
    private final Set<String> emptied = new HashSet<>();
    /** Sources that hold items we can never take (unreachable, or too big for an empty inventory). */
    private final Set<Long> stuck = new HashSet<>();
    /** Item names that came out of a pile, so a new pile of them can be built in Y. */
    final Set<String> stockpileable = new HashSet<>();

    /** Set when a scan found nothing left to take in X. */
    boolean exhausted = false;

    LoadFromArea(NArea area, Cargo cargo) {
        this.area = area;
        this.cargo = cargo;
    }

    int stuckCount() {
        return stuck.size();
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // Ensure presence: without it navigation skips the walk whenever X is merely reachable by
        // local pathfinding, and from Y the grid is loaded but X's piles and containers are not,
        // so the scan comes back empty and reads as "X is done".
        Pair<Coord2d, Coord2d> rc = AreaScan.reach(area, true);
        if (rc == null)
            return Results.ERROR("Area Mover: could not reach source area " + area.name);

        boolean probed = false;
        while (gui.getInventory().getNumberFreeCoord(new Coord(1, 1)) > 0) {
            ArrayList<Gob> candidates = new ArrayList<>();
            for (Gob gob : AreaScan.piles(rc)) {
                if (!stuck.contains(gob.id))
                    candidates.add(gob);
            }
            for (Gob gob : AreaScan.storage(rc)) {
                if (!stuck.contains(gob.id) && !emptied.contains(gob.ngob.hash) && !gob.ngob.isContainerEmpty())
                    candidates.add(gob);
            }
            Gob source = AreaScan.nearest(candidates);
            if (source == null) {
                // A large area may still hide objects past the loaded range of where we stand.
                // Look once more from its middle before calling it empty.
                if (!probed) {
                    probed = true;
                    if (AreaScan.walkToCenter(gui, rc))
                        continue;
                }
                System.out.println("[AreaMover] source " + area.name + " exhausted, stuck sources: " + stuck.size());
                exhausted = true;
                break;
            }

            int before = cargo.total(gui);
            boolean done = AreaScan.STORAGE.contains(source.ngob.name)
                    ? takeFromContainer(gui, source)
                    : takeFromPile(gui, source);
            int took = cargo.total(gui) - before;

            if (!done && took == 0) {
                // Nothing fit. With cargo aboard, unloading may free room; with none, it never will.
                if (cargo.total(gui) > 0)
                    break;
                stuck.add(source.id);
            }
        }
        return Results.SUCCESS();
    }

    /** @return true when the container has nothing left for us. */
    private boolean takeFromContainer(NGameUI gui, Gob gob) throws InterruptedException {
        if (!PathFinder.isAvailable(gob)) {
            stuck.add(gob.id);
            return true;
        }
        Container container = new Container(gob, AreaScan.cap(gob), area);
        if (!new PathFinder(gob).run(gui).IsSuccess()) {
            stuck.add(gob.id);
            return true;
        }
        new OpenTargetContainer(container).run(gui);

        NInventory inv = gui.getInventory(container.cap);
        HashSet<String> names = new HashSet<>();
        if (inv != null) {
            for (WItem item : inv.getItems()) {
                String name = ((NGItem) item.item).name();
                if (name != null)
                    names.add(name);
            }
        }
        boolean done = names.isEmpty();
        if (!done) {
            TakeItemsFromContainer take = new TakeItemsFromContainer(container, names, null);
            take.exactMatch = true;
            done = take.run(gui).IsSuccess();
        }
        new CloseTargetContainer(container).run(gui);
        if (done)
            emptied.add(gob.ngob.hash);
        return done;
    }

    /** @return true when the pile is gone or can never be taken from. */
    private boolean takeFromPile(NGameUI gui, Gob cached) throws InterruptedException {
        // Taking the last item destroys a pile, so never trust a reference from an earlier scan.
        Gob gob = Finder.findGob(cached.id);
        if (gob == null)
            return true;
        if (!PathFinder.isAvailable(gob)) {
            stuck.add(gob.id);
            return true;
        }
        if (!new PathFinder(gob).run(gui).IsSuccess()) {
            stuck.add(gob.id);
            return true;
        }
        new OpenTargetContainer("Stockpile", gob).run(gui);

        // One item first: it names the pile's contents and gives the footprint to budget the rest by.
        NISBox box = gui.getStockpile();
        if (box != null) {
            TakeItemsFromPile first = new TakeItemsFromPile(gob, box, 1);
            first.run(gui);
            if (!first.newItems().isEmpty()) {
                NGItem sample = first.newItems().get(0);
                String name = sample.name();
                if (name != null)
                    stockpileable.add(name);
                box = (Finder.findGob(gob.id) == null) ? null : gui.getStockpile();
                if (box != null && name != null) {
                    int room = StackSupporter.getOptimalItemCapacity(gui.getInventory(), name, footprint(gui, name), box.total());
                    if (room > 0)
                        new TakeItemsFromPile(gob, box, room).run(gui);
                }
            }
        }
        if (gui.getStockpile() != null)
            new CloseTargetWindow(gui.getWindow("Stockpile")).run(gui);
        return Finder.findGob(gob.id) == null;
    }

    /** Footprint in the (rows, columns) order getNumberFreeCoord expects. */
    private static Coord footprint(NGameUI gui, String name) throws InterruptedException {
        ArrayList<WItem> items = Cargo.items(gui, name);
        if (items.isEmpty())
            return new Coord(1, 1);
        return items.get(0).sz.div(Inventory.sqsz).swapXY();
    }
}
