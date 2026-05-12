package nurgling.db.dao;

import nurgling.db.DatabaseAdapter;
import org.json.JSONObject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for Area entities
 */
public class AreaDao {

    /**
     * Area data class for database operations
     */
    public static class AreaData {
        private final int id;
        private final String uuid;          // Phase 3
        private final String name;
        private final String path;
        private final boolean hide;
        private final int colorR;
        private final int colorG;
        private final int colorB;
        private final int colorA;
        private final String data; // JSON string containing space, in, out, spec
        private final String profile;
        private final Timestamp updatedAt;
        private final int version;
        private final String lastTouchedBy;  // Phase 5
        private final Timestamp lastTouchedAt; // Phase 5
        private final Timestamp deletedAt;   // Phase 3 tombstone

        public AreaData(int id, String uuid, String name, String path, boolean hide,
                       int colorR, int colorG, int colorB, int colorA,
                       String data, String profile, Timestamp updatedAt, int version,
                       String lastTouchedBy, Timestamp lastTouchedAt, Timestamp deletedAt) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
            this.path = path;
            this.hide = hide;
            this.colorR = colorR;
            this.colorG = colorG;
            this.colorB = colorB;
            this.colorA = colorA;
            this.data = data;
            this.profile = profile;
            this.updatedAt = updatedAt;
            this.version = version;
            this.lastTouchedBy = lastTouchedBy;
            this.lastTouchedAt = lastTouchedAt;
            this.deletedAt = deletedAt;
        }

        public int getId() { return id; }
        public String getUuid() { return uuid; }
        public String getName() { return name; }
        public String getPath() { return path; }
        public boolean isHide() { return hide; }
        public int getColorR() { return colorR; }
        public int getColorG() { return colorG; }
        public int getColorB() { return colorB; }
        public int getColorA() { return colorA; }
        public String getData() { return data; }
        public String getProfile() { return profile; }
        public Timestamp getUpdatedAt() { return updatedAt; }
        public int getVersion() { return version; }
        public String getLastTouchedBy() { return lastTouchedBy; }
        public Timestamp getLastTouchedAt() { return lastTouchedAt; }
        public Timestamp getDeletedAt() { return deletedAt; }
        public boolean isTombstone() { return deletedAt != null; }

        /**
         * Convert to JSON for NArea compatibility
         */
        public JSONObject toJson() {
            JSONObject json = new JSONObject(data);
            json.put("id", id);
            if (uuid != null) json.put("uuid", uuid);
            json.put("name", name);
            json.put("path", path);
            json.put("hide", hide);

            JSONObject color = new JSONObject();
            color.put("r", colorR);
            color.put("g", colorG);
            color.put("b", colorB);
            color.put("a", colorA);
            json.put("color", color);

            json.put("version", version);
            return json;
        }
    }

    /** Column list used for SELECTs (kept in one place so additions are easy). */
    private static final String SELECT_COLS =
        "id, uuid, name, path, hide, color_r, color_g, color_b, color_a, " +
        "data, profile, updated_at, version, last_touched_by, last_touched_at, deleted_at";

    private static AreaData readRow(ResultSet rs) throws SQLException {
        return new AreaData(
            rs.getInt("id"),
            rs.getString("uuid"),
            rs.getString("name"),
            rs.getString("path"),
            rs.getBoolean("hide"),
            rs.getInt("color_r"),
            rs.getInt("color_g"),
            rs.getInt("color_b"),
            rs.getInt("color_a"),
            rs.getString("data"),
            rs.getString("profile"),
            rs.getTimestamp("updated_at"),
            rs.getInt("version"),
            rs.getString("last_touched_by"),
            rs.getTimestamp("last_touched_at"),
            rs.getTimestamp("deleted_at")
        );
    }

    /**
     * Outcome of an OCC save attempt.
     */
    public enum SaveOutcome {
        INSERTED,        // new row created
        UPDATED,         // existing row updated; baselineVersion matched
        VERSION_CONFLICT // existing row has a newer version than we expected
    }

    public static final class SaveResult {
        public final SaveOutcome outcome;
        public final int newVersion;
        public SaveResult(SaveOutcome outcome, int newVersion) {
            this.outcome = outcome;
            this.newVersion = newVersion;
        }
    }

    /**
     * OCC-aware save. If expectedVersion == 0 the row is INSERTed; otherwise
     * UPDATE WHERE id=? AND version=expectedVersion. Returns VERSION_CONFLICT
     * if 0 rows are affected, meaning some other client wrote first.
     */
    public SaveResult saveAreaOCC(DatabaseAdapter adapter,
                                  int id, String uuid,
                                  String name, String path, boolean hide,
                                  int colorR, int colorG, int colorB, int colorA,
                                  String data, String profile, int expectedVersion,
                                  String touchedBy) throws SQLException {
        Object hideValue = (adapter instanceof nurgling.db.PostgresAdapter) ? hide : (hide ? 1 : 0);

        if (expectedVersion <= 0) {
            // New row. Use INSERT with conflict-on-id falling back to UPDATE only
            // if the row exists AND is a tombstone we're resurrecting (rare).
            if (adapter instanceof nurgling.db.PostgresAdapter) {
                String sql = "INSERT INTO areas (id, uuid, name, path, hide, color_r, color_g, color_b, color_a, " +
                             "data, profile, version, updated_at, last_touched_by, last_touched_at, deleted_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL) " +
                             "ON CONFLICT (id) DO UPDATE SET " +
                             "uuid = COALESCE(areas.uuid, EXCLUDED.uuid), " +
                             "name = EXCLUDED.name, path = EXCLUDED.path, hide = EXCLUDED.hide, " +
                             "color_r = EXCLUDED.color_r, color_g = EXCLUDED.color_g, " +
                             "color_b = EXCLUDED.color_b, color_a = EXCLUDED.color_a, " +
                             "data = EXCLUDED.data, profile = EXCLUDED.profile, " +
                             "version = areas.version + 1, updated_at = CURRENT_TIMESTAMP, " +
                             "last_touched_by = EXCLUDED.last_touched_by, last_touched_at = CURRENT_TIMESTAMP, " +
                             "deleted_at = NULL " +
                             "RETURNING version";
                try (ResultSet rs = adapter.executeQuery(sql,
                        id, uuid, name, path, hideValue,
                        colorR, colorG, colorB, colorA, data, profile, touchedBy)) {
                    if (rs.next()) {
                        return new SaveResult(SaveOutcome.INSERTED, rs.getInt("version"));
                    }
                }
                return new SaveResult(SaveOutcome.INSERTED, 1);
            } else {
                // SQLite
                String sql = "INSERT OR REPLACE INTO areas (id, uuid, name, path, hide, color_r, color_g, color_b, color_a, " +
                             "data, profile, version, updated_at, last_touched_by, last_touched_at, deleted_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                             "COALESCE((SELECT version + 1 FROM areas WHERE id = ?), 1), " +
                             "CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL)";
                adapter.executeUpdate(sql,
                    id, uuid, name, path, hideValue,
                    colorR, colorG, colorB, colorA, data, profile, id, touchedBy);
                try (ResultSet rs = adapter.executeQuery("SELECT version FROM areas WHERE id = ?", id)) {
                    if (rs.next()) {
                        return new SaveResult(SaveOutcome.INSERTED, rs.getInt("version"));
                    }
                }
                return new SaveResult(SaveOutcome.INSERTED, 1);
            }
        }

        // Existing row: OCC UPDATE
        String updateSql = "UPDATE areas SET " +
            "name = ?, path = ?, hide = ?, color_r = ?, color_g = ?, color_b = ?, color_a = ?, " +
            "data = ?, profile = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP, " +
            "last_touched_by = ?, last_touched_at = CURRENT_TIMESTAMP, deleted_at = NULL " +
            (uuid != null ? ", uuid = COALESCE(uuid, ?) " : "") +
            "WHERE id = ? AND version = ?";
        int rowsAffected;
        if (uuid != null) {
            rowsAffected = adapter.executeUpdate(updateSql,
                name, path, hideValue, colorR, colorG, colorB, colorA, data, profile,
                touchedBy, uuid, id, expectedVersion);
        } else {
            rowsAffected = adapter.executeUpdate(updateSql,
                name, path, hideValue, colorR, colorG, colorB, colorA, data, profile,
                touchedBy, id, expectedVersion);
        }
        if (rowsAffected == 0) {
            return new SaveResult(SaveOutcome.VERSION_CONFLICT, expectedVersion);
        }
        try (ResultSet rs = adapter.executeQuery("SELECT version FROM areas WHERE id = ?", id)) {
            if (rs.next()) {
                return new SaveResult(SaveOutcome.UPDATED, rs.getInt("version"));
            }
        }
        return new SaveResult(SaveOutcome.UPDATED, expectedVersion + 1);
    }

    /**
     * Get version of an area (live rows only, ignoring tombstones).
     */
    public int getAreaVersion(DatabaseAdapter adapter, int id, String profile) throws SQLException {
        String sql = "SELECT version FROM areas WHERE id = ? AND profile = ? AND deleted_at IS NULL";
        try (ResultSet rs = adapter.executeQuery(sql, id, profile)) {
            if (rs.next()) {
                return rs.getInt("version");
            }
        }
        return 0;
    }

    /**
     * Load all live areas for a specific profile.
     */
    public List<AreaData> loadAreasByProfile(DatabaseAdapter adapter, String profile) throws SQLException {
        List<AreaData> areas = new ArrayList<>();
        String sql = "SELECT " + SELECT_COLS + " FROM areas WHERE profile = ? AND deleted_at IS NULL ORDER BY id";
        try (ResultSet rs = adapter.executeQuery(sql, profile)) {
            while (rs.next()) {
                areas.add(readRow(rs));
            }
        }
        return areas;
    }

    /**
     * Load area by id and profile (live rows only).
     */
    public AreaData loadArea(DatabaseAdapter adapter, int id, String profile) throws SQLException {
        String sql = "SELECT " + SELECT_COLS + " FROM areas WHERE id = ? AND profile = ? AND deleted_at IS NULL";
        try (ResultSet rs = adapter.executeQuery(sql, id, profile)) {
            if (rs.next()) {
                return readRow(rs);
            }
        }
        return null;
    }

    /**
     * Load area by id including tombstones (used by sync to detect deletes).
     */
    public AreaData loadAreaIncludingTombstone(DatabaseAdapter adapter, int id, String profile) throws SQLException {
        String sql = "SELECT " + SELECT_COLS + " FROM areas WHERE id = ? AND profile = ?";
        try (ResultSet rs = adapter.executeQuery(sql, id, profile)) {
            if (rs.next()) {
                return readRow(rs);
            }
        }
        return null;
    }

    /**
     * Tombstone an area: set deleted_at instead of physically removing the row,
     * so other clients converge on the delete.
     */
    public void tombstoneArea(DatabaseAdapter adapter, int id, String profile, String byPlayer) throws SQLException {
        adapter.executeUpdate(
            "UPDATE areas SET deleted_at = CURRENT_TIMESTAMP, version = version + 1, " +
            "last_touched_by = ?, last_touched_at = CURRENT_TIMESTAMP " +
            "WHERE id = ? AND profile = ? AND deleted_at IS NULL",
            byPlayer, id, profile);
    }

    /**
     * Hard-delete tombstoned rows older than the cutoff.
     */
    public int purgeTombstonesOlderThan(DatabaseAdapter adapter, Timestamp cutoff) throws SQLException {
        return adapter.executeUpdate(
            "DELETE FROM areas WHERE deleted_at IS NOT NULL AND deleted_at < ?", cutoff);
    }

    /**
     * Get the maximum updated_at timestamp for a profile.
     */
    public Timestamp getLastUpdateTime(DatabaseAdapter adapter, String profile) throws SQLException {
        String sql = "SELECT MAX(updated_at) as last_update FROM areas WHERE profile = ?";
        try (ResultSet rs = adapter.executeQuery(sql, profile)) {
            if (rs.next()) {
                return rs.getTimestamp("last_update");
            }
        }
        return null;
    }

    /**
     * Get areas updated after a specific timestamp.
     */
    public List<AreaData> getAreasUpdatedAfter(DatabaseAdapter adapter, String profile, Timestamp after) throws SQLException {
        List<AreaData> areas = new ArrayList<>();
        String sql = "SELECT " + SELECT_COLS + " FROM areas WHERE profile = ? AND updated_at > ? ORDER BY id";
        try (ResultSet rs = adapter.executeQuery(sql, profile, after)) {
            while (rs.next()) {
                areas.add(readRow(rs));
            }
        }
        return areas;
    }

    /**
     * Get count of live (non-tombstoned) areas for a profile.
     */
    public int getAreasCount(DatabaseAdapter adapter, String profile) throws SQLException {
        String sql = "SELECT COUNT(*) as cnt FROM areas WHERE profile = ? AND deleted_at IS NULL";
        try (ResultSet rs = adapter.executeQuery(sql, profile)) {
            if (rs.next()) {
                return rs.getInt("cnt");
            }
        }
        return 0;
    }

    /**
     * Version state of every area for a profile (live + tombstoned). Used by
     * the sync poll to spot rows that changed state.
     */
    public java.util.Map<Integer, AreaVersionInfo> getAllAreaVersions(DatabaseAdapter adapter, String profile) throws SQLException {
        java.util.Map<Integer, AreaVersionInfo> versions = new java.util.HashMap<>();
        String sql = "SELECT id, version, deleted_at FROM areas WHERE profile = ?";
        try (ResultSet rs = adapter.executeQuery(sql, profile)) {
            while (rs.next()) {
                versions.put(rs.getInt("id"),
                    new AreaVersionInfo(rs.getInt("version"), rs.getTimestamp("deleted_at") != null));
            }
        }
        return versions;
    }

    public static final class AreaVersionInfo {
        public final int version;
        public final boolean tombstoned;
        public AreaVersionInfo(int version, boolean tombstoned) {
            this.version = version;
            this.tombstoned = tombstoned;
        }
    }
}
