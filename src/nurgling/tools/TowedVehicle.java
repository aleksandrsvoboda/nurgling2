package nurgling.tools;

import haven.Gob;
import haven.Homing;
import haven.OCache;

/**
 * Tracks which vehicle, if any, this character is currently towing.
 *
 * <p>Needed because neither half of the question is answerable from a single field. The gob's
 * {@code Moving} attribute says <em>whether</em> something is being towed but only while it is
 * catching up &mdash; a towed cart carries {@link Homing} while it moves and nothing at all at
 * rest, which is exactly when a bot plans its path. The model marker
 * ({@link VehicleMarker#isTowed}) says towed-or-not at any time, but not <em>by whom</em>: another
 * character's cart rolling past also has the bit set, and that one genuinely should block us.
 *
 * <p>So ownership is established once, from {@code Homing.tgt == playerId} (or an explicit
 * {@link #adopt} when a bot ties the vehicle itself), and then held for as long as the marker says
 * the vehicle is still under tow. An untie clears the bit, which releases it automatically.
 *
 * <p>Instances live on {@code NCore}, one per session &mdash; a shared instance would hand one
 * character's cart to another's pathfinder.
 */
public class TowedVehicle {

    private static final NAlias VEHICLE = new NAlias("vehicle");

    private volatile long towedId = -1;

    /** Record a vehicle as ours, for a tie we performed and confirmed ourselves. */
    public void adopt(Gob vehicle) {
        towedId = (vehicle == null) ? -1 : vehicle.id;
    }

    public void release() {
        towedId = -1;
    }

    /** The vehicle currently believed to be under tow, or -1. Does not re-check. */
    public long id() {
        return towedId;
    }

    /**
     * Resolve the towed vehicle, adopting one if the character has started towing since the last
     * call and releasing the current one if it has untied.
     *
     * <p>The caller must already hold the object cache's monitor.
     *
     * @return the gob id of the towed vehicle, or -1 if nothing is being towed
     */
    public long resolve(OCache oc, long playerId) {
        if (oc == null || playerId < 0) {
            towedId = -1;
            return -1;
        }

        // Fast path: we already know which one it is, so no scan while actually towing.
        if (towedId >= 0) {
            Gob known = oc.getgob(towedId);
            if (known != null && VehicleMarker.isTowed(known))
                return towedId;
            towedId = -1;
        }

        for (Gob gob : oc) {
            Homing homing = gob.getattr(Homing.class);
            if (homing == null || homing.tgt != playerId)
                continue;
            if (gob.ngob == null || !NParser.checkName(gob.ngob.name, VEHICLE))
                continue;
            if (!VehicleMarker.isTowed(gob))
                continue;
            towedId = gob.id;
            return towedId;
        }
        return -1;
    }
}
