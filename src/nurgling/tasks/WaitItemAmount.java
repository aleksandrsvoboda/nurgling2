package nurgling.tasks;

import haven.WItem;
import nurgling.NGItem;
import nurgling.tools.DrinkContainers;

/**
 * Waits until a container that already holds liquid gains more of it.
 *
 * WaitItemContent cannot be used for topping off: it waits for the content list to
 * become non-empty, which is already true for a partially filled container, so it
 * returns before the server has answered.
 *
 * Self-limiting on purpose. The task stays infinite as far as NCore is concerned -
 * a finite NTask that exhausts maxCounter throws InterruptedException and kills the
 * whole bot - so instead it gives up on its own frame budget and lets the caller
 * carry on with the container it is holding.
 */
public class WaitItemAmount extends NTask
{
    private static final int MAX_FRAMES = 100;
    private static final float GAIN_EPSILON = 0.01f;
    private static final float FULL_EPSILON = 0.05f;

    private final WItem item;
    private final float startAmount;
    private final float capacity;
    private int frames = 0;

    public WaitItemAmount(WItem item, float startAmount, float capacity)
    {
        this.item = item;
        this.startAmount = startAmount;
        this.capacity = capacity;
    }

    @Override
    public boolean check()
    {
        if (frames++ >= MAX_FRAMES)
            return true;
        if (item == null || item.item == null)
            return true;
        if (item.item.spr == null)
            return false;
        float now = DrinkContainers.amount((NGItem) item.item);
        if (now > startAmount + GAIN_EPSILON)
            return true;
        return capacity > 0f && now >= capacity - FULL_EPSILON;
    }
}
