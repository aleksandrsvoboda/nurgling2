package nurgling.tasks;

import haven.WItem;
import haven.Widget;
import nurgling.NGItem;
import nurgling.NInventory;
import nurgling.tools.NAlias;
import nurgling.tools.StackSupporter;

public class GetNotStack extends NTask
{
    NAlias name;
    NInventory inventory;

    final int maxSize;

    public GetNotStack(NInventory inventory, NAlias name)
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
                        if (
                                item.item.contents == null
                                && StackSupporter.isStackable((NInventory) item.item.parent, ((NGItem) item.item).name())
                        ) {
                            result = item;
                            return false;
                        }
                    }
                }
            }
        }
        return false;
    }

    private WItem result = null;

    public WItem getResult(){
        return result;
    }
}