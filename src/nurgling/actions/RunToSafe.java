package nurgling.actions;

import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.widgets.Specialisation;

public class RunToSafe implements Action{
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NUtils.setSpeed(4);
        NContext context = new NContext(gui);
        NArea nArea = context.goToArea(Specialisation.SpecName.safe);
        if(nArea!=null) {
            return Results.SUCCESS();
        }
        else {
            return Results.FAIL();
        }
    }
}
