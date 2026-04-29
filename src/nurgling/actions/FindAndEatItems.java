package nurgling.actions;

import haven.*;
import nurgling.*;
import nurgling.areas.NContext;
import nurgling.iteminfo.NFoodInfo;
import nurgling.tools.NAlias;

import java.util.ArrayList;

public class FindAndEatItems implements Action
{
    final NContext cnt;
    ArrayList<String> items;
    double level;
    Pair<Coord2d,Coord2d> area;
    public FindAndEatItems(NContext context, ArrayList<String> items, int level, Pair<Coord2d,Coord2d> area)
    {
        this.cnt = context;
        this.items = items;
        this.level = level;
        this.area = area;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        for(String item : items)
        {
           cnt.addInItem(item, null);
        }

        eatAll(gui);
        return Results.SUCCESS();
    }

    boolean calcCalories() throws InterruptedException {
        double curlvl = NUtils.getEnergy()*10000;
        ArrayList<WItem> taritems = NUtils.getGameUI().getInventory().getItems(new NAlias(items));
        for(WItem item: taritems)
        {
            NFoodInfo fi = ((NGItem)item.item).getInfo(NFoodInfo.class);
            curlvl+=fi.end*100;
        }
        return curlvl<level;
    }

    void eatAll(NGameUI gui) throws InterruptedException {
        ArrayList<WItem> titems = NUtils.getGameUI().getInventory().getItems(new NAlias(items));

        for (WItem item : titems)
        {
            new SelectFlowerAction("Eat", (NWItem) item).run(gui);
        }
    }
}
