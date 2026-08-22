package nurgling.tasks;

import haven.WItem;
import haven.Widget;
import nurgling.NGItem;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

/**
 * Waits until a transfer that was just issued actually removed something from the player.
 *
 * {@link WaitItems} cannot be used for this: its check is {@code actualCount >= target_size},
 * which is satisfied the instant it is asked about a shrinking inventory, so callers that used it
 * to wait for items to leave never waited at all and walked away mid-transfer.
 *
 * The item currently held in the hand counts towards the total, so taking one item to hand before
 * the transfer does not read as progress on its own. When the count stops changing for
 * {@code stableLimit} consecutive checks the container is refusing the item (full, or holding
 * something else) and the task completes with {@link #decreased()} false, letting the caller move
 * on instead of blocking forever.
 */
public class WaitItemsDecrease extends NTask
{
    private final NInventory inventory;
    private final NAlias name;
    private final String exactName;
    private final int before;
    private final int stableLimit;

    private int stable = 0;
    private int last = -1;
    private boolean decreased = false;

    public WaitItemsDecrease(NInventory inventory, NAlias name, String exactName, int before)
    {
        this(inventory, name, exactName, before, 150);
    }

    public WaitItemsDecrease(NInventory inventory, NAlias name, String exactName, int before, int stableLimit)
    {
        this.inventory = inventory;
        this.name = name;
        this.exactName = exactName;
        this.before = before;
        this.stableLimit = stableLimit;
    }

    /** Whether anything actually left the player before the count went quiet. */
    public boolean decreased()
    {
        return decreased;
    }

    @Override
    public boolean check()
    {
        int cur = count(inventory.child);
        if (cur < 0)
            return false;
        cur += handCount();

        if (cur < before)
        {
            decreased = true;
            return true;
        }
        if (cur == last)
            return ++stable >= stableLimit;
        last = cur;
        stable = 0;
        return false;
    }

    /** Number of matching items below {@code first}, or -1 while any of them is still loading. */
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
            if (item.item.contents != null)
            {
                int sub = count(item.item.contents.child);
                if (sub < 0)
                    return -1;
                res += sub;
            }
            else if (matches(item))
            {
                res++;
            }
        }
        return res;
    }

    private int handCount()
    {
        WItem hand = NUtils.getGameUI() == null ? null : NUtils.getGameUI().vhand;
        return (hand != null && matches(hand)) ? 1 : 0;
    }

    private boolean matches(WItem item)
    {
        String itemName = ((NGItem) item.item).name();
        if (itemName == null)
            return false;
        if (exactName != null)
            return itemName.equals(exactName);
        return name == null || NParser.checkName(itemName, name);
    }
}
