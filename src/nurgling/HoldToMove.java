package nurgling;

import haven.Coord;
import haven.Coord2d;
import haven.UI;
import haven.Utils;

/**
 * Press-and-hold steering state, shared by the world view and the maps.
 *
 * While the left button is held down the spot under the pointer is re-sampled and
 * re-sent as a move command, so the character keeps walking towards the cursor
 * instead of stopping at the single point that was clicked. This class only keeps
 * the state and the pacing rules - sampling and the actual click message stay with
 * the caller, since the world view and the maps resolve a cursor position in
 * completely different ways.
 */
public class HoldToMove {
    /** Minimum time between two move commands. Matches the waypoint drag rate limit. */
    private static final double INTERVAL = 0.15;
    /** How far the pointer must travel before a press that landed on an object starts steering. */
    private static final int MOVE_THRESHOLD = UI.scale(8);

    private boolean armed = false;
    private boolean waitmove = false;
    private boolean moved = false;
    private boolean pending = false;
    private Coord press = null;
    private Coord2d lastsent = null;
    private double lasttime = 0;

    public static boolean enabled() {
        Object val = NConfig.get(NConfig.Key.holdToMove);
        return((val instanceof Boolean) && (Boolean)val);
    }

    /**
     * Arm on a fresh left-button press.
     *
     * @param waitmove true when the press landed on an object: the interaction it just
     *                 started must not be cancelled by a re-sample, so steering waits
     *                 until the pointer actually moves.
     */
    public void arm(Coord c, boolean waitmove) {
        this.armed = true;
        this.waitmove = waitmove;
        this.moved = false;
        this.pending = false;
        this.press = c;
        this.lastsent = null;
        // Pace the first steering command off the press, so a short ordinary click never
        // gets a second, near-identical click chasing it a frame later.
        this.lasttime = Utils.rtime();
    }

    public boolean armed() {
        return(armed);
    }

    /** The press turned out to be on bare ground after all - steer without waiting for movement. */
    public void allowIdleSteer() {
        this.waitmove = false;
    }

    public boolean steering() {
        return(armed && (!waitmove || moved));
    }

    /** Feed pointer positions so a press that landed on an object can turn into a steer. */
    public void pointer(Coord c) {
        if(armed && !moved && (press != null) && (c.dist(press) > MOVE_THRESHOLD))
            moved = true;
    }

    /** True when a new sample may be taken - rate limited, one in flight at a time. */
    public boolean due() {
        if(!steering() || pending)
            return(false);
        return(Utils.rtime() - lasttime >= INTERVAL);
    }

    public void begin() {
        pending = true;
    }

    public void done() {
        pending = false;
    }

    /**
     * Record a resolved sample. Returns false when it sits close enough to the previous
     * command to not be worth another one; the next sample is paced by INTERVAL either way.
     */
    public boolean accept(Coord2d p, double mindist) {
        lasttime = Utils.rtime();
        if((lastsent != null) && (p.dist(lastsent) < mindist))
            return(false);
        lastsent = p;
        return(true);
    }

    /** True once at least one steering command has gone out. Query before {@link #disarm}. */
    public boolean steered() {
        return(lastsent != null);
    }

    public void disarm() {
        armed = false;
        waitmove = false;
        moved = false;
        pending = false;
        press = null;
        lastsent = null;
        lasttime = 0;
    }
}
