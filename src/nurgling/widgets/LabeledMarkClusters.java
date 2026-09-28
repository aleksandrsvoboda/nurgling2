package nurgling.widgets;

import haven.Coord;
import nurgling.conf.ProspectKind;
import nurgling.conf.ProspectMarkSettings;

import java.util.*;

/**
 * Groups the labeled marks of one map segment into badges, so a patch of finds reads as one
 * icon per resource type instead of a pile of overlapping icons and labels.
 *
 * <p>Only mined finds (ore, gems and stone, which includes quarryartz) are grouped; ground
 * samples from the Checker bots are sparse and always drawn one per mark.
 *
 * <p>Mined marks of a type that sit within {@code mergePx} of each other on screen become one
 * cluster, anchored on its best mark: that is the tile worth walking to. Clusters of different
 * types stay on their own marks and may overlap; the best is drawn on top and the hover card
 * lists everything under the cursor. Laying them out side by side instead grew into ever
 * longer rows, pushed away from their marks, the further the map was zoomed out.
 *
 * <p>With grouping switched off every mark is its own badge, which is the plain
 * one-icon-per-mark view with the same ordering, hover card and right-click.
 *
 * <p>Everything is measured in tiles, which makes the result independent of where the map is
 * scrolled; only the zoom, the marks and the filter change it. Callers reuse a result until
 * one of those does.
 */
final class LabeledMarkClusters {
    /** Marks of one resource type drawn as a single badge. */
    static final class Cluster {
        final String type;
        /** Best quality first. */
        final List<LabeledMinimapMark> marks = new ArrayList<>();
        /** Tile the badge is drawn at: its best mark. */
        final Coord anchor;

        Cluster(LabeledMinimapMark best) {
            this.type = best.resourceType;
            this.marks.add(best);
            this.anchor = best.tileCoords;
        }

        LabeledMinimapMark best() {
            return marks.get(0);
        }

        LabeledMinimapMark worst() {
            return marks.get(marks.size() - 1);
        }

        /** Screen position of the badge centre for a view at {@code viewTc}. */
        Coord screenPos(Coord viewTc, double scale, Coord hsz) {
            return new Coord((int)Math.round((anchor.x - viewTc.x) / scale) + hsz.x,
                             (int)Math.round((anchor.y - viewTc.y) / scale) + hsz.y);
        }
    }

    private static final Comparator<LabeledMinimapMark> BEST_FIRST =
        Comparator.comparingDouble((LabeledMinimapMark m) -> m.quality).reversed()
                  .thenComparing(LabeledMinimapMark::getLocationId);

    private final List<LabeledMinimapMark> source;
    private final double scale;
    private final boolean cluster;
    private final int filterState;
    /** Lowest best quality first, which is the drawing order: the best badge ends up on top. */
    final List<Cluster> clusters;

    /**
     * @param marks    every mark of the segment
     * @param settings the visibility filter, or null to show everything
     * @param scale    tiles per screen pixel
     * @param mergePx  how close two marks of a type may be before they share a badge
     * @param cluster  whether mined marks are grouped at all
     */
    LabeledMarkClusters(List<LabeledMinimapMark> marks, ProspectMarkSettings settings, double scale, int mergePx,
                        boolean cluster) {
        this.source = marks;
        this.scale = scale;
        this.cluster = cluster;
        this.filterState = filterState(settings);

        List<LabeledMinimapMark> grouped = new ArrayList<>(marks.size());
        List<Cluster> built = new ArrayList<>();
        for(LabeledMinimapMark mark : marks) {
            if(settings != null && !settings.shows(mark.kind, mark.quality))
                continue;
            if(cluster && isMined(mark.kind))
                grouped.add(mark);
            else
                built.add(new Cluster(mark));
        }
        grouped.sort(BEST_FIRST);
        built.addAll(group(grouped, mergePx * scale));
        built.sort(Comparator.comparingDouble((Cluster c) -> c.best().quality));
        this.clusters = Collections.unmodifiableList(built);
    }

    /** Whether this result still describes the given marks, filter, zoom and grouping switch. */
    boolean matches(List<LabeledMinimapMark> marks, ProspectMarkSettings settings, double scale, boolean cluster) {
        return (marks == source) && (this.scale == scale) && (this.cluster == cluster)
            && (filterState == filterState(settings));
    }

    private static boolean isMined(ProspectKind kind) {
        return kind == ProspectKind.ORE || kind == ProspectKind.GEM || kind == ProspectKind.STONE;
    }

    /**
     * Cheap fingerprint of the filter. The settings object is mutated in place from the Map
     * Tools window, so identity cannot tell whether it changed.
     */
    private static int filterState(ProspectMarkSettings settings) {
        if(settings == null)
            return 0;
        int h = settings.master ? 1 : 2;
        for(ProspectKind kind : ProspectKind.values())
            h = h * 31 + (settings.enabled(kind) ? settings.threshold(kind) + 1 : 0);
        return h;
    }

    /**
     * Walk the marks best first: each joins the nearest cluster of its type whose best mark is
     * within {@code radius} tiles, or starts a new one. Clusters are bucketed on a grid of that
     * size, so only the 3x3 neighbourhood has to be searched.
     */
    private static List<Cluster> group(List<LabeledMinimapMark> bestFirst, double radius) {
        List<Cluster> out = new ArrayList<>();
        Map<String, Map<Long, List<Cluster>>> grids = new HashMap<>();
        double r2 = radius * radius;
        for(LabeledMinimapMark mark : bestFirst) {
            Map<Long, List<Cluster>> grid = grids.computeIfAbsent(mark.resourceType, k -> new HashMap<>());
            int cx = (int)Math.floor(mark.tileCoords.x / radius);
            int cy = (int)Math.floor(mark.tileCoords.y / radius);
            Cluster nearest = null;
            double nearestD2 = r2;
            for(int ox = -1; ox <= 1; ox++) {
                for(int oy = -1; oy <= 1; oy++) {
                    List<Cluster> cell = grid.get(cellKey(cx + ox, cy + oy));
                    if(cell == null)
                        continue;
                    for(Cluster c : cell) {
                        double d2 = dist2(c.anchor, mark.tileCoords);
                        if(d2 < nearestD2) {
                            nearestD2 = d2;
                            nearest = c;
                        }
                    }
                }
            }
            if(nearest != null) {
                nearest.marks.add(mark);
            } else {
                Cluster c = new Cluster(mark);
                grid.computeIfAbsent(cellKey(cx, cy), k -> new ArrayList<>()).add(c);
                out.add(c);
            }
        }
        return out;
    }

    private static long cellKey(int cx, int cy) {
        return ((long)cx << 32) ^ (cy & 0xffffffffL);
    }

    private static double dist2(Coord a, Coord b) {
        double dx = a.x - b.x, dy = a.y - b.y;
        return dx * dx + dy * dy;
    }
}
