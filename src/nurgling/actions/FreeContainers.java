package nurgling.actions;

import haven.Gob;
import haven.WItem;
import nurgling.*;
import nurgling.areas.NContext;
import nurgling.tools.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;

public class FreeContainers implements Action
{
    ArrayList<Container> containers;
    NAlias pattern = null;

    public FreeContainers(ArrayList<Container> containers) {
        this.containers = containers;
    }

    public FreeContainers(ArrayList<Container> containers, NAlias pattern) {
        this.containers = containers;
        this.pattern = pattern;
    }

    HashSet<String> targets = new HashSet<>();

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        NContext context = new NContext(gui);

        for (Container container : containers)
        {
            Container.Space space;
            if ((space = container.getattr(Container.Space.class)).isReady())
            {
                if (space.getRes().get(Container.Space.FREESPACE) == space.getRes().get(Container.Space.MAXSPACE))
                    continue;
            }

            navigateToTargetContainer(gui, container);

            new OpenTargetContainer(container).run(gui);
            ArrayList<WItem> items = (pattern == null) ? gui.getInventory(container.cap).getItems() : gui.getInventory(container.cap).getItems(pattern);
            // NContext.addOutItem() caches the best-fitting area per item name and reuses it for
            // later calls whose quality merely exceeds an already-cached (possibly lower) threshold.
            // That cache is only correct if items are fed to it in descending quality order per name
            // (see FreeInventory2, which does the same sort) - otherwise a low-quality item scanned
            // first can lock in a low-threshold area, and a higher-quality item of the same name that
            // should go to a better zone gets routed there instead.
            items.sort(new Comparator<WItem>() {
                @Override
                public int compare(WItem o1, WItem o2) {
                    Float q1 = ((NGItem) o1.item).quality;
                    Float q2 = ((NGItem) o2.item).quality;
                    if (q1 == null && q2 == null)
                        return 0;
                    if (q1 == null)
                        return 1;
                    if (q2 == null)
                        return -1;
                    return Float.compare(q2, q1);
                }
            });
            for (WItem item : items)
            {
                if (context.addOutItem(((NGItem) item.item).name(), null, ((NGItem) item.item).quality != null ? ((NGItem) item.item).quality : 1))
                    targets.add(((NGItem) item.item).name());
            }
            while (!new TakeItemsFromContainer(container, targets, pattern).run(gui).isSuccess)
            {
                new TransferItems2(context, targets).run(gui);
                navigateToTargetContainer(gui, container);
                new OpenTargetContainer(container).run(gui);
            }
            new CloseTargetContainer(container).run(gui);
        }
        new TransferItems2(context, targets).run(gui);
        return Results.SUCCESS();
    }

    private void navigateToTargetContainer(NGameUI gui, Container container) throws InterruptedException {
        PathFinder pf;

        Gob gob = Finder.findGob(container.gobHash);
        if(gob!= null && PathFinder.isAvailable(gob)) {
            pf = new PathFinder(gob);
            pf.isHardMode = true;
            pf.run(gui);
        }
        else
        {
            if(container.parent!=null)
            {
                NUtils.navigateToArea(container.parent);
                gob = Finder.findGob(container.gobHash);
                if(gob!= null && PathFinder.isAvailable(gob)) {
                    pf = new PathFinder(gob);
                    pf.isHardMode = true;
                    pf.run(gui);
                }
            }
        }
    }
}
