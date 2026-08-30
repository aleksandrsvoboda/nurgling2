package nurgling.db;

import haven.Coord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides which of another player's grids have to be replayed, and in what order, for their map to
 * line up with this one.
 *
 * <p>This is the part of the import that is easy to get quietly wrong, so it is a pure function over
 * plain data: no database, no {@link haven.MapFile}, no threads. Everything it needs about a grid -
 * where the uploader puts it, how old the shared copy is, whether this client has it and in which of
 * its own segments - arrives as a {@link Candidate}, and what comes back is a list of chunks to emit
 * plus the two sets the import filter needs. {@code MapImportPlannerTest} exercises it directly.
 *
 * <h2>Why the obvious plan is wrong</h2>
 *
 * <p>The tempting plan is "fetch only the grids we do not already have". It halves the transfer and
 * it breaks the feature, because {@code MapFile.Importer.importgrid} works out where an incoming
 * segment attaches from the local {@code GridInfo} of the <em>first grid it accepts</em>:
 *
 * <pre>
 * if(seg.noff == null) {
 *     if(info == null) { rseg = chseg(new Segment(seg.nseg = grid.gid)); seg.noff = Coord.z; }
 *     else             { rseg = chseg(seg.nseg = info.seg); seg.noff = seg.offs.get(info.seg); }
 * }
 * </pre>
 *
 * <p>Grids both players have are exactly the ones with a local {@code GridInfo}, and they are
 * exactly the ones "only what we lack" throws away. What is left is all {@code info == null}, so the
 * importer opens a brand new segment and the neighbour's land arrives as an island floating free of
 * the map it belongs to. Worse, offering such a grid is not enough either: {@code seg.offs} is
 * recorded before the filter runs but {@code seg.noff} is assigned inside it, so an <em>overlapping
 * grid has to be accepted</em>, not merely present.
 *
 * <p>Hence anchors. For every segment of the uploader's map that overlaps land this client already
 * knows, one overlapping grid per local segment it touches is forced through the filter regardless
 * of age. The first anchor tells the importer which local segment to attach to; any further anchor
 * lands in the merge branch and joins two of this client's own segments together, which is the other
 * half of what importing a neighbour's map is for.
 *
 * <h2>The price, and how it is kept small</h2>
 *
 * <p>A forced grid is written whatever its age, so it can replace a locally newer copy. That is why
 * an anchor is the overlapping grid with the smallest {@code localMtime - dbMtime}: a shared copy
 * that is newer than ours is free, and when every shared copy is older the one chosen is the one
 * that gives up the least. It is one grid per (uploader segment, local segment) pair, not one per
 * grid.
 */
public class MapImportPlanner {

    /**
     * One placement row of one uploader, with everything known about the grid it names.
     *
     * @param dbMtime   age of the shared copy, or null when the world holds no payload for this grid
     * @param localMtime age of this client's copy, or null when this client does not have it
     * @param localSeg  the segment of <em>this</em> client's map the grid sits in, or null when this
     *                  client does not have it. This is the importer's {@code info.seg}, and its
     *                  presence - not the mtime - is what makes a grid usable as an anchor.
     */
    public static final class Candidate {
        public final long gid;
        public final long segid;
        public final Coord sc;
        public final Long dbMtime;
        public final Long localMtime;
        public final Long localSeg;

        public Candidate(long gid, long segid, Coord sc, Long dbMtime, Long localMtime, Long localSeg) {
            this.gid = gid;
            this.segid = segid;
            this.sc = sc;
            this.dbMtime = dbMtime;
            this.localMtime = localMtime;
            this.localSeg = localSeg;
        }

        /** Whether the shared copy would be accepted on its own merits. */
        public boolean fresh() {
            return (dbMtime != null) && ((localMtime == null) || (dbMtime > localMtime));
        }

        /** Whether a payload exists to replay at all. */
        public boolean available() {
            return dbMtime != null;
        }

        /** Whether replaying this grid would tell the importer where the segment attaches. */
        public boolean anchorable() {
            return (localSeg != null) && (dbMtime != null);
        }
    }

    /** One of the uploader's segments, and the chunks to replay for it. */
    public static final class SegmentPlan {
        public final long segid;
        /** Ordered: anchors first, then the grids worth transferring on their own merits. */
        public final List<Candidate> emit;
        /** Gids among {@link #emit} that the filter must accept regardless of age. */
        public final Set<Long> anchors;

        SegmentPlan(long segid, List<Candidate> emit, Set<Long> anchors) {
            this.segid = segid;
            this.emit = Collections.unmodifiableList(emit);
            this.anchors = Collections.unmodifiableSet(anchors);
        }

        /** True when this segment is land the client has never seen, so no anchor is needed. */
        public boolean newland() {
            return anchors.isEmpty();
        }
    }

    /** The whole plan for one uploader. */
    public static final class Plan {
        /** Segments in ascending segid order; chunks must be emitted segment by segment. */
        public final List<SegmentPlan> segments;
        /** Every anchor gid, across all segments. */
        public final Set<Long> forced;
        /** Segments that will end up with a usable offset, so their markers can be replayed. */
        public final Set<Long> covered;
        /** Segments that were left out, and why. Diagnostics only. */
        public final List<String> skipped;

        Plan(List<SegmentPlan> segments, Set<Long> forced, Set<Long> covered, List<String> skipped) {
            this.segments = Collections.unmodifiableList(segments);
            this.forced = Collections.unmodifiableSet(forced);
            this.covered = Collections.unmodifiableSet(covered);
            this.skipped = Collections.unmodifiableList(skipped);
        }

        public int gridCount() {
            int n = 0;
            for (SegmentPlan s : segments)
                n += s.emit.size();
            return n;
        }
    }

    private MapImportPlanner() {}

    /**
     * Work out what to replay from one uploader.
     *
     * @param placements all of that uploader's placement rows
     * @param markerSegs the uploader's segments that carry markers; a segment with nothing but
     *                   markers still needs an anchor, or the markers are silently dropped
     */
    public static Plan plan(Collection<Candidate> placements, Set<Long> markerSegs) {
        Set<Long> wantMarkers = (markerSegs == null) ? Collections.emptySet() : markerSegs;

        Map<Long, List<Candidate>> bySeg = new TreeMap<>();
        for (Candidate c : placements)
            bySeg.computeIfAbsent(c.segid, k -> new ArrayList<>()).add(c);

        List<SegmentPlan> plans = new ArrayList<>();
        Set<Long> forced = new LinkedHashSet<>();
        Set<Long> covered = new LinkedHashSet<>();
        List<String> skipped = new ArrayList<>();

        for (Map.Entry<Long, List<Candidate>> e : bySeg.entrySet()) {
            long segid = e.getKey();
            List<Candidate> cands = e.getValue();
            cands.sort((a, b) -> Long.compare(a.gid, b.gid));

            List<Candidate> fresh = new ArrayList<>();
            boolean overlaps = false;
            for (Candidate c : cands) {
                if (c.fresh()) fresh.add(c);
                if (c.localSeg != null) overlaps = true;
            }
            boolean hasMarkers = wantMarkers.contains(segid);

            if (fresh.isEmpty() && !hasMarkers) {
                /* Nothing new to show and no markers riding along: replaying it would only force
                 * writes for their own sake. */
                continue;
            }

            List<Candidate> anchors = chooseAnchors(cands);

            if (overlaps && anchors.isEmpty()) {
                /* The segment overlaps land this client has, but not one overlapping grid has a
                 * payload to replay - so nothing can tell the importer where it attaches. Emitting
                 * the rest would strand it in a segment of its own, which is worse than skipping:
                 * the grids stay available for the next import, once whoever owns them uploads. */
                skipped.add(String.format("segment %x: overlaps this map but has no shared copy of "
                    + "any overlapping grid to anchor it", segid));
                continue;
            }
            if (!overlaps && fresh.isEmpty()) {
                /* Markers on land nobody has a grid for. */
                skipped.add(String.format("segment %x: no grid available to place its markers by", segid));
                continue;
            }

            Set<Long> anchorGids = new LinkedHashSet<>();
            List<Candidate> emit = new ArrayList<>(anchors);
            for (Candidate a : anchors)
                anchorGids.add(a.gid);
            for (Candidate c : fresh) {
                if (!anchorGids.contains(c.gid))
                    emit.add(c);
            }

            plans.add(new SegmentPlan(segid, emit, anchorGids));
            forced.addAll(anchorGids);
            covered.add(segid);
        }

        return new Plan(plans, forced, covered, skipped);
    }

    /**
     * One anchor per local segment the uploader's segment reaches into.
     *
     * <p>The first of them decides where the incoming segment attaches; the others land in
     * {@code importgrid}'s merge branch and join those local segments to each other. Within a local
     * segment the pick is the grid that loses the least by being overwritten - a shared copy newer
     * than ours scores negative and wins outright.
     */
    private static List<Candidate> chooseAnchors(List<Candidate> cands) {
        Map<Long, Candidate> best = new LinkedHashMap<>();
        for (Candidate c : cands) {
            if (!c.anchorable())
                continue;
            Candidate cur = best.get(c.localSeg);
            if ((cur == null) || (regression(c) < regression(cur))
                || ((regression(c) == regression(cur)) && (c.gid < cur.gid)))
                best.put(c.localSeg, c);
        }
        List<Candidate> ret = new ArrayList<>(best.values());
        ret.sort((a, b) -> Long.compare(a.gid, b.gid));
        return ret;
    }

    /** How much age a forced write would give up; negative means the shared copy is newer. */
    private static long regression(Candidate c) {
        long local = (c.localMtime == null) ? Long.MIN_VALUE / 4 : c.localMtime;
        return local - c.dbMtime;
    }

    /** Every gid the plan needs a payload for. */
    public static Set<Long> payloadGids(Plan plan) {
        Set<Long> ret = new LinkedHashSet<>();
        for (SegmentPlan s : plan.segments) {
            for (Candidate c : s.emit)
                ret.add(c.gid);
        }
        return ret;
    }

    /**
     * Drop segments whose anchors did not turn up, once the payloads have actually been fetched.
     *
     * <p>A row can go missing between planning and fetching - a grid deleted, or an upload cancelled
     * after its placements landed but before its payload did. Losing an anchor is not survivable for
     * that segment, so it goes; losing an ordinary grid just makes the segment smaller.
     */
    public static Plan restrict(Plan plan, Set<Long> haveGids) {
        List<SegmentPlan> segments = new ArrayList<>();
        Set<Long> forced = new LinkedHashSet<>();
        Set<Long> covered = new LinkedHashSet<>();
        List<String> skipped = new ArrayList<>(plan.skipped);
        for (SegmentPlan s : plan.segments) {
            boolean anchorsOk = haveGids.containsAll(s.anchors);
            if (!anchorsOk) {
                skipped.add(String.format("segment %x: its anchor grid is missing from the database",
                                          s.segid));
                continue;
            }
            List<Candidate> emit = new ArrayList<>();
            for (Candidate c : s.emit) {
                if (haveGids.contains(c.gid))
                    emit.add(c);
            }
            if (emit.isEmpty())
                continue;
            segments.add(new SegmentPlan(s.segid, emit, new HashSet<>(s.anchors)));
            forced.addAll(s.anchors);
            covered.add(s.segid);
        }
        return new Plan(segments, forced, covered, skipped);
    }
}
