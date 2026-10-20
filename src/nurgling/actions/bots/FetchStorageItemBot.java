package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.areas.NContext;
import nurgling.db.dao.StorageItemDao;
import nurgling.i18n.L10n;
import nurgling.tasks.*;
import nurgling.tools.*;
import nurgling.widgets.NStorageItemsWidget.GroupedItem;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bot that fetches items from storage containers based on database information.
 * Navigates to containers and collects specified items.
 */
public class FetchStorageItemBot implements Action {

    private final String itemName;
    private final int targetCount;
    private final List<StorageItemDao.StorageItemData> itemsToFetch;

    public static final AtomicBoolean stop = new AtomicBoolean(false);

    /**
     * Create a bot to fetch items matching the given criteria
     * @param item The grouped item to fetch
     * @param count Number of items to fetch
     * @param allItems All items from database matching this group
     */
    public FetchStorageItemBot(GroupedItem item, int count, List<StorageItemDao.StorageItemData> allItems) {
        this.itemName = item.name;
        this.targetCount = count;

        // Filter items that match the group criteria
        this.itemsToFetch = new ArrayList<>();
        for (StorageItemDao.StorageItemData data : allItems) {
            if (item.matches(data)) {
                itemsToFetch.add(data);
            }
        }
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        stop.set(false);

        if (itemsToFetch.isEmpty()) {
            gui.msg(L10n.get("storage.no_containers"), java.awt.Color.RED);
            return Results.FAIL();
        }

        // Group items by container
        Map<String, List<StorageItemDao.StorageItemData>> itemsByContainer = new LinkedHashMap<>();
        for (StorageItemDao.StorageItemData item : itemsToFetch) {
            itemsByContainer.computeIfAbsent(item.getContainer(), k -> new ArrayList<>()).add(item);
        }

        int collected = 0;
        int remaining = targetCount;

        gui.msg(L10n.get("storage.fetching_items").replace("{0}", String.valueOf(targetCount)));

        // Count items in player inventory before fetching
        int beforeCount = countItemsInInventory(gui, itemName);

        for (Map.Entry<String, List<StorageItemDao.StorageItemData>> entry : itemsByContainer.entrySet()) {
            if (stop.get()) {
                gui.msg(L10n.get("storage.fetch_cancelled"), java.awt.Color.YELLOW);
                return Results.FAIL();
            }

            if (remaining <= 0) break;

            String containerHash = entry.getKey();
            List<StorageItemDao.StorageItemData> containerItems = entry.getValue();
            // Pass actual DB records (limited by remaining) so we can match exact quality
            List<StorageItemDao.StorageItemData> toFetch = containerItems.subList(0, Math.min(containerItems.size(), remaining));

            // Try to find and navigate to container
            int fetched = fetchFromContainer(gui, containerHash, toFetch);

            if (fetched > 0) {
                collected += fetched;
                remaining -= fetched;
            }
        }

        // Verify actual items collected
        int afterCount = countItemsInInventory(gui, itemName);
        int actualCollected = afterCount - beforeCount;

        if (actualCollected > 0) {
            gui.msg(L10n.get("storage.fetch_complete").replace("{0}", String.valueOf(actualCollected)), java.awt.Color.GREEN);
            return Results.SUCCESS();
        } else {
            gui.msg(L10n.get("storage.container_not_found"), java.awt.Color.RED);
            return Results.FAIL();
        }
    }

    /**
     * Count items with matching name in player inventory
     */
    private int countItemsInInventory(NGameUI gui, String name) {
        try {
            return gui.getInventory().getItems(name).size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Navigate to container and fetch items matching exact quality from DB records.
     * @param requestedItems DB records with exact qualities to match
     * @return number of items actually fetched
     */
    private int fetchFromContainer(NGameUI gui, String containerHash, List<StorageItemDao.StorageItemData> requestedItems) throws InterruptedException {
        // Count items before
        int beforeCount = countItemsInInventory(gui, itemName);

        Gob containerGob = StorageNav.reach(gui, containerHash);
        if (containerGob == null) {
            return 0;
        }

        // Move to container if not close enough
        if (!PathFinder.isAvailable(containerGob)) {
            PathFinder pf = new PathFinder(containerGob);
            pf.run(gui);
        }

        if (containerGob.ngob != null && containerGob.ngob.name != null
                && containerGob.ngob.name.startsWith("gfx/terobjs/stockpile")) {
            return fetchFromPile(gui, containerGob, requestedItems.size());
        }

        // Open container
        String containerName = getContainerWindowName(containerGob);
        if (containerName == null) {
            System.out.println("[FetchStorageItemBot] Unknown container type: " +
                (containerGob.ngob != null ? containerGob.ngob.name : "null"));
            return 0;
        }
        NUtils.rclickGob(containerGob);
        NUtils.addTask(new WaitWindow(containerName));

        // Wait for inventory to load
        Thread.sleep(500);

        // Get container inventory
        Window containerWindow = gui.getWindow(containerName);
        if (containerWindow == null) {
            return 0;
        }

        NInventory containerInv = gui.getInventory(containerName);
        if (containerInv == null) {
            containerWindow.wdgmsg("close");
            return 0;
        }

        // Fetch items one by one, matching exact quality from DB records
        int transferred = 0;
        for (StorageItemDao.StorageItemData dbItem : requestedItems) {
            if (stop.get()) break;
            if (gui.getInventory().calcFreeSpace() == 0) break;

            // Find a WItem in container matching name AND exact quality
            WItem match = findItemByQuality(containerInv, dbItem.getName(), dbItem.getQuality());
            if (match == null) {
                // No item with this exact quality in the container — skip
                continue;
            }

            int oldCount = countItemsInInventory(gui, itemName);
            TransferToContainer.transfer(match, gui.getInventory(), 1);

            // Verify item was actually transferred
            int newCount = countItemsInInventory(gui, itemName);
            if (newCount > oldCount) {
                transferred++;
            }
        }

        // Close container window
        containerWindow.wdgmsg("close");

        // Return actual count based on inventory difference
        int afterCount = countItemsInInventory(gui, itemName);
        return afterCount - beforeCount;
    }

    /**
     * Take up to count items from a stockpile. Pile items have no stored quality, so any will do.
     * @return number of items actually taken
     */
    private int fetchFromPile(NGameUI gui, Gob pile, int count) throws InterruptedException {
        new OpenTargetContainer("Stockpile", pile).run(gui);
        NISBox box = gui.getStockpile();
        if (box == null) {
            return 0;
        }
        TakeItemsFromPile take = new TakeItemsFromPile(pile, box, count);
        take.run(gui);
        // Taking the last item destroys the pile and closes its window
        Window wnd = gui.getWindow("Stockpile");
        if (wnd != null) {
            new CloseTargetWindow(wnd).run(gui);
        }
        return take.getResult();
    }

    /**
     * Find a WItem in the container inventory matching the given name and exact quality.
     * Quality is compared rounded to 2 decimal places (same precision as DB storage).
     * @return matching WItem or null if not found
     */
    private WItem findItemByQuality(NInventory containerInv, String name, double targetQuality) throws InterruptedException {
        ArrayList<WItem> items = containerInv.getItems(name);
        for (WItem item : items) {
            if (item.item instanceof NGItem) {
                Float q = ((NGItem) item.item).quality;
                if (q != null) {
                    double rounded = Double.parseDouble(Utils.odformat2(q, 2));
                    if (Double.compare(rounded, targetQuality) == 0) {
                        return item;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Get the window name for a container Gob using the canonical NContext.contcaps mapping.
     * Returns null if the container type is unknown.
     */
    private String getContainerWindowName(Gob gob) {
        if (gob.ngob == null || gob.ngob.name == null) {
            return null;
        }
        return NContext.contcaps.get(gob.ngob.name);
    }
}
