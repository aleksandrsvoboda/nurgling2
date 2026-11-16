package nurgling.tasks;

import haven.*;
import nurgling.*;
import nurgling.tools.*;

/** Done once the player is within `ahead` of target (still walking, so the next click can follow without a stop), or has
 *  stopped walking. Wait for the walk to start first (IsMoving) - a character that hasn't set off yet reads as stopped. */
public class NearOrStopped extends NTask
{
    final Coord2d target;
    final double ahead;
    int count = 0;

    public NearOrStopped(Coord2d target, double ahead)
    {
        this.target = target;
        this.ahead = ahead;
    }

    @Override
    public boolean check()
    {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || gui.map == null)
            return false;
        Gob player = gui.map.player();
        if (player == null)
            return false;
        if (player.rc.dist(target) <= ahead)
            return true;
        // Same backstop as MovingCompleted.
        if (++count >= 1000)
            return true;
        Drawable drawable = player.getattr(Drawable.class);
        String pose;
        return drawable instanceof Composite && (pose = ((Composite) drawable).current_pose) != null
                && !NParser.checkName(pose, "borka/walking", "borka/running", "borka/wading");
    }
}
