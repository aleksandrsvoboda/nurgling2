package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.areas.NContext;
import nurgling.tasks.*;
import nurgling.tools.*;

import java.util.*;

public class SortContainersInArea implements Action {

    // A scanned item (stack or single). One per grid slot occupied.
    private static class Item {
        final String name;
        final double quality;
        final Coord size; // width x height in grid cells
        int container;    // which container index, -1 = player inv

        Item(String name, double quality, Coord size, int container) {
            this.name = name;
            this.quality = quality;
            this.size = size;
            this.container = container;
        }
    }

    // Container metadata
    private static class CInfo {
        final long gobId;
        final String gobHash;
        final String cap;
        final int gridW, gridH;
        final boolean[][] blocked; // true = sqmask blocked cell

        CInfo(long gobId, String gobHash, String cap, int gridW, int gridH, boolean[][] blocked) {
            this.gobId = gobId;
            this.gobHash = gobHash;
            this.cap = cap;
            this.gridW = gridW;
            this.gridH = gridH;
            this.blocked = blocked;
        }
    }

    // Virtual grid for tetris packing simulation
    private static class VGrid {
        final boolean[][] g;
        final int w, h;

        VGrid(int w, int h, boolean[][] blocked) {
            this.w = w;
            this.h = h;
            g = new boolean[w][h];
            if (blocked != null)
                for (int x = 0; x < w; x++)
                    for (int y = 0; y < h; y++)
                        g[x][y] = blocked[x][y];
        }

        boolean tryPlace(Coord size) {
            for (int y = 0; y <= h - size.y; y++)
                for (int x = 0; x <= w - size.x; x++)
                    if (fits(x, y, size)) { mark(x, y, size); return true; }
            return false;
        }

        private boolean fits(int px, int py, Coord s) {
            for (int x = px; x < px + s.x; x++)
                for (int y = py; y < py + s.y; y++)
                    if (g[x][y]) return false;
            return true;
        }

        private void mark(int px, int py, Coord s) {
            for (int x = px; x < px + s.x; x++)
                for (int y = py; y < py + s.y; y++)
                    g[x][y] = true;
        }
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // UI
        boolean sortStacks;
        nurgling.widgets.bots.SortContainersWnd wnd = null;
        try {
            wnd = new nurgling.widgets.bots.SortContainersWnd();
            NUtils.getUI().core.addTask(new WaitCheckable(NUtils.getGameUI().add(wnd, UI.scale(200, 200))));
            sortStacks = wnd.sortStacks;
        } finally {
            if (wnd != null) wnd.destroy();
        }

        // Require empty player inventory
        NInventory playerInv = gui.getInventory();
        if (playerInv != null && playerInv.getTopLevelItems().size() > 0) {
            gui.error("Player inventory must be empty!");
            return Results.ERROR("Player inventory not empty");
        }

        // Area selection
        gui.msg("Select area with containers to sort");
        SelectArea selectArea = new SelectArea(Resource.loadsimg("baubles/inputArea"));
        selectArea.run(gui);
        Pair<Coord2d, Coord2d> area = selectArea.getRCArea();
        if (area == null) return Results.ERROR("No area selected");

        ArrayList<Gob> gobs = Finder.findGobs(area, new NAlias(new ArrayList<>(NContext.contcaps.keySet())));
        if (gobs.isEmpty()) { gui.msg("No containers found"); return Results.SUCCESS(); }
        gobs.sort((a, b) -> {
            int c = Double.compare(a.rc.y, b.rc.y);
            return c != 0 ? c : Double.compare(a.rc.x, b.rc.x);
        });

        // ===== STEP 1+2: Visit each container, optionally stack sort, scan =====
        List<CInfo> containers = new ArrayList<>();
        List<Item> allItems = new ArrayList<>();

        for (Gob gob : gobs) {
            String cap = NContext.contcaps.get(gob.ngob.name);
            if (cap == null || !isSortableContainer(cap)) continue;

            Container cont = new Container(gob, cap, null);
            new PathFinder(gob).run(gui);
            new OpenTargetContainer(cont).run(gui);

            NInventory inv = gui.getInventory(cap);
            if (inv == null) { new CloseTargetContainer(cont).run(gui); continue; }

            if (sortStacks) new SortInventory(inv, true).run(gui);

            // Force item info to load
            inv.getItems();

            int ci = containers.size();

            // Read sqmask
            boolean[][] blocked = new boolean[inv.isz.x][inv.isz.y];
            if (inv.sqmask != null) {
                int mo = 0;
                for (int y = 0; y < inv.isz.y; y++)
                    for (int x = 0; x < inv.isz.x; x++)
                        blocked[x][y] = inv.sqmask[mo++];
            }

            // Scan top-level items
            for (WItem wi : inv.getTopLevelItems()) {
                if (!(wi.item instanceof NGItem)) continue;
                NGItem ng = (NGItem) wi.item;
                String name = ng.name();
                if (name == null || name.isEmpty()) continue;
                double q = getEffectiveQuality(ng);
                Coord sz = getItemSize(wi);
                allItems.add(new Item(name, q, sz, ci));
            }

            containers.add(new CInfo(gob.id, gob.ngob.hash, cap, inv.isz.x, inv.isz.y, blocked));
            System.out.println("[SORT] Container " + ci + " (" + cap + "): grid=" + inv.isz.x + "x" + inv.isz.y);

            new CloseTargetContainer(cont).run(gui);
        }

        if (containers.isEmpty() || allItems.isEmpty()) {
            gui.msg("Nothing to sort");
            return Results.SUCCESS();
        }

        // ===== STEP 3: Sort alphabetically by name, then descending quality =====
        allItems.sort((a, b) -> {
            int c = a.name.compareTo(b.name);
            if (c != 0) return c;
            return Double.compare(b.quality, a.quality);
        });

        System.out.println("[SORT] Total items: " + allItems.size());
        for (int i = 0; i < allItems.size(); i++) {
            Item it = allItems.get(i);
            System.out.println("[SORT]   [" + i + "] " + it.name + " q" + String.format("%.1f", it.quality)
                    + " " + it.size.x + "x" + it.size.y + " cur=" + it.container);
        }

        // ===== STEP 4: Tetris packing assignment =====
        int[] target = new int[allItems.size()];
        List<VGrid> vgrids = new ArrayList<>();
        for (CInfo ci : containers)
            vgrids.add(new VGrid(ci.gridW, ci.gridH, ci.blocked));

        int curC = 0;
        for (int i = 0; i < allItems.size(); i++) {
            Item it = allItems.get(i);
            boolean placed = false;
            for (int c = curC; c < containers.size(); c++) {
                if (vgrids.get(c).tryPlace(it.size)) {
                    target[i] = c;
                    placed = true;
                    curC = c;
                    break;
                }
            }
            if (!placed) {
                target[i] = it.container;
                System.out.println("[SORT]   WARN: no space for " + it.name + " q" + String.format("%.1f", it.quality));
            }
        }

        // Log moves
        int moveCount = 0;
        for (int i = 0; i < allItems.size(); i++) {
            if (allItems.get(i).container != target[i]) {
                System.out.println("[SORT]   MOVE " + allItems.get(i).name + " q" + String.format("%.1f", allItems.get(i).quality)
                        + " " + allItems.get(i).size.x + "x" + allItems.get(i).size.y
                        + ": c" + allItems.get(i).container + " -> c" + target[i]);
                moveCount++;
            }
        }
        System.out.println("[SORT] " + moveCount + " items need to move");

        // ===== STEP 5: Move items =====
        if (moveCount > 0) {
            gui.msg("Moving " + moveCount + " items...");
            moveItems(gui, containers, allItems, target);
        }

        // ===== STEP 6: Positional sort within each container =====
        gui.msg("Positional sort...");
        for (CInfo ci : containers) {
            Gob gob = findGob(ci);
            if (gob == null) continue;
            Container cont = new Container(gob, ci.cap, null);
            new PathFinder(gob).run(gui);
            new OpenTargetContainer(cont).run(gui);
            NInventory inv = gui.getInventory(ci.cap);
            if (inv != null) new SortInventory(inv, false).run(gui);
            new CloseTargetContainer(cont).run(gui);
        }

        gui.msg("All containers sorted!");
        return Results.SUCCESS();
    }

    /**
     * Moves items to their target containers.
     * Visits containers in order, deposits first (from player inv), then extracts wrong items.
     * Repeats passes until everything is in place.
     */
    private void moveItems(NGameUI gui, List<CInfo> containers, List<Item> allItems, int[] target)
            throws InterruptedException {

        for (int pass = 0; pass < 10; pass++) {
            boolean progress = false;

            for (int ci = 0; ci < containers.size(); ci++) {
                // What needs to arrive here from player inv?
                List<Item> toDeposit = new ArrayList<>();
                // What needs to leave here to player inv?
                List<Item> toExtract = new ArrayList<>();

                for (int i = 0; i < allItems.size(); i++) {
                    Item it = allItems.get(i);
                    if (target[i] == ci && it.container == -1) toDeposit.add(it);  // in player inv, belongs here
                    if (it.container == ci && target[i] != ci) toExtract.add(it);  // here, doesn't belong
                }

                if (toDeposit.isEmpty() && toExtract.isEmpty()) continue;

                CInfo cInfo = containers.get(ci);
                Gob gob = findGob(cInfo);
                if (gob == null) continue;

                Container cont = new Container(gob, cInfo.cap, null);
                new PathFinder(gob).run(gui);
                new OpenTargetContainer(cont).run(gui);

                NInventory containerInv = gui.getInventory(cInfo.cap);
                if (containerInv == null) { new CloseTargetContainer(cont).run(gui); continue; }
                containerInv.getItems(); // force load
                NInventory playerInv = gui.getInventory();

                // DEPOSIT: player inv -> container (one at a time, ISRemoved each)
                for (Item it : toDeposit) {
                    WItem found = findInInventory(playerInv, it.name, it.quality, it.size);
                    if (found == null) continue;
                    if (containerInv.getNumberFreeCoord(it.size) <= 0) break;
                    System.out.println("[SORT]   P" + pass + " IN " + it.name + " q" + String.format("%.1f", it.quality) + " -> c" + ci);
                    int wdgId = found.item.wdgid();
                    found.item.wdgmsg("transfer", Coord.z);
                    NUtils.addTask(new ISRemoved(wdgId));
                    it.container = ci;
                    progress = true;
                }

                // EXTRACT: container -> player inv (one at a time, ISRemoved each)
                for (Item it : toExtract) {
                    if (playerInv.getNumberFreeCoord(it.size) <= 0) {
                        System.out.println("[SORT]   Player inv full");
                        break;
                    }
                    WItem found = findInInventory(containerInv, it.name, it.quality, it.size);
                    if (found == null) continue;
                    System.out.println("[SORT]   P" + pass + " OUT " + it.name + " q" + String.format("%.1f", it.quality) + " from c" + ci);
                    int wdgId = found.item.wdgid();
                    found.item.wdgmsg("transfer", Coord.z);
                    NUtils.addTask(new ISRemoved(wdgId));
                    it.container = -1;
                    progress = true;
                }

                new CloseTargetContainer(cont).run(gui);
            }

            System.out.println("[SORT] === Pass " + pass + " done, progress=" + progress + " ===");
            if (!progress) break;
        }

        // Log final state
        System.out.println("[SORT] === FINAL STATE ===");
        for (int ci = 0; ci < containers.size(); ci++) {
            int count = 0;
            for (Item it : allItems) if (it.container == ci) count++;
            System.out.println("[SORT] Container " + ci + ": " + count + " items");
        }
        int playerCount = 0;
        for (Item it : allItems) if (it.container == -1) playerCount++;
        if (playerCount > 0)
            System.out.println("[SORT] Player inv: " + playerCount + " items remaining!");
    }

    /**
     * Finds a top-level item in inventory matching name, quality, and size.
     */
    private WItem findInInventory(NInventory inv, String name, double quality, Coord size) {
        WItem best = null;
        double bestDiff = Double.MAX_VALUE;
        for (WItem wi : inv.getTopLevelItems()) {
            if (!(wi.item instanceof NGItem)) continue;
            NGItem ng = (NGItem) wi.item;
            String n = ng.name();
            if (n == null || !n.equals(name)) continue;
            Coord sz = getItemSize(wi);
            if (!sz.equals(size)) continue;
            double q = getEffectiveQuality(ng);
            if (quality < 0) return wi; // unknown quality, match by name+size only
            double diff = Math.abs(q - quality);
            if (diff < 0.05) return wi; // exact match
            if (diff < bestDiff) { bestDiff = diff; best = wi; }
        }
        return bestDiff < 1.0 ? best : null;
    }

    private Gob findGob(CInfo ci) {
        if (ci.gobHash != null && !ci.gobHash.isEmpty()) {
            Gob g = Finder.findGob(ci.gobHash);
            if (g != null) return g;
        }
        return Finder.findGob(ci.gobId);
    }

    private static Coord getItemSize(WItem item) {
        if (item.item.spr != null)
            return item.item.spr.sz().div(UI.scale(32));
        return new Coord(1, 1);
    }

    private static double getEffectiveQuality(NGItem item) {
        // Stack: average quality of children
        if (item.contents != null) {
            double sum = 0;
            int count = 0;
            for (Widget ch : item.contents.children()) {
                if (ch instanceof NGItem) {
                    Float q = ((NGItem) ch).quality;
                    if (q != null && q > 0) { sum += q; count++; }
                }
            }
            if (count > 0) return sum / count;
        }
        // Single item
        if (item.quality != null && item.quality > 0) return item.quality;
        return -1;
    }

    private static boolean isSortableContainer(String cap) {
        for (String excluded : SortInventory.EXCLUDE_WINDOWS)
            if (cap.contains(excluded)) return false;
        return true;
    }
}
