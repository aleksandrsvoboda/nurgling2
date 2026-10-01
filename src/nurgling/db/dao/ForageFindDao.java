package nurgling.db.dao;

import nurgling.db.DatabaseAdapter;
import nurgling.db.PostgresAdapter;
import nurgling.forage.ForageFind;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data access for {@code forage_finds} (migration 15).
 *
 * <p>A find is written once and later deleted, never edited, so an upload that meets an existing row
 * leaves it alone - including a tombstone, which a retried upload must not bring back. Deletes are soft
 * so other clients hear about them.
 *
 * <p>{@code changed_at} is epoch ms on the database's clock, written on insert and on delete. The delta
 * poll compares it as a plain number, never as a bound timestamp, which SQLite would never match.
 */
public class ForageFindDao {

    /** A row, already turned into the model, plus what the sync needs to know about it. */
    public static final class Row {
        public final ForageFind find;
        public final boolean tombstoned;
        public final long changedAt;

        Row(ForageFind find, boolean tombstoned, long changedAt) {
            this.find = find;
            this.tombstoned = tombstoned;
            this.changedAt = changedAt;
        }
    }

    private static final String COLS =
        "id, grid_id, ox, oy, gob_res, item_res, item_name, quality, amount, found_at, found_by, " +
        "version, changed_at, deleted_at";

    private static String nowMs(DatabaseAdapter adapter) {
        return (adapter instanceof PostgresAdapter)
            ? "CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)"
            : "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)";
    }

    /** The database's clock, epoch ms. */
    public long now(DatabaseAdapter adapter) throws SQLException {
        try (ResultSet rs = adapter.executeQuery("SELECT " + nowMs(adapter))) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /**
     * Upload finds in two round trips, however many there are: a backlog recorded before the database was
     * switched on can be thousands, and remote villages sit ~180 ms away.
     *
     * @return id to the row's version, or -1 when the row exists as a tombstone: somebody deleted that find
     *         while an earlier copy of the upload was on its way
     */
    public Map<String, Integer> insertAll(DatabaseAdapter adapter, List<ForageFind> finds, String profile) throws SQLException {
        Map<String, Integer> out = new HashMap<>();
        if (finds.isEmpty())
            return out;
        String row = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, " + nowMs(adapter) + ", NULL)";
        StringBuilder vals = new StringBuilder();
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < finds.size(); i++) {
            ForageFind f = finds.get(i);
            vals.append(i == 0 ? row : ", " + row);
            in.append(i == 0 ? "?" : ", ?");
            Collections.addAll(args, f.id, profile, f.gridId, f.ox, f.oy, f.gobRes, f.itemRes, f.itemName,
                f.quality, f.amount, f.foundAt, f.foundBy);
        }
        String cols = "(id, profile, grid_id, ox, oy, gob_res, item_res, item_name, quality, amount, " +
                      "found_at, found_by, version, changed_at, deleted_at)";
        String sql = (adapter instanceof PostgresAdapter)
            ? "INSERT INTO forage_finds " + cols + " VALUES " + vals + " ON CONFLICT (id) DO NOTHING"
            : "INSERT OR IGNORE INTO forage_finds " + cols + " VALUES " + vals;
        adapter.executeUpdate(sql, args.toArray());

        Object[] ids = new Object[finds.size()];
        for (int i = 0; i < ids.length; i++)
            ids[i] = finds.get(i).id;
        try (ResultSet rs = adapter.executeQuery(
                "SELECT id, version, deleted_at FROM forage_finds WHERE id IN (" + in + ")", ids)) {
            while (rs.next())
                out.put(rs.getString(1), (rs.getTimestamp(3) != null) ? -1 : rs.getInt(2));
        }
        return out;
    }

    public void tombstone(DatabaseAdapter adapter, String id, String profile, String deletedBy) throws SQLException {
        adapter.executeUpdate(
            "UPDATE forage_finds SET deleted_at = CURRENT_TIMESTAMP, version = version + 1, " +
            "changed_at = " + nowMs(adapter) + ", deleted_by = ? " +
            "WHERE id = ? AND profile = ? AND deleted_at IS NULL",
            deletedBy, id, profile);
    }

    /** Every row of a world, tombstones included. */
    public List<Row> loadAll(DatabaseAdapter adapter, String profile) throws SQLException {
        return query(adapter, "SELECT " + COLS + " FROM forage_finds WHERE profile = ?", profile);
    }

    /** Rows inserted or deleted after the given database time. */
    public List<Row> loadChangedSince(DatabaseAdapter adapter, String profile, long sinceMs) throws SQLException {
        return query(adapter, "SELECT " + COLS + " FROM forage_finds WHERE profile = ? AND changed_at > ?",
            profile, sinceMs);
    }

    /** Remove tombstones older than the given number of days; the age is computed by the database. */
    public void purgeTombstones(DatabaseAdapter adapter, String profile, int days) throws SQLException {
        if (adapter instanceof PostgresAdapter) {
            adapter.executeUpdate(
                "DELETE FROM forage_finds WHERE profile = ? AND deleted_at IS NOT NULL " +
                "AND deleted_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')", profile, days);
        } else {
            adapter.executeUpdate(
                "DELETE FROM forage_finds WHERE profile = ? AND deleted_at IS NOT NULL " +
                "AND deleted_at < datetime('now', ?)", profile, "-" + days + " days");
        }
    }

    private static List<Row> query(DatabaseAdapter adapter, String sql, Object... args) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(sql, args)) {
            while (rs.next()) {
                ForageFind f = new ForageFind(
                    rs.getString("id"),
                    rs.getLong("grid_id"),
                    rs.getInt("ox"),
                    rs.getInt("oy"),
                    rs.getString("gob_res"),
                    rs.getString("item_res"),
                    rs.getString("item_name"),
                    rs.getDouble("quality"),
                    rs.getInt("amount"),
                    rs.getLong("found_at"),
                    rs.getString("found_by"),
                    rs.getInt("version"));
                out.add(new Row(f, rs.getTimestamp("deleted_at") != null, rs.getLong("changed_at")));
            }
        }
        return out;
    }
}
