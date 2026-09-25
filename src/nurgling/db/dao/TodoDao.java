package nurgling.db.dao;

import nurgling.db.DatabaseAdapter;
import nurgling.db.PostgresAdapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Data access for the shared To-Do list.
 *
 * <p><b>The rows live in the {@code routes} table.</b> That table has existed since migration v5, but route
 * sync was removed from the client on 2026-01-02 and nothing reads it any more. Reusing it means the to-do
 * list needs no migration: a new one would raise {@code CLIENT_MAX_SCHEMA_VERSION}, and every villager on
 * an older client would then lose all database sync until they updated.
 *
 * <p>The old route-sync clients only ever queried {@code WHERE profile = ?} with the bare genus, so rows
 * stored under {@link #profileFor} ({@code "todo@" + genus}) are invisible to them. Column mapping:
 * <ul>
 *   <li>{@code path}: {@link #PATH_LIST} or {@link #PATH_ITEM}</li>
 *   <li>{@code id}: a random positive int, re-rolled on a primary-key clash</li>
 *   <li>{@code name}: list name or task title</li>
 *   <li>{@code data}: JSON with every other field, including the {@code deleted} tombstone flag</li>
 *   <li>{@code version}: optimistic-concurrency counter, as in {@code areas}</li>
 * </ul>
 * This class is the only place that knows about the mapping.
 */
public class TodoDao {
    public static final String PATH_LIST = "todo/list";
    public static final String PATH_ITEM = "todo/item";
    private static final String PROFILE_PREFIX = "todo@";
    /** Ids per {@code IN (...)} fetch. */
    private static final int FETCH_BATCH = 200;

    public static final class Row {
        public final int id;
        public final String path;
        public final String name;
        public final String data;
        public final int version;

        Row(int id, String path, String name, String data, int version) {
            this.id = id;
            this.path = path;
            this.name = name;
            this.data = data;
            this.version = version;
        }

        public boolean isList() {
            return PATH_LIST.equals(path);
        }
    }

    public static String profileFor(String genus) {
        return PROFILE_PREFIX + ((genus == null || genus.isEmpty()) ? "global" : genus);
    }

    private static Row readRow(ResultSet rs) throws SQLException {
        return new Row(rs.getInt("id"), rs.getString("path"), rs.getString("name"),
            rs.getString("data"), rs.getInt("version"));
    }

    public List<Row> loadAll(DatabaseAdapter adapter, String profile) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT id, path, name, data, version FROM routes WHERE profile = ? AND (path = ? OR path = ?)",
                profile, PATH_LIST, PATH_ITEM)) {
            while (rs.next())
                out.add(readRow(rs));
        }
        return out;
    }

    /** id -> version for every to-do row of the profile, tombstones included. One round trip. */
    public Map<Integer, Integer> versions(DatabaseAdapter adapter, String profile) throws SQLException {
        Map<Integer, Integer> out = new HashMap<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT id, version FROM routes WHERE profile = ? AND (path = ? OR path = ?)",
                profile, PATH_LIST, PATH_ITEM)) {
            while (rs.next())
                out.put(rs.getInt("id"), rs.getInt("version"));
        }
        return out;
    }

    public List<Row> load(DatabaseAdapter adapter, String profile, Collection<Integer> ids) throws SQLException {
        List<Row> out = new ArrayList<>();
        List<Integer> all = new ArrayList<>(ids);
        for (int from = 0; from < all.size(); from += FETCH_BATCH) {
            List<Integer> batch = all.subList(from, Math.min(all.size(), from + FETCH_BATCH));
            StringBuilder sql = new StringBuilder(
                "SELECT id, path, name, data, version FROM routes WHERE profile = ? AND id IN (");
            Object[] params = new Object[batch.size() + 1];
            params[0] = profile;
            for (int i = 0; i < batch.size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
                params[i + 1] = batch.get(i);
            }
            sql.append(")");
            try (ResultSet rs = adapter.executeQuery(sql.toString(), params)) {
                while (rs.next())
                    out.add(readRow(rs));
            }
        }
        return out;
    }

    public Row loadOne(DatabaseAdapter adapter, String profile, int id) throws SQLException {
        try (ResultSet rs = adapter.executeQuery(
                "SELECT id, path, name, data, version FROM routes WHERE profile = ? AND id = ?", profile, id)) {
            if (rs.next())
                return readRow(rs);
        }
        return null;
    }

    /** Inserts at version 1. False when the id is already taken in this profile. */
    public boolean insert(DatabaseAdapter adapter, String profile, int id, String path, String name, String data)
            throws SQLException {
        String sql = (adapter instanceof PostgresAdapter)
            ? "INSERT INTO routes (id, name, path, data, profile, version, updated_at) "
              + "VALUES (?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP) ON CONFLICT (id, profile) DO NOTHING"
            : "INSERT OR IGNORE INTO routes (id, name, path, data, profile, version, updated_at) "
              + "VALUES (?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP)";
        return adapter.executeUpdate(sql, id, name, path, data, profile) > 0;
    }

    /** Writes only if the row is still at {@code expectedVersion}. Returns the new version, or -1 on conflict. */
    public int update(DatabaseAdapter adapter, String profile, int id, String name, String data, int expectedVersion)
            throws SQLException {
        int n = adapter.executeUpdate(
            "UPDATE routes SET name = ?, data = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = ? AND profile = ? AND version = ?",
            name, data, id, profile, expectedVersion);
        return n > 0 ? expectedVersion + 1 : -1;
    }

    /** False for a read-only (guest) login. SQLite has no roles, so it is always writable. */
    public boolean canWrite(DatabaseAdapter adapter) throws SQLException {
        if (!(adapter instanceof PostgresAdapter))
            return true;
        try (ResultSet rs = adapter.executeQuery("SELECT has_table_privilege('routes', 'UPDATE') AS w")) {
            return !rs.next() || rs.getBoolean("w");
        }
    }

    /** Deletes tombstones last written before the cutoff. Poll treats a vanished id as deleted. */
    public int purgeTombstones(DatabaseAdapter adapter, String profile, long cutoffMillis) throws SQLException {
        /* SQLite keeps CURRENT_TIMESTAMP as UTC text and compares a bound Timestamp as a number, which
         * never matches; hand it the same text form instead. */
        Object cutoff;
        if (adapter instanceof PostgresAdapter) {
            cutoff = new Timestamp(cutoffMillis);
        } else {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            cutoff = f.format(new Date(cutoffMillis));
        }
        return adapter.executeUpdate(
            "DELETE FROM routes WHERE profile = ? AND (path = ? OR path = ?) "
            + "AND data LIKE '%\"deleted\":true%' AND updated_at < ?",
            profile, PATH_LIST, PATH_ITEM, cutoff);
    }
}
