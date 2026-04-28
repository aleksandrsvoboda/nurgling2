package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;

import nurgling.widgets.FoodContainer;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

public class Eater implements Action {

    boolean oz = false;

    public Eater(boolean oz) {
        this.oz = oz;
    }

    public Eater() {
        this.oz = false;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        ArrayList<String> items = FoodContainer.getFoodNames();

        NContext cnt = new NContext(gui);
        NArea nArea = cnt.goToArea(Specialisation.SpecName.eat);
        if(nArea != null) {
            new FindAndEatItems(cnt, items, 8000, nArea.getRCArea()).run(gui);
            return NUtils.getEnergy()*10000>8000?Results.SUCCESS():Results.FAIL();
        }
        else
            return Results.FAIL();
    }
}
