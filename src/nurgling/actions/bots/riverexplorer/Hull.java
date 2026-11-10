package nurgling.actions.bots.riverexplorer;

import haven.Gob;
import nurgling.NHitBox;

/** The boat's size as River Explorer sees it. The hull sails lined up with the river, so what has to fit between the banks is
 *  its beam, not its length (Ocean Explorer's clearance uses the length, which needs a 5-tile channel even for a coracle). */
public final class Hull {
    /** Kept between the hull's side and the bank, in units. */
    static final double MARGIN = 1.0;
    /** Used when the boat has no hitbox: a rowboat's. */
    static final double DEFAULT_HALF_BEAM = 6.9, DEFAULT_HALF_LENGTH = 15.9;

    public final double halfBeam, halfLength;
    public final boolean ship;

    public Hull(double halfBeam, double halfLength, boolean ship) {
        this.halfBeam = halfBeam;
        this.halfLength = halfLength;
        this.ship = ship;
    }

    public static Hull of(Gob vessel, boolean ship) {
        NHitBox hb = (vessel.ngob != null) ? vessel.ngob.hitBox : null;
        if (hb == null) return new Hull(DEFAULT_HALF_BEAM, DEFAULT_HALF_LENGTH, ship);
        double hx = Math.max(Math.abs(hb.begin.x), Math.abs(hb.end.x));
        double hy = Math.max(Math.abs(hb.begin.y), Math.abs(hb.end.y));
        return new Hull(Math.min(hx, hy), Math.max(hx, hy), ship);
    }

    /** A cell fits the hull when its centre is at least this far from any wall. */
    public double fit() {
        return halfBeam + MARGIN;
    }
}
