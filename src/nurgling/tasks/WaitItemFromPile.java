package nurgling.tasks;

import haven.GItem;
import haven.WItem;
import haven.Widget;
import haven.res.ui.stackinv.ItemStack;
import nurgling.NGItem;
import nurgling.NInventory;
import nurgling.NUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Waits until a stockpile transfer has delivered target_size items.
 *
 * How much arrived cannot be measured by counting the item widgets the transfer created.
 * Once the client bundles items into stacks a stack is a synthetic container widget plus
 * one child widget per stacked item, so every stacked item shows up twice - on its own and
 * again through its container - and forming a stack also destroys and re-creates the
 * widgets of items that were already in the inventory, which never came from the pile at
 * all. Counting widgets therefore reports two to three times what was really taken, and a
 * caller that subtracts that from what it asked for lands below zero and never finishes.
 *
 * So whenever the inventory and its pre-transfer item count are known, progress is measured
 * as the growth of the inventory itself, which does not care how the client chose to group
 * the items. The widget monitor is still read, but only to report which items are new (see
 * getResult()), and there it is de-duplicated so a stacked item is listed once.
 */
public class WaitItemFromPile extends NTask
{
    private final int target_size;
    private final NInventory inv;
    private final int baseline;
    private int totalItemCount = 0;

    public WaitItemFromPile()
    {
        this(null, 0, 1);
    }

    public WaitItemFromPile(int target_size)
    {
        this(null, 0, target_size);
    }

    /**
     * @param inv      inventory the items are being transferred into
     * @param baseline number of items in inv taken before the transfer was started
     */
    public WaitItemFromPile(NInventory inv, int baseline, int target_size)
    {
        this.inv = inv;
        this.baseline = baseline;
        this.target_size = target_size;
    }

    @Override
    public boolean check()
    {
        Set<NGItem> fresh = Collections.newSetFromMap(new IdentityHashMap<NGItem, Boolean>());
        for (Widget widget : NUtils.getUI().getMonitorInfo())
        {
            if (!(widget instanceof NGItem))
                continue;
            NGItem item = (NGItem) widget;
            if (item.name() == null)
                return false;
            if (item.contents instanceof ItemStack)
            {
                /* A stack container carries no item of its own, so count what is inside it.
                 * Those children are normally monitored in their own right as well, which
                 * is exactly what the identity set is here to collapse. */
                for (Widget cwidget = item.contents.child; cwidget != null; cwidget = cwidget.next)
                {
                    if (cwidget instanceof WItem && ((WItem) cwidget).item instanceof NGItem)
                        fresh.add((NGItem) ((WItem) cwidget).item);
                }
            }
            else
            {
                fresh.add(item);
            }
        }
        result.clear();
        result.addAll(fresh);

        if (inv != null)
        {
            /* Reuse GetItems so this count is built exactly like the baseline the caller
             * measured with getItems().size(). */
            GetItems probe = new GetItems(inv);
            if (!probe.check())
                return false;
            totalItemCount = probe.getResult().size() - baseline;
        }
        else
        {
            totalItemCount = 0;
            for (NGItem item : fresh)
                totalItemCount += getItemCount(item);
        }
        return totalItemCount >= target_size;
    }

    private int getItemCount(NGItem item)
    {
        GItem.Amount amount = item.getInfo(GItem.Amount.class);
        if (amount != null && amount.itemnum() > 0) {
            return amount.itemnum();
        }
        return 1;
    }

    private final ArrayList<NGItem> result = new ArrayList<>();

    public ArrayList<NGItem> getResult(){
        return result;
    }

    public int getTotalItemCount(){
        return totalItemCount;
    }
}
