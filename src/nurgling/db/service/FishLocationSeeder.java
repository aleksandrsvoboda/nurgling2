package nurgling.db.service;

import haven.Coord;
import haven.MCache;
import haven.MapFile;
import nurgling.FishLocation;
import nurgling.NGameUI;
import nurgling.db.DatabaseManager;
import nurgling.db.dao.FishLocationDao;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The one bridge between the JSON file and the database: a manual, one-way import of a profile's
 * {@code fish_locations.nurgling.json} into the {@code fish_locations} table.
 *
 * <p>Normal operation keeps the two stores completely separate - file mode never touches the database and
 * database mode never touches the file - so this exists purely to carry spots saved before the database
 * was set up. It is idempotent: row ids are derived from the position and the fish, so importing twice,
 * or from two machines, converges on one row per spot.
 */
public class FishLocationSeeder {

    /** What one seed run did. */
    public static final class SeedResult {
        public int inserted;        // rows created
        public int alreadyPresent;  // row existed and was at least as fresh
        public int refreshed;       // row existed but the file's record was newer
        public int unresolvable;    // no grid id could be determined - skipped
        public int skippedDeleted;  // row exists as a tombstone - deliberately not resurrected

        public int total() { return inserted + alreadyPresent + refreshed + unresolvable + skippedDeleted; }

        @Override
        public String toString() {
            return "inserted=" + inserted + " present=" + alreadyPresent + " refreshed=" + refreshed
                 + " unresolvable=" + unresolvable + " skippedDeleted=" + skippedDeleted;
        }
    }

    private final DatabaseManager databaseManager;
    private final FishLocationDao dao = new FishLocationDao();

    public FishLocationSeeder(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Read the profile's JSON file straight off disk and push what it holds into the database.
     *
     * <p>The file is read rather than the in-memory map because in database mode that map holds database
     * rows, not file rows - the whole point of the action is to import what the file still has.
     */
    public CompletableFuture<SeedResult> seedAsync(NGameUI gui, String dataFile, String profile) {
        // Resolve legacy coordinates on the caller's side first: it needs the map file, not the database.
        final List<Candidate> candidates = new ArrayList<>();
        final SeedResult result = new SeedResult();
        readCandidates(gui, dataFile, profile, candidates, result);

        final String touchedBy = FishLocationDbService.currentPlayerName();

        /* NOTE: one executeOperation per record, never one wrapping the loop. saveArea's comment in
         * AreaService explains why: nested borrows on the same thread self-deadlock the SQLite pool,
         * which has a single connection. */
        return CompletableFuture.supplyAsync(() -> {
            for (Candidate c : candidates) {
                try {
                    FishLocationDao.FishRow existing = databaseManager.executeOperation(
                        adapter -> dao.loadIncludingTombstone(adapter, c.id, profile));

                    if (existing != null && existing.isTombstone()) {
                        // Someone deleted this spot on purpose. A seed must not undo that.
                        result.skippedDeleted++;
                        continue;
                    }
                    if (existing != null && existing.caughtAt >= c.caughtAt) {
                        result.alreadyPresent++;
                        continue;
                    }

                    databaseManager.executeOperation(adapter -> {
                        dao.upsert(adapter, c.id, c.gridId, c.offset.x, c.offset.y,
                            c.fishName, c.fishRes, c.percentage, c.gameTime, c.moonPhase,
                            c.rod, c.hook, c.line, c.bait, c.caughtAt, profile, touchedBy);
                        return null;
                    });
                    if (existing == null) result.inserted++;
                    else result.refreshed++;
                } catch (SQLException e) {
                    System.err.println("Fish seed failed for " + c.fishName + " (" + c.id + "): " + e.getMessage());
                }
            }
            return result;
        });
    }

    /**
     * Parse the file and work out a grid id for every record.
     *
     * <p>v2 records already carry one. v1 records only have a map-file segment id plus segment-relative
     * tile coords, which are meaningless on another client, so they are converted here through the
     * segment's grid map. Records whose segment the map file no longer knows are counted as unresolvable
     * rather than dropped silently.
     */
    private void readCandidates(NGameUI gui, String dataFile, String profile,
                                List<Candidate> out, SeedResult result) {
        String content = NFileUtils.readWithBackupFallback(dataFile);
        if (content == null || content.isEmpty()) return;

        JSONArray array;
        try {
            array = new JSONObject(content).getJSONArray("fishLocations");
        } catch (RuntimeException e) {
            System.err.println("Fish seed: cannot parse " + dataFile + ": " + e.getMessage());
            return;
        }

        MapFile file = (gui != null && gui.mmap != null) ? gui.mmap.file : null;

        for (int i = 0; i < array.length(); i++) {
            FishLocation loc;
            try {
                loc = new FishLocation(array.getJSONObject(i));
            } catch (RuntimeException e) {
                result.unresolvable++;
                continue;
            }

            long gridId = loc.getGridId();
            Coord offset = loc.getOffset();

            if (!loc.hasGrid()) {
                Coord legacyTc = loc.getLegacyTc();
                if (legacyTc == null || file == null) {
                    result.unresolvable++;
                    continue;
                }
                Long resolved = null;
                file.lock.readLock().lock();
                try {
                    MapFile.Segment seg = file.segments.get(loc.getLegacySegId());
                    if (seg != null) {
                        resolved = seg.map.get(legacyTc.div(MCache.cmaps));
                    }
                } finally {
                    file.lock.readLock().unlock();
                }
                if (resolved == null) {
                    result.unresolvable++;
                    continue;
                }
                gridId = resolved;
                offset = legacyTc.mod(MCache.cmaps);
            }

            Candidate c = new Candidate();
            c.gridId = gridId;
            c.offset = offset;
            c.id = FishLocation.deterministicId(profile, gridId, offset, loc.getFishName());
            c.fishName = loc.getFishName();
            c.fishRes = loc.getFishResource();
            c.percentage = loc.getPercentage();
            c.gameTime = loc.getGameTime();
            c.moonPhase = loc.getMoonPhase();
            c.rod = loc.getFishingRod();
            c.hook = loc.getHook();
            c.line = loc.getLine();
            c.bait = loc.getBait();
            c.caughtAt = loc.getTimestamp();
            out.add(c);
        }
    }

    /** One file record, already converted to the grid-relative form the table stores. */
    private static final class Candidate {
        String id;
        long gridId;
        Coord offset;
        String fishName, fishRes, percentage, gameTime, moonPhase, rod, hook, line, bait;
        long caughtAt;
    }
}
