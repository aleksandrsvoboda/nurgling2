package nurgling.tools;

import haven.Gob;
import haven.Indir;
import haven.Resource;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.actions.CloseTargetContainer;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.PathFinder;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.navigation.ChunkNavManager;

import java.util.ArrayList;

/** Shared area/container stock-counting logic, used by both MaintainStockBot and Forager's Maintain feature (ForagerAction.maintainQuantity). */
public class AreaStock {

    /** Every standard container gob plus any stockpile found within area. */
    public static ArrayList<Gob> findContainersInArea(NArea area) throws InterruptedException {
        ArrayList<Gob> containers = new ArrayList<>();

        NAlias containerAlias = new NAlias(new ArrayList<>(NContext.contcaps.keySet()), new ArrayList<>());
        containers.addAll(Finder.findGobs(area, containerAlias));

        containers.addAll(Finder.findGobs(area, new NAlias("stockpile")));

        return containers;
    }

    /** The inventory window caption for a container gob, or null if gob isn't a known container. */
    public static String getContainerCap(Gob gob) {
        if (gob == null || gob.ngob == null || gob.ngob.name == null) {
            return null;
        }

        String cap = NContext.contcaps.get(gob.ngob.name);
        if (cap != null) {
            return cap;
        }

        if (gob.ngob.name.contains("stockpile")) {
            return "Stockpile";
        }

        return null;
    }

    /** Counts inv's items whose underlying resource matches resource - not display name, which can vary by growth/quality stage. */
    public static int countByResource(NInventory inv, String resource) throws InterruptedException {
        int count = 0;
        for (WItem w : inv.getItems()) {
            if (w.item instanceof NGItem) {
                Indir<Resource> res = ((NGItem) w.item).res;
                if (res != null && res.get() != null && resource.equals(res.get().name)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Travels to area via ChunkNavManager.navigateToArea (single real path, buildings/portals included), same as GotoArea; falls back to NUtils.navigateToArea only if ChunkNav isn't initialized. */
    private static boolean travelToArea(NGameUI gui, NArea area) throws InterruptedException {
        if (gui.map instanceof NMapView) {
            ChunkNavManager chunkNav = ((NMapView) gui.map).getChunkNavManager();
            if (chunkNav != null && chunkNav.isInitialized()) {
                return chunkNav.navigateToArea(area, gui).IsSuccess();
            }
        }
        return NUtils.navigateToArea(area, true);
    }

    /** Travels to area, opens every container in it, tallies items matching itemResource, closes each again (guaranteed even if counting is interrupted mid-visit); 0 if unreachable/empty. */
    public static int countItemsInAreaContainers(NGameUI gui, NArea area, String itemResource) throws InterruptedException {
        if (!travelToArea(gui, area)) {
            return 0;
        }

        ArrayList<Gob> containerGobs = findContainersInArea(area);
        if (containerGobs.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (Gob gob : containerGobs) {
            String containerCap = getContainerCap(gob);
            if (containerCap == null) {
                continue;
            }

            Container container = new Container(gob, containerCap, area);

            new PathFinder(gob).run(gui);
            new OpenTargetContainer(container).run(gui);
            try {
                NInventory inv = gui.getInventory(containerCap);
                if (inv != null) {
                    count += countByResource(inv, itemResource);
                }
            } finally {
                new CloseTargetContainer(container).run(gui);
            }
        }
        return count;
    }
}
