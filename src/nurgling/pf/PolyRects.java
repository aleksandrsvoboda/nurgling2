package nurgling.pf;

import haven.Coord2d;

import java.util.*;

/**
 * Turns a raw obstacle outline into a small set of axis-aligned rectangles.
 *
 * <p>Obstacle geometry off the wire ({@code Resource.Obstacle.p}, {@code lib/obst}) is an array of
 * closed loops of arbitrary vertex count. Most of it is a single rectangle, but some objects - the
 * timber tunnel being the motivating case - describe a footprint with a hole or a gap in it, either
 * as several disjoint loops or as one non-convex outline. Reducing either to a single bounding box
 * is what makes the bot refuse to walk between the legs.
 *
 * <p>Two paths:
 * <ul>
 *   <li>every loop is already an axis-aligned rectangle and none is nested inside another - use the
 *       loops verbatim, no precision lost;</li>
 *   <li>anything else - rasterize all loops together with an even-odd fill (so a loop nested inside
 *       another correctly punches a hole) and greedily merge the filled cells back into rectangles.</li>
 * </ul>
 *
 * <p>Results are only as good as the raster step, so this is deliberately finer than the pathfinding
 * grid: {@link #STEP} world units against {@code MCache.tilehsz}'s 5.5.
 */
public class PolyRects {
    /** Raster resolution, world units. Well under one pathfinding cell (5.5). */
    public static final double STEP = 1.0;
    /** Never rasterize more than this many cells per axis; step grows for very large objects. */
    public static final int MAX_CELLS = 320;
    /**
     * Above this many rectangles the shape is too fiddly to be worth modelling exactly, and the
     * caller is better off with the bounding box. Real footprints need a handful.
     */
    public static final int MAX_RECTS = 64;

    private static final double EPS = 1e-6;

    /** A loop is an axis-aligned rectangle when all four of its edges are axis-parallel. */
    public static boolean isAxisRect(Coord2d[] loop) {
        if (loop == null || loop.length != 4)
            return false;
        for (int i = 0; i < 4; i++) {
            Coord2d a = loop[i], b = loop[(i + 1) % 4];
            boolean sameX = Math.abs(a.x - b.x) < EPS;
            boolean sameY = Math.abs(a.y - b.y) < EPS;
            // Exactly one of the two must hold: both means a degenerate edge, neither a diagonal.
            if (sameX == sameY)
                return false;
        }
        return true;
    }

    /** {@code {ul, br}} of a loop's bounding box. */
    public static Coord2d[] bounds(Coord2d[] loop) {
        double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
        double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
        for (Coord2d v : loop) {
            if (v.x < minx) minx = v.x;
            if (v.y < miny) miny = v.y;
            if (v.x > maxx) maxx = v.x;
            if (v.y > maxy) maxy = v.y;
        }
        return new Coord2d[]{Coord2d.of(minx, miny), Coord2d.of(maxx, maxy)};
    }

    /** {@code {ul, br}} across every loop. */
    public static Coord2d[] bounds(Coord2d[][] loops) {
        double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
        double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
        for (Coord2d[] loop : loops) {
            if (loop == null)
                continue;
            for (Coord2d v : loop) {
                if (v.x < minx) minx = v.x;
                if (v.y < miny) miny = v.y;
                if (v.x > maxx) maxx = v.x;
                if (v.y > maxy) maxy = v.y;
            }
        }
        if (minx > maxx || miny > maxy)
            return null;
        return new Coord2d[]{Coord2d.of(minx, miny), Coord2d.of(maxx, maxy)};
    }

    /**
     * Decompose an obstacle outline into rectangles, each as a {@code {ul, br}} pair.
     *
     * @return the rectangles, or an empty list when the outline is unusable or too complex to be
     *         worth representing exactly (the caller should fall back to the bounding box).
     */
    public static List<Coord2d[]> decompose(Coord2d[][] loops) {
        if (loops == null || loops.length == 0)
            return Collections.emptyList();

        List<Coord2d[]> usable = new ArrayList<>();
        for (Coord2d[] loop : loops) {
            if (loop != null && loop.length >= 3)
                usable.add(loop);
        }
        if (usable.isEmpty())
            return Collections.emptyList();

        if (allPlainRects(usable)) {
            List<Coord2d[]> res = new ArrayList<>(usable.size());
            for (Coord2d[] loop : usable)
                res.add(bounds(loop));
            return res;
        }
        return rasterize(usable.toArray(new Coord2d[0][]));
    }

    /**
     * True when every loop is an axis-aligned rectangle and no rectangle sits inside another. The
     * nesting check matters because a loop inside a loop is a hole under even-odd filling, and
     * taking the loops verbatim would fill it in.
     */
    private static boolean allPlainRects(List<Coord2d[]> loops) {
        for (Coord2d[] loop : loops) {
            if (!isAxisRect(loop))
                return false;
        }
        for (int i = 0; i < loops.size(); i++) {
            Coord2d[] a = bounds(loops.get(i));
            for (int j = 0; j < loops.size(); j++) {
                if (i == j)
                    continue;
                Coord2d[] b = bounds(loops.get(j));
                if (a[0].x >= b[0].x - EPS && a[0].y >= b[0].y - EPS &&
                        a[1].x <= b[1].x + EPS && a[1].y <= b[1].y + EPS)
                    return false;
            }
        }
        return true;
    }

    private static List<Coord2d[]> rasterize(Coord2d[][] loops) {
        Coord2d[] bb = bounds(loops);
        if (bb == null)
            return Collections.emptyList();
        double w = bb[1].x - bb[0].x, h = bb[1].y - bb[0].y;
        if (w <= EPS || h <= EPS)
            return Collections.emptyList();

        double step = Math.max(STEP, Math.max(w, h) / MAX_CELLS);
        int nx = (int) Math.ceil(w / step);
        int ny = (int) Math.ceil(h / step);
        if (nx < 1 || ny < 1)
            return Collections.emptyList();

        boolean[][] fill = new boolean[nx][ny];
        for (int i = 0; i < nx; i++) {
            double cx = bb[0].x + (i + 0.5) * step;
            for (int j = 0; j < ny; j++) {
                double cy = bb[0].y + (j + 0.5) * step;
                fill[i][j] = inside(loops, cx, cy);
            }
        }

        List<Coord2d[]> res = new ArrayList<>();
        boolean[][] used = new boolean[nx][ny];
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                if (!fill[i][j] || used[i][j])
                    continue;
                int rw = 1;
                while (i + rw < nx && fill[i + rw][j] && !used[i + rw][j])
                    rw++;
                int rh = 1;
                grow:
                while (j + rh < ny) {
                    for (int k = i; k < i + rw; k++) {
                        if (!fill[k][j + rh] || used[k][j + rh])
                            break grow;
                    }
                    rh++;
                }
                for (int a = i; a < i + rw; a++) {
                    for (int b = j; b < j + rh; b++)
                        used[a][b] = true;
                }
                res.add(new Coord2d[]{
                        Coord2d.of(bb[0].x + i * step, bb[0].y + j * step),
                        Coord2d.of(bb[0].x + (i + rw) * step, bb[0].y + (j + rh) * step)});
                if (res.size() > MAX_RECTS)
                    return Collections.emptyList();
            }
        }
        return res;
    }

    /** Even-odd point-in-polygon across every loop at once, so nested loops read as holes. */
    private static boolean inside(Coord2d[][] loops, double x, double y) {
        boolean in = false;
        for (Coord2d[] loop : loops) {
            for (int i = 0, j = loop.length - 1; i < loop.length; j = i++) {
                double yi = loop[i].y, yj = loop[j].y;
                if ((yi > y) != (yj > y)) {
                    double xint = loop[i].x + (y - yi) * (loop[j].x - loop[i].x) / (yj - yi);
                    if (x < xint)
                        in = !in;
                }
            }
        }
        return in;
    }
}
