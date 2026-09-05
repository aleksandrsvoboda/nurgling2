package nurgling.actions;

import haven.Coord;
import haven.Gob;
import haven.WItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.StackSupporter;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Deposits items to containers in a specialized area.
 * Uses Container.ItemCount updater to track specific item counts per container.
 *
 * Algorithm:
 * 1. Scan all containers, calculate total needed items using ItemCount updater
 * 2. Fetch-Distribute Loop (makes multiple trips until done):
 *    a. Calculate how many items still needed across all containers
 *    b. Fetch what fits in inventory from source
 *    c. Distribute to containers until inventory empty
 *    d. Repeat until all containers full OR source exhausted
 */
public class DepositItemsToSpecArea implements Action {

    private final NContext context;
    private final NAlias itemAlias;
    private final Specialisation.SpecName destinationSpec;
    private final int maxPerContainer;
    private Specialisation.SpecName originSpec = null;
    private NAlias fetchAlias = null;

    private Map<Long, Integer> containerFreeSpaceMap = new HashMap<>();

    public DepositItemsToSpecArea(NContext context, NAlias itemAlias, Specialisation.SpecName destinationSpec, int maxPerContainer) {
        this.context = context;
        this.itemAlias = itemAlias;
        this.destinationSpec = destinationSpec;
        this.maxPerContainer = maxPerContainer;
    }

    public DepositItemsToSpecArea(NContext context, NAlias itemAlias, Specialisation.SpecName destinationSpec, Specialisation.SpecName originSpec, int maxPerContainer) {
        this.context = context;
        this.itemAlias = itemAlias;
        this.destinationSpec = destinationSpec;
        this.maxPerContainer = maxPerContainer;
        this.originSpec = originSpec;
    }

    /**
     * Narrow what is fetched from the source, without narrowing what is counted toward
     * maxPerContainer. Defaults to the item alias.
     * <p>
     * The two are not always the same. A container's target can be met by several item kinds
     * while only some of them ever exist at the source: a silkmoth breeding cupboard's 16 slots
     * hold cocoons OR the moths they hatch into, so moths must be COUNTED - they occupy a slot -
     * but they only ever appear inside the breeding cupboards themselves. Leaving them in the
     * fetch list sent the bot touring the whole feeding area for them on every single trip.
     */
    public DepositItemsToSpecArea setFetchAlias(NAlias fetchAlias) {
        this.fetchAlias = fetchAlias;
        return this;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        gui.msg("DepositItems: Starting. Item=" + itemAlias + ", maxPerContainer=" + maxPerContainer);
        
        // Get the destination area
        NArea area = context.goToArea(destinationSpec);
        if (area == null) return Results.ERROR("Destination spec area not found!");

        // Get all containers in this area (cupboards, troughs, etc)
        ArrayList<Gob> gobs = Finder.findGobs(area, new NAlias(new ArrayList<>(NContext.contcaps.keySet())));
        ArrayList<Container> containers = new ArrayList<>();
        for (Gob gob : gobs) {
            Container c = new Container(gob, NContext.contcaps.get(gob.ngob.name), area);
            // Initialize ItemCount updater with our target item and max count
            c.initItemCount(itemAlias, maxPerContainer);
            // Also initialize Space for free space tracking
            c.initattr(Container.Space.class);
            containers.add(c);
        }
        if (containers.isEmpty()) return Results.ERROR("No containers in target area!");
        
        gui.msg("DepositItems: Found " + containers.size() + " containers in area");

        // Step 1: Scan all containers and calculate total needed items
        int totalNeeded = 0;
        ArrayList<Container> containersNeedingItems = new ArrayList<>();
        int containerIndex = 0;

        for (Container container : containers) {
            containerIndex++;
            new PathFinder(Finder.findGob(container.gobid)).run(gui);
            new OpenTargetContainer(container).run(gui);

            // Update the ItemCount updater (counts specific items)
            Container.ItemCount itemCount = container.getattr(Container.ItemCount.class);
            itemCount.update();
            
            // Also update Space for free space tracking
            Container.Space space = container.getattr(Container.Space.class);
            space.update();

            int currentCount = itemCount.getCurrentCount();
            int needed = itemCount.getNeeded();
            int freeSpace = space.getFreeSpace();
            
            gui.msg("DepositItems: Container #" + containerIndex + " [gob=" + container.gobid + "]: current=" + currentCount + ", target=" + maxPerContainer + ", needed=" + needed + ", freeSpace=" + freeSpace);
            
            // Store free space for external access. Stays in CELLS - callers size non-stacking
            // items (silkworms) off it, so converting here would break them.
            containerFreeSpaceMap.put(container.gobid, freeSpace);

            // Only add if we need items AND have space
            if (needed > 0 && freeSpace > 0) {
                /* Ask for what will actually be deposited. TransferToContainer caps itself on
                 * ItemCount.getNeeded() and never looks at free cells, so clamping the plan by
                 * cells - even converted to items - just under-fetches: a cupboard reporting one
                 * free cell took ten more leaves, because a part-filled stack absorbs them without
                 * using a cell at all. Planning and execution now use the same number. */
                int canAdd = needed;
                totalNeeded += canAdd;
                containersNeedingItems.add(container);
                gui.msg("DepositItems: Container #" + containerIndex + " NEEDS " + canAdd + " items (added to fill list)");
            } else {
                gui.msg("DepositItems: Container #" + containerIndex + " SKIPPED (needed=" + needed + ", freeSpace=" + freeSpace + ")");
            }

            new CloseTargetContainer(container).run(gui);
        }

        gui.msg("DepositItems: TOTAL NEEDED = " + totalNeeded + ", containers to fill = " + containersNeedingItems.size());

        if (totalNeeded == 0) {
            gui.msg("DepositItems: All containers are full, nothing to do");
            return Results.SUCCESS(); // All containers are already filled
        }

        // Step 2: Register items in context
        for (String key : this.itemAlias.getKeys()) {
            context.addInItem(key, null);
        }

        // Step 3-4: Fetch-Distribute Loop (multiple trips until done or source empty)
        int tripNumber = 0;
        NAlias sourceAlias = (fetchAlias != null) ? fetchAlias : itemAlias;
        /* Per item name, the source containers we have already emptied. Kept across trips so
         * later trips do not walk back to storages with nothing left in them. Safe here because
         * a deposit's source area is never its destination area, so nothing refills them
         * while we run. One set per item: a cupboard out of cocoons may still be full of leaves. */
        Map<String, Set<String>> depletedSources = new HashMap<>();

        /* Rank each source item across the WHOLE origin area once, so the fetch below takes the
         * best copies rather than whichever container happens to be opened first - it matters most
         * for silkworm cocoons, where the moths they hatch into set the quality of every egg, worm
         * and cocoon after them. Same contract FillContainers2 relies on: the cut-off is the
         * totalNeeded-th best quality, so every copy clearing it belongs in the result and the
         * greedy per-container fetch stays globally correct without re-walking anything.
         *
         * Costs one pass over the source CONTAINERS, once per deposit. Pile-fed items (mulberry
         * leaves) are unaffected - the scan skips stockpiles without visiting them, and a null
         * cut-off then means "no filtering", exactly the old behaviour. */
        Map<String, Float> minQualities = new HashMap<>();
        for (String key : sourceAlias.getKeys()) {
            FindQualityThreshold scan = (this.originSpec != null)
                    ? new FindQualityThreshold(context, key, totalNeeded, originSpec)
                    : new FindQualityThreshold(context, key, totalNeeded);
            scan.run(gui);
            Float cutoff = scan.getThreshold();
            if (cutoff == null)
                continue;
            minQualities.put(key, cutoff);
            // Containers proven to hold nothing that good are not worth walking to at all.
            depletedSources.computeIfAbsent(key, k -> new HashSet<>()).addAll(scan.getWithoutEligible());
            gui.msg("DepositItems: taking " + key + " of q" + String.format("%.1f", cutoff) + " and above");
        }

        while (!containersNeedingItems.isEmpty()) {
            tripNumber++;

            // Calculate how many items still needed across all containers
            int totalStillNeeded = 0;
            for (Container container : containersNeedingItems) {
                Container.ItemCount itemCount = container.getattr(Container.ItemCount.class);
                Container.Space space = container.getattr(Container.Space.class);
                int needed = itemCount.getNeeded();
                int freeSpace = space.getFreeSpace();
                totalStillNeeded += needed;
            }

            if (totalStillNeeded == 0) {
                gui.msg("DepositItems: All containers are now full");
                break;
            }

            gui.msg("DepositItems: Trip #" + tripNumber + " - fetching up to " + totalStillNeeded + " items from source...");

            // Fetch items from source
            for (String key : sourceAlias.getKeys()) {
                int currentInInventory = gui.getInventory().getItems(itemAlias).size();
                int stillNeeded = totalStillNeeded - currentInInventory;
                if (stillNeeded <= 0) break;

                /* Ask for one load, never the whole area's demand. TakeItems2 keeps walking
                 * source storages until it holds `count` items, so a count the inventory can
                 * never reach makes it tour every source container on every trip - with a full
                 * inventory - before we ever get to unload. This is the same clamp
                 * FillContainers2 already applies around its own TakeItems2 call. */
                int room = StackSupporter.getOptimalItemCapacity(gui.getInventory(), key, new Coord(1, 1), stillNeeded);
                if (room <= 0) {
                    gui.msg("DepositItems: Inventory full, distributing before fetching more");
                    break;
                }

                // TakeItems2 counts what is already held of this single key, so aim at that plus room.
                int keyInInventory = gui.getInventory().getItems(new NAlias(key)).size();
                gui.msg("DepositItems: Taking " + room + " of '" + key + "'");

                TakeItems2 take = (this.originSpec != null)
                        ? new TakeItems2(context, key, keyInInventory + room, originSpec, NInventory.QualityType.High)
                        : new TakeItems2(context, key, keyInInventory + room, NInventory.QualityType.High);
                take.depleted = depletedSources.computeIfAbsent(key, k -> new HashSet<>());
                take.minQuality = minQualities.get(key);
                take.run(gui);
            }

            int itemsFetched = gui.getInventory().getItems(itemAlias).size();
            gui.msg("DepositItems: Trip #" + tripNumber + " - fetched " + itemsFetched + " items");

            if (itemsFetched == 0) {
                gui.msg("DepositItems: No items available from source, stopping");
                break;
            }

            // Distribute items to containers
            gui.msg("DepositItems: Trip #" + tripNumber + " - distributing to " + containersNeedingItems.size() + " containers...");
            ArrayList<Container> stillNeedingItems = new ArrayList<>();
            int fillIndex = 0;
            int carriedBeforeDistribute = gui.getInventory().getItems(itemAlias).size();

            for (Container container : containersNeedingItems) {
                fillIndex++;
                ArrayList<WItem> itemsInInventory = gui.getInventory().getItems(itemAlias);

                if (itemsInInventory.isEmpty()) {
                    // No more items in inventory, but this container still needs - add to next trip
                    stillNeedingItems.add(container);
                    gui.msg("DepositItems: Container #" + fillIndex + " [gob=" + container.gobid + "] - no items left, will try next trip");
                    continue;
                }

                gui.msg("DepositItems: Fill container #" + fillIndex + " [gob=" + container.gobid + "], items in inventory=" + itemsInInventory.size());

                // Refresh the area context
                context.goToArea(destinationSpec);

                // Transfer items to this container
                // TransferToContainer will open container, call ItemCount.update(), and limit transfer to getNeeded()
                new TransferToContainer(container, itemAlias).run(gui);

                // Update container info after transfer (container should still be open from TransferToContainer)
                Container.ItemCount itemCount = container.getattr(Container.ItemCount.class);
                Container.Space space = container.getattr(Container.Space.class);
                /* isReady() only says the attribute was initialised during the scan, not that the
                 * window is open now. TransferToContainer returns without opening anything when
                 * there is nothing matching to move or no room, and Space.update() then calls
                 * getFreeSpace() on a null inventory and kills the bot. Keep the last known value
                 * instead - the reads below use the cached figure and cope with it being stale. */
                if (space.isReady() && gui.getInventory(container.cap) != null) {
                    space.update();
                    containerFreeSpaceMap.put(container.gobid, space.getFreeSpace());
                }

                // ItemCount was updated by TransferToContainer, get current values
                int afterCount = itemCount.getCurrentCount();
                int afterNeeded = itemCount.getNeeded();
                int afterFreeSpace = space.getFreeSpace();
                gui.msg("DepositItems: Container #" + fillIndex + " after transfer: count=" + afterCount + ", stillNeeded=" + afterNeeded + ", freeSpace=" + afterFreeSpace);

                // Check if container still needs more items
                boolean isFull = itemCount.isFull() || afterFreeSpace == 0;
                if (isFull) {
                    gui.msg("DepositItems: Container #" + fillIndex + " is now FULL");
                } else if (afterNeeded > 0) {
                    // Container still needs items - add to next trip
                    stillNeedingItems.add(container);
                    gui.msg("DepositItems: Container #" + fillIndex + " still needs " + afterNeeded + " items, will try next trip");
                }

                new CloseTargetContainer(container).run(gui);
            }

            // Update list for next iteration
            containersNeedingItems = stillNeedingItems;

            gui.msg("DepositItems: Trip #" + tripNumber + " complete. Containers still needing items: " + containersNeedingItems.size());

            /* Nothing left the inventory this trip, yet we are still carrying items: the
             * remaining containers cannot accept what we hold, so another trip would replay
             * this one forever. */
            if (gui.getInventory().getItems(itemAlias).size() >= carriedBeforeDistribute) {
                gui.msg("DepositItems: Trip #" + tripNumber + " moved nothing, stopping");
                break;
            }
        }

        int remainingItems = gui.getInventory().getItems(itemAlias).size();
        gui.msg("DepositItems: DONE after " + tripNumber + " trip(s). Remaining items in inventory: " + remainingItems);

        return Results.SUCCESS();
    }

    /**
     * Getter method to access container free space mapping.
     * Used by SilkProductionBot to calculate how many silkworms can fit.
     */
    public Map<Long, Integer> getContainerFreeSpaceMap() {
        return containerFreeSpaceMap;
    }
}
