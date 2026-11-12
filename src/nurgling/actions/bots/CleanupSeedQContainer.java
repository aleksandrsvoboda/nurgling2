package nurgling.actions.bots;

import haven.Coord;
import haven.Gob;
import haven.WItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.tasks.WaitFreeHand;
import nurgling.tools.Container;
import nurgling.areas.NContext;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.List;

public class CleanupSeedQContainer implements Action {
    NArea seed;
    NAlias iseed;
    NArea trougha;

    // Crops that can't go to a trough (champignons: containers only) move their low-quality
    // overflow to a container store instead. Null for every trough-cleaned crop.
    AreaContainerStore overflow = null;
    List<NAlias> overflowKinds = null;

    public CleanupSeedQContainer(NArea seed, NAlias iseed, NArea trougha) {
        this.seed = seed;
        this.iseed = iseed;
        this.trougha = trougha;
    }

    public CleanupSeedQContainer(NArea seed, NAlias iseed, AreaContainerStore overflow, List<NAlias> overflowKinds) {
        this(seed, iseed, null);
        this.overflow = overflow;
        this.overflowKinds = overflowKinds;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        ArrayList<Container> containers = new ArrayList<>();
        for (Gob sm : Finder.findGobs(seed.getRCArea(), new NAlias(new ArrayList<>(NContext.contcaps.keySet())))) {
            Container cand = new Container(sm, NContext.contcaps.get(sm.ngob.name), null);
            cand.initattr(Container.Space.class);
            containers.add(cand);
        }

        if (containers.isEmpty())
            throw new RuntimeException("No container found in seed area!");
        Container container = containers.get(0);

        Gob containerGob = Finder.findGob(container.gobid);

        new PathFinder(containerGob).run(gui);

        new OpenTargetContainer(container).run(gui);

        // Get container capacity
        Coord containerSize = gui.getInventory(container.cap).isz;
        int containerCapacity = containerSize.x * containerSize.y;

        combineSmallSeedStacks(gui, container.cap, iseed);

        // Get all seeds in the container
        ArrayList<WItem> seeds = gui.getInventory(container.cap).getItems(iseed);

        if (seeds.size() <= containerCapacity / 2) {
            new CloseTargetContainer(container).run(gui);
            return Results.SUCCESS();
        }

        while (seeds.size() > containerCapacity / 2) {
            // The trough is a best-effort target: RedOnionFarmerQ registers it as optional and
            // ignores our result, and HarvestCrop treats an empty trough list as normal. If none
            // is in loaded range, or every one is full, stop cleanup gracefully instead of
            // crashing the whole farmer run. Checked before fetching so we never orphan seeds in
            // the inventory.
            ArrayList<Gob> troughs = overflow != null ? new ArrayList<>() : TransferToTrough.findTroughs(trougha);
            if (overflow == null && !TransferToTrough.hasRoom(troughs))
                break;

            int fetchCount = Math.min(seeds.size() - (containerCapacity / 2), gui.getInventory().getFreeSpace());

            // No free inventory space to pull seeds into - nothing more can be moved this pass.
            // Guards against fetchCount == 0 both crashing (empty transfer) and looping forever.
            if (fetchCount <= 0)
                break;

            // Fetch low seeds to inventory
            new TakeAvailableItemsFromContainer(container, iseed, fetchCount, NInventory.QualityType.Low).run(gui);

            // Nothing actually landed in the inventory (e.g. no free coord of the seed's shape) -
            // stop rather than issuing an empty transfer or spinning.
            if (gui.getInventory().getItems(iseed).isEmpty())
                break;

            if (overflow != null) {
                // Store containers are found by window caption, so the quality container
                // (often the same kind of chest) must not stay open during the trip.
                new CloseTargetContainer(container).run(gui);
                overflow.deposit(gui, overflowKinds, 0);
            }
            else
                new TransferToTrough(troughs, iseed).run(gui);

            new PathFinder(containerGob).run(gui);

            new OpenTargetContainer(container).run(gui);

            seeds = gui.getInventory(container.cap).getItems(iseed);
        }

        new CloseTargetContainer(container).run(gui);

        return Results.SUCCESS();
    }

    private void combineSmallSeedStacks(NGameUI gui, String containerCap, NAlias iseed) throws InterruptedException {
        while (true) {
            ArrayList<WItem> seeds = gui.getInventory(containerCap).getItems(iseed);
            ArrayList<WItem> smallSeeds = new ArrayList<>();
            ArrayList<Integer> amounts = new ArrayList<>();
            for (WItem seed : seeds) {
                int amount = -1;
                if (seed.item.info != null) {
                    for (haven.ItemInfo info : seed.item.info) {
                        if (info instanceof haven.GItem.Amount) {
                            amount = ((haven.GItem.Amount) info).itemnum();
                            break;
                        }
                    }
                }
                if (amount > 0 && amount < 5) {
                    smallSeeds.add(seed);
                    amounts.add(amount);
                }
            }
            if (smallSeeds.size() < 2) break;

            int total = 0;
            for (int amt : amounts) total += amt;
            if (total < 5) break;

            int combined = amounts.get(0);
            WItem target = smallSeeds.get(0);
            for (int i = 1; i < smallSeeds.size(); i++) {
                int nextAmt = amounts.get(i);
                if (combined + nextAmt <= 50) {
                    NUtils.takeItemToHand(smallSeeds.get(i));
                    NUtils.itemact(target);
                    NUtils.addTask(new WaitFreeHand());
                    combined += nextAmt;
                }
                if (combined >= 5) break;
            }
        }
    }

}
