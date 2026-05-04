package nurgling.actions.bots;

import haven.Resource;
import nurgling.NGameUI;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.NAlias;

public class Plower implements Action
{


    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        NContext context = new NContext(gui);
        String outsaId = context.createArea("Please select area for plowing", Resource.loadsimg("baubles/inputArea"));
        NArea outsaArea = context.goToAreaById(outsaId);

        new PatrolArea(new NAlias( "vehicle/plow" ), outsaArea.getRCArea() ).run(gui);

        return Results.SUCCESS();
    }


}
