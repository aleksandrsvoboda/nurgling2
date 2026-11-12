package nurgling.sessions.squad;

import haven.Coord2d;
import haven.MCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where each squad member should stand after a move order. Pure arithmetic in one session's frame.
 */
public final class Formation {
    public enum Kind { GRID, LINE, KEEP, STACK }

    /** One tile between neighbours. */
    public static final double SPACING = MCache.tilesz.x;
    /** KEEP gives up and becomes GRID when the squad is spread wider than this from its centre. */
    public static final double KEEP_MAX_SPREAD = 6 * MCache.tilesz.x;

    private Formation() {}

    /**
     * One target per member, in member order.
     *
     * @param members each member's position in the same frame as {@code target}; null when unknown
     * @param target  the clicked point
     */
    public static List<Coord2d> slots(List<Coord2d> members, Coord2d target, Kind kind) {
        int n = members.size();
        List<Coord2d> out = new ArrayList<>(n);
        if (n == 0)
            return out;
        if (kind == Kind.STACK || n == 1) {
            for (int i = 0; i < n; i++)
                out.add(target);
            return out;
        }
        Coord2d centre = centroid(members);
        if (kind == Kind.KEEP && centre != null && !members.contains(null)) {
            double spread = 0;
            for (Coord2d m : members)
                spread = Math.max(spread, m.dist(centre));
            if (spread <= KEEP_MAX_SPREAD) {
                for (Coord2d m : members)
                    out.add(target.add(m.sub(centre)));
                return out;
            }
            kind = Kind.GRID;
        }
        Coord2d fwd = (centre == null) ? null : target.sub(centre);
        if (fwd == null || fwd.abs() < 1e-6)
            fwd = Coord2d.of(1, 0);
        else
            fwd = fwd.norm();
        Coord2d right = Coord2d.of(-fwd.y, fwd.x);
        int cols = (kind == Kind.LINE) ? n : (int) Math.ceil(Math.sqrt(n));
        List<Coord2d> places = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int row = i / cols, col = i % cols;
            int inRow = Math.min(cols, n - row * cols);
            double across = (col - (inRow - 1) / 2.0) * SPACING;
            double back = row * SPACING;
            places.add(target.add(right.mul(across)).sub(fwd.mul(back)));
        }
        return assign(members, places);
    }

    /** Centre of the known positions, or null when none is known. */
    static Coord2d centroid(List<Coord2d> members) {
        double x = 0, y = 0;
        int k = 0;
        for (Coord2d m : members) {
            if (m == null)
                continue;
            x += m.x;
            y += m.y;
            k++;
        }
        return (k == 0) ? null : Coord2d.of(x / k, y / k);
    }

    /**
     * Greedy nearest assignment: the closest member/place pair is fixed first, so paths rarely cross. Members whose
     * position is unknown take the places left over, in order.
     */
    static List<Coord2d> assign(List<Coord2d> members, List<Coord2d> places) {
        int n = members.size();
        Coord2d[] res = new Coord2d[n];
        boolean[] used = new boolean[places.size()];
        boolean[] done = new boolean[n];
        while (true) {
            int bm = -1, bp = -1;
            double best = Double.MAX_VALUE;
            for (int m = 0; m < n; m++) {
                if (done[m] || members.get(m) == null)
                    continue;
                for (int p = 0; p < places.size(); p++) {
                    if (used[p])
                        continue;
                    double d = members.get(m).dist(places.get(p));
                    if (d < best) {
                        best = d;
                        bm = m;
                        bp = p;
                    }
                }
            }
            if (bm < 0)
                break;
            res[bm] = places.get(bp);
            done[bm] = true;
            used[bp] = true;
        }
        int p = 0;
        for (int m = 0; m < n; m++) {
            if (done[m])
                continue;
            while (used[p])
                p++;
            res[m] = places.get(p);
            used[p] = true;
        }
        return new ArrayList<>(Arrays.asList(res));
    }

    /* ---- Around an object several characters use ---- */

    /** Direction (radians) from {@code from} to {@code to}; 0 when they coincide. */
    private static double angle(Coord2d from, Coord2d to) {
        double dx = to.x - from.x, dy = to.y - from.y;
        return (dx == 0 && dy == 0) ? 0 : Math.atan2(dy, dx);
    }

    /**
     * Places on an arc around an object, centred on the squad's side of it and one {@code spacing} apart, so several
     * characters reach it at once without queueing for the same spot. Each member gets the place nearest to it.
     */
    public static List<Coord2d> aroundObject(List<Coord2d> members, Coord2d center, double radius, double spacing) {
        int n = members.size();
        Coord2d c = centroid(members);
        double front = (c == null) ? 0 : angle(center, c);
        double step = Math.min(spacing / Math.max(radius, 1), 2 * Math.PI / Math.max(n, 1));
        List<Coord2d> places = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = front + (i - (n - 1) / 2.0) * step;
            places.add(Coord2d.of(center.x + radius * Math.cos(a), center.y + radius * Math.sin(a)));
        }
        return assign(members, places);
    }
}
