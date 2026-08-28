package nurgling.tools;

import haven.Gob;
import haven.Indir;
import haven.Resource;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.CloseTargetContainer;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.PathFinder;
import nurgling.areas.NArea;
import nurgling.areas.NContext;

import java.util.ArrayList;

/**
 * Shared area/container stock-counting logic, extracted from {@link nurgling.actions.bots.MaintainStockBot}
 * (which now calls these same methods for its own stock check, rather than keeping its own copy)
 * so Forager's Maintain feature ({@code ForagerAction.maintainQuantity}) can follow the exact same
 * "how much of this item is already sitting in its Put area" logic without duplicating it.
 */
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

    /**
     * Counts inv's items whose underlying resource (e.g. "gfx/invobjs/chestnut") matches
     * resource - shared by Forager's carried-inventory count and its Put-area container count,
     * both of which need to match this way rather than by display name/NAlias: a forageable
     * item's display name can vary by growth/quality stage (e.g. "Unripe Chestnut" vs
     * "Chestnut") while its resource stays constant (see ForagerAction.sourceItemResource).
     */
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

    /**
     * Travels to area (skips the visit entirely if it can't be reached), opens every container
     * found in it, tallies items whose resource matches itemResource across all of them, closes
     * each again (guaranteed once open succeeds, even if counting itself throws/is interrupted -
     * this runs after Forager's threat watcher has started, so an interrupt genuinely can land
     * mid-visit here). Returns the total found - 0 if the area is unreachable or has no containers.
     */
    public static int countItemsInAreaContainers(NGameUI gui, NArea area, String itemResource) throws InterruptedException {
        if (!NUtils.navigateToArea(area, true)) {
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
