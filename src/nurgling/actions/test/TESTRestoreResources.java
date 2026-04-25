package nurgling.actions.test;

import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.RestoreResources;
import nurgling.actions.Results;

public class TESTRestoreResources extends Test
{
    public TESTRestoreResources()
    {
        this.num = 1;
    }

    @Override
    public void body(NGameUI gui) throws InterruptedException
    {
        NUtils.getGameUI().tickmsg("TESTRestoreResources: starting. Stam=" + NUtils.getStamina() + " Energy=" + NUtils.getEnergy());
        Results r = new RestoreResources().run(gui);
        NUtils.getGameUI().tickmsg("TESTRestoreResources: done. success=" + r.IsSuccess() + " Stam=" + NUtils.getStamina() + " Energy=" + NUtils.getEnergy());
    }
}
