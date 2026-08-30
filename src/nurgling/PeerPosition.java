package nurgling;

import haven.*;
import nurgling.tools.GridLocator;

/**
 * One other player's last published position, as this client currently understands it.
 *
 * <p>"Peer" here means any character publishing to the same database, which is not the same set as
 * the in-game Kin list - sharing is scoped by who has the database credentials, not by who has been
 * kinned. Kin membership only decides what colour the marker is drawn in.
 *
 * <p>The position itself is a {@link GridLocator.Ref} - a server grid id and a tile offset - which
 * resolves lazily and may never resolve at all if this client has neither walked nor imported that
 * part of the world. That is a normal state, not an error: they are simply somewhere we cannot
 * draw yet.
 */
public class PeerPosition {
    public final String charName;
    public final GridLocator.Ref ref;
    /** Facing, for the arrow. */
    public volatile double angle;

    /** Age at the moment the row was last read, measured on the database's clock. */
    private volatile long baseAge;
    /** Local timestamp of that read, used only as a monotonic delta to advance the age between polls. */
    private volatile double fetched;

    /**
     * Past this the character is treated as logged out rather than stale, and is not drawn.
     *
     * <p>Sized against the heartbeat, not against how long a position stays interesting. A client
     * that is online and standing still rewrites its row every {@code PeerPositionService.HEARTBEAT}
     * seconds, so this is how many missed heartbeats it takes to be declared gone - currently four,
     * which absorbs a slow database round trip or a loading screen without blinking a live player off
     * the map, and still calls a logged-out one within the minute.
     *
     * <p>There is no longer-lived "here five minutes ago" tier. A stale position is not the useful
     * half-truth it looks like: the two states these markers exist to tell apart are "AFK in the
     * barn" and "logged out", and anything that keeps drawing someone who left makes exactly that
     * distinction unreadable.
     */
    public static final long DROP_MS = 60_000;
    /** Under this, a marker is drawn at full strength - comfortably more than one heartbeat. */
    private static final long FRESH_MS = 20_000;
    /** Age at which fading bottoms out. Fading runs right up to the drop, so a marker visibly dims
     *  on the way out rather than vanishing from full strength. */
    private static final long FADE_MS = DROP_MS;
    /** Alpha a fully faded marker settles at. */
    private static final double FLOOR = 0.35;

    public PeerPosition(String charName, long gid, Coord local, double angle, long ageMillis) {
        this.charName = charName;
        this.ref = new GridLocator.Ref(gid, local);
        this.angle = angle;
        this.baseAge = ageMillis;
        this.fetched = Utils.rtime();
    }

    /**
     * Take a newer reading of a character who has not moved.
     *
     * <p>Needed because a stationary player is deliberately <i>not</i> replaced with a fresh record -
     * that would throw away the segment position already resolved for them and make the marker blink
     * once per poll. Without refreshing the age here, though, someone standing still would keep
     * ageing locally while their heartbeat kept the database row current, and would eventually fade
     * out and vanish while still very much online.
     */
    public void refresh(long ageMillis, double angle) {
        this.baseAge = ageMillis;
        this.fetched = Utils.rtime();
        this.angle = angle;
    }

    /**
     * Age in milliseconds. The database's age at fetch time plus the time elapsed locally since,
     * so the marker keeps ageing smoothly between polls without ever consulting a wall clock that
     * might disagree with the one that stamped the row.
     */
    public long age() {
        return(baseAge + (long)((Utils.rtime() - fetched) * 1000.0));
    }

    /**
     * Whether this character should be listed as online.
     *
     * <p>The same test as {@link #expired()}, deliberately. It used to be a separate, tighter
     * threshold because a position was drawn for a quarter of an hour and only the first two minutes
     * of that meant "online"; now that a marker is dropped as soon as the heartbeat stops, anything
     * still drawn is by definition someone still publishing, and the roster and the map must not be
     * able to disagree about who that is.
     */
    public boolean online() {
        return(!expired());
    }

    public boolean expired() {
        return(age() >= DROP_MS);
    }

    /**
     * Drawing strength. Full while the heartbeat is arriving, then easing off as it stops. The dim
     * marker in the last few seconds is the only warning that someone is about to disappear, so it
     * eases to a floor rather than to nothing - a marker that fades to invisible and one that is
     * removed look the same, and the removal is the thing worth seeing.
     */
    public double alpha() {
        long age = age();
        if(age <= FRESH_MS)
            return(1.0);
        if(age >= FADE_MS)
            return(FLOOR);
        double u = (double)(age - FRESH_MS) / (FADE_MS - FRESH_MS);
        return(1.0 - (u * (1.0 - FLOOR)));
    }

    /** True once the position is old enough that the age should be spelled out next to the name. */
    public boolean stale() {
        return(age() > FRESH_MS);
    }

    /**
     * Compact age for a label - "34s", "4m". Only meaningful when {@link #stale()}.
     *
     * <p>Seconds matter now: nothing is drawn past a minute, so rounding down to whole minutes would
     * label every stale marker "0m".
     */
    public String agestr() {
        long s = age() / 1000;
        if(s < 60)
            return(s + "s");
        if(s < 3600)
            return((s / 60) + "m");
        return((s / 3600) + "h");
    }
}
