package nurgling.tasks;

import haven.GItem;
import haven.WItem;
import haven.Widget;
import nurgling.NGItem;
import nurgling.NISBox;
import nurgling.NInventory;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.function.IntSupplier;

public class WaitMoreItems extends NTask
{
    private final int target_size;
    NAlias name = null;
    Widget inventory;
    private IntSupplier visibilitySource = null;

    GItem target = null;
    public WaitMoreItems(NInventory inventory, NAlias name, int size)
    {
        this.name = name;
        this.inventory = inventory;
        this.target_size = size;
    }

    public WaitMoreItems(NInventory inventory, NAlias name, int size, int maxChecks)
    {
        this(inventory, name, size);
        makeBounded(maxChecks);
    }

    WaitMoreItems(IntSupplier visibilitySource, int size, int maxChecks)
    {
        this.visibilitySource = visibilitySource;
        this.inventory = null;
        this.target_size = size;
        makeBounded(maxChecks);
    }

    public WaitMoreItems(NInventory inventory, GItem target, int size)
    {
        this.target = target;
        this.inventory = inventory;
        this.target_size = size;
    }

    public WaitMoreItems(NISBox inv, int size)
    {
        this.inventory = inv;
        this.target_size = size;
    }

    public WaitMoreItems(NInventory inv, int size)
    {
        this.inventory = inv;
        this.target_size = size;
    }

    @Override
    public boolean check()
    {
        if (visibilitySource != null)
        {
            int visibleItems = visibilitySource.getAsInt();
            return visibleItems >= target_size && visibleItems > 0;
        }

        if (target != null)
            if (((NGItem) target).name() != null)
                name = new NAlias(((NGItem) target).name());
            else
                return false;

        if(inventory instanceof NInventory)
        {
            result.clear();
            synchronized (inventory.ui)
            {
                if (!InventoryItemTree.collectMatching(
                        directItems(inventory.child),
                        name,
                        WITEM_ADAPTER,
                        result
                ))
                    return false;
            }
            return result.size() >= target_size && !result.isEmpty();
        }
        else if(inventory instanceof NISBox)
        {
            return ((NISBox) inventory).calcFreeSpace() >= target_size;
        }
        return false;
    }

    private void makeBounded(int maxChecks)
    {
        if (maxChecks <= 0)
            throw new IllegalArgumentException("maxChecks must be positive");
        this.maxCounter = maxChecks;
        this.infinite = false;
    }

    private static ArrayList<WItem> directItems(Widget first)
    {
        ArrayList<WItem> items = new ArrayList<>();
        for (Widget widget = first; widget != null; widget = widget.next)
        {
            if (widget instanceof WItem)
                items.add((WItem) widget);
        }
        return items;
    }

    private static final InventoryItemTree.Adapter<WItem> WITEM_ADAPTER =
            new InventoryItemTree.Adapter<WItem>() {
                @Override
                public String name(WItem item) {
                    return ((NGItem) item.item).name();
                }

                @Override
                public Iterable<WItem> children(WItem item) {
                    return item.item.contents == null
                            ? null
                            : directItems(item.item.contents.child);
                }
            };

    private ArrayList<WItem> result = new ArrayList<>();

    public ArrayList<WItem> getResult(){
        return result;
    }
}
