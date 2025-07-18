package nurgling.actions;

import haven.Coord2d;
import haven.Gob;
import haven.Pair;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

public class ReturnBarrelFromWorkArea implements Action{
    NContext context;
    String item;



    public ReturnBarrelFromWorkArea(NContext context, String item)
    {
        this.context = context;
        this.item = item;
    }
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if(context.workstation==null)
            context.getSpecArea(Specialisation.SpecName.barrelworkarea);
        else
            context.getSpecArea(context.workstation);
        long barrelid = -1;

        for(Long gobid : context.barrelsid)
        {
            Gob gob = Finder.findGob(gobid);
            if(NUtils.barrelHasContent(gob) && NUtils.getContentsOfBarrel(gob).equals(context.getBarrelStorage(item).olname))
            {
                new LiftObject(gob).run(gui);
                barrelid = gob.id;
                break;
            }
        }

        if(barrelid == -1)
        {
            for(Long gobid : context.barrelsid)
            {
                Gob gob = Finder.findGob(gobid);
                if(!NUtils.barrelHasContent(gob))
                {
                    new LiftObject(gob).run(gui);
                    barrelid = gob.id;
                    break;
                }
            }
        }
        context.navigateToBarrelArea(item);
        new PlaceObject(Finder.findGob(barrelid),context.getBarrelStorage(item).coord.getCurrentCoord(),0).run(gui);
        return Results.SUCCESS();
    }
}
