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
 * Data access for {@code planning_folders}. UUID primary key.
 *
 * NOTE: visibility is intentionally not stored in DB — it's a per-user local
 * preference. The folder row carries only the shared structural fields.
 */
public class PlanningFolderDao {

    public static final class FolderRow {
        public final String id;
        public final String name;
        public final int orderIndex;
        public final String profile;
        public final int version;
        public final Timestamp updatedAt;
        public final String lastTouchedBy;
        public final Timestamp lastTouchedAt;
        public final Timestamp deletedAt;

        public FolderRow(String id, String name, int orderIndex,
                         String profile, int version, Timestamp updatedAt,
                         String lastTouchedBy, Timestamp lastTouchedAt, Timestamp deletedAt) {
            this.id = id;
            this.name = name;
            this.orderIndex = orderIndex;
            this.profile = profile;
            this.version = version;
            this.updatedAt = updatedAt;
            this.lastTouchedBy = lastTouchedBy;
            this.lastTouchedAt = lastTouchedAt;
            this.deletedAt = deletedAt;
        }

        public boolean isTombstone() { return deletedAt != null; }
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
        public VersionInfo(int version, boolean tombstoned) {
            this.version = version;
            this.tombstoned = tombstoned;
        }
    }

    private static final String COLS =
        "id, name, order_index, profile, version, updated_at, " +
        "last_touched_by, last_touched_at, deleted_at";

    private static FolderRow readRow(ResultSet rs) throws SQLException {
        return new FolderRow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getInt("order_index"),
            rs.getString("profile"),
            rs.getInt("version"),
            rs.getTimestamp("updated_at"),
            rs.getString("last_touched_by"),
            rs.getTimestamp("last_touched_at"),
            rs.getTimestamp("deleted_at")
        );
    }

    public SaveResult saveOCC(DatabaseAdapter adapter,
                              String id, String name, int orderIndex,
                              String profile, int expectedVersion, String touchedBy) throws SQLException {
        if (expectedVersion <= 0) {
            if (adapter instanceof PostgresAdapter) {
                String sql = "INSERT INTO planning_folders " +
                             "(id, name, order_index, profile, version, updated_at, last_touched_by, last_touched_at, deleted_at) " +
                             "VALUES (?, ?, ?, ?, 1, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL) " +
                             "ON CONFLICT (id) DO UPDATE SET " +
                             "name = EXCLUDED.name, order_index = EXCLUDED.order_index, " +
                             "profile = EXCLUDED.profile, version = planning_folders.version + 1, " +
                             "updated_at = CURRENT_TIMESTAMP, last_touched_by = EXCLUDED.last_touched_by, " +
                             "last_touched_at = CURRENT_TIMESTAMP, deleted_at = NULL " +
                             "RETURNING version";
                try (ResultSet rs = adapter.executeQuery(sql, id, name, orderIndex, profile, touchedBy)) {
                    if (rs.next()) return new SaveResult(SaveOutcome.INSERTED, rs.getInt("version"));
                }
                return new SaveResult(SaveOutcome.INSERTED, 1);
            } else {
                String sql = "INSERT OR REPLACE INTO planning_folders " +
                             "(id, name, order_index, profile, version, updated_at, last_touched_by, last_touched_at, deleted_at) " +
                             "VALUES (?, ?, ?, ?, " +
                             "COALESCE((SELECT version + 1 FROM planning_folders WHERE id = ?), 1), " +
                             "CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, NULL)";
                adapter.executeUpdate(sql, id, name, orderIndex, profile, id, touchedBy);
                try (ResultSet rs = adapter.executeQuery("SELECT version FROM planning_folders WHERE id = ?", id)) {
                    if (rs.next()) return new SaveResult(SaveOutcome.INSERTED, rs.getInt("version"));
                }
                return new SaveResult(SaveOutcome.INSERTED, 1);
            }
        }

        int rowsAffected = adapter.executeUpdate(
            "UPDATE planning_folders SET name = ?, order_index = ?, profile = ?, " +
            "version = version + 1, updated_at = CURRENT_TIMESTAMP, " +
            "last_touched_by = ?, last_touched_at = CURRENT_TIMESTAMP, deleted_at = NULL " +
            "WHERE id = ? AND version = ?",
            name, orderIndex, profile, touchedBy, id, expectedVersion);
        if (rowsAffected == 0) {
            return new SaveResult(SaveOutcome.VERSION_CONFLICT, expectedVersion);
        }
        try (ResultSet rs = adapter.executeQuery("SELECT version FROM planning_folders WHERE id = ?", id)) {
            if (rs.next()) return new SaveResult(SaveOutcome.UPDATED, rs.getInt("version"));
        }
        return new SaveResult(SaveOutcome.UPDATED, expectedVersion + 1);
    }

    public List<FolderRow> loadAll(DatabaseAdapter adapter, String profile) throws SQLException {
        List<FolderRow> out = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT " + COLS + " FROM planning_folders WHERE profile = ? AND deleted_at IS NULL ORDER BY order_index, id",
                profile)) {
            while (rs.next()) out.add(readRow(rs));
        }
        return out;
    }

    public FolderRow loadOne(DatabaseAdapter adapter, String id, String profile) throws SQLException {
        try (ResultSet rs = adapter.executeQuery(
                "SELECT " + COLS + " FROM planning_folders WHERE id = ? AND profile = ? AND deleted_at IS NULL",
                id, profile)) {
            if (rs.next()) return readRow(rs);
        }
        return null;
    }

    public FolderRow loadIncludingTombstone(DatabaseAdapter adapter, String id, String profile) throws SQLException {
        try (ResultSet rs = adapter.executeQuery(
                "SELECT " + COLS + " FROM planning_folders WHERE id = ? AND profile = ?",
                id, profile)) {
            if (rs.next()) return readRow(rs);
        }
        return null;
    }

    public Map<String, VersionInfo> getAllVersions(DatabaseAdapter adapter, String profile) throws SQLException {
        Map<String, VersionInfo> out = new HashMap<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT id, version, deleted_at FROM planning_folders WHERE profile = ?", profile)) {
            while (rs.next()) {
                out.put(rs.getString("id"),
                    new VersionInfo(rs.getInt("version"), rs.getTimestamp("deleted_at") != null));
            }
        }
        return out;
    }

    public void tombstone(DatabaseAdapter adapter, String id, String profile, String byPlayer) throws SQLException {
        adapter.executeUpdate(
            "UPDATE planning_folders SET deleted_at = CURRENT_TIMESTAMP, version = version + 1, " +
            "last_touched_by = ?, last_touched_at = CURRENT_TIMESTAMP " +
            "WHERE id = ? AND profile = ? AND deleted_at IS NULL",
            byPlayer, id, profile);
    }
}
