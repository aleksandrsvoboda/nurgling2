package nurgling.db.dao;

import nurgling.db.DatabaseAdapter;
import nurgling.db.PostgresAdapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access for live player positions.
 *
 * <p>The set of characters here is everyone publishing to this database, which is deliberately not
 * the same as anyone's in-game Kin list: holding the database credentials is what grants membership.
 *
 * <p>One row per character per world, keyed on (profile, char_name), so a walking player rewrites
 * their own row rather than adding to a log. The table therefore holds as many rows as the village
 * has characters and never grows, which is what lets the read below get away with fetching all of
 * them and filtering by age afterwards.
 *
 * <p>Every write stamps {@code updated_at} from the database's clock, and always in UTC. Age is what
 * decides whether a marker is drawn live, faded, or not at all, and comparing two clients' wall
 * clocks would make that decision wrong for anyone whose machine has drifted.
 *
 * <p>The UTC part is not decoration. {@code updated_at} is {@code timestamp without time zone}, so
 * the value carries no zone of its own and only means something if every client agrees on which zone
 * it is in. Plain {@code CURRENT_TIMESTAMP} does not give that agreement: it is a {@code timestamptz}
 * and storing it into the column silently converts it to the writing session's {@code TimeZone},
 * which pgjdbc sets from that client's JVM. A village spread across two zones therefore wrote wall
 * clocks hours apart into one column, and a reader west of the writers computed negative ages that
 * clamped to zero - every peer permanently "just seen", nobody ever ageing out. Writing and reading
 * {@code AT TIME ZONE 'UTC'} pins both ends to the same zone whoever is connected.
 *
 * <p>For the same reason the age is computed by the database and returned as a number rather than as
 * two timestamps subtracted in Java: a {@code Timestamp} pulled out of a zoneless column is
 * reinterpreted in the reader's JVM zone, which is the bug again by another route.
 */
public class PeerPositionDao {

    /** One character's published position, as stored. */
    public static final class Row {
        public final String charName;
        public final long gid;
        public final int ox, oy;
        public final double angle;
        /** Milliseconds since this row was written, measured entirely on the database's clock. */
        public final long ageMillis;

        public Row(String charName, long gid, int ox, int oy, double angle, long ageMillis) {
            this.charName = charName;
            this.gid = gid;
            this.ox = ox;
            this.oy = oy;
            this.angle = angle;
            this.ageMillis = ageMillis;
        }
    }

    /** One character's position on its way to the database. */
    public static final class Push {
        public final String charName;
        public final long gid;
        public final int ox, oy;
        public final double angle;

        public Push(String charName, long gid, int ox, int oy, double angle) {
            this.charName = charName;
            this.gid = gid;
            this.ox = ox;
            this.oy = oy;
            this.angle = angle;
        }
    }

    /**
     * Publish positions for one profile. Every character a client is logged in as goes in a single
     * JDBC batch, so a player running five sessions still costs one round trip per tick rather than
     * five.
     */
    public void upsertBatch(DatabaseAdapter adapter, String profile, List<Push> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        String sql;
        if (adapter instanceof PostgresAdapter) {
            sql = "INSERT INTO peer_positions (profile, char_name, gid, ox, oy, angle, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP AT TIME ZONE 'UTC') "
                + "ON CONFLICT (profile, char_name) DO UPDATE SET "
                + "gid = EXCLUDED.gid, ox = EXCLUDED.ox, oy = EXCLUDED.oy, "
                + "angle = EXCLUDED.angle, updated_at = CURRENT_TIMESTAMP AT TIME ZONE 'UTC'";
        } else {
            /* SQLite's CURRENT_TIMESTAMP is already UTC and has no AT TIME ZONE, so it needs no
             * conversion - and a SQLite database is one machine's file anyway. */
            sql = "INSERT OR REPLACE INTO peer_positions "
                + "(profile, char_name, gid, ox, oy, angle, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
        }
        List<Object[]> params = new ArrayList<>(rows.size());
        for (Push p : rows) {
            params.add(new Object[]{profile, p.charName, p.gid, p.ox, p.oy, p.angle});
        }
        adapter.executeBatch(sql, params);
    }

    /**
     * Every position published for a world, each carrying its age.
     *
     * <p>The whole profile is fetched rather than filtered in SQL: there is one row per character, so
     * this is a few dozen rows of a handful of bytes, and a WHERE on {@code updated_at} would want an
     * index the table deliberately does not have (see migration 12). The age arithmetic happens in
     * the database, in UTC, so it never touches a client clock or a client time zone.
     */
    public List<Row> loadByProfile(DatabaseAdapter adapter, String profile) throws SQLException {
        List<Row> ret = new ArrayList<>();
        String age = (adapter instanceof PostgresAdapter)
            ? "(EXTRACT(EPOCH FROM ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - updated_at)) * 1000)::bigint"
            : "CAST((julianday('now') - julianday(updated_at)) * 86400000.0 AS INTEGER)";
        String sql = "SELECT char_name, gid, ox, oy, angle, " + age + " AS age_ms "
                   + "FROM peer_positions WHERE profile = ?";
        try (ResultSet rs = adapter.executeQuery(sql, profile)) {
            while (rs.next()) {
                long ageMillis = rs.getLong("age_ms");
                if (rs.wasNull()) {
                    continue;
                }
                if (ageMillis < 0) {
                    /* A row stamped fractionally ahead of now is normal - the write and this read are
                     * different statements - and must read as "brand new", not as a negative age.
                     * Hours ahead is not skew: it is a row written in some zone other than UTC, whose
                     * age cannot be known. Dropping it hides someone who may well be online, which is
                     * the safe way to be wrong; the alternative is what this whole column was doing
                     * before, showing everyone forever. */
                    if (ageMillis < -MAX_SKEW_MS) {
                        warnFutureRow(rs.getString("char_name"), ageMillis);
                        continue;
                    }
                    ageMillis = 0;
                }
                ret.add(new Row(rs.getString("char_name"), rs.getLong("gid"),
                                rs.getInt("ox"), rs.getInt("oy"), rs.getDouble("angle"), ageMillis));
            }
        }
        return ret;
    }

    /** Skew this side of which a future-dated row is just two statements racing, not a bad clock. */
    private static final long MAX_SKEW_MS = 5_000;

    private static final java.util.concurrent.atomic.AtomicBoolean warnedFuture =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Once per run: this fires every poll while it lasts, and it is a config problem, not news. */
    private static void warnFutureRow(String charName, long ageMillis) {
        if (warnedFuture.compareAndSet(false, true)) {
            System.err.println("[PeerPositionDao] Ignoring position for " + charName + " dated "
                + (-ageMillis / 1000) + "s in the future; it was written by a client that is not"
                + " stamping updated_at in UTC (an old client, or a clock that is badly off).");
        }
    }

    /** Withdraw one character's position, on logout or when sharing is switched off. */
    public void delete(DatabaseAdapter adapter, String profile, String charName) throws SQLException {
        adapter.executeUpdate("DELETE FROM peer_positions WHERE profile = ? AND char_name = ?",
                              profile, charName);
    }
}
