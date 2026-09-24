package nurgling.actions.bots.cheeseconveyor;

import haven.WItem;
import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.CreateTraysWithCurds;
import nurgling.actions.FreeInventory2;
import nurgling.actions.Results;
import nurgling.actions.bots.cheese.CheeseConstants;
import nurgling.actions.bots.cheese.CheeseInventoryOperations;
import nurgling.actions.bots.cheese.CheeseUtils;
import nurgling.areas.NContext;
import nurgling.cheese.CheeseBranch;
import nurgling.cheese.CheeseOrder;
import nurgling.cheese.ConveyorOrder;
import nurgling.cheese.ConveyorOrdersManager;

import java.util.List;

/**
 * Copy of {@link nurgling.actions.ProcessCheeseOrderInBatches} for the Cheese Conveyor bot: fills
 * curd trays for one conveyor order and puts them on the racks of its first stage, limited to the
 * first-stage slots this order was given.
 */
public class ConveyorStartCurds implements Action {
    private final ConveyorOrder order;
    private final int allotted;
    private final ConveyorOrdersManager ordersManager;
    private final ConveyorRackPlacer placer = new ConveyorRackPlacer();
    private int placed = 0;

    public ConveyorStartCurds(ConveyorOrder order, int allotted, ConveyorOrdersManager ordersManager) {
        this.order = order;
        this.allotted = allotted;
        this.ordersManager = ordersManager;
    }

    /** Trays put on racks by the last run. */
    public int getPlaced() {
        return placed;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        placed = 0;
        List<CheeseBranch.Cheese> chain = CheeseBranch.getChainToProduct(order.getCheeseType());
        if (chain == null || chain.size() < 2)
            return Results.ERROR("Unknown cheese type: " + order.getCheeseType());

        CheeseOrder.StepStatus start = order.startStep();
        CheeseBranch.Place firstPlace = chain.get(1).place;
        int target = Math.min(allotted, start.left);

        while (placed < target) {
            int batch = Math.min(gui.getInventory().getNumberFreeCoord(CheeseConstants.CHEESE_TRAY_SIZE), target - placed);
            if (batch <= 0)
                break;

            CreateTraysWithCurds create = new CreateTraysWithCurds(start.name, batch);
            create.run(gui);
            int created = create.getLastTraysCreated();

            int inInventory = countTraysOfType(gui, start.name);
            int placedNow = 0;
            if (inInventory > 0)
                placedNow = placer.moveTraysToRacks(gui, firstPlace, Math.min(inInventory, target - placed), start.name, ordersManager, order);
            returnEmptyTraysToStorage(gui);

            placed += placedNow;
            if (created == 0 || placedNow == 0)
                break;
        }
        return Results.SUCCESS();
    }

    private int countTraysOfType(NGameUI gui, String contentName) throws InterruptedException {
        int count = 0;
        for (WItem tray : CheeseInventoryOperations.getCheeseTrays(gui))
            if (contentName.equals(CheeseUtils.getContentName(tray)))
                count++;
        return count;
    }

    /**
     * Return unused empty trays to storage after filled trays have been placed on racks,
     * so trays of different curds don't mix in the inventory.
     */
    private void returnEmptyTraysToStorage(NGameUI gui) throws InterruptedException {
        for (WItem tray : CheeseInventoryOperations.getCheeseTrays(gui)) {
            if (CheeseUtils.isEmpty(tray)) {
                NContext context = new NContext(gui);
                context.addOutItem(CheeseConstants.CHEESE_TRAY_NAME, null, 0);
                new FreeInventory2(context).run(gui);
                return;
            }
        }
    }
}
