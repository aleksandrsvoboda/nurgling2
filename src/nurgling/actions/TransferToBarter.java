package nurgling.actions;

import haven.Gob;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.res.ui.barterbox.Shopbox;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NContext;
import nurgling.tasks.WaitItems;
import nurgling.tasks.WindowIsClosed;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;

public class TransferToBarter implements Action{

    NAlias items;
    NContext.Barter barter;
    int th = 1;

    public TransferToBarter(NContext.Barter barter, NAlias items) {
        this.barter = barter;
        this.items = items;
    }

    public TransferToBarter(NContext.Barter barter, NAlias items, int th) {
        this.barter = barter;
        this.items = items;
        this.th = th;
    }


    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Gob barterGob = Finder.findGob(barter.barter);
        Gob chestGob = Finder.findGob(barter.chest);

        ArrayList<WItem> wItems = NUtils.getGameUI().getInventory().getItems(items,th);
        while (!wItems.isEmpty()) {
            new PathFinder(barterGob).run(gui);
            new OpenTargetContainer("Barter Stand", barterGob).run(gui);

            Window barter_wnd = gui.getWindow("Barter Stand");
            if (barter_wnd == null) {
                return Results.ERROR("No Barter window");
            }

            for (Widget ch = barter_wnd.child; ch != null; ch = ch.next) {
                if (ch instanceof Shopbox) {
                    Shopbox sb = (Shopbox) ch;
                    Shopbox.ShopItem price = sb.getPrice();
                    if (price != null) {
                        if (NParser.checkName(price, items)) {

                            int startSize = gui.getInventory().getItems("Branch").size();
                            int target_size = (sb.leftNum != 0) ? Math.min(wItems.size(), sb.leftNum) : wItems.size();
                            for (int i = 0; i < target_size; i++) {
                                sb.wdgmsg("buy", new Object[0]);
                            }
                            NUtils.getUI().core.addTask(new WaitItems(NUtils.getGameUI().getInventory(), new NAlias("Branch"), target_size + startSize));
                            new PathFinder(chestGob).run(gui);
                            new OpenTargetContainer("Chest", chestGob).run(gui);
                            ArrayList<WItem> branchitems = gui.getInventory().getItems("Branch");
                            new SimpleTransferToContainer(gui.getInventory("Chest"), gui.getInventory().getItems("Branch"), branchitems.size()-startSize).run(gui);
                            wItems = NUtils.getGameUI().getInventory().getItems(items,th);
                            Window wnd = NUtils.getGameUI().getWindow("Chest");
                            if(wnd!=null) {
                                wnd.wdgmsg("close");
                                gui.ui.core.addTask(new WindowIsClosed(wnd));
                            }
                        }
                    }
                }
            }
        }
        return Results.SUCCESS();
    }

}
