package nurgling.actions.bots.cheeseconveyor;

import haven.WItem;
import nurgling.*;
import nurgling.actions.SelectFlowerAction;
import nurgling.actions.bots.cheese.CheeseConstants;
import nurgling.actions.bots.cheese.CheeseUtils;
import nurgling.cheese.ConveyorOrder;
import nurgling.tasks.NTask;
import nurgling.tools.NAlias;

/**
 * Copy of {@link nurgling.actions.bots.cheese.CheeseSlicingManager} for the Cheese Conveyor bot:
 * counts a slice against the conveyor order instead of the Cheese Production Bot's orders.
 */
public class ConveyorSlicer {
    /**
     * Slice a cheese tray and count it against {@code order}.
     */
    public void sliceCheese(NGameUI gui, WItem tray, ConveyorOrder order) throws InterruptedException {
        if (tray == null) {
            gui.msg("Cannot slice: tray is null");
            return;
        }

        String cheeseType = CheeseUtils.getContentName(tray);
        if (cheeseType == null || cheeseType.isEmpty()) {
            gui.msg("Cannot slice: unable to determine cheese type");
            return;
        }

        // Slicing typically produces 4-5 cheese pieces + 1 empty tray
        if (gui.getInventory().getFreeSpace() < CheeseConstants.SLICING_INVENTORY_REQUIREMENT) {
            return;
        }

        // Count items before slicing to detect changes
        int initialCheeseCount = getCheeseCount(gui, cheeseType);
        int initialEmptyTrayCount = getEmptyTrayCount(gui);

        new SelectFlowerAction("Slice up", tray).run(gui);

        // Wait for slicing to complete - check for cheese pieces OR empty tray to appear
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                try {
                    int currentCheeseCount = getCheeseCount(gui, cheeseType);
                    int currentEmptyTrayCount = getEmptyTrayCount(gui);
                    return currentCheeseCount > initialCheeseCount || currentEmptyTrayCount > initialEmptyTrayCount;
                } catch (InterruptedException e) {
                    return false;
                }
            }
        });

        if (order != null)
            order.sliced();
    }

    private int getCheeseCount(NGameUI gui, String cheeseType) throws InterruptedException {
        return gui.getInventory().getItems(new NAlias(cheeseType)).size();
    }

    private int getEmptyTrayCount(NGameUI gui) throws InterruptedException {
        return gui.getInventory().getItems(new NAlias(CheeseConstants.EMPTY_CHEESE_TRAY_NAME)).size();
    }
}
