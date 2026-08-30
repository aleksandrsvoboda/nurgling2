package nurgling.db.service;

import nurgling.NConfig;
import nurgling.db.DatabaseAdapterFactory;
import nurgling.db.DatabaseManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * How much disk the database is actually using, which table is using it, and the one way to get
 * some of it back.
 *
 * <p>Everything the client stores now - shared maps, storage contents, recipes, planning layers -
 * lands in one file or one PostgreSQL database that nothing in the game ever reports the size of.
 * The only way to find out was {@code du} or {@code psql}, so nobody looked until the disk filled.
 *
 * <p>Both back ends can answer how big it is, but not at the same price. PostgreSQL keeps sizes in its catalogue,
 * so the breakdown is a single indexed lookup and always comes back. SQLite has no such record: the
 * {@code dbstat} virtual table works it out by reading every page of the file, which is seconds on a
 * large map database - so there the breakdown is only produced when explicitly asked for.
 */
public class DbStorageService {
    /** One table, with its indexes and (on PostgreSQL) its TOAST folded in. */
    public static class Entry {
        public final String name;
        public final long bytes;

        public Entry(String name, long bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    /** A measurement. {@link #tables} is empty when only the total was asked for or available. */
    public static class DbSize {
        /** "PostgreSQL" or "SQLite". */
        public String backend = "";
        /** Bytes on disk, or -1 when it could not be determined. */
        public long total = -1;
        /** Largest first. */
        public List<Entry> tables = Collections.emptyList();
        /** Why there is no breakdown, when there is none. Empty otherwise. */
        public String breakdownNote = "";
        /**
         * Whether this account may actually throw the shared map away.
         *
         * <p>The village grants members SELECT/INSERT/UPDATE/DELETE and nothing more, so TRUNCATE
         * belongs to whoever owns the database - which is the right answer for an act that wipes
         * everyone's map at once, and a reason to not offer the button rather than to let it fail.
         */
        public boolean canClearMap = false;

        public boolean hasBreakdown() {
            return !tables.isEmpty();
        }

        /** What the per-table figures come to; the rest of {@link #total} is catalogue and free space. */
        public long tableSum() {
            long sum = 0;
            for (Entry e : tables)
                sum += e.bytes;
            return sum;
        }
    }

    /**
     * The shared terrain, and the only thing here worth a Clear button.
     *
     * <p>{@code map_grids} holds a tile payload per grid and is, on any village that has played for
     * a while, effectively the whole database - everything else in the schema is names, coordinates
     * and quality numbers. {@code map_grid_placements} is a few dozen bytes a row, but it says where
     * a grid sits, so it is meaningless once the grid is gone and has to leave with it.
     *
     * <p>{@code map_markers} is deliberately absent: markers are place names somebody typed, they
     * cost kilobytes, and reclaiming those is not worth throwing them away.
     */
    private static final String[] SHARED_MAP_TABLES = {"map_grids", "map_grid_placements"};

    private final DatabaseManager databaseManager;

    public DbStorageService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /** Which tables {@link #clearSharedMapAsync} empties, in the order it names them. */
    public static java.util.List<String> sharedMapTables() {
        return java.util.Arrays.asList(SHARED_MAP_TABLES);
    }

    /**
     * Throw away the shared map: every grid payload, and the layout rows that point at them.
     *
     * <p>Not a {@code DELETE} on either back end, because a delete would answer the wrong question.
     * PostgreSQL leaves the pages behind until a {@code VACUUM FULL}, and SQLite leaves the file
     * exactly as large as it was - so on the one screen whose entire purpose is a number going down,
     * a delete looks indistinguishable from nothing happening.
     *
     * @return how many grid rows went, so the player is told a fact rather than reassured
     */
    public CompletableFuture<Integer> clearSharedMapAsync() {
        return databaseManager.executeWithRetry(adapter -> {
            int grids = countRows(adapter, "map_grids");

            if (DatabaseAdapterFactory.isPostgres()) {
                /* One statement for both: TRUNCATE takes a list, and doing them separately would
                 * leave a window where the layout points at grids that are already gone. */
                adapter.executeUpdate("TRUNCATE TABLE " + String.join(", ", SHARED_MAP_TABLES));
            } else {
                for (String table : SHARED_MAP_TABLES)
                    adapter.executeUpdate("DELETE FROM " + table);
                vacuum(adapter);
            }
            return grids;
        }, "clear shared map");
    }

    private static int countRows(nurgling.db.DatabaseAdapter adapter, String table)
        throws SQLException {
        try (ResultSet rs = adapter.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Hand the freed pages back to the filesystem.
     *
     * <p>VACUUM refuses to run inside a transaction and the connection pool hands out connections
     * with autocommit off, so the delete is landed first and the flag is put back afterwards - the
     * connection returns to the pool exactly as it was borrowed.
     */
    private static void vacuum(nurgling.db.DatabaseAdapter adapter) throws SQLException {
        java.sql.Connection conn = adapter.getConnection();
        conn.commit();
        conn.setAutoCommit(true);
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("VACUUM");
        } finally {
            conn.setAutoCommit(false);
        }
    }

    /**
     * Measure the database.
     *
     * @param deepScan allow a measurement that has to read the whole file. Only SQLite has one;
     *                 PostgreSQL produces its breakdown either way, because it costs nothing.
     */
    public CompletableFuture<DbSize> measureAsync(boolean deepScan) {
        final boolean postgres = DatabaseAdapterFactory.isPostgres();
        return databaseManager.executeWithRetry(
            adapter -> postgres ? measurePostgres(adapter) : measureSqlite(adapter, deepScan),
            "measure database size");
    }

    // ---- PostgreSQL ---------------------------------------------------------------------

    private DbSize measurePostgres(nurgling.db.DatabaseAdapter adapter) throws SQLException {
        DbSize out = new DbSize();
        out.backend = "PostgreSQL";

        /* The whole database, not the sum of the tables below: it also counts the system catalogues
         * and pages freed by deletes that have not been given back to the filesystem. That gap is
         * the difference between this number and what `du` says, so it is the one to show. */
        try (ResultSet rs = adapter.executeQuery("SELECT pg_database_size(current_database())")) {
            if (rs.next())
                out.total = rs.getLong(1);
        }

        /* pg_class rather than information_schema, deliberately: information_schema hides a table
         * this role has no privileges on, and a table you cannot read still occupies your disk.
         * pg_total_relation_size counts the table, its indexes and its TOAST, which is what a
         * player means by "how much is the map costing me". */
        List<Entry> tables = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT c.relname, pg_total_relation_size(c.oid) AS bytes,"
              + "       has_table_privilege(c.oid, 'TRUNCATE') AS may_truncate"
              + "  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
              + " WHERE n.nspname = 'public' AND c.relkind = 'r'"
              + " ORDER BY bytes DESC")) {
            while (rs.next()) {
                String name = rs.getString(1);
                tables.add(new Entry(name, rs.getLong(2)));
                /* Read off the row that is already here rather than asked for separately - the
                 * answer is a column on a query this screen runs anyway. */
                if (name.equals("map_grids"))
                    out.canClearMap = rs.getBoolean(3);
            }
        }
        out.tables = tables;
        return out;
    }

    // ---- SQLite -------------------------------------------------------------------------

    private DbSize measureSqlite(nurgling.db.DatabaseAdapter adapter, boolean deepScan)
        throws SQLException {
        DbSize out = new DbSize();
        out.backend = "SQLite";
        out.total = sqliteBytesOnDisk(adapter);
        // A local file has no grants: whoever opened it may empty it.
        out.canClearMap = true;

        if (!deepScan) {
            out.breakdownNote = "notmeasured";
            return out;
        }

        /* dbstat lists every page, index pages included, keyed by the index's own name - so join
         * back through sqlite_master to charge an index to the table it belongs to. Without that
         * join the biggest "table" in a map database is one of its indexes. */
        List<Entry> tables = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT COALESCE(m.tbl_name, d.name) AS tbl, SUM(d.pgsize) AS bytes"
              + "  FROM dbstat d LEFT JOIN sqlite_master m ON m.name = d.name"
              + " GROUP BY tbl ORDER BY bytes DESC")) {
            while (rs.next())
                tables.add(new Entry(rs.getString(1), rs.getLong(2)));
        } catch (SQLException e) {
            /* The jar Nurgling ships is built with SQLITE_ENABLE_DBSTAT_VTAB on every platform, but
             * a client running against some other driver would land here rather than lose the
             * total it already has. */
            out.breakdownNote = "unsupported";
            return out;
        }
        out.tables = tables;
        return out;
    }

    /**
     * What the database costs on the filesystem, which is the question being asked - so the
     * write-ahead log counts too. It is a real file, it can be larger than the database itself, and
     * it is the usual reason a ".db of 40 MB" occupies 300.
     *
     * <p>Falls back to the page count in the file header when the path is unusable, which at least
     * cannot be wrong about the database proper.
     */
    private long sqliteBytesOnDisk(nurgling.db.DatabaseAdapter adapter) throws SQLException {
        String path = str(NConfig.get(NConfig.Key.dbFilePath));
        if (!path.isEmpty()) {
            try {
                java.nio.file.Path main = java.nio.file.Paths.get(path);
                if (java.nio.file.Files.isRegularFile(main)) {
                    long total = java.nio.file.Files.size(main);
                    total += sizeOrZero(path + "-wal");
                    total += sizeOrZero(path + "-shm");
                    return total;
                }
            } catch (java.io.IOException | java.nio.file.InvalidPathException ignore) {
                // Unreadable or nonsense path: fall through to asking the database itself.
            }
        }

        try (ResultSet rs = adapter.executeQuery(
                "SELECT (SELECT page_count FROM pragma_page_count())"
              + "     * (SELECT page_size FROM pragma_page_size())")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static long sizeOrZero(String path) {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(path);
            return java.nio.file.Files.isRegularFile(p) ? java.nio.file.Files.size(p) : 0;
        } catch (java.io.IOException | java.nio.file.InvalidPathException e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return (o == null) ? "" : o.toString();
    }

    /**
     * Whether a failure was the database refusing on privilege grounds.
     *
     * <p>Worth telling apart from every other failure: it is not a fault, it is the answer, and the
     * player needs to be pointed at the account that owns the database rather than at a log.
     */
    public static boolean isPermissionDenied(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof SQLException && "42501".equals(((SQLException) c).getSQLState()))
                return true;
        }
        return false;
    }

    /** "412.3 MB" - the unit a player reads on their own disk, not bytes. */
    public static String humanBytes(long bytes) {
        if (bytes < 0)
            return "?";
        if (bytes < 1024)
            return bytes + " B";
        double v = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024.0 && i < units.length - 1) {
            v /= 1024.0;
            i++;
        }
        return (v >= 100 ? String.format("%.0f %s", v, units[i])
                         : String.format("%.1f %s", v, units[i]));
    }
}
