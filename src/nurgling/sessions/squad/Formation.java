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
}
