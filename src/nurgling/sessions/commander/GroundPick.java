package nurgling.sessions.commander;

import haven.*;

/**
 * Screen point to ground point without the click buffers: Newton's method on the view's own world-to-screen
 * transform, against the live ground's heights where they are loaded and the remembered ground's elsewhere. It works
 * where nothing clickable is drawn (remembered ground, black), costs a few dozen matrix products, and answers at once,
 * on the calling thread.
 */
public final class GroundPick {
    private static final int ITER = 8;
    /** Close enough, in screen pixels. */
    private static final double DONE = 0.25;

    private GroundPick() {}

    /** The ground's height at a local point, or NaN where neither the live nor the remembered ground knows it. */
    public static double height(MapView mv, Coord2d p) {
        try {
            return mv.glob.map.getcz(p);
        } catch (Loading l) {
            return mv.remembered.height(p);
        }
    }

    /**
     * The local ground point under screen point {@code sc}, starting the search at {@code from}; {@code z} is the
     * height used where the ground's own is unknown. Null when the search does not settle (a point off the ground).
     */
    public static Coord2d pick(MapView mv, Coord sc, Coord2d from, double z) {
        Coord2d p = from;
        double pz = z;
        for (int i = 0; i < ITER; i++) {
            double h = height(mv, p);
            if (!Double.isNaN(h))
                pz = h;
            Coord3f s0 = mv.screenxf(new Coord3f((float) p.x, (float) p.y, (float) pz));
            Coord3f sx = mv.screenxf(new Coord3f((float) p.x + 1, (float) p.y, (float) pz));
            Coord3f sy = mv.screenxf(new Coord3f((float) p.x, (float) p.y + 1, (float) pz));
            if (s0 == null || sx == null || sy == null)
                return null;
            double ex = sc.x - s0.x, ey = sc.y - s0.y;
            if (ex * ex + ey * ey < DONE * DONE)
                return p;
            double a = sx.x - s0.x, b = sy.x - s0.x, c = sx.y - s0.y, d = sy.y - s0.y;
            double det = a * d - b * c;
            if (Math.abs(det) < 1e-9)
                return null;
            double dx = (d * ex - b * ey) / det, dy = (-c * ex + a * ey) / det;
            /* Steps are bounded so a bad start near the horizon can't throw the search across the world. */
            double len = Math.hypot(dx, dy), max = 20 * MCache.cmaps.x * MCache.tilesz.x;
            if (len > max) {
                dx *= max / len;
                dy *= max / len;
            }
            p = p.add(dx, dy);
        }
        return p;
    }

    /** How far on the ground one screen pixel goes around screen point {@code sc}, or NaN. */
    public static double perPixel(MapView mv, Coord sc, Coord2d at, double z) {
        Coord2d a = pick(mv, sc, at, z), b = pick(mv, sc.add(UI.scale(50), 0), at, z);
        if (a == null || b == null)
            return Double.NaN;
        return a.dist(b) / UI.scale(50);
    }
}
