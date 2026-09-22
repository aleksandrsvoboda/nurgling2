package nurgling.actions.bots.cheeseconveyor;

import haven.Gob;
import haven.WItem;
import nurgling.NGameUI;
import nurgling.actions.CloseTargetContainer;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.PathFinder;
import nurgling.actions.TransferWItemsToContainer;
import nurgling.actions.bots.cheese.CheeseAreaManager;
import nurgling.actions.bots.cheese.CheeseConstants;
import nurgling.actions.bots.cheese.CheeseInventoryOperations;
import nurgling.actions.bots.cheese.CheeseRackOverlayUtils;
import nurgling.actions.bots.cheese.CheeseUtils;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.cheese.CheeseBranch;
import nurgling.cheese.ConveyorOrder;
import nurgling.cheese.ConveyorOrdersManager;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;

/**
 * Copy of {@link nurgling.actions.bots.cheese.CheeseRackManager} for the Cheese Conveyor bot:
 * places filled curd trays on the racks of their first stage and counts them against the
 * conveyor order.
 */
public class ConveyorRackPlacer {
    /**
     * Move curd trays of {@code curdType} from the inventory to racks in the areas of {@code targetPlace}.
     * Fills one area completely before moving to the next.
     *
     * @return number of trays placed
     */
    public int moveTraysToRacks(NGameUI gui, CheeseBranch.Place targetPlace, int quantity, String curdType,
                                ConveyorOrdersManager ordersManager, ConveyorOrder order) throws InterruptedException {
        int moved = 0;

        ArrayList<NArea> targetAreas = CheeseAreaManager.getAllCheeseAreas(targetPlace);
        if (targetAreas.isEmpty()) {
            return 0;
        }

        for (NArea targetArea : targetAreas) {
            if (moved >= quantity) break;

            NContext context = new NContext(gui);
            context.goToAreaById(targetArea.id);

            ArrayList<Gob> racks = Finder.findGobs(targetArea, new NAlias(CheeseConstants.CHEESE_RACK_RESOURCE));

            for (Gob rack : racks) {
                if (moved >= quantity) break;

                // Skip visually full racks before walking to them
                if (CheeseRackOverlayUtils.isRackFull(rack)) {
                    continue;
                }

                Container rackContainer = new Container(rack, CheeseConstants.RACK_CONTAINER_TYPE, targetArea);
                rackContainer.initattr(Container.Space.class);
                new PathFinder(rack).run(gui);
                new OpenTargetContainer(rackContainer).run(gui);

                int availableSpace = gui.getInventory(rackContainer.cap).getNumberFreeCoord(CheeseConstants.CHEESE_TRAY_SIZE);
                if (availableSpace == 0) {
                    new CloseTargetContainer(rackContainer).run(gui);
                    continue;
                }

                ArrayList<WItem> trays = getTraysOfType(gui, curdType, Math.min(availableSpace, quantity - moved));
                if (!trays.isEmpty()) {
                    new TransferWItemsToContainer(rackContainer, trays).run(gui);
                    moved += trays.size();
                    order.advance(curdType, CheeseBranch.Place.start, trays.size());
                    ordersManager.save();
                }

                new CloseTargetContainer(rackContainer).run(gui);
            }
        }

        return moved;
    }

    private ArrayList<WItem> getTraysOfType(NGameUI gui, String cheeseType, int maxCount) throws InterruptedException {
        ArrayList<WItem> specificTrays = new ArrayList<>();
        for (WItem tray : CheeseInventoryOperations.getCheeseTrays(gui)) {
            if (specificTrays.size() >= maxCount) break;
            if (cheeseType.equals(CheeseUtils.getContentName(tray))) {
                specificTrays.add(tray);
            }
        }
        return specificTrays;
    }
}
