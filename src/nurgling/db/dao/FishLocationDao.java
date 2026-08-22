package nurgling.db.dao;

import nurgling.db.DatabaseAdapter;
import nurgling.db.PostgresAdapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data access for {@code fish_locations}.
 *
 * <p>The primary key is deterministic - derived from profile, grid id, in-grid offset and fish name -
 * so the same physical spot is the same row on every client. That is what makes the seed action
 * idempotent and stops two players who saved one spot from creating two rows.
 *
 * <p>There is no field-group merge logic here, unlike areas: a fish record is created once and deleted
 * once, never edited, so concurrent adds and deletes are naturally independent upserts and tombstones.
 */
public class FishLocationDao {

    /** One row of {@code fish_locations}. */
    public static final class FishRow {
        public final String id;
        public final long gridId;
        public final int ox, oy;
        public final String fishName;
        public final String fishRes;
        public final String percentage;
        public final String gameTime;
        public final String moonPhase;
        public final String rod, hook, line, bait;
        public final long caughtAt;
        public final String profile;
        public final int version;
        public final Timestamp deletedAt;

        public FishRow(String id, long gridId, int ox, int oy,
                       String fishName, String fishRes, String percentage,
                       String gameTime, String moonPhase,
                       String rod, String hook, String line, String bait,
                       long caughtAt, String profile, int version, Timestamp deletedAt) {
            this.id = id;
            this.gridId = gridId;
            this.ox = ox;
            this.oy = oy;
            this.fishName = fishName;
            this.fishRes = fishRes;
            this.percentage = percentage;
            this.gameTime = gameTime;
            this.moonPhase = moonPhase;
            this.rod = rod;
            this.hook = hook;
            this.line = line;
            this.bait = bait;
            this.caughtAt = caughtAt;
            this.profile = profile;
            this.version = version;
            this.deletedAt = deletedAt;
        }

        public boolean isTombstone() { return deletedAt != null; }
    }

    /** Version and liveness of one row, for the delta poll. */
    public static final class VersionInfo {
        public final int version;
        public final boolean tombstoned;
        public VersionInfo(int version, boolean tombstoned) {
            this.version = version;
            this.tombstoned = tombstoned;
        }
    }

    private static final String COLS =
        "id, grid_id, ox, oy, fish_name, fish_res, percentage, game_time, moon_phase, " +
        "rod, hook, line, bait, caught_at, profile, version, deleted_at";

    private static FishRow readRow(ResultSet rs) throws SQLException {
        return new FishRow(
            rs.getString("id"),
            rs.getLong("grid_id"),
            rs.getInt("ox"),
            rs.getInt("oy"),
            rs.getString("fish_name"),
            rs.getString("fish_res"),
            rs.getString("percentage"),
            rs.getString("game_time"),
            rs.getString("moon_phase"),
            rs.getString("rod"),
            rs.getString("hook"),
            rs.getString("line"),
            rs.getString("bait"),
            rs.getLong("caught_at"),
            rs.getString("profile"),
            rs.getInt("version"),
            rs.getTimestamp("deleted_at"));
    }

    /**
     * Insert or update one record. Clears any tombstone: re-saving a spot someone deleted is a
     * deliberate re-add, and the caller (the seeder) is the one that refuses to resurrect.
     */
    public void upsert(DatabaseAdapter adapter,
                       String id, long gridId, int ox, int oy,
                       String fishName, String fishRes, String percentage,
                       String gameTime, String moonPhase,
                       String rod, String hook, String line, String bait,
                       long caughtAt, String profile, String touchedBy) throws SQLException {
        if (adapter instanceof PostgresAdapter) {
            String sql = "INSERT INTO fish_locations " +
                "(id, grid_id, ox, oy, fish_name, fish_res, percentage, game_time, moon_phase, " +
                "rod, hook, line, bait, caught_at, profile, version, updated_at, " +
                "last_touched_by, last_touched_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL) " +
                "ON CONFLICT (id) DO UPDATE SET " +
                "grid_id = EXCLUDED.grid_id, ox = EXCLUDED.ox, oy = EXCLUDED.oy, " +
                "fish_name = EXCLUDED.fish_name, fish_res = EXCLUDED.fish_res, " +
                "percentage = EXCLUDED.percentage, game_time = EXCLUDED.game_time, " +
                "moon_phase = EXCLUDED.moon_phase, rod = EXCLUDED.rod, hook = EXCLUDED.hook, " +
                "line = EXCLUDED.line, bait = EXCLUDED.bait, caught_at = EXCLUDED.caught_at, " +
                "profile = EXCLUDED.profile, version = fish_locations.version + 1, " +
                "updated_at = CURRENT_TIMESTAMP, last_touched_by = EXCLUDED.last_touched_by, " +
                "last_touched_at = CURRENT_TIMESTAMP, deleted_at = NULL";
            adapter.executeUpdate(sql, id, gridId, ox, oy, fishName, fishRes, percentage,
                gameTime, moonPhase, rod, hook, line, bait, caughtAt, profile, touchedBy);
        } else {
            String sql = "INSERT OR REPLACE INTO fish_locations " +
                "(id, grid_id, ox, oy, fish_name, fish_res, percentage, game_time, moon_phase, " +
                "rod, hook, line, bait, caught_at, profile, version, updated_at, " +
                "last_touched_by, last_touched_at, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                "COALESCE((SELECT version + 1 FROM fish_locations WHERE id = ?), 1), " +
                "CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL)";
            adapter.executeUpdate(sql, id, gridId, ox, oy, fishName, fishRes, percentage,
                gameTime, moonPhase, rod, hook, line, bait, caughtAt, profile, id, touchedBy);
        }
    }

    /** Every live row for a profile. */
    public List<FishRow> loadAll(DatabaseAdapter adapter, String profile) throws SQLException {
        List<FishRow> out = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT " + COLS + " FROM fish_locations WHERE profile = ? AND deleted_at IS NULL",
                profile)) {
            while (rs.next()) out.add(readRow(rs));
        }
        return out;
    }

    /** One row by id, tombstones included - the seeder needs to see them to refuse a resurrection. */
    public FishRow loadIncludingTombstone(DatabaseAdapter adapter, String id, String profile) throws SQLException {
        try (ResultSet rs = adapter.executeQuery(
                "SELECT " + COLS + " FROM fish_locations WHERE id = ? AND profile = ?", id, profile)) {
            if (rs.next()) return readRow(rs);
        }
        return null;
    }

    /** id -> version/liveness for every row of a profile, tombstones included. Drives the delta poll. */
    public Map<String, VersionInfo> getAllVersions(DatabaseAdapter adapter, String profile) throws SQLException {
        Map<String, VersionInfo> out = new HashMap<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT id, version, deleted_at FROM fish_locations WHERE profile = ?", profile)) {
            while (rs.next()) {
                out.put(rs.getString("id"),
                    new VersionInfo(rs.getInt("version"), rs.getTimestamp("deleted_at") != null));
            }
        }
        return out;
    }

    /** Rows newer than the versions the caller already has. */
    public List<FishRow> loadByIds(DatabaseAdapter adapter, String profile, List<String> ids) throws SQLException {
        List<FishRow> out = new ArrayList<>();
        if (ids.isEmpty()) return out;
        for (String id : ids) {
            try (ResultSet rs = adapter.executeQuery(
                    "SELECT " + COLS + " FROM fish_locations WHERE id = ? AND profile = ? AND deleted_at IS NULL",
                    id, profile)) {
                if (rs.next()) out.add(readRow(rs));
            }
        }
        return out;
    }

    public void tombstone(DatabaseAdapter adapter, String id, String profile, String byPlayer) throws SQLException {
        adapter.executeUpdate(
            "UPDATE fish_locations SET deleted_at = CURRENT_TIMESTAMP, version = version + 1, " +
            "last_touched_by = ?, last_touched_at = CURRENT_TIMESTAMP " +
            "WHERE id = ? AND profile = ? AND deleted_at IS NULL",
            byPlayer, id, profile);
    }
}
