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

    private double initialStamina = -2;
    private double lastStamina = -2;
    private int stuckTicks = 0;
    private int totalTicks = 0;

    @Override
    public boolean check()
    {
        totalTicks++;

        double cur = NUtils.getStamina();
        if (initialStamina == -2) {
            initialStamina = cur;
            lastStamina = cur;
        }

        if (cur != lastStamina) {
            lastStamina = cur;
            stuckTicks = 0;
        } else {
            stuckTicks++;
        }

        if (cur >= lvl) return true;

        boolean rose = (cur > initialStamina + 0.001);

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

        // Stamina plateau: either a swig finished (stamina rose then settled) or the drink
        // never started. Only treat as no_water when stamina never moved from the initial
        // value — otherwise the swig was real and the caller should re-click for the next.
        if (stuckTicks > STUCK_LIMIT) {
            if (!rose) no_water = true;
            return true;
        }

        // Same logic for the hard cap: if stamina has been rising the drink is still
        // legitimately in progress; let the caller observe stamina < lvl and re-click.
        if (totalTicks > HARD_CAP) {
            if (!rose) no_water = true;
            return true;
        }

        return false;
    }

    public boolean isNoWater()
    {
        return no_water;
    }
}
