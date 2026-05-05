package nurgling.actions.bots;

import nurgling.*;
import nurgling.actions.*;
import nurgling.areas.NContext;

import nurgling.widgets.FoodContainer;

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

        if (items.isEmpty()) {
            return Results.ERROR("No allowed food items configured");
        }

        NContext cnt = new NContext(gui);
        Results res = new FindAndEatItems(cnt, items, 8000).run(gui);
        if (!res.IsSuccess()) {
            return res;
        }
        return NUtils.getEnergy() * 10000 > 8000 ? Results.SUCCESS() : Results.FAIL();
    }
}
