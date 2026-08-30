package nurgling.db;

import haven.MapFile;
import haven.MessageBuf;
import haven.Utils;
import nurgling.db.dao.MapDataDao;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileLockInterruptionException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays what other players have shared into this client's {@link MapFile}.
 *
 * <p>Everything the merge needs from the database arrives through {@link Source}, and everything it
 * has to say goes out through {@link Reporter}, so the interesting half of the import - which grids
 * to replay, in what order, which ones to force, what the filter then accepts - runs without a
 * database, a UI or a thread behind it. {@code MapMergeTest} drives it against a {@link MapFile}
 * backed by an in-memory cache; {@link nurgling.tools.MapDbTransfer} wires the same code to
 * PostgreSQL and a progress window.
 *
 * <p>The decisions themselves live in {@link MapImportPlanner}, which is where the reasoning about
 * segment alignment is written down.
 */
public class MapMerge {

    /** Grid ids fetched per round trip. */
    private static final int PAGE = MapDataDao.BATCH;

    /** Everything the merge reads. One world, one asking player; both are bound by the caller. */
    public interface Source {
        Map<Long, Long> manifest() throws SQLException;

        /** Players who have shared this world, excluding the one asking. */
        List<String> uploaders() throws SQLException;

        List<MapDataDao.Placement> placements(String uploader) throws SQLException;

        List<MapDataDao.MarkerRow> markers(String uploader) throws SQLException;

        Map<Long, byte[]> payloads(List<Long> gids) throws SQLException;
    }

    /** Where progress goes. Every method is optional. */
    public interface Reporter {
        default void phase(String what) {}

        default void merging(String uploader) {}

        default void fetching(String uploader, int done, int total) {}
    }

    public enum Status {
        /** No player has shared anything for this world. */
        NOTHING_SHARED,
        /** Others have shared, but everything on offer is this player's own. */
        NO_OTHERS,
        /** The merge ran; see the counts. */
        DONE
    }

    /** What a merge did. */
    public static final class Report {
        public final Status status;
        /** Grids accepted because they were genuinely newer - anchors forced in are not counted. */
        public final int grids;
        public final int markers;
        /** Uploaders that contributed something. */
        public final int players;
        /** Segments and uploaders that were left out, and why. */
        public final List<String> notes;

        Report(Status status, int grids, int markers, int players, List<String> notes) {
            this.status = status;
            this.grids = grids;
            this.markers = markers;
            this.players = players;
            this.notes = Collections.unmodifiableList(notes);
        }

        public boolean changedNothing() {
            return (grids == 0) && (markers == 0);
        }
    }

    private MapMerge() {}

    /** Pull in everything every other player has shared for this world. */
    public static Report run(MapFile file, Source src, boolean shareMarkers, Reporter rep)
            throws SQLException, InterruptedException {
        List<String> notes = new ArrayList<>();

        Map<Long, Long> manifest = src.manifest();
        if (manifest.isEmpty())
            return new Report(Status.NOTHING_SHARED, 0, 0, 0, notes);
        List<String> uploaders = src.uploaders();
        if (uploaders.isEmpty())
            return new Report(Status.NO_OTHERS, 0, 0, 0, notes);

        /* What this client already has, read once per grid and kept current as grids land, so that
         * the next player's map is planned against the map as it now stands rather than as it was
         * when the import started. Getting that wrong is not cosmetic: a second uploader who also
         * has land the first one just delivered would otherwise see it as unknown, and open a
         * second segment for terrain that is already in one. */
        LocalIndex local = new LocalIndex(file);

        int[] counts = {0, 0};
        int players = 0;
        for (String up : uploaders) {
            Utils.checkirq();
            rep.merging(up);
            if (mergeOne(file, src, up, shareMarkers, manifest, local, counts, notes, rep))
                players++;
        }
        return new Report(Status.DONE, counts[0], counts[1], players, notes);
    }

    /**
     * Replay one player's map into this one.
     *
     * @return whether anything was replayed at all
     */
    private static boolean mergeOne(MapFile file, Source src, String uploader, boolean shareMarkers,
                                    Map<Long, Long> manifest, LocalIndex local, int[] counts,
                                    List<String> notes, Reporter rep)
            throws SQLException, InterruptedException {
        List<MapDataDao.Placement> placements = src.placements(uploader);
        if (placements.isEmpty())
            return false;
        List<MapDataDao.MarkerRow> markerRows = shareMarkers ? src.markers(uploader) : List.of();
        Set<Long> markerSegs = new HashSet<>();
        for (MapDataDao.MarkerRow m : markerRows)
            markerSegs.add(m.segid);

        List<MapImportPlanner.Candidate> cands = new ArrayList<>(placements.size());
        for (MapDataDao.Placement p : placements) {
            Utils.checkirq();
            cands.add(new MapImportPlanner.Candidate(p.gid, p.segid, p.sc, manifest.get(p.gid),
                                                     local.mtime(p.gid), local.seg(p.gid)));
        }
        MapImportPlanner.Plan plan = MapImportPlanner.plan(cands, markerSegs);
        if (plan.segments.isEmpty()) {
            note(notes, uploader, plan.skipped);
            return false;
        }

        /* Only what the plan needs is worth its transfer: the anchors that align each segment, and
         * the grids that are genuinely newer than this client's. */
        List<Long> need = new ArrayList<>(MapImportPlanner.payloadGids(plan));
        Map<Long, byte[]> blobs = new HashMap<>();
        for (int off = 0; off < need.size(); off += PAGE) {
            Utils.checkirq();
            int end = Math.min(off + PAGE, need.size());
            blobs.putAll(src.payloads(need.subList(off, end)));
            rep.fetching(uploader, end, need.size());
        }
        plan = MapImportPlanner.restrict(plan, blobs.keySet());
        note(notes, uploader, plan.skipped);
        if (plan.segments.isEmpty())
            return false;

        List<MapStreamCodec.GridEmit> emits = new ArrayList<>(plan.gridCount());
        for (MapImportPlanner.SegmentPlan s : plan.segments) {
            for (MapImportPlanner.Candidate c : s.emit)
                emits.add(new MapStreamCodec.GridEmit(blobs.get(c.gid), c.segid, c.sc));
        }
        List<byte[]> markPayloads = new ArrayList<>();
        for (MapDataDao.MarkerRow m : markerRows) {
            if (plan.covered.contains(m.segid))
                markPayloads.add(m.payload);
        }

        byte[] stream = MapStreamCodec.assemble(emits, markPayloads);
        emits.clear();
        blobs.clear();

        /* The same two-pass shape MapWnd.importmap uses. The first pass writes nothing; it exists so
         * that "Inconsistent grid locations detected" - one player's layout disagreeing with this
         * map about where a segment sits - is found before anything has been written. Carrying on
         * past that error would place the rest of the segment by an offset already known to be
         * wrong, so the whole uploader is skipped instead. */
        try {
            file.reimport(new MessageBuf(stream), MapFile.ImportFilter.readonly);
        } catch (RuntimeException e) {
            if (cancelled(e))
                throw asInterrupt(e);
            notes.add(uploader + ": layout disagrees with this map, skipped entirely ("
                      + e.getMessage() + ")");
            return false;
        }
        try {
            file.reimport(new MessageBuf(stream), filter(local, plan.forced, counts, uploader, notes));
        } catch (RuntimeException e) {
            if (cancelled(e))
                throw asInterrupt(e);
            throw e;
        }
        return true;
    }

    /**
     * Whether a failure is really this thread's own interrupt coming back at us.
     *
     * <p>MapFile stores grids through an NIO channel, and a channel interrupted mid-write closes
     * itself and throws {@link ClosedByInterruptException} - wrapped, by the time it gets here, in
     * an unchecked {@code StreamMessage.IOError}. Pressing Cancel during the write phase would
     * otherwise be reported as "import failed", or worse, mistaken by the caller above for the
     * uploader's layout disagreeing with this map.
     *
     * <p>Nothing is corrupted by it: the cache writes to a temporary file and moves it into place at
     * the end, so an interrupted write leaves the previous grid untouched and an orphaned temp file
     * behind. Whatever landed before the interrupt is whole, and running the import again finishes
     * the job.
     */
    public static boolean cancelled(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if ((c instanceof ClosedByInterruptException) || (c instanceof FileLockInterruptionException)
                || (c instanceof InterruptedIOException) || (c instanceof InterruptedException))
                return true;
        }
        return Thread.currentThread().isInterrupted();
    }

    private static InterruptedException asInterrupt(Throwable cause) {
        InterruptedException ret = new InterruptedException("cancelled during " + cause);
        ret.initCause(cause);
        return ret;
    }

    private static void note(List<String> notes, String uploader, List<String> skipped) {
        for (String s : skipped)
            notes.add(uploader + ": " + s);
    }

    /**
     * Accept a grid when it is genuinely newer than the local copy, or when the plan needs it as an
     * anchor.
     *
     * <p>The freshness half matters more than it looks: {@code Importer.importgrid} saves whatever
     * it is given without comparing timestamps, so replaying an older snapshot of a grid - a
     * villager who mapped an area before it was built on - would quietly undo newer terrain.
     *
     * <p>The anchor half is what makes the merge work at all. {@code seg.noff}, which decides
     * whether an incoming segment attaches to one of this client's or opens a new one, is assigned
     * inside this very branch, so a grid the two maps have in common has to be <em>accepted</em>
     * and not merely offered. See {@link MapImportPlanner}.
     */
    static MapFile.ImportFilter filter(LocalIndex local, Set<Long> forced, int[] counts,
                                       String uploader, List<String> notes) {
        return new MapFile.ImportFilter() {
            public boolean includegrid(MapFile.ImportedGrid grid, boolean hasprev) {
                Long cur = local.mtime(grid.gid);
                boolean newer = (cur == null) || (grid.mtime > cur);
                if (!newer && !forced.contains(grid.gid))
                    return false;
                /* Record what is now genuinely on disk, so the next player's map is planned against
                 * it rather than against what was there when the import started. */
                local.record(grid.gid, grid.mtime);
                if (newer)
                    counts[0]++;
                return true;
            }

            public boolean includemark(MapFile.Marker mark, MapFile.Marker prev) {
                if (prev != null)
                    return false;
                counts[1]++;
                return true;
            }

            /**
             * A bad marker is skipped; a bad grid is not. Grid errors were already ruled out by the
             * validation pass, so one here means the map changed underneath us - and continuing
             * would place the rest of that segment by an offset known to be wrong.
             */
            public void handleerror(RuntimeException exc, String ctx) {
                if ("mark".equals(ctx)) {
                    notes.add(uploader + ": skipped a marker (" + exc.getMessage() + ")");
                    return;
                }
                throw exc;
            }
        };
    }

    // ------------------------------------------------------------------ local map index

    /**
     * What this client knows about a grid, memoised.
     *
     * <p>Two questions get asked of every grid a neighbour offers: how old is our copy, and which of
     * our segments is it in. Both are answered from the mapfile and both are expensive enough that
     * asking twenty thousand times unmemoised dominates an import - {@code Grid.load} in particular
     * inflates the whole grid, tiles and height map and all, to reach one {@code long}, which is why
     * {@link MapFile.Grid#loadmtime} exists.
     *
     * <p>The segment answer is also the importer's {@code info}, and its presence rather than the
     * mtime is what decides whether a grid can anchor a segment - so a grid file with no
     * {@code GridInfo} counts as absent here, matching what the importer will do with it.
     */
    static final class LocalIndex {
        private final MapFile file;
        private final Map<Long, Long> segs = new HashMap<>();
        private final Map<Long, Long> mtimes = new HashMap<>();

        LocalIndex(MapFile file) {
            this.file = file;
        }

        Long seg(long gid) {
            if (segs.containsKey(gid))
                return segs.get(gid);
            MapFile.GridInfo info;
            file.lock.readLock().lock();
            try {
                info = file.gridinfo.get(gid);
            } catch (RuntimeException e) {
                info = null;
            } finally {
                file.lock.readLock().unlock();
            }
            Long ret = (info == null) ? null : info.seg;
            segs.put(gid, ret);
            return ret;
        }

        Long mtime(long gid) {
            if (mtimes.containsKey(gid))
                return mtimes.get(gid);
            Long ret = (seg(gid) == null) ? null : MapFile.Grid.loadmtime(file, gid);
            mtimes.put(gid, ret);
            return ret;
        }

        /** Note a grid that has just been written, and forget which segment it used to be in. */
        void record(long gid, long mtime) {
            mtimes.put(gid, mtime);
            segs.remove(gid);
        }
    }
}
