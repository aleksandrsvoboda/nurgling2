package nurgling.tasks;

import haven.*;
import nurgling.*;
import nurgling.widgets.DrinkMeter;

public class DrinkToLvl extends NTask
{
    public DrinkToLvl(double lvl)
    {
        this.lvl = lvl;
    }

    double lvl;

    boolean no_water = false;

    // ~10s with no stamina progress means water flow stalled.
    private static final int STUCK_LIMIT = 300;
    // ~30s hard ceiling so the task can never deadlock the caller.
    private static final int HARD_CAP = 600;

    private double lastStamina = -2;
    private int stuckTicks = 0;
    private int totalTicks = 0;

    @Override
    public boolean check()
    {
        totalTicks++;
        if (totalTicks > HARD_CAP) {
            no_water = true;
            return true;
        }

        double cur = NUtils.getStamina();
        if (cur != lastStamina) {
            lastStamina = cur;
            stuckTicks = 0;
        } else {
            stuckTicks++;
        }

        String lastError = NUtils.getUI().getLastError();
        if (lastError != null && lastError.equals("You have nothing on your hotbelt to drink."))
        {
            // Check if there's still water in the DrinkMeter
            NGameUI gui = NUtils.getGameUI();
            if (gui != null && gui.drinkMeter != null) {
                float available = gui.drinkMeter.getTotalDrinkable();
                if (available > 0) {
                    // Still have water, clear error and retry
                    NUtils.getUI().dropLastError();
                    return false;
                }
            }
            no_water = true;
            return true;
        }

        if (stuckTicks > STUCK_LIMIT) {
            no_water = true;
            return true;
        }

        return cur >= lvl;
    }

    public boolean isNoWater()
    {
        return no_water;
    }
}
