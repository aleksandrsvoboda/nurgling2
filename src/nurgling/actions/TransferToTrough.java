package nurgling.actions;

import haven.Coord2d;
import haven.Gob;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.bots.SwillItemRegistry;
import nurgling.tasks.ChangeModelAtrib;
import nurgling.tasks.FilledTrough;
import nurgling.tasks.WaitFreeHand;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;

public class TransferToTrough implements Action {
    @Override
    public Results run ( NGameUI gui )
            throws InterruptedException {
            if(trough == null)
                return Results.ERROR("NO THROUGH");
            ArrayList<WItem> witems;

            while(!(witems = gui.getInventory ().getItems( items )).isEmpty()) {
                new PathFinder(trough).run(gui);
                if(trough.ngob.getModelAttribute()==7 )
                {
                    if(cistern!=null) {
                        if (NUtils.getGameUI().vhand != null) {
                            gui.getInventory().dropOn(gui.getInventory().findFreeCoord(NUtils.getGameUI().vhand));
                        }
                        Coord2d pos = trough.rc;
                        double a = trough.a;
                        new LiftObject(trough).run(gui);
                        new PathFinder ( cistern ).run(gui);
                        NUtils.activateGob ( cistern );
                        NUtils.getUI().core.addTask(new ChangeModelAtrib(trough, 7));
                        new PlaceObject(trough, pos, a).run(gui);
                    }
                    else {
                        // Full trough and nowhere to empty it - nothing more can be deposited,
                        // so leave the rest in the inventory instead of looping on a drop that
                        // the server will keep refusing.
                        break;
                    }
                }
                boolean carriesOtherSwill = carriesOtherSwill(gui);
                NUtils.takeItemToHand(witems.get(0));
                if(carriesOtherSwill) {
                    NUtils.activateItem(trough, false);
                    NUtils.getUI().core.addTask(new WaitFreeHand());
                } else {
                    NUtils.dropsame(trough);
                    NUtils.getUI().core.addTask(new FilledTrough(trough, items));
                }
            }
        return Results.SUCCESS();
        }

    /**
     * Whether the inventory holds swill-compatible items other than the ones being transferred.
     *
     * dropsame() is a shift+ctrl itemact, which makes the server empty everything the trough
     * accepts out of the inventory - not just the item in hand. A pumpkin harvest reaches the
     * trough holding both Pumpkin Seeds and Pumpkin Flesh, and the flesh went in with the seeds
     * instead of to its own stockpile. When anything else edible is carried, deposit one item at
     * a time so only the requested items move.
     */
    private boolean carriesOtherSwill(NGameUI gui) throws InterruptedException {
        for (WItem item : gui.getInventory().getItems()) {
            String name = ((NGItem) item.item).name();
            if (name == null || NParser.checkName(name, items))
                continue;
            if (SwillItemRegistry.isSwillItem(name))
                return true;
        }
        return false;
    }



    public TransferToTrough(
            Gob gob,
            NAlias items
    )
    {
        this.trough = gob;
        this.items = items;
    }

    public TransferToTrough(
            Gob gob,
            NAlias items,
            Gob cistern
    )
    {
        this(gob,items);
        this.cistern = cistern;
    }

    Gob trough;
    Gob cistern = null;
    NAlias items;
}