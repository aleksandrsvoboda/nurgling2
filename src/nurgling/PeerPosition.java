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

    /** Under this, a marker is drawn at full strength. */
    private static final long FRESH_MS = 60_000;
    /** Age at which fading bottoms out; a marker stays legible past here, just visibly old. */
    private static final long FADE_MS = 300_000;
    /** Past this the character is treated as gone rather than stale, and is not drawn. */
    public static final long DROP_MS = 900_000;
    /** Age past which a character is no longer counted as online. */
    private static final long ONLINE_MS = 120_000;
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
     * <p>Generous next to the 30 s heartbeat: a tick delayed by a slow database round trip must not
     * blink someone out of the roster and back in. Anything older than this is someone who stopped
     * publishing rather than someone standing still.
     */
    public boolean online() {
        return(age() < ONLINE_MS);
    }

    public boolean expired() {
        return(age() >= DROP_MS);
    }

    /**
     * Drawing strength. Full for the first minute, easing to a floor by five, and never quite to
     * nothing: a marker that says "here four minutes ago" is useful, and one that has silently
     * vanished is indistinguishable from someone who logged out.
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

    /** Compact age for a label - "4m", "2h". Only meaningful when {@link #stale()}. */
    public String agestr() {
        long s = age() / 1000;
        if(s < 3600)
            return((s / 60) + "m");
        return((s / 3600) + "h");
    }
}
