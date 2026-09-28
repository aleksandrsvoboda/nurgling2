package nurgling.db.dao;

import nurgling.db.DatabaseAdapter;
import nurgling.db.PostgresAdapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data access for villagers' shared quests.
 *
 * <p>One row per sharing character per world, keyed on (profile, char_name), written only by that
 * character's client. Nobody else ever writes a row, so there is no merge and no optimistic locking:
 * {@code version} exists only so readers can tell "changed" from "same" without downloading
 * {@code data}.
 *
 * <p>{@code version} starts from the clock rather than from 1. A character who withdraws and shares
 * again inside one read interval would otherwise come back at version 1, which a reader may already
 * hold for the old content, and that reader would never refetch. Readers only compare for inequality,
 * so the value wrapping around is harmless.
 *
 * <p>Ages are computed by the database in UTC, never from two client clocks; see
 * {@link PeerPositionDao} for why {@code AT TIME ZONE 'UTC'} is needed on both ends in PostgreSQL.
 */
public class QuestShareDao {

    /** What a reader polls: enough to decide whether to fetch {@code data}. */
    public static final class Head {
        public final String charName;
        public final int version;
        /** Milliseconds since the row was last written or touched, on the database's clock. */
        public final long ageMillis;

        public Head(String charName, int version, long ageMillis) {
            this.charName = charName;
            this.version = version;
            this.ageMillis = ageMillis;
        }
    }

    /** One character's quests on their way to the database. */
    public static final class Push {
        public final String charName;
        public final String data;

        public Push(String charName, String data) {
            this.charName = charName;
            this.data = data;
        }
    }

    /** One fetched row. */
    public static final class Body {
        public final int version;
        public final String data;

        public Body(int version, String data) {
            this.version = version;
            this.data = data;
        }
    }

    private static String now(DatabaseAdapter adapter) {
        /* SQLite's CURRENT_TIMESTAMP is already UTC and it has no AT TIME ZONE. */
        return (adapter instanceof PostgresAdapter) ? "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')" : "CURRENT_TIMESTAMP";
    }

    /** Write content for several characters of one profile in one batch. */
    public void upsertBatch(DatabaseAdapter adapter, String profile, List<Push> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO quest_shares (profile, char_name, data, version, updated_at) "
                   + "VALUES (?, ?, ?, ?, " + now(adapter) + ") "
                   + "ON CONFLICT (profile, char_name) DO UPDATE SET "
                   + "data = EXCLUDED.data, version = quest_shares.version + 1, "
                   + "updated_at = " + now(adapter);
        int first = (int) ((System.currentTimeMillis() / 1000L) & 0x3fffffffL);
        List<Object[]> params = new ArrayList<>(rows.size());
        for (Push p : rows) {
            params.add(new Object[]{profile, p.charName, p.data, first});
        }
        adapter.executeBatch(sql, params);
    }

    /** Heartbeat: refresh {@code updated_at} without touching {@code version}. */
    public void touch(DatabaseAdapter adapter, String profile, Collection<String> charNames) throws SQLException {
        if (charNames.isEmpty()) {
            return;
        }
        String sql = "UPDATE quest_shares SET updated_at = " + now(adapter)
                   + " WHERE profile = ? AND char_name = ?";
        List<Object[]> params = new ArrayList<>(charNames.size());
        for (String n : charNames) {
            params.add(new Object[]{profile, n});
        }
        adapter.executeBatch(sql, params);
    }

    /** Every row of a world, without its data. */
    public List<Head> loadHeads(DatabaseAdapter adapter, String profile) throws SQLException {
        List<Head> ret = new ArrayList<>();
        String age = (adapter instanceof PostgresAdapter)
            ? "(EXTRACT(EPOCH FROM ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - updated_at)) * 1000)::bigint"
            : "CAST((julianday('now') - julianday(updated_at)) * 86400000.0 AS INTEGER)";
        try (ResultSet rs = adapter.executeQuery(
                "SELECT char_name, version, " + age + " AS age_ms FROM quest_shares WHERE profile = ?",
                profile)) {
            while (rs.next()) {
                /* A row stamped slightly ahead of now is two statements racing; clamp it. */
                ret.add(new Head(rs.getString("char_name"), rs.getInt("version"),
                                 Math.max(0L, rs.getLong("age_ms"))));
            }
        }
        return ret;
    }

    /** {@code data} for the named characters of one world. */
    public Map<String, Body> loadBodies(DatabaseAdapter adapter, String profile, Collection<String> charNames)
            throws SQLException {
        Map<String, Body> ret = new HashMap<>();
        if (charNames.isEmpty()) {
            return ret;
        }
        StringBuilder sql = new StringBuilder(
            "SELECT char_name, version, data FROM quest_shares WHERE profile = ? AND char_name IN (");
        Object[] params = new Object[charNames.size() + 1];
        params[0] = profile;
        int i = 1;
        for (String n : charNames) {
            sql.append(i == 1 ? "?" : ", ?");
            params[i++] = n;
        }
        sql.append(")");
        try (ResultSet rs = adapter.executeQuery(sql.toString(), params)) {
            while (rs.next()) {
                ret.put(rs.getString("char_name"), new Body(rs.getInt("version"), rs.getString("data")));
            }
        }
        return ret;
    }

    /** Withdraw one character's quests. */
    public void delete(DatabaseAdapter adapter, String profile, String charName) throws SQLException {
        adapter.executeUpdate("DELETE FROM quest_shares WHERE profile = ? AND char_name = ?", profile, charName);
    }

    /**
     * Drop rows nobody has written or touched for {@code days}: characters whose players stopped
     * using nurgling and so never withdrew. The cutoff is computed in SQL, not bound as a parameter,
     * because a bound Timestamp never compares against SQLite's text timestamps.
     */
    public int purgeOlderThan(DatabaseAdapter adapter, String profile, int days) throws SQLException {
        String cutoff = (adapter instanceof PostgresAdapter)
            ? "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - INTERVAL '" + days + " days'"
            : "datetime('now', '-" + days + " days')";
        return adapter.executeUpdate(
            "DELETE FROM quest_shares WHERE profile = ? AND updated_at < " + cutoff, profile);
    }

    /** False for a read-only (guest) login. SQLite has no roles, so it is always writable. */
    public boolean canWrite(DatabaseAdapter adapter) throws SQLException {
        if (!(adapter instanceof PostgresAdapter)) {
            return true;
        }
        try (ResultSet rs = adapter.executeQuery("SELECT has_table_privilege('quest_shares', 'INSERT') AS w")) {
            return !rs.next() || rs.getBoolean("w");
        }
    }
}
