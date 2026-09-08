package nurgling.pf;

import haven.*;
import nurgling.NHitBox;

/**
 * A gob's footprint in world space as one or more oriented boxes.
 *
 * <p>{@link NHitBoxD} is a single rotated rectangle, which is all most objects need. A handful -
 * see {@link NHitBox#isCompoundName} - block only parts of their bounding box, and collapsing them
 * to one rectangle is what stops a bot walking between a timber tunnel's legs. This wraps the parts
 * so callers can ask "does anything of this object overlap that" without caring which kind it is.
 *
 * <p>{@link #bounds} is the union box and doubles as a cheap reject before the per-part tests, so a
 * plain single-box footprint costs exactly what it did before.
 */
public class NHitShapeD {
    /** The blocking rectangles. Always at least one; the same object as {@link #bounds} when simple. */
    public final NHitBoxD[] parts;
    /** The whole footprint as one box - the conservative "treat it as solid" answer. */
    public final NHitBoxD bounds;

    private NHitShapeD(NHitBoxD bounds, NHitBoxD[] parts) {
        this.bounds = bounds;
        this.parts = parts;
    }

    public static NHitShapeD of(Gob gob) {
        return of(gob.ngob.hitBox, gob.rc, gob.a);
    }

    public static NHitShapeD of(NHitBox hb, Coord2d rc, double angle) {
        // Decided once, from the union, then applied to every part - see the NHitBoxD constructor.
        boolean asym = (hb.begin.x != -hb.end.x);
        NHitBoxD bounds = new NHitBoxD(hb.begin, hb.end, rc, angle, asym);
        NHitBox[] hp = hb.parts();
        if (hp == null)
            return new NHitShapeD(bounds, new NHitBoxD[]{bounds});

        // The union's effective rotation, half-turn included. Each part is built around its OWN
        // centre - orbited about the gob's origin and then oriented the same way - rather than
        // being handed the gob's origin as its centre. The corners come out identical either way,
        // but NHitBoxD's overlap test has a shortcut that asks whether the other box contains
        // this.rc, which assumes rc is inside the box. For a compound footprint the gob's origin
        // usually sits in the gap, so a part carrying it as its centre would report a hit on
        // exactly the empty space this whole exercise exists to open up.
        double theta = asym ? angle + Math.PI : angle;
        NHitBoxD[] pd = new NHitBoxD[hp.length];
        for (int i = 0; i < hp.length; i++) {
            Coord2d half = hp[i].end.sub(hp[i].begin).div(2);
            Coord2d local = hp[i].begin.add(hp[i].end).div(2);
            pd[i] = new NHitBoxD(half.inv(), half, rc.add(local.rot(theta)), theta, false);
        }
        return new NHitShapeD(bounds, pd);
    }

    public boolean intersects(NHitBoxD other, boolean includeBorder) {
        if (!bounds.intersects(other, includeBorder))
            return false;
        if (parts.length == 1)
            return true;
        for (NHitBoxD p : parts) {
            if (p.intersects(other, includeBorder))
                return true;
        }
        return false;
    }

    public boolean intersects(NHitShapeD other, boolean includeBorder) {
        if (!bounds.intersects(other.bounds, includeBorder))
            return false;
        if (parts.length == 1 && other.parts.length == 1)
            return true;
        for (NHitBoxD a : parts) {
            for (NHitBoxD b : other.parts) {
                if (a.intersects(b, includeBorder))
                    return true;
            }
        }
        return false;
    }

    public Coord2d getCircumscribedUL() {
        return bounds.getCircumscribedUL();
    }

    public Coord2d getCircumscribedBR() {
        return bounds.getCircumscribedBR();
    }
}
