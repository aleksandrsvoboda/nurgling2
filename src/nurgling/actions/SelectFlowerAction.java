package nurgling.actions;

import haven.*;
import static haven.OCache.posres;
import haven.res.gfx.terobjs.roastspit.*;
import nurgling.*;
import nurgling.tasks.*;

public class SelectFlowerAction implements Action
{
    String opt;
    java.util.List<String> optCandidates = null;

    Object target;
    Sprite spr = null;
    Boolean petalIgnored = false;
    boolean ignoreErrors = false;

    public SelectFlowerAction(String opt, WItem item)
    {
        this.opt = opt;
        this.target = item;
    }

    public SelectFlowerAction(String opt, Gob gob)
    {
        this.opt = opt;
        this.target = gob;
    }

    /**
     * As SelectFlowerAction(String, Gob), but tries several candidate flower-menu option strings
     * in priority order rather than one exact known string - see NFlowerMenu.chooseOpt(List).
     */
    public SelectFlowerAction(java.util.List<String> optCandidates, Gob gob)
    {
        this.optCandidates = optCandidates;
        this.target = gob;
    }

    public SelectFlowerAction(String opt, Gob gob, Boolean petalIgnored)
    {
        this.opt = opt;
        this.target = gob;
        this.petalIgnored = petalIgnored;
    }

    public SelectFlowerAction(String opt, Gob gob, Roastspit spr)
    {
        this.opt = opt;
        this.target = gob;
        this.spr = spr;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        if(target instanceof WItem)
        {
            WItem item = (WItem) target;
            item.item.wdgmsg("iact", item.c, 0);
        }
        else if (target instanceof Gob)
        {
            Gob gob = (Gob) target;
            if (spr==null)
            {
                gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 3, 0, 1, (int) gob.id, gob.rc.floor(posres),
                        0, -1);
            }
            else
            {
                for (Gob.Overlay ol : gob.ols) {
                    if (ol.spr == spr)
                        gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 3, 0, 1, (int) gob.id,
                                gob.rc.floor(posres), ol.id, -1);
                }
            }
        }

        NFlowerMenu fm = NUtils.getFlowerMenu();
        if(fm==null)
            if(!petalIgnored)
                return Results.FAIL();
            else
                return Results.SUCCESS();
        boolean chosen = (optCandidates != null) ? fm.chooseOpt(optCandidates) : fm.chooseOpt(opt);
        if(chosen)
        {
            NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
            return Results.SUCCESS();
        }
        else
        {
            NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
            if(!ignoreErrors)
                return Results.ERROR("NO OPT:" + (optCandidates != null ? String.join(",", optCandidates) : opt));
            return Results.FAIL();
        }

    }
}
