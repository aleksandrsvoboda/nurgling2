package nurgling.widgets;

import haven.Coord;
import nurgling.forage.ForageFind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Forage finds of the displayed segment grouped into map badges: finds of the same item closer than the
 * merge distance on screen share one badge, showing the best of them. Same approach as
 * {@link LabeledMarkClusters}, over finds instead of prospect marks.
 */
final class ForageClusters {
    /** A find together with where it sits in the displayed segment. */
    static final class Placed {
        final ForageFind find;
        final Coord tc;

        Placed(ForageFind find, Coord tc) {
            this.find = find;
            this.tc = tc;
        }
    }

    static final class Cluster {
        /** Best quality first. */
        final List<Placed> finds = new ArrayList<>();
        final Coord anchor;

        Cluster(Placed best) {
            finds.add(best);
            anchor = best.tc;
        }

        ForageFind best() {return finds.get(0).find;}

        ForageFind worst() {return finds.get(finds.size() - 1).find;}

        Coord screenPos(Coord viewTc, double scale, Coord hsz) {
            return new Coord((int)Math.round((anchor.x - viewTc.x) / scale) + hsz.x,
                             (int)Math.round((anchor.y - viewTc.y) / scale) + hsz.y);
        }
    }

    private static final Comparator<Placed> BEST_FIRST =
        Comparator.comparingDouble((Placed p) -> p.find.quality).reversed()
                  .thenComparing(p -> p.find.id);

    /** Drawn lowest quality first, so the best badge ends up on top. */
    final List<Cluster> clusters;

    ForageClusters(List<Placed> placed, double scale, int mergePx, boolean cluster) {
        List<Placed> sorted = new ArrayList<>(placed);
        sorted.sort(BEST_FIRST);
        List<Cluster> built;
        if(cluster) {
            built = group(sorted, mergePx * scale);
        } else {
            built = new ArrayList<>(sorted.size());
            for(Placed p : sorted)
                built.add(new Cluster(p));
        }
        built.sort(Comparator.comparingDouble((Cluster c) -> c.best().quality));
        clusters = Collections.unmodifiableList(built);
    }

    /** Greedy grouping, best first, on a hash grid of cells one merge radius wide. */
    private static List<Cluster> group(List<Placed> bestFirst, double radius) {
        List<Cluster> out = new ArrayList<>();
        Map<String, Map<Long, List<Cluster>>> grids = new HashMap<>();
        double r2 = radius * radius;
        for(Placed p : bestFirst) {
            Map<Long, List<Cluster>> grid = grids.computeIfAbsent(p.find.itemName, k -> new HashMap<>());
            int cx = (int)Math.floor(p.tc.x / radius);
            int cy = (int)Math.floor(p.tc.y / radius);
            Cluster nearest = null;
            double nearestD2 = r2;
            for(int ox = -1; ox <= 1; ox++) {
                for(int oy = -1; oy <= 1; oy++) {
                    List<Cluster> cell = grid.get(cellKey(cx + ox, cy + oy));
                    if(cell == null)
                        continue;
                    for(Cluster c : cell) {
                        double dx = c.anchor.x - p.tc.x, dy = c.anchor.y - p.tc.y;
                        double d2 = dx * dx + dy * dy;
                        if(d2 < nearestD2) {
                            nearestD2 = d2;
                            nearest = c;
                        }
                    }
                }
            }
            if(nearest != null) {
                nearest.finds.add(p);
            } else {
                Cluster c = new Cluster(p);
                grid.computeIfAbsent(cellKey(cx, cy), k -> new ArrayList<>()).add(c);
                out.add(c);
            }
        }
        return out;
    }

    private static long cellKey(int x, int y) {
        return ((long)x << 32) | (y & 0xffffffffL);
    }
}
