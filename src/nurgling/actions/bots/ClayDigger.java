package nurgling.actions.bots;

import haven.Resource;
import haven.UI;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.conf.NClayDiggerProp;
import nurgling.tasks.WaitCheckable;
import nurgling.tools.NAlias;

public class ClayDigger implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        nurgling.widgets.bots.ClayDigger w = null;
        NClayDiggerProp prop = null;
        try {
            NUtils.getUI().core.addTask(new WaitCheckable( NUtils.getGameUI().add((w = new nurgling.widgets.bots.ClayDigger()), UI.scale(200,200))));
            prop = w.prop;
        }
        catch (InterruptedException e)
        {
            throw e;
        }
        finally {
            if(w!=null)
                w.destroy();
        }
        if(prop == null)
        {
            return Results.ERROR("No config");
        }
        if(prop.shovel==null)
        {
            return Results.ERROR("Not set required tools");
        }
        NContext context = new NContext(gui);
        String insaId = context.createArea("Please select area for dig clay", Resource.loadsimg("baubles/clayTime"));
        NArea insaArea = context.goToAreaById(insaId);


        NArea area = NContext.findOut(new NAlias("clay"),1);
        if(area==null || area.getRCArea() == null)
        {
            String onsaId = context.createArea("Please select area for output clay", Resource.loadsimg("baubles/clayPiles"));
            NArea onsaArea = context.goToAreaById(onsaId);
            new DiggingResources(insaArea.getRCArea(),onsaArea.getRCArea(),new NAlias("clay"), prop.shovel).run(gui);
        }
        else {
            new DiggingResources(insaArea.getRCArea(), area.getRCArea(), new NAlias("clay"), prop.shovel).run(gui);
        }

        return Results.SUCCESS();
    }
}
