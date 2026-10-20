package nurgling.actions;

import haven.*;
import nurgling.*;
import nurgling.areas.NContext;
import nurgling.db.dao.StorageItemDao;
import nurgling.tasks.NTask;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.StackSupporter;
import nurgling.widgets.storage.VillageStorage;

import java.sql.SQLException;
import java.util.*;

import static haven.OCache.posres;

/**
 * Brings the player's inventory up to a target count of one item, taken from containers and
 * stockpiles the Storage Items index lists - places that have no area. Only items whose name is
 * exactly the wanted name are taken: the index is queried with SQL '=', every item is checked by
 * NGItem.name().equals before it is moved, and a stockpile whose window shows another name is
 * sampled with one item, which is put back unless its name matches.
 */
public class TakeFromStorageIndex implements Action {
    /** Upper bound on waits for an item to land in, or leave, the inventory. */
    private static final int ITEM_WAIT_TICKS = 200;

    private final Source source;
    private final String name;
    private final int target;

    /**
     * @param target absolute count of name wanted in the inventory, as with TakeItems2
     */
    public TakeFromStorageIndex(Source source, String name, int target) {
        this.source = source;
        this.name = name;
        this.target = target;
    }

    /**
     * What the index lists for the names one craft uses, plus what this run has learnt since:
     * containers found to hold none of a name are not visited again for it. Shared across a
     * craft's batches so a later batch doesn't walk back to a chest an earlier one emptied.
     */
    public static class Source {
        private final Map<String, LinkedHashMap<String, StorageItemDao.LocatedItem>> byName = new HashMap<>();
        private final Set<String> depleted = new HashSet<>();
        private final Set<Long> village;

        private Source(Set<Long> village) {
            this.village = village;
        }

        /**
         * Load the rows for names, keeping only storage in the village (VillageStorage's rule, as
         * the Storage Items window uses). With the database off or failing, the source is empty.
         * @param village the village's chunks from {@link VillageStorage#village}
         */
        public static Source load(Collection<String> names, Set<Long> village) {
            Source s = new Source(village);
            s.fill(names);
            return s;
        }

        public boolean has(String name) {
            LinkedHashMap<String, StorageItemDao.LocatedItem> conts = byName.get(name);
            if (conts == null)
                return false;
            for (String hash : conts.keySet())
                if (!depleted.contains(key(name, hash)))
                    return true;
            return false;
        }

        private void fill(Collection<String> names) {
            // Not isReady(): it is false while every pooled connection is busy; the query waits for one
            if (names.isEmpty() || NCore.databaseManager == null)
                return;
            List<StorageItemDao.LocatedItem> rows;
            try {
                rows = NCore.databaseManager.getStorageItemService().loadByExactNames(names);
            } catch (SQLException e) {
                System.out.println("[TakeFromStorageIndex] storage query failed: " + e.getMessage());
                return;
            }
            for (String n : names)
                byName.remove(n);
            for (StorageItemDao.LocatedItem row : rows)
                if (VillageStorage.inVillage(row.gridId, village))
                    byName.computeIfAbsent(row.name, k -> new LinkedHashMap<>()).putIfAbsent(row.container, row);
        }

        private void reload(String name) {
            fill(Collections.singleton(name));
        }

        private void markDepleted(String name, String hash) {
            depleted.add(key(name, hash));
        }

        /** The not-yet-depleted container for name nearest the player: loaded gobs and grids first. */
        private StorageItemDao.LocatedItem nearest(String name, NGameUI gui) {
            LinkedHashMap<String, StorageItemDao.LocatedItem> conts = byName.get(name);
            Gob player = NUtils.player();
            if (conts == null || player == null)
                return null;
            StorageItemDao.LocatedItem best = null;
            double bestDist = Double.POSITIVE_INFINITY;
            for (StorageItemDao.LocatedItem row : conts.values()) {
                if (depleted.contains(key(name, row.container)))
                    continue;
                double d = distance(gui, player, row);
                if (best == null || d < bestDist) {
                    best = row;
                    bestDist = d;
                }
            }
            return best;
        }

        private static double distance(NGameUI gui, Gob player, StorageItemDao.LocatedItem row) {
            Gob gob = Finder.findStorageGob(row.container);
            if (gob != null)
                return gob.rc.dist(player.rc);
            MCache mcache = gui.map.glob.map;
            synchronized (mcache.grids) {
                for (MCache.Grid g : mcache.grids.values()) {
                    if (g.id == row.gridId) {
                        Coord local = parseCoord(row.coord);
                        if (local == null)
                            break;
                        Coord2d pos = new Coord2d(g.ul.x * MCache.tilesz.x, g.ul.y * MCache.tilesz.y)
                                .add(local.x * posres.x, local.y * posres.y);
                        return pos.dist(player.rc);
                    }
                }
            }
            // Not loaded: after everything that is, in index order
            return Double.MAX_VALUE;
        }

        private static Coord parseCoord(String coord) {
            if (coord == null)
                return null;
            String[] parts = coord.replace("(", "").replace(")", "").replace(" ", "").split(",");
            if (parts.length != 2)
                return null;
            try {
                return new Coord((int) Double.parseDouble(parts[0]), (int) Double.parseDouble(parts[1]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String key(String name, String hash) {
            return name + "|" + hash;
        }
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        int start = countExact(gui.getInventory(), name);
        boolean reloaded = false;
        while (true) {
            int have = countExact(gui.getInventory(), name);
            if (have >= target)
                return Results.SUCCESS();
            if (gui.getInventory().getNumberFreeCoord(new Coord(1, 1)) <= 0 && gui.getInventory().findNotFullStack(name) == null)
                break;
            StorageItemDao.LocatedItem next = source.nearest(name, gui);
            if (next == null) {
                // Rows only refresh when a container is closed, so ask once more before giving up
                if (reloaded)
                    break;
                source.reload(name);
                reloaded = true;
                continue;
            }
            visit(gui, next, target - have);
        }
        return countExact(gui.getInventory(), name) > start ? Results.SUCCESS() : Results.FAIL();
    }

    /** Take up to want of name from one stored container; mark it depleted unless some is left. */
    private void visit(NGameUI gui, StorageItemDao.LocatedItem row, int want) throws InterruptedException {
        Gob gob = StorageNav.reach(gui, row.container, row.gridId, row.coord);
        if (gob == null || gob.ngob == null || gob.ngob.name == null) {
            source.markDepleted(name, row.container);
            return;
        }
        int before = countExact(gui.getInventory(), name);
        boolean left;
        if (gob.ngob.name.startsWith("gfx/terobjs/stockpile")) {
            left = visitPile(gui, gob, want);
        } else {
            String cap = NContext.contcaps.get(gob.ngob.name);
            left = (cap != null) && visitContainer(gui, gob, cap, want);
        }
        // A visit that took nothing would be repeated forever, whatever is still in there
        if (!left || countExact(gui.getInventory(), name) <= before)
            source.markDepleted(name, row.container);
    }

    /** @return whether exact-name copies are still in the container */
    private boolean visitContainer(NGameUI gui, Gob gob, String cap, int want) throws InterruptedException {
        Container cont = new Container(gob, cap, null);
        new PathFinder(gob).run(gui);
        new OpenTargetContainer(cont).run(gui);
        NInventory inv = gui.getInventory(cap);
        boolean left = false;
        // getInventory resolves by caption; never take from a window provably bound to another gob
        if (inv != null && (inv.parentGob == null || inv.parentGob.id == gob.id)) {
            takeExact(gui, inv, want);
            left = !exact(inv.getItems(new NAlias(name))).isEmpty();
        } else {
            System.out.println("[TakeFromStorageIndex] window is not " + cap + " " + gob.id + ", skipped");
        }
        new CloseTargetContainer(cont).run(gui);
        return left;
    }

    /** Move exact-name items one transfer at a time until want are taken or nothing more fits. */
    private void takeExact(NGameUI gui, NInventory src, int want) throws InterruptedException {
        NInventory inv = gui.getInventory();
        int taken = 0;
        while (taken < want) {
            // GetItems waits until every item's info, and so its name, has loaded
            ArrayList<WItem> items = exact(src.getItems(new NAlias(name)));
            if (items.isEmpty())
                return;
            WItem first = items.get(0);
            Coord shape = first.sz.div(Inventory.sqsz).swapXY();
            if (StackSupporter.getOptimalItemCapacity(inv, name, shape, want - taken) <= 0)
                return;
            int before = countExact(inv, name);
            TransferToContainer.transfer(first, inv, want - taken);
            int after = waitCountChange(inv, before);
            if (after <= before)
                return;
            taken += after - before;
        }
    }

    /** @return whether the pile may still hold name */
    private boolean visitPile(NGameUI gui, Gob gob, int want) throws InterruptedException {
        new PathFinder(gob).run(gui);
        new OpenTargetContainer("Stockpile", gob).run(gui);
        NISBox box = gui.getStockpile();
        if (box == null)
            return false;
        boolean matches = name.equals(pileName(box));
        if (!matches) {
            // The window shows only a pile's kind (fish, onions); one item tells which it really is
            TakeItemsFromPile sample = new TakeItemsFromPile(gob, box, 1);
            sample.run(gui);
            if (sample.newItems().isEmpty()) {
                closePile(gui);
                return false;
            }
            NGItem got = sample.newItems().get(0);
            waitName(got);
            if (!name.equals(got.name())) {
                System.out.println("[TakeFromStorageIndex] pile holds " + got.name() + ", not " + name + "; put back");
                putBack(gui, got);
                closePile(gui);
                return false;
            }
            want--;
        }
        if (want > 0 && Finder.findGob(gob.id) != null && gui.getStockpile() != null) {
            int room = StackSupporter.getOptimalItemCapacity(gui.getInventory(), name, new Coord(1, 1), want);
            if (room > 0)
                new TakeItemsFromPile(gob, gui.getStockpile(), room).run(gui);
        }
        // Taking the last item destroys the pile
        boolean left = Finder.findGob(gob.id) != null;
        closePile(gui);
        return left;
    }

    private static String pileName(NISBox box) {
        try {
            Resource.Tooltip tt = box.itemres.get().layer(Resource.tooltip);
            return (tt != null) ? tt.text() : null;
        } catch (Loading l) {
            return null;
        }
    }

    private void putBack(NGameUI gui, NGItem item) throws InterruptedException {
        NISBox box = gui.getStockpile();
        if (box == null)
            return;
        int id = item.wdgid();
        box.put(1);
        NUtils.addTask(new NTask() {
            int ticks = 0;

            @Override
            public boolean check() {
                return NUtils.getUI().getwidget(id) == null || ticks++ > ITEM_WAIT_TICKS;
            }
        });
    }

    private static void closePile(NGameUI gui) throws InterruptedException {
        Window wnd = gui.getWindow("Stockpile");
        if (wnd != null)
            new CloseTargetWindow(wnd).run(gui);
    }

    private static void waitName(NGItem item) throws InterruptedException {
        NUtils.addTask(new NTask() {
            int ticks = 0;

            @Override
            public boolean check() {
                return item.name() != null || ticks++ > ITEM_WAIT_TICKS;
            }
        });
    }

    /** Wait, briefly, for the count of name in inv to rise above before; return the new count. */
    private int waitCountChange(NInventory inv, int before) throws InterruptedException {
        for (int tries = 0; tries < 20; tries++) {
            int now = countExact(inv, name);
            if (now > before)
                return now;
            NUtils.addTask(new NTask() {
                int ticks = 0;

                @Override
                public boolean check() {
                    return ticks++ >= ITEM_WAIT_TICKS / 20;
                }
            });
        }
        return before;
    }

    private ArrayList<WItem> exact(ArrayList<WItem> items) {
        ArrayList<WItem> res = new ArrayList<>();
        for (WItem w : items)
            if (name.equals(((NGItem) w.item).name()))
                res.add(w);
        return res;
    }

    /** Number of items in inv named exactly name. */
    public static int countExact(NInventory inv, String name) throws InterruptedException {
        int n = 0;
        for (WItem w : inv.getItems(new NAlias(name)))
            if (name.equals(((NGItem) w.item).name()))
                n++;
        return n;
    }
}
