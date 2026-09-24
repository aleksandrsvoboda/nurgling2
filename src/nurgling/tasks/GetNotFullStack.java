package nurgling.tasks;

import haven.WItem;
import haven.Widget;
import haven.res.ui.stackinv.ItemStack;
import nurgling.NGItem;
import nurgling.NInventory;
import nurgling.tools.NAlias;
import nurgling.tools.StackSupporter;

public class GetNotFullStack extends NTask
{
    NAlias name;
    NInventory inventory;

    final int maxSize;

    public GetNotFullStack(NInventory inventory, NAlias name)
    {
        this.name = name;
        this.inventory = inventory;
        maxSize = StackSupporter.getFullStackSize(name.getDefault());
    }


    @Override
    public boolean check()
    {
        result = null;
        return !checkContainer(inventory.child);
    }

    private boolean checkContainer(Widget first) {
        for (Widget widget = first; widget != null; widget = widget.next) {
            if (widget instanceof WItem) {
                WItem item = (WItem) widget;
                if (!NGItem.validateItem(item)) {
                    return true;
                } else {
                    /* matchesExact, not checkName: a stack merge only succeeds between
                     * identical items, but NAlias matches by substring, so a search for
                     * "Animal Fat" also returns "Rendered Animal Fat". Handing that back
                     * as a merge target makes the caller itemact() an item the server
                     * refuses to merge; the merge never happens and the StackSizeChanged
                     * that follows waits forever (NTask.infinite defaults to true). */
                    if (name.matchesExact(((NGItem)item.item).name())) {
                        /* Strictly less than, not "different from": maxSize is our guess at the
                         * server's stack depth, and when it guesses low every stack the server
                         * built deeper than that would come back as a fill target. Merging into
                         * one is a no-op server-side, and the item stays stuck in the hand. */
                        if (item.item.contents != null && ((ItemStack) item.item.contents).wmap.size() < maxSize) {
                            result = (ItemStack) item.item.contents;
                            return false;
                        }
                    }
                }
            }
        }
        return false;
    }

    private ItemStack result = null;

    public ItemStack getResult(){
        return result;
    }
}