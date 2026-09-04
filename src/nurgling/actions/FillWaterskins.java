package nurgling.actions;

import haven.*;
import nurgling.*;
import nurgling.actions.bots.SelectArea;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.DrinkContainers;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.NEquipory;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

/**
 * Finds a water source and fills every water container the character carries.
 *
 * This class owns only the "where is the water" half: resolving the zone, picking a
 * source that actually holds water and walking to it. The filling itself is done by
 * {@link FillWaterContainers}, which is shared with the context menu actions.
 *
 * Two modes:
 * - useGlobalZone=false (default): prompts user to select a water zone
 * - useGlobalZone=true: uses NContext water specialisation area (local then global), errors if not found
 */
public class FillWaterskins implements Action {

    private static final NAlias WATER_SOURCE = new NAlias("barrel", "cistern", "well");
    private static final NAlias BARREL = new NAlias("barrel");

    protected final boolean useGlobalZone;

    public FillWaterskins() { this.useGlobalZone = false; }
    public FillWaterskins(boolean useGlobalZone) { this.useGlobalZone = useGlobalZone; }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Pair<Coord2d, Coord2d> area;

        if (useGlobalZone) {
            NContext context = new NContext(gui);
            NArea nArea = context.goToArea(Specialisation.SpecName.water);
            if (nArea == null) {
                return Results.ERROR("No water area found! Please create an area with 'water' specialization.");
            }
            area = nArea.getRCArea();
        } else {
            SelectArea insa;
            NUtils.getGameUI().msg("Please, select area with cistern or barrel");
            (insa = new SelectArea(Resource.loadsimg("baubles/waterRefiller"))).run(gui);
            area = insa.getRCArea();
        }

        if (area == null) {
            return Results.ERROR("no water area");
        }

        Gob target = findWaterSource(area);
        if (target == null) {
            return Results.ERROR("No containers with water");
        }

        new PathFinder(target).run(gui);
        return new FillWaterContainers(FillWaterContainers.fromGob(target)).run(gui);
    }

    /** First cistern or well in the area, or the first barrel that actually holds water. */
    private static Gob findWaterSource(Pair<Coord2d, Coord2d> area) throws InterruptedException {
        for (Gob cand : Finder.findGobs(area, WATER_SOURCE)) {
            if (NParser.isIt(cand, BARREL)) {
                if (NUtils.barrelHasContent(cand) && NParser.checkName(NUtils.getContentsOfBarrel(cand), "water")) {
                    return cand;
                }
            } else {
                return cand;
            }
        }
        return null;
    }

    /**
     * True when the character carries water containers but none of them holds water.
     *
     * Covers the belt, both feet, both hands and the main inventory, so a character
     * carrying only a glass jug or a kuksa is no longer reported as needing nothing.
     */
    public static boolean checkIfNeed() throws InterruptedException {
        NEquipory equip = NUtils.getEquipment();
        if (equip == null) {
            return false;
        }

        boolean hasContainer = false;

        WItem wbelt = equip.findItem(NEquipory.Slots.BELT.idx);
        if (wbelt != null && wbelt.item.contents instanceof NInventory) {
            for (WItem item : ((NInventory) wbelt.item.contents).getItems(DrinkContainers.ALL)) {
                if (!(item.item instanceof NGItem))
                    continue;
                hasContainer = true;
                if (DrinkContainers.isWater((NGItem) item.item))
                    return false;
            }
        }

        int[] slots = {
                NEquipory.Slots.LFOOT.idx,
                NEquipory.Slots.RFOOT.idx,
                NEquipory.Slots.HAND_LEFT.idx,
                NEquipory.Slots.HAND_RIGHT.idx
        };
        for (int slot : slots) {
            WItem item = equip.findItem(slot);
            if (item == null || !(item.item instanceof NGItem))
                continue;
            NGItem ngItem = (NGItem) item.item;
            if (!DrinkContainers.isContainer(ngItem.name()))
                continue;
            hasContainer = true;
            if (DrinkContainers.isWater(ngItem))
                return false;
        }

        NInventory inv = NUtils.getGameUI().getInventory();
        if (inv != null) {
            ArrayList<WItem> items = inv.getItems(DrinkContainers.ALL);
            for (WItem item : items) {
                if (!(item.item instanceof NGItem))
                    continue;
                hasContainer = true;
                if (DrinkContainers.isWater((NGItem) item.item))
                    return false;
            }
        }

        return hasContainer;
    }
}
