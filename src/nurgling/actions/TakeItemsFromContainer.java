package nurgling.actions;

import haven.Coord;
import haven.Inventory;
import haven.WItem;
import nurgling.*;
import nurgling.NInventory.QualityType;
import nurgling.tasks.WaitItems;
import nurgling.tools.Container;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.tools.StackSupporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;

public class TakeItemsFromContainer implements Action
{
    Coord target_coord = new Coord(1,1);
    Container cont;
    HashSet<String> names;
    NAlias pattern;
    QualityType qualityType;
    public int minSize = Integer.MAX_VALUE;
    public boolean exactMatch = false;

    /**
     * Optional lower bound on quality: copies below it are left where they are. Set by callers
     * that already ranked the whole source area (see {@link FindQualityThreshold}) so that taking
     * greedily, container by container, still yields the globally best copies.
     */
    public Float minQuality = null;
    public TakeItemsFromContainer(Container cont, HashSet<String> names, NAlias pattern)
    {
        this.cont = cont;
        this.names = names;
        this.pattern = pattern;
        this.qualityType = null;
    }

    double targetq = -1;
    public TakeItemsFromContainer(Container cont, HashSet<String> names, NAlias pattern, double q)
    {
        this.cont = cont;
        this.names = names;
        this.pattern = pattern;
        this.targetq = q;
        this.qualityType = null;
    }

    public TakeItemsFromContainer(Container cont, HashSet<String> names, NAlias pattern, QualityType qualityType)
    {
        this.cont = cont;
        this.names = names;
        this.pattern = pattern;
        this.qualityType = qualityType;
    }

    int target_size = 0;
    boolean took = false;

    // Quality comparators for sorting items (following GetItems pattern but with null safety)
    private Comparator<WItem> high = new Comparator<WItem>() {
        @Override
        public int compare(WItem lhs, WItem rhs) {
            Float qualityL = ((NGItem) lhs.item).quality;
            Float qualityR = ((NGItem) rhs.item).quality;
            if (qualityL == null && qualityR == null) return 0;
            if (qualityL == null) return 1;
            if (qualityR == null) return -1;
            return Double.compare(qualityR, qualityL);
        }
    };

    private Comparator<WItem> low = new Comparator<WItem>() {
        @Override
        public int compare(WItem lhs, WItem rhs) {
            Float qualityL = ((NGItem) lhs.item).quality;
            Float qualityR = ((NGItem) rhs.item).quality;
            if (qualityL == null && qualityR == null) return 0;
            if (qualityL == null) return 1;
            if (qualityR == null) return -1;
            return Double.compare(qualityL, qualityR);
        }
    };
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NInventory inv = gui.getInventory(cont.cap);
        int remainingToTake = minSize; // Track how many items we still need to take
        
        for(String name: names) {
            if (remainingToTake <= 0) {
                break; // Already took enough items
            }
            
            WItem item = inv.getItem(name);
            if (item != null) {

                target_coord = inv.getItem(name).sz.div(Inventory.sqsz);
                int oldSpace = gui.getInventory().getItems(name).size();
                ArrayList<WItem> items = getItems(gui, name);
                int items_size = items.size();
                target_size = Math.min(remainingToTake, Math.min(gui.getInventory().getNumberFreeCoord(target_coord.swapXY())*StackSupporter.getFullStackSize(name), items.size()));


                int temptr = target_size;

                for (int i = 0; i < temptr; i++) {
                    int left = target_size + oldSpace - gui.getInventory().getItems(name).size();
                    /* A quality-filtered take must move stack members one at a time. transfer()
                     * otherwise hands over a whole source stack once we want at least as many as
                     * it holds, and a stack mixes qualities - that would drag copies below the
                     * bound along with the good one we actually picked. */
                    TransferToContainer.transfer(items.get(i), gui.getInventory(), left, minQuality != null);
                    items = getItems(gui, name);
                    if(gui.getInventory().getItems(name).size()>=target_size+oldSpace)
                        break;
                    temptr=Math.min(remainingToTake, Math.min(gui.getInventory().getNumberFreeCoord(target_coord.swapXY())*StackSupporter.getFullStackSize(name), items.size()));
                    i = -1;
                }
                WaitItems wi = new WaitItems(gui.getInventory(), new NAlias(name), oldSpace + target_size);
                NUtils.getUI().core.addTask(wi);
                cont.update();
                
                // Reduce remainingToTake by how many we actually took
                int actuallyTaken = gui.getInventory().getItems(name).size() - oldSpace;
                remainingToTake -= actuallyTaken;
                
                if(items_size>target_size) {
                    took = false;
                    return Results.FAIL();
                }
            }
        }
        took = true;
        return Results.SUCCESS();
    }

    private ArrayList<WItem> getItems(NGameUI gui, String name) throws InterruptedException
    {
        ArrayList<WItem> items = gui.getInventory(cont.cap).getItems(name,1);
        HashSet<WItem> forRemove = new HashSet<>();

        for(WItem item1: items) {
            // Check for exact name match if exactMatch flag is set
            if (exactMatch && item1.item instanceof NGItem) {
                String itemName = ((NGItem) item1.item).name();
                if (itemName != null && !itemName.equalsIgnoreCase(name)) {
                    forRemove.add(item1);
                    continue;
                }
            }
            
            if (minQuality != null) {
                Float quality = ((NGItem) item1.item).quality;
                if (quality == null || quality < minQuality - FindQualityThreshold.QEPS) {
                    forRemove.add(item1);
                    continue;
                }
            }

            if (pattern != null) {
                if (!NParser.checkName(((NGItem) item1.item).name(), pattern)) {
                    forRemove.add(item1);
                }
            }
            if(targetq!=-1)
            {
                if(((NGItem) item1.item).quality>targetq)
                {
                    forRemove.add(item1);
                }
            }
        }
        items.removeAll(forRemove);

        // Apply quality sorting if priority is specified
        if (qualityType != null) {
            if (qualityType == QualityType.High) {
                Collections.sort(items, high);
            } else if (qualityType == QualityType.Low) {
                Collections.sort(items, low);
            }
        }

        return items;
    }

    public boolean getResult()
    {
        return took;
    }

    public int getTarget_size() {
        return target_size;
    }
}
