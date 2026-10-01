package nurgling.db.dao;

import nurgling.db.DatabaseAdapter;
import nurgling.db.PostgresAdapter;
import nurgling.timers.Timer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data access for {@code timers} (migration 14).
 *
 * <p>Shaped like {@link FishLocationDao}: grid id plus offset for position, {@code profile} per world, a
 * {@code version} that only drives the delta poll, and soft deletes. Unlike fish, a timer is edited -
 * restarted, renamed, moved - so an upsert keeps the row with the later {@code started_at}: when two
 * villagers restart the same timer, the more recent collection is the one that counts.
 *
 * <p>{@code started_at} is epoch ms on the database's clock. Callers convert with the offset from
 * {@link #clockOffset}; nothing here compares a bound timestamp, which SQLite would never match.
 */
public class TimerDao {

    /** A row, already turned into the model, plus whether it is a tombstone. */
    public static final class Row {
        public final Timer timer;
        public final boolean tombstoned;

        Row(Timer timer, boolean tombstoned) {
            this.timer = timer;
            this.tombstoned = tombstoned;
        }
    }

    public static final class VersionInfo {
        public final int version;
        public final boolean tombstoned;

        VersionInfo(int version, boolean tombstoned) {
            this.version = version;
            this.tombstoned = tombstoned;
        }
    }

    private static final String COLS =
        "id, kind, grid_id, ox, oy, res_type, name, icon, started_at, duration_ms, repeat_ms, " +
        "set_by, task_id, assignee, version, deleted_at";

    /**
     * Milliseconds to add to this client's clock to get the database's. Players' PC clocks drift by
     * minutes, which would otherwise make a villager's timer ready early or late on everyone else's
     * screen. SQLite runs on this machine, so there is nothing to correct.
     */
    public long clockOffset(DatabaseAdapter adapter) throws SQLException {
        if (!(adapter instanceof PostgresAdapter))
            return 0;
        long before = System.currentTimeMillis();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)")) {
            long after = System.currentTimeMillis();
            if (rs.next())
                return rs.getLong(1) - (before + after) / 2;
        }
        return 0;
    }

    /** Write a timer. {@code startedAt} must already be on the database clock. Returns the new version. */
    public int upsert(DatabaseAdapter adapter, Timer t, long startedAt, String profile, String touchedBy) throws SQLException {
        // Reminders have no position; the adapter binds null as an untyped SQL NULL.
        Long gridId = t.hasLocation() ? t.gridId : null;
        Integer ox = t.hasLocation() ? t.ox : null;
        Integer oy = t.hasLocation() ? t.oy : null;
        Integer taskId = (t.taskId != 0) ? t.taskId : null;
        String assignee = (t.taskId != 0) ? t.assignee : null;
        if (adapter instanceof PostgresAdapter) {
            /* A stale edit - an older restart arriving after a newer one - leaves the row alone; the
             * WHERE on DO UPDATE makes that a no-op instead of rolling the timer back. A deleted row is
             * always taken over: saving it again is a deliberate re-add. */
            adapter.executeUpdate(
                "INSERT INTO timers (id, profile, kind, grid_id, ox, oy, res_type, name, icon, " +
                "started_at, duration_ms, repeat_ms, set_by, task_id, assignee, version, updated_at, last_touched_by, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP, ?, NULL) " +
                "ON CONFLICT (id) DO UPDATE SET " +
                "kind = EXCLUDED.kind, grid_id = EXCLUDED.grid_id, ox = EXCLUDED.ox, oy = EXCLUDED.oy, " +
                "res_type = EXCLUDED.res_type, name = EXCLUDED.name, icon = EXCLUDED.icon, " +
                "started_at = EXCLUDED.started_at, duration_ms = EXCLUDED.duration_ms, " +
                "repeat_ms = EXCLUDED.repeat_ms, task_id = EXCLUDED.task_id, assignee = EXCLUDED.assignee, " +
                "version = timers.version + 1, updated_at = CURRENT_TIMESTAMP, " +
                "last_touched_by = EXCLUDED.last_touched_by, deleted_at = NULL " +
                "WHERE timers.deleted_at IS NOT NULL OR timers.started_at <= EXCLUDED.started_at",
                t.id, profile, t.kind.key(), gridId, ox, oy, t.resType, t.name, t.icon,
                startedAt, t.durationMs, t.repeatMs, t.setBy, taskId, assignee, touchedBy);
        } else {
            adapter.executeUpdate(
                "INSERT OR REPLACE INTO timers (id, profile, kind, grid_id, ox, oy, res_type, name, icon, " +
                "started_at, duration_ms, repeat_ms, set_by, task_id, assignee, version, updated_at, last_touched_by, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                "COALESCE((SELECT version + 1 FROM timers WHERE id = ?), 1), CURRENT_TIMESTAMP, ?, NULL)",
                t.id, profile, t.kind.key(), gridId, ox, oy, t.resType, t.name, t.icon,
                startedAt, t.durationMs, t.repeatMs, t.setBy, taskId, assignee, t.id, touchedBy);
        }
        try (ResultSet rs = adapter.executeQuery("SELECT version FROM timers WHERE id = ?", t.id)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public void tombstone(DatabaseAdapter adapter, String id, String profile, String touchedBy) throws SQLException {
        adapter.executeUpdate(
            "UPDATE timers SET deleted_at = CURRENT_TIMESTAMP, version = version + 1, " +
            "updated_at = CURRENT_TIMESTAMP, last_touched_by = ? " +
            "WHERE id = ? AND profile = ? AND deleted_at IS NULL",
            touchedBy, id, profile);
    }

    /** Every row of a world, tombstones included, with {@code startedAt} converted to this client's clock. */
    public List<Row> loadAll(DatabaseAdapter adapter, String profile, long offset) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT " + COLS + " FROM timers WHERE profile = ?", profile)) {
            while (rs.next())
                out.add(readRow(rs, offset));
        }
        return out;
    }

    public Map<String, VersionInfo> getAllVersions(DatabaseAdapter adapter, String profile) throws SQLException {
        Map<String, VersionInfo> out = new HashMap<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT id, version, deleted_at FROM timers WHERE profile = ?", profile)) {
            while (rs.next())
                out.put(rs.getString(1), new VersionInfo(rs.getInt(2), rs.getTimestamp(3) != null));
        }
        return out;
    }

    /** The given rows in one round trip. */
    public List<Row> loadByIds(DatabaseAdapter adapter, String profile, List<String> ids, long offset) throws SQLException {
        if (ids.isEmpty())
            return Collections.emptyList();
        StringBuilder in = new StringBuilder();
        Object[] args = new Object[ids.size() + 1];
        args[0] = profile;
        for (int i = 0; i < ids.size(); i++) {
            in.append(i == 0 ? "?" : ", ?");
            args[i + 1] = ids.get(i);
        }
        List<Row> out = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT " + COLS + " FROM timers WHERE profile = ? AND id IN (" + in + ")", args)) {
            while (rs.next())
                out.add(readRow(rs, offset));
        }
        return out;
    }

    /** Remove tombstones older than the given number of days; the age is computed by the database. */
    public void purgeTombstones(DatabaseAdapter adapter, String profile, int days) throws SQLException {
        if (adapter instanceof PostgresAdapter) {
            adapter.executeUpdate(
                "DELETE FROM timers WHERE profile = ? AND deleted_at IS NOT NULL " +
                "AND deleted_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')", profile, days);
        } else {
            adapter.executeUpdate(
                "DELETE FROM timers WHERE profile = ? AND deleted_at IS NOT NULL " +
                "AND deleted_at < datetime('now', ?)", profile, "-" + days + " days");
        }
    }

    private static Row readRow(ResultSet rs, long offset) throws SQLException {
        long gridId = rs.getLong("grid_id");
        boolean located = !rs.wasNull();
        Timer t = new Timer(
            rs.getString("id"),
            Timer.Kind.of(rs.getString("kind")),
            located ? gridId : 0,
            rs.getInt("ox"),
            rs.getInt("oy"),
            rs.getString("res_type"),
            rs.getString("name"),
            rs.getString("icon"),
            rs.getLong("started_at") - offset,
            rs.getLong("duration_ms"),
            rs.getLong("repeat_ms"),
            rs.getString("set_by"),
            true,
            rs.getInt("version"),
            0, null,
            rs.getInt("task_id"),
            rs.getString("assignee"));
        return new Row(t, rs.getTimestamp("deleted_at") != null);
    }
}
