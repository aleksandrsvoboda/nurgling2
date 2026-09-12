package nurgling.tasks;

import haven.WItem;
import haven.Widget;
import haven.res.ui.stackinv.ItemStack;
import nurgling.NGItem;
import nurgling.NInventory;
import nurgling.tools.NAlias;
import nurgling.tools.StackSupporter;

public class GetNotFullStack extends NTask {
    NAlias name;
    NInventory inventory;

    final int maxSize;

    public GetNotFullStack(NInventory inventory, NAlias name) {
        this.name = name;
        this.inventory = inventory;
        maxSize = StackSupporter.getFullStackSize(name.getDefault());
    }

    @Override
    public boolean check() {
        result = null;
        return !checkContainer(inventory.child);
    }

    private boolean checkContainer(Widget first) {
        for (Widget widget = first; widget != null; widget = widget.next) {
            if (widget instanceof WItem) {
                WItem item = (WItem) widget;

                if (!NGItem.validateItem(item)) {
                    return true;
                }

                String actualName = ((NGItem) item.item).name();

                if (name.getDefault().equals(actualName)) {
                    /*
                     * Strictly less than, not "different from": maxSize is our
                     * guess at the server's stack depth.
                     */
                    if (item.item.contents != null
                        && ((ItemStack) item.item.contents).wmap.size() < maxSize) {
                        result = (ItemStack) item.item.contents;
                        return false;
                    }
                }
            }
        }

        return false;
    }

    private ItemStack result = null;

    public ItemStack getResult() {
        return result;
    }
}