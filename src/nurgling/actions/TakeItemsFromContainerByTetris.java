package nurgling.actions;

import haven.Coord;
import haven.UI;
import haven.WItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.tasks.WaitItems;
import nurgling.tools.Container;
import nurgling.tools.NAlias;

import java.util.ArrayList;

public class TakeItemsFromContainerByTetris implements Action
{
    Container sourceCont;
    NAlias transferedItems;
    ArrayList<Container> conts;
    boolean isDone = false;

    public TakeItemsFromContainerByTetris(Container sourceCont, NAlias transferedItems, ArrayList<Container> conts)
    {
        this.sourceCont = sourceCont;
        this.transferedItems = transferedItems;
        this.conts = conts;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        Coord target_coord = new Coord(1, 1);
        for (Container container : conts) {
            Container.Tetris tetris = container.getattr(Container.Tetris.class);
            for (Coord coord : (ArrayList<Coord>) tetris.getRes().get(Container.Tetris.TARGET_COORD)) {
                target_coord.x = Math.max(target_coord.x, coord.x);
                target_coord.y = Math.max(target_coord.y, coord.y);
            }
        }

        while (gui.getInventory().getNumberFreeCoord(target_coord) > 0)
        {
            NInventory srcInv = gui.getInventory(sourceCont.cap);
            if (srcInv == null)
                break;
            WItem source = srcInv.getItem(transferedItems);
            if (source == null)
                break; // source container has no more matching items

            ArrayList<WItem> beforeItems = gui.getInventory().getItems(transferedItems);
            int moved = TransferToContainer.transfer(source, gui.getInventory(), 1);
            if (moved <= 0)
                break; // couldn't move anything -> avoid spinning forever

            WaitItems wi = new WaitItems(gui.getInventory(), transferedItems, beforeItems.size() + moved);
            NUtils.getUI().core.addTask(wi);
            sourceCont.update();

            // Only the item(s) that just arrived should be tried against the tetris grids -
            // tryPlace has no notion of "already placed", so re-trying items from a previous
            // pass would double-consume virtual grid cells.
            ArrayList<WItem> newlyArrived = gui.getInventory().getItems(transferedItems);
            newlyArrived.removeAll(beforeItems);

            for (WItem witem : newlyArrived) {
                for (Container container : conts) {
                    Container.Tetris tetris = container.getattr(Container.Tetris.class);
                    if (tetris.tryPlace(witem.item.spr.sz().div(UI.scale(32)).swapXY()))
                        break;
                }
            }

            boolean isSuccess = true;
            for (Container container : conts) {
                Container.Tetris tetris = container.getattr(Container.Tetris.class);
                if (!(Boolean) tetris.getRes().get(Container.Tetris.VIRTUAL)) {
                    isSuccess = false;
                }
            }
            if (isSuccess) {
                isDone = true;
                return Results.SUCCESS();
            }
        }
        if (gui.getInventory().getNumberFreeCoord(target_coord) == 0)
            isDone = true;
        return Results.SUCCESS();
    }

    public boolean isDone()
    {
        return isDone;
    }
}
