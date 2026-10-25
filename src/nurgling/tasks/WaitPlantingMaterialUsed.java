package nurgling.tasks;

import haven.GItem;
import haven.WItem;
import haven.Widget;
import nurgling.NGItem;
import nurgling.NInventory;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

/**
 * Waits until the inventory shows that planting used up some of the planting material.
 *
 * The crop gob can show up before the inventory update for the seed that grew it. An action
 * that goes on with the inventory in that gap still sees the planted item, and taking it to the
 * hand then waits forever, because the server has already destroyed it.
 *
 * Material is counted in units: the Amount of seed piles, or one per vegetable. Stack members
 * are counted one by one, the same way {@link GetItems} lists them.
 */
public class WaitPlantingMaterialUsed extends NTask
{
    private final NInventory inventory;
    private final NAlias name;
    private final int before;

    public WaitPlantingMaterialUsed(NInventory inventory, NAlias name, int before)
    {
        this.inventory = inventory;
        this.name = name;
        this.before = before;
    }

    @Override
    public boolean check()
    {
        int cur;
        synchronized (inventory.ui)
        {
            cur = count(inventory.child);
        }
        return cur >= 0 && cur < before;
    }

    /** Units of the material below {@code first}, or -1 while any item is still loading. */
    private int count(Widget first)
    {
        int res = 0;
        for (Widget widget = first; widget != null; widget = widget.next)
        {
            if (!(widget instanceof WItem))
                continue;
            WItem item = (WItem) widget;
            if (!NGItem.validateItem(item))
                return -1;
            if (!NParser.checkName(((NGItem) item.item).name(), name))
                continue;
            if (item.item.contents != null)
            {
                int sub = count(item.item.contents.child);
                if (sub < 0)
                    return -1;
                res += sub;
            }
            else
            {
                res += units(item);
            }
        }
        return res;
    }

    /** Planting units one inventory item holds: its Amount, or 1 when it has none. */
    public static int units(WItem item)
    {
        GItem.Amount amount = ((NGItem) item.item).getInfo(GItem.Amount.class);
        return amount != null ? amount.itemnum() : 1;
    }
}
