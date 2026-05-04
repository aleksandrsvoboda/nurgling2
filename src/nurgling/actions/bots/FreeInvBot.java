package nurgling.actions.bots;

import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.FreeInventory2;
import nurgling.actions.Results;
import nurgling.areas.NContext;

public class FreeInvBot implements Action {

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        new FreeInventory2(new NContext(gui)).run(gui);
        return Results.SUCCESS();
    }
}
