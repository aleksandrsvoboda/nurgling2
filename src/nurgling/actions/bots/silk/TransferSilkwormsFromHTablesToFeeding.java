package nurgling.actions.bots.silk;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.Pair;
import haven.WItem;
import nurgling.NUtils;
import nurgling.NGameUI;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Container;

import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static nurgling.areas.NContext.contcaps;

/**
 * Transfers silkworms from herbalist tables to feeding containers
 * Records available space in herbalist tables for future egg placement
 */
public class TransferSilkwormsFromHTablesToFeeding implements Action {
    private final int totalSilkwormsNeeded;
    private int totalEggsNeeded = 0;
    
    public TransferSilkwormsFromHTablesToFeeding(int totalSilkwormsNeeded) {
        this.totalSilkwormsNeeded = totalSilkwormsNeeded;
    }
    
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);
        String worms = "Silkworm";
        NAlias wormsAlias = new NAlias(new ArrayList<>(List.of(worms)), new ArrayList<>(List.of("egg")));
        
        totalEggsNeeded = 0;
        ArrayList<Container> htableContainers = new ArrayList<>();
        ArrayList<Container> feedingContainers = new ArrayList<>();
        
        // Pre-populate feeding containers for efficiency
        NArea feedingArea = context.goToArea(Specialisation.SpecName.silkwormFeeding);
        if (feedingArea != null) {
            feedingContainers = createContainersFromArea(feedingArea);
        }
        
        /* Always tour the herbalist tables, even with nowhere to put a single worm.
         *
         * This pass does two unrelated jobs: it lifts hatched worms off the tables, and it measures
         * how much table space is free so SilkProductionBot knows how many eggs to plant. Gating
         * both on totalSilkwormsNeeded - which is derived from the free space the LEAF refill
         * found - meant that running out of leaves silently stopped the whole pipeline: the tables
         * were never visited, the worms on them starved where they lay, totalEggsNeeded stayed 0
         * and the egg planting was skipped as well.
         *
         * No wrapper is needed to respect the budget. The loop's own first test,
         * "wormsTransferredTotal >= totalSilkwormsNeeded", is already true at 0 and sends every
         * table down the measure-egg-capacity-only branch - which is exactly the wanted behaviour
         * when there is no room for worms. */
        {
            int wormsTransferredTotal = 0;
            
            // Take silkworms from herbalist tables - use container-by-container approach
            NArea htablesArea = context.goToArea(Specialisation.SpecName.htable, "Silkworm Egg");
            if (htablesArea != null) {
                htableContainers = createContainersFromArea(htablesArea);
                
                // Process each herbalist table container individually
                for (Container htableContainer : htableContainers) {
                    if (wormsTransferredTotal >= totalSilkwormsNeeded) {
                        // Still need to check remaining containers for egg capacity only
                        new PathFinder(Finder.findGob(htableContainer.gobid)).run(gui);
                        new OpenTargetContainer(htableContainer).run(gui);
                        
                        // Record free space for eggs
                        int freeSpace = gui.getInventory(htableContainer.cap).getFreeSpace();
                        totalEggsNeeded += freeSpace;
                        
                        new CloseTargetContainer(htableContainer).run(gui);
                        continue;
                    }
                    
                    new PathFinder(Finder.findGob(htableContainer.gobid)).run(gui);
                    new OpenTargetContainer(htableContainer).run(gui);
                    
                    // Get all silkworm WItems from this container, excluding anything with "egg" in the name
                    ArrayList<WItem> silkwormItems = gui.getInventory(htableContainer.cap).getItems(wormsAlias);
                    
                    // Transfer silkworms from this container in batches based on inventory space
                    int wormsFromThisContainer = 0;
                    while (!silkwormItems.isEmpty() &&
                           wormsTransferredTotal + wormsFromThisContainer < totalSilkwormsNeeded) {
                        
                        // Take what fits in inventory
                        int inventorySpace = gui.getInventory().getFreeSpace();
                        int wormsToTake = Math.min(silkwormItems.size(), inventorySpace);
                        
                        if (wormsToTake == 0) {
                            // Inventory full - drop off and continue
                            dropOffWormsToFeedingContainers(gui, feedingContainers, wormsAlias, context);
                            context.goToArea(Specialisation.SpecName.htable, "Silkworm Egg");

                            new PathFinder(Finder.findGob(htableContainer.gobid)).run(gui);
                            new OpenTargetContainer(htableContainer).run(gui);

                            // Refresh silkworm items list (may have changed)
                            silkwormItems = gui.getInventory(htableContainer.cap).getItems(wormsAlias);

                            continue;
                        }
                        
                        ArrayList<WItem> wormsToTakeBatch = new ArrayList<>();
                        for (int i = 0; i < wormsToTake; i++) {
                            wormsToTakeBatch.add(silkwormItems.get(i));
                        }
                        
                        new TakeWItemsFromContainer(htableContainer, wormsToTakeBatch).run(gui);
                        wormsFromThisContainer += wormsToTakeBatch.size();
                        
                        // Remove taken items from our tracking list
                        for (int i = 0; i < wormsToTake; i++) {
                            silkwormItems.remove(0);
                        }
                    }
                    
                    wormsTransferredTotal += wormsFromThisContainer;
                    
                    // Record free space for eggs (done once per container)
                    new OpenTargetContainer(htableContainer).run(gui);
                    int freeSpace = gui.getInventory(htableContainer.cap).getFreeSpace();
                    totalEggsNeeded += freeSpace;
                    
                    new CloseTargetContainer(htableContainer).run(gui);
                }
                
                // Drop off any remaining silkworms in inventory after processing all containers
                if (!gui.getInventory().getItems(wormsAlias).isEmpty()) {
                    dropOffWormsToFeedingContainers(gui, feedingContainers, wormsAlias, context);
                }
            }
        }
        
        return Results.SUCCESS();
    }
    
    public int getTotalEggsNeeded() {
        return totalEggsNeeded;
    }
    
    /**
     * Diagnostics only - prints why an area does or does not resolve to real coordinates.
     * Finder.findGobs(area, ...) calls area.getRCArea() and, when that is null, quietly matches
     * nothing at all, so an area can look present and still yield zero gobs with no error.
     */
    private void diagnoseArea(NArea area) {
        if (area == null) {
            System.out.println("[AREA-DIAG] area is NULL");
            return;
        }
        System.out.println("[AREA-DIAG] area='" + area.name + "' id=" + area.id
                + " visible=" + area.isVisible() + " hide=" + area.hide);

        // WHERE IS THE PLAYER, unconditionally - the previous version only printed this when
        // getRCArea() succeeded, which is exactly the case we are not interested in.
        Gob pl = NUtils.player();
        System.out.println("[AREA-DIAG] player rc=" + (pl == null ? "null" : pl.rc));

        // Every grid actually loaded right now, so a stored id that no longer matches is visible.
        StringBuilder loaded = new StringBuilder();
        synchronized (NUtils.getGameUI().map.glob.map.grids) {
            for (MCache.Grid g : NUtils.getGameUI().map.glob.map.grids.values()) {
                loaded.append("\n[AREA-DIAG]   loaded grid ").append(g.id).append(" ul=").append(g.ul);
                if (pl != null) {
                    Coord ptile = pl.rc.floor(MCache.tilesz);
                    if (ptile.x >= g.ul.x && ptile.y >= g.ul.y
                            && ptile.x < g.ul.x + MCache.cmaps.x && ptile.y < g.ul.y + MCache.cmaps.y)
                        loaded.append("   <== PLAYER IS STANDING ON THIS GRID");
                }
            }
        }
        System.out.println("[AREA-DIAG] grids loaded in session:" + loaded);

        int total = 0, missing = 0;
        if (area.space != null && area.space.space != null) {
            for (Long gridId : area.space.space.keySet()) {
                total++;
                MCache.Grid grid = NUtils.getGameUI().map.glob.map.findGrid(gridId);
                if (grid == null) {
                    missing++;
                    System.out.println("[AREA-DIAG]   grid " + gridId + " -> NOT LOADED  <== aborts getRCArea");
                } else {
                    System.out.println("[AREA-DIAG]   grid " + gridId + " -> loaded at " + grid.ul);
                }
            }
        }
        System.out.println("[AREA-DIAG] grids: " + total + " total, " + missing + " not loaded");

        /* Is 258 even the right area? List every area carrying this specialisation, so a stale
         * duplicate - the DB sync loads 297 areas, the local json has far fewer - is visible. */
        System.out.println("[AREA-DIAG] --- all areas with spec 'htable' ---");
        for (NArea a : NUtils.getGameUI().map.glob.map.areas.values()) {
            if (a == null || a.spec == null)
                continue;
            for (NArea.Specialisation sp : a.spec) {
                if (!"htable".equals(sp.name))
                    continue;
                StringBuilder g = new StringBuilder();
                if (a.space != null && a.space.space != null) {
                    for (Long gid : a.space.space.keySet()) {
                        boolean ok = NUtils.getGameUI().map.glob.map.findGrid(gid) != null;
                        g.append(" grid=").append(gid).append(ok ? "(LOADED)" : "(not loaded)");
                    }
                }
                System.out.println("[AREA-DIAG]   id=" + a.id + " name='" + a.name
                        + "' subtype='" + sp.subtype + "' visible=" + a.isVisible()
                        + " hide=" + a.hide + g
                        + (a.id == area.id ? "   <== THE ONE THE BOT CHOSE" : ""));
            }
        }
        System.out.println("[AREA-DIAG] --- end ---");

        /* Where are the herbalist tables ACTUALLY, right now? If real htable gobs are sitting on
         * loaded grids while the area points at a grid that is not here, the area record is wrong
         * - not the navigation. */
        System.out.println("[AREA-DIAG] --- live 'gfx/terobjs/htable' gobs in session ---");
        int found = 0;
        synchronized (NUtils.getGameUI().ui.sess.glob.oc) {
            for (Gob g : NUtils.getGameUI().ui.sess.glob.oc) {
                if (g.ngob == null || g.ngob.name == null || !g.ngob.name.contains("htable"))
                    continue;
                found++;
                Coord tile = g.rc.floor(MCache.tilesz);
                Coord gul = tile.div(MCache.cmaps).mul(MCache.cmaps);
                MCache.Grid og = null;
                synchronized (NUtils.getGameUI().map.glob.map.grids) {
                    for (MCache.Grid cand : NUtils.getGameUI().map.glob.map.grids.values()) {
                        if (cand.ul.equals(gul)) { og = cand; break; }
                    }
                }
                if (found <= 30)
                    System.out.println("[AREA-DIAG]   htable gob " + g.id + " rc=" + g.rc
                            + " tile=" + tile + " gridUl=" + gul
                            + " gridId=" + (og == null ? "UNKNOWN" : String.valueOf(og.id))
                            + " distToPlayer=" + (pl == null ? -1 : (int) g.rc.dist(pl.rc)));
            }
        }
        System.out.println("[AREA-DIAG] total live htable gobs = " + found);
        System.out.println("[AREA-DIAG] --- end ---");

        Pair<Coord2d, Coord2d> rc = area.getRCArea();
        if (rc == null) {
            System.out.println("[AREA-DIAG] getRCArea() = NULL  -> findGobs will match nothing");
        } else {
            Coord2d p = (NUtils.player() == null) ? null : NUtils.player().rc;
            System.out.println("[AREA-DIAG] getRCArea() = " + rc.a + " .. " + rc.b
                    + " ; player=" + p
                    + (p == null ? "" : " ; dist a=" + (int) rc.a.dist(p) + " b=" + (int) rc.b.dist(p)
                       + " (both >1000 also returns null)"));
        }
    }

    private ArrayList<Container> createContainersFromArea(NArea area) throws InterruptedException {
        ArrayList<Container> containers = new ArrayList<>();
        diagnoseArea(area);
        ArrayList<Gob> gobs = Finder.findGobs(area, new NAlias(new ArrayList<>(NContext.contcaps.keySet())));
        System.out.println("[AREA-DIAG] Finder.findGobs returned " + gobs.size() + " container gob(s)");
        for (Gob gob : gobs) {
            Container cand = new Container(gob, contcaps.get(gob.ngob.name), area);
            cand.initattr(Container.Space.class);
            containers.add(cand);
        }
        return containers;
    }
    
    private void dropOffWormsToFeedingContainers(NGameUI gui, ArrayList<Container> feedingContainers, NAlias wormsAlias, NContext context) throws InterruptedException {
        context.goToArea(Specialisation.SpecName.silkwormFeeding);
        
        for (Container feedingContainer : feedingContainers) {
            if (gui.getInventory().getItems(wormsAlias).isEmpty()) {
                break; // No more silkworms in inventory
            }
            
            new PathFinder(Finder.findGob(feedingContainer.gobid)).run(gui);
            new OpenTargetContainer(feedingContainer).run(gui);
            
            int currentWorms = gui.getInventory(feedingContainer.cap).getItems(wormsAlias).size();
            int spaceAvailable = Math.max(0, 56 - currentWorms);
            
            if (spaceAvailable > 0) {
                new TransferToContainer(feedingContainer, wormsAlias).run(gui);
            }
            
            new CloseTargetContainer(feedingContainer).run(gui);
        }
    }
}