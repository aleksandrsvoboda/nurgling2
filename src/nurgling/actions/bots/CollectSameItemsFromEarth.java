package nurgling.actions.bots;

import haven.Gob;
import haven.Resource;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.WaitItems;
import nurgling.tools.NAlias;

import java.util.ArrayList;

public class CollectSameItemsFromEarth implements Action {

    NAlias itemName;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);

        SelectGob selgob;
        NUtils.getGameUI().msg("Please select item for pile");
        (selgob = new SelectGob(Resource.loadsimg("baubles/selectItem"))).run(gui);
        Gob target = selgob.result;
        if(target==null)
        {
            return Results.ERROR("Item not found");
        }
        String insaId = context.createArea("Please select area with items", Resource.loadsimg("baubles/inputArea"));
        NArea insaArea = context.goToAreaById(insaId);
        String outsaId = context.createArea("Please select area for piles", Resource.loadsimg("baubles/outputArea"));
        NArea outsaArea = context.goToAreaById(outsaId);

        new PathFinder(target).run(gui);
        ArrayList<WItem> oldItems = NUtils.getGameUI().getInventory().getItems();
        NUtils.rclickGob(target);
        WaitItems wi = new WaitItems(NUtils.getGameUI().getInventory(),oldItems.size() + 1);
        NUtils.addTask(wi);
        for(WItem wItem : wi.getResult())
        {
            if(!oldItems.contains(wItem))
            {
                itemName = new NAlias(((NGItem)wItem.item).name());
                itemName.keys.add(target.ngob.name);
                itemName.buildCaches(); // Rebuild caches after modifying keys
                break;
            }
        }
        new CollectItemsToPile(insaArea.getRCArea(),outsaArea.getRCArea(),itemName).run(gui);
        return Results.SUCCESS();
    }
}
