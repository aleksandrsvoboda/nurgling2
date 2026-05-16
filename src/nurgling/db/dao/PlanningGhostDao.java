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
 * Data access for {@code planning_ghosts}. UUID primary key. Each ghost is an
 * independent atomic row — there's no field-group merge logic here; concurrent
 * adds/deletes by different users are naturally independent inserts/tombstones.
 */
public class PlanningGhostDao {

    public static final class GhostRow {
        public final String id;
        public final String layerId;
        public final String resName;
        public final String sdtB64;     // nullable
        public final long gridId;
        public final double ox;
        public final double oy;
        public final double angle;
        public final String profile;
        public final int version;
        public final Timestamp updatedAt;
        public final String lastTouchedBy;
        public final Timestamp lastTouchedAt;
        public final Timestamp deletedAt;

        public GhostRow(String id, String layerId, String resName, String sdtB64,
                        long gridId, double ox, double oy, double angle,
                        String profile, int version, Timestamp updatedAt,
                        String lastTouchedBy, Timestamp lastTouchedAt, Timestamp deletedAt) {
            this.id = id;
            this.layerId = layerId;
            this.resName = resName;
            this.sdtB64 = sdtB64;
            this.gridId = gridId;
            this.ox = ox;
            this.oy = oy;
            this.angle = angle;
            this.profile = profile;
            this.version = version;
            this.updatedAt = updatedAt;
            this.lastTouchedBy = lastTouchedBy;
            this.lastTouchedAt = lastTouchedAt;
            this.deletedAt = deletedAt;
        }
    }

    public enum SaveOutcome { INSERTED, UPDATED, VERSION_CONFLICT }
    public static final class SaveResult {
        public final SaveOutcome outcome;
        public final int newVersion;
        public SaveResult(SaveOutcome outcome, int newVersion) {
            this.outcome = outcome;
            this.newVersion = newVersion;
        }
    }

    public static final class VersionInfo {
        public final int version;
        public final boolean tombstoned;
        public final String layerId;
        public VersionInfo(int version, boolean tombstoned, String layerId) {
            this.version = version;
            this.tombstoned = tombstoned;
            this.layerId = layerId;
        }
    }

    private static final String COLS =
        "id, layer_id, res_name, sdt_b64, grid_id, ox, oy, angle, profile, version, updated_at, " +
        "last_touched_by, last_touched_at, deleted_at";

    private static GhostRow readRow(ResultSet rs) throws SQLException {
        return new GhostRow(
            rs.getString("id"),
            rs.getString("layer_id"),
            rs.getString("res_name"),
            rs.getString("sdt_b64"),
            rs.getLong("grid_id"),
            rs.getDouble("ox"),
            rs.getDouble("oy"),
            rs.getDouble("angle"),
            rs.getString("profile"),
            rs.getInt("version"),
            rs.getTimestamp("updated_at"),
            rs.getString("last_touched_by"),
            rs.getTimestamp("last_touched_at"),
            rs.getTimestamp("deleted_at")
        );
    }

    public SaveResult saveOCC(DatabaseAdapter adapter,
                              String id, String layerId, String resName, String sdtB64,
                              long gridId, double ox, double oy, double angle,
                              String profile, int expectedVersion, String touchedBy) throws SQLException {
        if (expectedVersion <= 0) {
            if (adapter instanceof PostgresAdapter) {
                String sql = "INSERT INTO planning_ghosts " +
                             "(id, layer_id, res_name, sdt_b64, grid_id, ox, oy, angle, profile, version, " +
                             "updated_at, last_touched_by, last_touched_at, deleted_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL) " +
                             "ON CONFLICT (id) DO UPDATE SET " +
                             "layer_id = EXCLUDED.layer_id, res_name = EXCLUDED.res_name, sdt_b64 = EXCLUDED.sdt_b64, " +
                             "grid_id = EXCLUDED.grid_id, ox = EXCLUDED.ox, oy = EXCLUDED.oy, angle = EXCLUDED.angle, " +
                             "profile = EXCLUDED.profile, version = planning_ghosts.version + 1, " +
                             "updated_at = CURRENT_TIMESTAMP, last_touched_by = EXCLUDED.last_touched_by, " +
                             "last_touched_at = CURRENT_TIMESTAMP, deleted_at = NULL " +
                             "RETURNING version";
                try (ResultSet rs = adapter.executeQuery(sql,
                        id, layerId, resName, sdtB64, gridId, ox, oy, angle, profile, touchedBy)) {
                    if (rs.next()) return new SaveResult(SaveOutcome.INSERTED, rs.getInt("version"));
                }
                return new SaveResult(SaveOutcome.INSERTED, 1);
            } else {
                String sql = "INSERT OR REPLACE INTO planning_ghosts " +
                             "(id, layer_id, res_name, sdt_b64, grid_id, ox, oy, angle, profile, version, " +
                             "updated_at, last_touched_by, last_touched_at, deleted_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                             "COALESCE((SELECT version + 1 FROM planning_ghosts WHERE id = ?), 1), " +
                             "CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL)";
                adapter.executeUpdate(sql, id, layerId, resName, sdtB64, gridId, ox, oy, angle, profile, id, touchedBy);
                try (ResultSet rs = adapter.executeQuery("SELECT version FROM planning_ghosts WHERE id = ?", id)) {
                    if (rs.next()) return new SaveResult(SaveOutcome.INSERTED, rs.getInt("version"));
                }
                return new SaveResult(SaveOutcome.INSERTED, 1);
            }
        }

        int rowsAffected = adapter.executeUpdate(
            "UPDATE planning_ghosts SET layer_id = ?, res_name = ?, sdt_b64 = ?, grid_id = ?, ox = ?, oy = ?, angle = ?, " +
            "profile = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP, " +
            "last_touched_by = ?, last_touched_at = CURRENT_TIMESTAMP, deleted_at = NULL " +
            "WHERE id = ? AND version = ?",
            layerId, resName, sdtB64, gridId, ox, oy, angle, profile, touchedBy, id, expectedVersion);
        if (rowsAffected == 0) {
            return new SaveResult(SaveOutcome.VERSION_CONFLICT, expectedVersion);
        }
        try (ResultSet rs = adapter.executeQuery("SELECT version FROM planning_ghosts WHERE id = ?", id)) {
            if (rs.next()) return new SaveResult(SaveOutcome.UPDATED, rs.getInt("version"));
        }
        return new SaveResult(SaveOutcome.UPDATED, expectedVersion + 1);
    }

    public List<GhostRow> loadAll(DatabaseAdapter adapter, String profile) throws SQLException {
        List<GhostRow> out = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT " + COLS + " FROM planning_ghosts WHERE profile = ? AND deleted_at IS NULL",
                profile)) {
            while (rs.next()) out.add(readRow(rs));
        }
        return out;
    }

    public GhostRow loadOne(DatabaseAdapter adapter, String id, String profile) throws SQLException {
        try (ResultSet rs = adapter.executeQuery(
                "SELECT " + COLS + " FROM planning_ghosts WHERE id = ? AND profile = ? AND deleted_at IS NULL",
                id, profile)) {
            if (rs.next()) return readRow(rs);
        }
        return null;
    }

    public Map<String, VersionInfo> getAllVersions(DatabaseAdapter adapter, String profile) throws SQLException {
        Map<String, VersionInfo> out = new HashMap<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT id, version, deleted_at, layer_id FROM planning_ghosts WHERE profile = ?", profile)) {
            while (rs.next()) {
                out.put(rs.getString("id"),
                    new VersionInfo(
                        rs.getInt("version"),
                        rs.getTimestamp("deleted_at") != null,
                        rs.getString("layer_id")));
            }
        }
        return out;
    }

    public void tombstone(DatabaseAdapter adapter, String id, String profile, String byPlayer) throws SQLException {
        adapter.executeUpdate(
            "UPDATE planning_ghosts SET deleted_at = CURRENT_TIMESTAMP, version = version + 1, " +
            "last_touched_by = ?, last_touched_at = CURRENT_TIMESTAMP " +
            "WHERE id = ? AND profile = ? AND deleted_at IS NULL",
            byPlayer, id, profile);
    }
}
