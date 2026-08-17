package nurgling.tasks;

import haven.Gob;
import nurgling.actions.WorkBellows;
import nurgling.actions.bots.LightObject;
import nurgling.tools.Finder;

import java.util.ArrayList;

/**
 * Waits out a burn, but wakes early whenever a stack furnace's bellows boost lapses.
 *
 * <p>{@link WaitForBurnout} is a pure barrier: it returns only once every workstation is out, so a bot
 * sitting in it cannot react to anything during the burn. The boost covers roughly half a burn and then
 * expires while the fuel keeps going, so under that barrier the back half of every burn runs unboosted.
 *
 * <p>This returns on either condition and {@link #needsPump()} says which fired, letting the caller pump
 * and go back to waiting without disturbing the service cycle. No timers are involved - "burning but not
 * boosted" is directly readable from the model attribute, which is authoritative and survives a pump that
 * silently failed.
 *
 * <p>The flame bit is resolved per gob, since a battery can mix types: an Ore Smelter burns on bit 2 and a
 * Stack Furnace on bit 4.
 */
public class WaitBurnoutOrBellows extends NTask
{
    private final ArrayList<String> lighted;
    private final ArrayList<String> furnaces;
    /** Written from the UI thread in check(), read from the bot thread once addTask returns. */
    private volatile boolean needsPump = false;

    /**
     * @param lighted  every workstation whose burn we are waiting out
     * @param furnaces the stack furnaces among them, the only ones with bellows
     */
    public WaitBurnoutOrBellows(ArrayList<String> lighted, ArrayList<String> furnaces)
    {
        this.lighted = lighted;
        this.furnaces = furnaces;
    }

    /** True when the wait ended because a furnace wants pumping rather than because the burn finished. */
    public boolean needsPump()
    {
        return needsPump;
    }

    @Override
    public boolean check()
    {
        /* A lapsed boost is actionable immediately, so it is tested first. */
        for (String hash : furnaces)
        {
            Gob gob = Finder.findGob(hash);
            if (gob == null || gob.ngob == null)
                continue;
            long attr = gob.ngob.getModelAttribute();
            if ((attr & WorkBellows.BURNING) != 0 && (attr & WorkBellows.BOOST) == 0)
            {
                needsPump = true;
                return true;
            }
        }

        for (String hash : lighted)
        {
            Gob gob = Finder.findGob(hash);
            if (gob == null || gob.ngob == null || gob.ngob.name == null)
                continue;
            int flag = LightObject.fireFlag(gob.ngob.name);
            if (flag != 0 && (gob.ngob.getModelAttribute() & flag) != 0)
                return false;
        }

        needsPump = false;
        return true;
    }
}
