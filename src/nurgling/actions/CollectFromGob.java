package nurgling.actions;

import haven.*;
import nurgling.NFlowerMenu;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.NFlowerMenuIsClosed;
import nurgling.tasks.WaitCollectState;
import nurgling.tasks.WaitPose;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import static haven.OCache.posres;


public class CollectFromGob implements Action{

    Gob target;
    String action;
    String pose;
    boolean withPiles = false;
    Coord targetSize = null;
    int marker = - 1;
    public CollectFromGob(Gob target, String action, String pose, Coord targetSize, NAlias targetItems, Pair<Coord2d, Coord2d> pileArea) {
        this.target = target;
        this.action = action;
        this.pose = pose;
        this.targetSize = targetSize;
        this.withPiles = true;
        this.targetItems = targetItems;
        this.pileArea = pileArea;
    }

    public CollectFromGob(Gob target, String action, String pose, boolean withPiles, Coord targetSize, int marker, NAlias targetItems, Pair<Coord2d, Coord2d> pileArea) {
        this.target = target;
        this.action = action;
        this.pose = pose;
        this.withPiles = withPiles;
        this.targetSize = targetSize;
        this.marker = marker;
        this.targetItems = targetItems;
        this.pileArea = pileArea;
    }

    public CollectFromGob(Gob target, String action, String pose, Coord targetSize,  NAlias targetItems, boolean withoutTransfer)
    {
        this.target = target;
        this.action = action;
        this.pose = pose;
        this.withPiles = false;
        this.targetSize = targetSize;
        this.targetItems = targetItems;
        this.withoutTransfer = withoutTransfer;
    }

    /**
     * Zone-mode variant. A full inventory is handed to {@link FreeInventory2}, which routes every
     * item to whatever output zone is registered for it, rather than to a hand-picked pile area -
     * so a bot driven off a specialisation zone needs no second area selection. returnArea is where
     * to walk back to afterwards, since FreeInventory2 can leave the player in another map cell.
     */
    public CollectFromGob(Gob target, String action, String pose, Coord targetSize, NAlias targetItems, NContext context, NArea returnArea) {
        this.target = target;
        this.action = action;
        this.pose = pose;
        this.targetSize = targetSize;
        this.targetItems = targetItems;
        this.dumpContext = context;
        this.returnArea = returnArea;
    }

     NAlias targetItems;
    Pair<Coord2d,Coord2d> pileArea = null;

    NContext dumpContext = null;
    NArea returnArea = null;

    boolean withoutTransfer = false;
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        WaitCollectState wcs = null;
        do {
            if(!withoutTransfer) {
                if (NUtils.getGameUI().getInventory().getNumberFreeCoord(targetSize) == 0) {
                    if (dumpContext != null) {
                        new FreeInventory2(dumpContext).run(gui);
                        // Nothing could be stored - no output zone accepts these items, so every
                        // further collect would just refill a pack that can never be emptied.
                        if (NUtils.getGameUI().getInventory().getNumberFreeCoord(targetSize) == 0)
                            return Results.FAIL();
                        // ensurePresence=true: the next thing this loop does is click target, so the
                        // area has to be streamed back in, not merely "reachable by local PF". The
                        // gob object itself is dropped when its grid unloads - re-resolve it by id.
                        NUtils.navigateToArea(returnArea, true);
                        Gob fresh = Finder.findGob(target.id);
                        if (fresh == null)
                            return Results.SUCCESS();
                        target = fresh;
                    }
                    else if (withPiles)
                        new TransferToPiles(pileArea, targetItems).run(gui);
                }
            }
            if(marker!=-1)
            {
                if((target.ngob.getModelAttribute()&marker)!=marker)
                {
                    return Results.SUCCESS();
                }
            }
            gui.map.wdgmsg("click", Coord.z, target.rc.floor(posres), 3, 0, 1, (int) target.id, target.rc.floor(posres),
                    0, -1);
            NFlowerMenu fm = NUtils.findFlowerMenu();
            if (fm != null) {
                if (fm.hasOpt(action)) {
                    new PathFinder(target).run(gui);
                    if (fm.chooseOpt(action)) {
                        NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
                        NUtils.getUI().core.addTask(new WaitPose(NUtils.player(), pose));
                        wcs = new WaitCollectState(target, targetSize);
                        NUtils.getUI().core.addTask(wcs);
                    } else {
                        NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
                    }
                } else {
                    fm.wdgmsg("cl", -1);
                    NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
                    return Results.FAIL();
                }
            }
            else
            {
                return Results.FAIL();
            }

        }
        while (wcs!=null && wcs.getState()!= WaitCollectState.State.NOITEMSFORCOLLECT);
        return Results.SUCCESS();
    }
}
