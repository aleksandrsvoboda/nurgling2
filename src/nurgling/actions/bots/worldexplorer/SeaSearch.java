package nurgling.actions.bots.worldexplorer;

import haven.Coord;
import haven.Coord2d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Ocean Explorer's searches over the tile map: the nearest edge to head for in open water, the way out when every ray is
 *  blocked, and the extent of an island the boat has sailed all the way round. */
public final class SeaSearch {
    private SeaSearch() {}

    private static final Coord[] NEIGHBOURS = {
            Coord.of(1, 0), Coord.of(-1, 0), Coord.of(0, 1), Coord.of(0, -1),
            Coord.of(1, 1), Coord.of(1, -1), Coord.of(-1, 1), Coord.of(-1, -1)};

    /** The nearest known tile outside the band (and not ignored) within maxR tiles, by square rings - null if none is loaded. */
    public static Coord nearestEdge(SeaGrid.Tiles tiles, Predicate<String> band, Set<Coord> ignored, Coord centre, int maxR) {
        for (int r = 1; r <= maxR; r++) {
            Coord best = null;
            double bestD = Double.MAX_VALUE;
            for (int dy = -r; dy <= r; dy++) {
                int step = (dy == -r || dy == r) ? 1 : 2 * r;
                for (int dx = -r; dx <= r; dx += step) {
                    Coord tc = centre.add(dx, dy);
                    String name = tiles.name(tc);
                    if (name != null && !band.test(name) && !ignored.contains(tc)) {
                        double d = Math.hypot(dx, dy);
                        if (d < bestD) {
                            bestD = d;
                            best = tc;
                        }
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** Tiles from `from` to the nearest tile that isn't blocked, moving only over tiles the hull can be on; `from` itself may be
     *  anything (the boat can be wedged against a wall). Empty when `from` is already free, null when nothing free is reachable. */
    public static List<Coord> escape(SeaGrid g, Coord from) {
        if (!g.blocked(from)) return new ArrayList<>();
        Map<Coord, Coord> prev = new HashMap<>();
        ArrayDeque<Coord> queue = new ArrayDeque<>();
        prev.put(from, from);
        queue.add(from);
        while (!queue.isEmpty()) {
            Coord c = queue.poll();
            for (Coord o : NEIGHBOURS) {
                Coord n = c.add(o);
                if (prev.containsKey(n) || g.hard(n)) continue;
                // Corners: no squeezing diagonally between two hard tiles.
                if (o.x != 0 && o.y != 0 && (g.hard(c.add(o.x, 0)) || g.hard(c.add(0, o.y)))) continue;
                prev.put(n, c);
                if (!g.blocked(n)) {
                    List<Coord> path = new ArrayList<>();
                    for (Coord t = n; !t.equals(from); t = prev.get(t))
                        path.add(t);
                    Collections.reverse(path);
                    return path;
                }
                queue.add(n);
            }
        }
        return null;
    }

    /** The farthest point along path that the boat at p reaches in a straight line over sailable tiles. */
    public static Coord2d farthestInSight(SeaGrid g, Coord2d p, List<Coord> path) {
        Coord2d best = SeaGrid.centreOf(path.get(0));
        for (Coord tc : path) {
            Coord2d c = SeaGrid.centreOf(tc);
            if (g.lineSailable(p, c))
                best = c;
            else
                break;
        }
        return best;
    }

    /** Every known tile outside the band connected to seed, or null when the region isn't an island: it runs into unloaded map
     *  or is bigger than maxTiles. */
    public static Set<Coord> island(SeaGrid.Tiles tiles, Predicate<String> band, Coord seed, int maxTiles) {
        Set<Coord> seen = new HashSet<>();
        ArrayDeque<Coord> queue = new ArrayDeque<>();
        seen.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            Coord c = queue.poll();
            for (int k = 0; k < 4; k++) {
                Coord n = c.add(NEIGHBOURS[k]);
                if (seen.contains(n)) continue;
                String name = tiles.name(n);
                if (name == null) return null;
                if (band.test(name)) continue;
                seen.add(n);
                if (seen.size() > maxTiles) return null;
                queue.add(n);
            }
        }
        return seen;
    }
}
