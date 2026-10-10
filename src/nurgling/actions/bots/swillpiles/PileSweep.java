package nurgling.actions.bots.swillpiles;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Inventory;
import haven.Pair;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NISBox;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.CloseTargetWindow;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.actions.TakeItemsFromPile;
import nurgling.actions.bots.SwillItemRegistry;
import nurgling.areas.NArea;
import nurgling.tasks.NTask;
import nurgling.tools.Finder;
import nurgling.tools.StackSupporter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * One swill area's share of the run: fill the inventory from its stockpiles, nearest first.
 * Modelled on areamover.LoadFromArea.takeFromPile, piles only. The bot keeps one instance per
 * area for the whole run, so piles found stuck stay skipped across delivery trips.
 */
class PileSweep implements Action {
    private enum Outcome { DONE, FULL }

    private final NArea area;
    private final Shared shared;

    /** Piles we can never take from: unreachable, not swill, or too big for the inventory. */
    private final Set<Long> stuck = new HashSet<>();

    /** Set when a scan found no pile left to take from. */
    boolean exhausted = false;

    /** What all sweeps of one run learn about pile contents, keyed by the piled item's resource. */
    static class Shared {
        /** Resources whose item turned out not to be swill; their piles are skipped unopened. */
        final Set<String> rejected = new HashSet<>();
        /** Display names of the rejected items, for the report. */
        final Set<String> rejectedNames = new HashSet<>();
        int pilesEmptied = 0;
        int pilesSkipped = 0;
    }

    PileSweep(NArea area, Shared shared) {
        this.area = area;
        this.shared = shared;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // Ensure presence: a navigation that never walks leaves the piles unloaded and the scan empty.
        Pair<Coord2d, Coord2d> rc = SwillScan.reach(area, true);
        if (rc == null)
            return Results.ERROR("could not reach swill area " + area.name);

        boolean probed = false;
        while (true) {
            ArrayList<Gob> candidates = new ArrayList<>();
            for (Gob gob : Finder.findGobs(rc, SwillScan.PILES)) {
                if (!stuck.contains(gob.id))
                    candidates.add(gob);
            }
            Gob pile = SwillScan.nearest(candidates);
            if (pile == null) {
                // A large area may still hide piles past the loaded range of where we stand.
                if (!probed) {
                    probed = true;
                    if (SwillScan.walkToCenter(gui, rc))
                        continue;
                }
                exhausted = true;
                return Results.SUCCESS();
            }
            if (takeFromPile(gui, pile) == Outcome.FULL)
                return Results.SUCCESS();
        }
    }

    private Outcome takeFromPile(NGameUI gui, Gob cached) throws InterruptedException {
        // Taking the last item destroys a pile, so never trust a reference from an earlier scan.
        Gob gob = Finder.findGob(cached.id);
        if (gob == null)
            return Outcome.DONE;
        if (!PathFinder.isAvailable(gob) || !new PathFinder(gob).run(gui).IsSuccess()) {
            skip(gob);
            return Outcome.DONE;
        }
        new OpenTargetContainer("Stockpile", gob).run(gui);
        NISBox box = gui.getStockpile();
        if (box == null) {
            skip(gob);
            return Outcome.DONE;
        }
        String res = SwillScan.waitResName(box.itemres);
        if (res != null && shared.rejected.contains(res)) {
            close(gui);
            skip(gob);
            return Outcome.DONE;
        }

        int carried = SwillScan.countSwill(gui);
        if (gui.getInventory().getNumberFreeCoord(new Coord(1, 1)) == 0) {
            close(gui);
            return noRoom(gob, carried);
        }

        // One item first: it names the pile's contents and gives the footprint to budget the rest by.
        TakeItemsFromPile first = new TakeItemsFromPile(gob, box, 1);
        first.run(gui);
        if (first.newItems().isEmpty()) {
            close(gui);
            return noRoom(gob, carried);
        }
        String name = first.newItems().get(0).name();
        if (name == null || !SwillItemRegistry.isSwillItem(name)) {
            reject(gui, gob, res, name);
            return Outcome.DONE;
        }

        box = (Finder.findGob(gob.id) == null) ? null : gui.getStockpile();
        if (box != null) {
            int room = StackSupporter.getOptimalItemCapacity(gui.getInventory(), name, footprint(gui, name), box.total());
            if (room > 0)
                new TakeItemsFromPile(gob, box, room).run(gui);
        }
        close(gui);
        if (Finder.findGob(gob.id) == null) {
            shared.pilesEmptied++;
            return Outcome.DONE;
        }
        // The pile outlasted everything that fits.
        return Outcome.FULL;
    }

    /**
     * Nothing fit. With swill aboard a delivery frees room; without it, it never will
     * (an item too big for the free cells, or an inventory packed with the player's own things).
     */
    private Outcome noRoom(Gob gob, int carried) {
        if (carried > 0)
            return Outcome.FULL;
        skip(gob);
        return Outcome.DONE;
    }

    /** Puts the sample back when the pile still exists, and skips every pile of that item from now on. */
    private void reject(NGameUI gui, Gob gob, String res, String name) throws InterruptedException {
        if (res != null)
            shared.rejected.add(res);
        if (name != null) {
            shared.rejectedNames.add(name);
            NISBox box = (Finder.findGob(gob.id) == null) ? null : gui.getStockpile();
            if (box != null) {
                // Watch the pile's label, not the inventory: inventory queries are tasks
                // themselves and cannot run from inside a task check.
                int before = box.total();
                box.put(1);
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        NISBox now = gui.getStockpile();
                        return now == null || now.calcCount() > before;
                    }
                });
            }
        }
        System.out.println("[SwillPiles] pile of " + name + " (" + res + ") in " + area.name + " is not swill, skipped");
        close(gui);
        skip(gob);
    }

    private void skip(Gob gob) {
        if (stuck.add(gob.id))
            shared.pilesSkipped++;
    }

    private static void close(NGameUI gui) throws InterruptedException {
        if (gui.getStockpile() != null)
            new CloseTargetWindow(gui.getWindow("Stockpile")).run(gui);
    }

    private static ArrayList<WItem> items(NGameUI gui, String name) throws InterruptedException {
        ArrayList<WItem> result = new ArrayList<>();
        for (WItem item : gui.getInventory().getItems(name)) {
            if (name.equals(((NGItem) item.item).name()))
                result.add(item);
        }
        return result;
    }

    /** Footprint in the (rows, columns) order getNumberFreeCoord expects. */
    private static Coord footprint(NGameUI gui, String name) throws InterruptedException {
        ArrayList<WItem> found = items(gui, name);
        if (found.isEmpty())
            return new Coord(1, 1);
        return found.get(0).sz.div(Inventory.sqsz).swapXY();
    }
}
