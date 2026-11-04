package nurgling.actions.bots.worldexplorer;

import haven.Coord2d;

import java.util.function.Supplier;

/** Ocean Explorer's steering: a wall follower that looks along rays instead of stepping tile by tile (the idea of Hurricane's
 *  Ocean Scout Bot). The sweep starts square to the followed side and turns through straight ahead toward the open side; the
 *  first ray that stays clear for its whole length wins. Starting on the edge side is what makes it hug: on a straight coast
 *  that ray is blocked and the next clear one runs along the coast; round a cape it is clear and the boat turns in.
 *  <p>Hugging only happens while an edge is on the followed side. Otherwise (open water, a rock, an edge on the wrong side) the
 *  helm <i>approaches</i>: it heads for the nearest edge with the sweep starting dead ahead, so on arrival the sweep turns it
 *  along the edge with the edge on the followed side. Hugging with nothing on that side spins the boat in place, which is
 *  what Hurricane's bot does in open water until its "lost" counter runs out.
 *  <p>Angles are in world coordinates, where y grows southward, so a growing angle turns clockwise on the map. */
public final class Helm {
    public static final int STEPS = 36;
    static final double STEP = 2 * Math.PI / STEPS;

    /** +1 keeps the followed edge on the right, -1 on the left. */
    public final int side;
    /** The sweep starts square to this, on the followed side: the course along the edge while hugging, a quarter turn off the
     *  course while approaching. */
    double heading;
    /** The direction last picked. */
    public double course;
    /** True while hugging an edge, false while approaching one or crossing open water. */
    public boolean following = false;

    public Helm(boolean edgeOnRight, double course) {
        this.side = edgeOnRight ? 1 : -1;
        this.course = course;
        this.heading = course;
    }

    /** Which side keeps the edge: following the shoreline clockwise round the land puts the land on the right; the deeper
     *  edge lies on the other side of the band, so it is the other way round. */
    public static boolean edgeOnRight(boolean clockwise, boolean deeper) {
        return clockwise != deeper;
    }

    /** Result of one sweep. */
    static final class Scan {
        /** First clear ray from the start, null when every ray is blocked. */
        final Double pick;
        /** A ray within a quarter turn of the start reached a followed edge: there is an edge on the followed side. */
        final boolean sideEdge;

        Scan(Double pick, boolean sideEdge) {
            this.pick = pick;
            this.sideEdge = sideEdge;
        }
    }

    /** Rays are tested from `from` to `len`: `from` skips the boat's own surroundings, so a boat already close to a wall can
     *  still find its way off it. */
    Scan scan(SeaGrid g, Coord2d p, double from, double len) {
        double a0 = heading + side * Math.PI / 2;
        Double pick = null;
        boolean sideEdge = false;
        for (int k = 0; k < STEPS; k++) {
            double a = norm(a0 - side * k * STEP);
            if (pick == null && g.rayClear(p, a, from, len))
                pick = a;
            boolean nearStart = k < STEPS / 4 || k > 3 * STEPS / 4;
            if (!sideEdge && nearStart && g.rayTouchesEdge(p, a, len))
                sideEdge = true;
        }
        return new Scan(pick, sideEdge);
    }

    /**
     * Picks the next course, or null when every ray is blocked.
     * @param mayApproach false to hug whatever is near (the shortened retries in a narrow channel)
     * @param seek        the spot to head for when no edge is on the followed side (nearest edge tile), null to hold the course
     */
    public Double steer(SeaGrid g, Coord2d p, double from, double len, boolean mayApproach, Supplier<Coord2d> seek) {
        Scan s = scan(g, p, from, len);
        if (s.pick == null) return null;
        if (s.sideEdge || !mayApproach) {
            following = true;
            heading = s.pick;
            course = s.pick;
            return course;
        }
        following = false;
        Coord2d target = seek.get();
        aimAt(target != null ? p.angle(target) : course);
        Scan q = scan(g, p, from, len);
        course = (q.pick != null) ? q.pick : s.pick;
        aimAt(course);
        return course;
    }

    /** Makes the next sweep start at `bearing`. */
    void aimAt(double bearing) {
        heading = norm(bearing - side * Math.PI / 2);
    }

    public static double norm(double a) {
        a %= 2 * Math.PI;
        if (a <= -Math.PI) a += 2 * Math.PI;
        if (a > Math.PI) a -= 2 * Math.PI;
        return a;
    }

    /** Smallest absolute difference between two angles. */
    public static double diff(double a, double b) {
        return Math.abs(norm(a - b));
    }
}
