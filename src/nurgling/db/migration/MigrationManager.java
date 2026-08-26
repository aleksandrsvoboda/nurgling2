package nurgling.db.migration;

import nurgling.db.DatabaseAdapter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Database migration manager that handles schema updates
 */
public class MigrationManager {
    /**
     * The highest schema version this client knows about. If the database
     * has a higher version it means a newer client has already migrated it
     * and this older client may not understand the new columns/tables; we
     * refuse to sync in that case rather than write incompatible rows.
     */
    public static final int CLIENT_MAX_SCHEMA_VERSION = 12;

    /** Version of the migration that creates kin_secrets; optional, see {@link Migration#optional}. */
    public static final int MIGRATION_KIN_SECRETS = 9;

    /** Version of the migration that creates fish_locations; optional, see {@link Migration#optional}. */
    public static final int MIGRATION_FISH_LOCATIONS = 10;

    /** Version of the migration that creates the shared map tables; optional, see {@link Migration#optional}. */
    public static final int MIGRATION_MAP_DATA = 11;

    /** Version of the migration that creates kin_positions; optional, see {@link Migration#optional}. */
    public static final int MIGRATION_KIN_POSITIONS = 12;

    public static class SchemaTooNewException extends SQLException {
        public final int clientVersion;
        public final int dbVersion;
        public SchemaTooNewException(int clientVersion, int dbVersion) {
            super("Database schema version " + dbVersion + " is newer than this client supports ("
                + clientVersion + "). Update your client to sync with this database.");
            this.clientVersion = clientVersion;
            this.dbVersion = dbVersion;
        }
    }

    private final Connection connection;
    private final DatabaseAdapter adapter;

    public MigrationManager(Connection connection, DatabaseAdapter adapter) {
        this.connection = connection;
        this.adapter = adapter;
    }

    /**
     * Apply every migration the database is behind on.
     *
     * @return the optional migrations that could not be applied, as version -> reason. An empty
     *         map means the schema is fully up to date. Migrations that are not
     *         {@link Migration#optional} still throw, because the core schema has to be right
     *         before anything writes to it.
     */
    public Map<Integer, String> runMigrations() throws SQLException {
        boolean versionTableExists = checkVersionTableExists();
        int currentVersion = 0;

        if (versionTableExists) {
            currentVersion = getCurrentVersion();
        }

        if (currentVersion > CLIENT_MAX_SCHEMA_VERSION) {
            throw new SchemaTooNewException(CLIENT_MAX_SCHEMA_VERSION, currentVersion);
        }

        Map<Integer, String> skipped = new LinkedHashMap<>();
        List<Migration> migrations = getMigrations();
        System.out.println("Current schema version: " + currentVersion + ", available migrations: " + migrations.size());
        for (Migration migration : migrations) {
            if (migration.version > currentVersion) {
                System.out.println("Running migration version " + migration.version + ": " + migration.description);
                try {
                    migration.run(adapter);

                    // Create version table if it doesn't exist yet (after first migration)
                    if (!versionTableExists) {
                        ensureVersionTableExists();
                        versionTableExists = true;
                    }

                    updateVersion(migration.version);
                    connection.commit();
                    System.out.println("Migration " + migration.version + " completed successfully");
                } catch (SQLException e) {
                    connection.rollback();
                    System.err.println("Migration " + migration.version + " failed: " + e.getMessage());
                    if (!migration.optional) {
                        throw e;
                    }
                    /* An optional migration only backs one feature, so the rest of the client keeps
                     * working without it. Its version is deliberately NOT recorded, so it is retried
                     * on the next start once whatever blocked it (usually a missing DDL grant) is
                     * fixed. That is also why nothing after it may run: recording a later version
                     * would bury this one for good. */
                    skipped.put(migration.version, e.getMessage());
                    System.err.println("Migration " + migration.version + " is optional; continuing without it"
                        + ((migrations.indexOf(migration) < migrations.size() - 1)
                           ? " (later migrations deferred until it succeeds)" : ""));
                    break;
                }
            }
        }
        return skipped;
    }

    private boolean checkVersionTableExists() {
        try {
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT version FROM schema_version LIMIT 1");
            rs.close();
            stmt.close();
            return true;
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                // Ignore rollback errors
            }
            return false;
        }
    }

    private void ensureVersionTableExists() throws SQLException {
        String createTableQuery = "CREATE TABLE schema_version (" +
                                 "version INTEGER PRIMARY KEY, " +
                                 "applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                                 ")";
        Statement stmt = connection.createStatement();
        stmt.executeUpdate(createTableQuery);
        stmt.close();
        grantDml(adapter, "schema_version");
        System.out.println("Created schema_version table");
    }

    private int getCurrentVersion() throws SQLException {
        String query = "SELECT MAX(version) as max_version FROM schema_version";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            if (rs.next()) {
                int version = rs.getInt("max_version");
                return rs.wasNull() ? 0 : version;
            }
        }
        return 0;
    }

    private void updateVersion(int version) throws SQLException {
        String query = adapter instanceof nurgling.db.PostgresAdapter
            ? "INSERT INTO schema_version (version) VALUES (" + version + ")"
            : "INSERT INTO schema_version (version) VALUES (" + version + ")";

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(query);
        }
    }

    private List<Migration> getMigrations() {
        List<Migration> migrations = new ArrayList<>();

        migrations.add(new Migration(1, "Initial migration: create favorite_recipes table and add UNIQUE constraints") {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                // Create favorite_recipes table if it doesn't exist
                if (!adapter.tableExists("favorite_recipes")) {
                    String createFavoriteRecipes = "CREATE TABLE favorite_recipes (" +
                                                  "recipe_hash VARCHAR(64) PRIMARY KEY REFERENCES recipes (recipe_hash) ON DELETE CASCADE" +
                                                  ")";
                    createTable(adapter, "favorite_recipes", createFavoriteRecipes);
                    System.out.println("Created favorite_recipes table");
                }

                // Add UNIQUE constraints for ingredients and feps
                if (adapter instanceof nurgling.db.PostgresAdapter) {
                    // For PostgreSQL, add unique constraints
                    try {
                        adapter.executeUpdate("ALTER TABLE ingredients ADD CONSTRAINT ingredients_unique UNIQUE (recipe_hash, name)");
                        System.out.println("Added UNIQUE constraint to ingredients table");
                    } catch (SQLException e) {
                        if (e.getSQLState().equals("42P07") || e.getMessage().contains("already exists")) {
                            System.out.println("UNIQUE constraint on ingredients already exists");
                        } else {
                            throw e;
                        }
                    }

                    try {
                        adapter.executeUpdate("ALTER TABLE feps ADD CONSTRAINT feps_unique UNIQUE (recipe_hash, name)");
                        System.out.println("Added UNIQUE constraint to feps table");
                    } catch (SQLException e) {
                        if (e.getSQLState().equals("42P07") || e.getMessage().contains("already exists")) {
                            System.out.println("UNIQUE constraint on feps already exists");
                        } else {
                            throw e;
                        }
                    }
                } else {
                    // For SQLite, check if constraints already exist
                    ensureSqliteUniqueConstraints(adapter);
                }
            }
        });

        migrations.add(new Migration(2, "Add resource_name column to ingredients table for layered sprites") {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                // Check if column already exists using proper metadata query
                boolean columnExists = false;
                if (adapter instanceof nurgling.db.PostgresAdapter) {
                    // PostgreSQL: use information_schema with explicit schema
                    try (ResultSet rs = adapter.executeQuery(
                            "SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'ingredients' AND column_name = 'resource_name'")) {
                        columnExists = rs.next();
                    }
                } else {
                    // SQLite: use pragma
                    try (ResultSet rs = adapter.executeQuery("PRAGMA table_info(ingredients)")) {
                        while (rs.next()) {
                            if ("resource_name".equals(rs.getString("name"))) {
                                columnExists = true;
                                break;
                            }
                        }
                    }
                }
                
                if (columnExists) {
                    System.out.println("resource_name column already exists in ingredients table");
                } else {
                    adapter.executeUpdate("ALTER TABLE ingredients ADD COLUMN resource_name VARCHAR(512)");
                    System.out.println("Added resource_name column to ingredients table");
                }
            }
        });

        migrations.add(new Migration(3, "Create areas table for shared area storage") {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                // Create areas table if it doesn't exist
                if (!adapter.tableExists("areas")) {
                    String createAreasSql = "CREATE TABLE areas (" +
                            "id INTEGER PRIMARY KEY, " +
                            "name VARCHAR(255) NOT NULL, " +
                            "path VARCHAR(512) DEFAULT '', " +
                            "hide " + (adapter instanceof nurgling.db.PostgresAdapter ? "BOOLEAN" : "INTEGER") + " DEFAULT " + 
                                (adapter instanceof nurgling.db.PostgresAdapter ? "FALSE" : "0") + ", " +
                            "color_r INTEGER DEFAULT 194, " +
                            "color_g INTEGER DEFAULT 194, " +
                            "color_b INTEGER DEFAULT 65, " +
                            "color_a INTEGER DEFAULT 56, " +
                            "data TEXT NOT NULL, " +  // JSON data for space, in, out, spec
                            "profile VARCHAR(255) DEFAULT 'global', " +  // profile/genus for filtering
                            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")";
                    createTable(adapter, "areas", createAreasSql);
                    System.out.println("Created areas table");

                    // Create index for faster profile-based queries
                    String createIndexSql = "CREATE INDEX idx_areas_profile ON areas (profile)";
                    adapter.executeUpdate(createIndexSql);
                    System.out.println("Created index on areas.profile");
                }
            }
        });

        migrations.add(new Migration(4, "Add version column to areas table") {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                // Check if column already exists using proper metadata query
                boolean columnExists = false;
                if (adapter instanceof nurgling.db.PostgresAdapter) {
                    // PostgreSQL: use information_schema with explicit schema
                    try (ResultSet rs = adapter.executeQuery(
                            "SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'areas' AND column_name = 'version'")) {
                        columnExists = rs.next();
                    }
                } else {
                    // SQLite: use pragma
                    try (ResultSet rs = adapter.executeQuery("PRAGMA table_info(areas)")) {
                        while (rs.next()) {
                            if ("version".equals(rs.getString("name"))) {
                                columnExists = true;
                                break;
                            }
                        }
                    }
                }
                
                if (columnExists) {
                    System.out.println("version column already exists in areas table");
                } else {
                    adapter.executeUpdate("ALTER TABLE areas ADD COLUMN version INTEGER DEFAULT 1");
                    System.out.println("Added version column to areas table");
                }
            }
        });

        migrations.add(new Migration(5, "Create routes table for shared route storage") {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                if (!adapter.tableExists("routes")) {
                    String createRoutesSql = "CREATE TABLE routes (" +
                            "id INTEGER NOT NULL, " +
                            "name VARCHAR(255) NOT NULL, " +
                            "path VARCHAR(512) DEFAULT '', " +
                            "data TEXT NOT NULL, " +  // JSON data for waypoints, spec
                            "profile VARCHAR(255) NOT NULL, " +
                            "version INTEGER DEFAULT 1, " +
                            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "PRIMARY KEY (id, profile)" +
                            ")";
                    createTable(adapter, "routes", createRoutesSql);
                    System.out.println("Created routes table");

                    String createIndexSql = "CREATE INDEX idx_routes_profile ON routes (profile)";
                    adapter.executeUpdate(createIndexSql);
                    System.out.println("Created index on routes.profile");
                }
            }
        });

        migrations.add(new Migration(6, "Add uuid + tombstone columns to areas table for stable identity and converged deletes") {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                addColumnIfMissing(adapter, "areas", "uuid", "VARCHAR(36)");
                addColumnIfMissing(adapter, "areas", "deleted_at", "TIMESTAMP");

                // Backfill uuid for any rows that lack one. Per-row generation
                // keeps this database-agnostic (no gen_random_uuid() on SQLite).
                java.util.List<Integer> pendingIds = new java.util.ArrayList<>();
                java.util.List<String> pendingProfiles = new java.util.ArrayList<>();
                try (ResultSet rs = adapter.executeQuery("SELECT id, profile FROM areas WHERE uuid IS NULL")) {
                    while (rs.next()) {
                        pendingIds.add(rs.getInt("id"));
                        pendingProfiles.add(rs.getString("profile"));
                    }
                }
                for (int i = 0; i < pendingIds.size(); i++) {
                    String uuid = java.util.UUID.randomUUID().toString();
                    adapter.executeUpdate("UPDATE areas SET uuid = ? WHERE id = ? AND profile = ?",
                        uuid, pendingIds.get(i), pendingProfiles.get(i));
                }
                if (!pendingIds.isEmpty()) {
                    System.out.println("Backfilled " + pendingIds.size() + " UUIDs for existing areas");
                }

                try {
                    adapter.executeUpdate("CREATE UNIQUE INDEX idx_areas_uuid ON areas (uuid)");
                } catch (SQLException e) {
                    if (!isAlreadyExists(e)) throw e;
                }
                try {
                    adapter.executeUpdate("CREATE INDEX idx_areas_deleted_at ON areas (deleted_at)");
                } catch (SQLException e) {
                    if (!isAlreadyExists(e)) throw e;
                }
            }
        });

        migrations.add(new Migration(7, "Add presence columns (last_touched_by, last_touched_at) to areas table") {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                addColumnIfMissing(adapter, "areas", "last_touched_by", "VARCHAR(255)");
                addColumnIfMissing(adapter, "areas", "last_touched_at", "TIMESTAMP");
            }
        });

        migrations.add(new Migration(8, "Create planning_folders / planning_layers / planning_ghosts tables for Base planner") {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                // NOTE: visibility intentionally NOT a column — it's a local
                // per-user preference stored alongside the DB in
                // planning_view.nurgling.json.
                if (!adapter.tableExists("planning_folders")) {
                    createTable(adapter, "planning_folders",
                        "CREATE TABLE planning_folders (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "name VARCHAR(255) NOT NULL, " +
                        "order_index INTEGER NOT NULL DEFAULT 0, " +
                        "profile VARCHAR(255) NOT NULL DEFAULT 'global', " +
                        "version INTEGER NOT NULL DEFAULT 1, " +
                        "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "last_touched_by VARCHAR(255), " +
                        "last_touched_at TIMESTAMP, " +
                        "deleted_at TIMESTAMP" +
                        ")");
                    safeCreateIndex(adapter, "CREATE INDEX idx_pf_profile ON planning_folders (profile)");
                    safeCreateIndex(adapter, "CREATE INDEX idx_pf_deleted ON planning_folders (deleted_at)");
                    System.out.println("Created planning_folders table");
                }

                if (!adapter.tableExists("planning_layers")) {
                    createTable(adapter, "planning_layers",
                        "CREATE TABLE planning_layers (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "parent_folder_id VARCHAR(36), " +
                        "name VARCHAR(255) NOT NULL, " +
                        "order_index INTEGER NOT NULL DEFAULT 0, " +
                        "profile VARCHAR(255) NOT NULL DEFAULT 'global', " +
                        "version INTEGER NOT NULL DEFAULT 1, " +
                        "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "last_touched_by VARCHAR(255), " +
                        "last_touched_at TIMESTAMP, " +
                        "deleted_at TIMESTAMP" +
                        ")");
                    safeCreateIndex(adapter, "CREATE INDEX idx_pl_profile ON planning_layers (profile)");
                    safeCreateIndex(adapter, "CREATE INDEX idx_pl_parent ON planning_layers (parent_folder_id)");
                    safeCreateIndex(adapter, "CREATE INDEX idx_pl_deleted ON planning_layers (deleted_at)");
                    System.out.println("Created planning_layers table");
                }

                if (!adapter.tableExists("planning_ghosts")) {
                    createTable(adapter, "planning_ghosts",
                        "CREATE TABLE planning_ghosts (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "layer_id VARCHAR(36) NOT NULL, " +
                        "res_name VARCHAR(512) NOT NULL, " +
                        "sdt_b64 TEXT, " +
                        "grid_id BIGINT NOT NULL, " +
                        "ox DOUBLE PRECISION NOT NULL, " +
                        "oy DOUBLE PRECISION NOT NULL, " +
                        "angle DOUBLE PRECISION NOT NULL, " +
                        "profile VARCHAR(255) NOT NULL DEFAULT 'global', " +
                        "version INTEGER NOT NULL DEFAULT 1, " +
                        "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "last_touched_by VARCHAR(255), " +
                        "last_touched_at TIMESTAMP, " +
                        "deleted_at TIMESTAMP" +
                        ")");
                    safeCreateIndex(adapter, "CREATE INDEX idx_pg_profile ON planning_ghosts (profile)");
                    safeCreateIndex(adapter, "CREATE INDEX idx_pg_layer ON planning_ghosts (layer_id)");
                    safeCreateIndex(adapter, "CREATE INDEX idx_pg_deleted ON planning_ghosts (deleted_at)");
                    System.out.println("Created planning_ghosts table");
                }
            }
        });

        /* Optional: kin_secrets backs only the Kith & Kin "pull from database" button. A role
         * without CREATE on the schema (the Postgres 15 default for a non-owner) must not lose
         * area, planning and recipe sync over it. */
        migrations.add(new Migration(9, "Create kin_secrets table for shared hearth secrets", true) {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                if (!adapter.tableExists("kin_secrets")) {
                    createTable(adapter, "kin_secrets",
                        "CREATE TABLE kin_secrets (" +
                        "profile VARCHAR(255) NOT NULL, " +
                        "char_name VARCHAR(255) NOT NULL, " +
                        "secret VARCHAR(255) NOT NULL, " +
                        "updated_at TIMESTAMP, " +
                        "PRIMARY KEY (profile, char_name)" +
                        ")");
                    safeCreateIndex(adapter, "CREATE INDEX idx_ks_profile ON kin_secrets (profile)");
                    System.out.println("Created kin_secrets table");
                }
            }
        });

        /* Optional: fish_locations backs only the saved-fish-spot feature, which falls back to its JSON
         * file when the table is missing. A role without CREATE on the schema must not lose area,
         * planning and recipe sync over it. */
        migrations.add(new Migration(10, "Create fish_locations table for shared fish spots", true) {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                if (!adapter.tableExists("fish_locations")) {
                    createTable(adapter, "fish_locations",
                        "CREATE TABLE fish_locations (" +
                        "id VARCHAR(64) PRIMARY KEY, " +
                        "grid_id BIGINT NOT NULL, " +
                        "ox INTEGER NOT NULL, " +
                        "oy INTEGER NOT NULL, " +
                        "fish_name VARCHAR(255) NOT NULL, " +
                        "fish_res VARCHAR(512), " +
                        "percentage VARCHAR(32), " +
                        "game_time VARCHAR(32), " +
                        "moon_phase VARCHAR(64), " +
                        "rod VARCHAR(255), " +
                        "hook VARCHAR(255), " +
                        "line VARCHAR(255), " +
                        "bait VARCHAR(255), " +
                        "caught_at BIGINT, " +
                        "profile VARCHAR(255) NOT NULL DEFAULT 'global', " +
                        "version INTEGER NOT NULL DEFAULT 1, " +
                        "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "last_touched_by VARCHAR(255), " +
                        "last_touched_at TIMESTAMP, " +
                        "deleted_at TIMESTAMP" +
                        ")");
                    safeCreateIndex(adapter, "CREATE INDEX idx_fl_profile ON fish_locations (profile)");
                    safeCreateIndex(adapter, "CREATE INDEX idx_fl_deleted ON fish_locations (deleted_at)");
                    safeCreateIndex(adapter, "CREATE INDEX idx_fl_grid ON fish_locations (profile, grid_id)");
                    System.out.println("Created fish_locations table");
                }
            }
        });

        /* Optional: the map_* tables back only the map window's "to database" / "from database"
         * buttons, which report themselves unavailable and leave the file-based Export/Import
         * working. A role without CREATE on the schema must not lose area, planning and recipe
         * sync over them. */
        migrations.add(new Migration(11, "Create map_grids/map_grid_placements/map_markers for shared maps", true) {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                /* Postgres spells a byte array BYTEA; SQLite would give that name NUMERIC affinity
                 * and try to coerce the payload, so it gets BLOB instead. */
                String blob = (adapter instanceof nurgling.db.PostgresAdapter) ? "BYTEA" : "BLOB";

                if (!adapter.tableExists("map_grids")) {
                    /* Keyed by the server-assigned grid id, so the same physical chunk of world is
                     * one row no matter how many villagers walked it. mtime is what decides whose
                     * copy survives; see MapDataDao.upsertGrids. */
                    createTable(adapter, "map_grids",
                        "CREATE TABLE map_grids (" +
                        "profile VARCHAR(255) NOT NULL, " +
                        "gid BIGINT NOT NULL, " +
                        "mtime BIGINT NOT NULL, " +
                        "payload " + blob + " NOT NULL, " +
                        "uploader VARCHAR(255), " +
                        "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "PRIMARY KEY (profile, gid)" +
                        ")");
                    System.out.println("Created map_grids table");
                }

                if (!adapter.tableExists("map_grid_placements")) {
                    /* Where one player's map puts a grid. Segment layout is per-player, so this is
                     * per-uploader - but it is a couple of dozen bytes a row, unlike the payload. */
                    createTable(adapter, "map_grid_placements",
                        "CREATE TABLE map_grid_placements (" +
                        "profile VARCHAR(255) NOT NULL, " +
                        "uploader VARCHAR(255) NOT NULL, " +
                        "gid BIGINT NOT NULL, " +
                        "segid BIGINT NOT NULL, " +
                        "sc_x INTEGER NOT NULL, " +
                        "sc_y INTEGER NOT NULL, " +
                        /* segid is part of the key because a grid legitimately appears in more
                         * than one of a player's segments - MapFile's own export emits one chunk
                         * per (segment, grid) pair, and dropping the extras would lose the very
                         * links the importer uses to merge two segments together. */
                        "PRIMARY KEY (profile, uploader, gid, segid)" +
                        ")");
                    safeCreateIndex(adapter,
                        "CREATE INDEX idx_mgp_seg ON map_grid_placements (profile, uploader, segid)");
                    System.out.println("Created map_grid_placements table");
                }

                if (!adapter.tableExists("map_markers")) {
                    createTable(adapter, "map_markers",
                        "CREATE TABLE map_markers (" +
                        "profile VARCHAR(255) NOT NULL, " +
                        "uploader VARCHAR(255) NOT NULL, " +
                        "mkey VARCHAR(128) NOT NULL, " +
                        "segid BIGINT NOT NULL, " +
                        "payload " + blob + " NOT NULL, " +
                        "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "PRIMARY KEY (profile, uploader, mkey)" +
                        ")");
                    safeCreateIndex(adapter, "CREATE INDEX idx_mm_profile ON map_markers (profile)");
                    System.out.println("Created map_markers table");
                }
            }
        });

        /* Optional: kin_positions backs only the live kin markers on the map. A role without CREATE
         * on the schema must not lose area, planning and recipe sync over it - the map simply stops
         * showing where kin are. */
        migrations.add(new Migration(12, "Create kin_positions table for live kin map positions", true) {
            @Override
            public void run(DatabaseAdapter adapter) throws SQLException {
                if (adapter.tableExists("kin_positions")) {
                    return;
                }
                boolean pg = (adapter instanceof nurgling.db.PostgresAdapter);

                /* This table is written far harder than anything else in the schema: one row per
                 * character, every row rewritten every few seconds, forever. Three storage choices
                 * follow from that, and all three are about keeping a table of a few dozen rows from
                 * behaving like a busy one.
                 *
                 * UNLOGGED: positions are disposable. If the server restarts and the table comes back
                 * empty it refills within one sync tick, so there is nothing worth paying a WAL write
                 * and an fsync per update for.
                 *
                 * fillfactor 70: Postgres updates a row by writing a new version and marking the old
                 * one dead. If the new version fits on the same page it can be a HOT update - the
                 * indexes are left alone and dead versions are reclaimed by opportunistic pruning,
                 * usually without autovacuum having to run at all. Packing pages full (the default
                 * 100) leaves no room for that and forces every update onto a fresh page.
                 *
                 * autovacuum thresholds: scaled off row count, a 50-row table would need to double
                 * before autovacuum looked at it, so a flat threshold is what actually triggers. */
                String storage = pg
                    ? " WITH (fillfactor = 70, autovacuum_vacuum_scale_factor = 0,"
                      + " autovacuum_vacuum_threshold = 200)"
                    : "";

                createTable(adapter, "kin_positions",
                    "CREATE " + (pg ? "UNLOGGED " : "") + "TABLE kin_positions (" +
                    "profile VARCHAR(255) NOT NULL, " +
                    "char_name VARCHAR(255) NOT NULL, " +
                    /* Server-assigned grid id plus the tile offset inside it - the only position two
                     * clients can both make sense of. See nurgling.tools.GridLocator. */
                    "gid BIGINT NOT NULL, " +
                    "ox INTEGER NOT NULL, " +
                    "oy INTEGER NOT NULL, " +
                    "angle REAL, " +
                    /* Always written as CURRENT_TIMESTAMP, never from a client clock: staleness is
                     * compared against the database's own clock so a player whose machine clock has
                     * drifted does not appear permanently stale, or permanently fresh, to everyone. */
                    "updated_at TIMESTAMP NOT NULL, " +
                    "PRIMARY KEY (profile, char_name)" +
                    ")" + storage);

                /* Deliberately no index on updated_at. It changes on every single write, and a HOT
                 * update is only possible when no indexed column changed - indexing it would disable
                 * the fast path this table is tuned for and bloat the index instead. Nothing needs it:
                 * a profile holds a few dozen rows, so the read filters by age in the query's output
                 * rather than seeking on it. The primary key never changes, so it stays HOT-friendly. */
                System.out.println("Created kin_positions table");
            }
        });

        return migrations;
    }

    /**
     * Create a table and immediately hand out DML on it.
     * <p>
     * PostgreSQL grants nothing on a new table to anyone but its owner, so a table created by the
     * one client whose role has DDL rights would stay unusable - in fact invisible, since
     * information_schema filters by privilege - to every other role sharing the database. Granting
     * at creation time is what lets a single privileged launch set the schema up for the whole
     * village instead of someone having to run SQL by hand after every schema change.
     */
    private static void createTable(DatabaseAdapter adapter, String table, String ddl) throws SQLException {
        adapter.executeUpdate(ddl);
        grantDml(adapter, table);
    }

    /**
     * Role that gets DML on tables this client creates. Defaults to PUBLIC, i.e. every role that
     * can connect to this database, which is what a shared village database wants. Set
     * {@code dbGrantRole} in the config to a group role instead if the database also carries roles
     * that must not get write access.
     */
    private static String grantee() {
        Object cfg = null;
        try {
            cfg = nurgling.NConfig.get(nurgling.NConfig.Key.dbGrantRole);
        } catch (Exception | LinkageError ignore) {
            /* Reading a preference must never be what breaks a migration; fall back to the default. */
        }
        String role = (cfg == null) ? "" : String.valueOf(cfg).trim();
        if (role.isEmpty() || role.equalsIgnoreCase("PUBLIC")) {
            return "PUBLIC";
        }
        /* The value is an identifier spliced into DDL, so it cannot go through a parameter. Only
         * a plain unquoted identifier is accepted; anything else is refused rather than escaped. */
        if (!role.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            System.err.println("[MigrationManager] dbGrantRole '" + role
                + "' is not a plain identifier; skipping grants");
            return null;
        }
        return role;
    }

    /**
     * Grant DML on one table. Wrapped in a savepoint because a failed statement aborts the whole
     * PostgreSQL transaction, which would take the migration's own version bump down with it - and
     * a missing grant is worth a warning, not a failed migration. No-op outside PostgreSQL, which
     * is the only back end here that has grants at all.
     */
    private static void grantDml(DatabaseAdapter adapter, String table) {
        if (!(adapter instanceof nurgling.db.PostgresAdapter)) {
            return;
        }
        String role = grantee();
        if (role == null) {
            return;
        }
        Connection conn = adapter.getConnection();
        java.sql.Savepoint sp = null;
        try {
            sp = conn.setSavepoint("nurgling_grant");
            adapter.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO " + role);
            conn.releaseSavepoint(sp);
            System.out.println("Granted DML on " + table + " to " + role);
        } catch (SQLException e) {
            if (sp != null) {
                try {
                    conn.rollback(sp);
                } catch (SQLException ignore) {
                }
            }
            System.err.println("[MigrationManager] could not grant on " + table + " to " + role
                + " (" + e.getMessage() + "); other roles may need the grant applied by hand");
        }
    }

    private static void safeCreateIndex(DatabaseAdapter adapter, String sql) throws SQLException {
        try {
            adapter.executeUpdate(sql);
        } catch (SQLException e) {
            if (!isAlreadyExists(e)) throw e;
        }
    }

    /** Helper: ALTER TABLE ADD COLUMN unless the column already exists. */
    private static void addColumnIfMissing(DatabaseAdapter adapter, String table, String column, String type)
            throws SQLException {
        boolean exists = false;
        if (adapter instanceof nurgling.db.PostgresAdapter) {
            try (ResultSet rs = adapter.executeQuery(
                    "SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                    table, column)) {
                exists = rs.next();
            }
        } else {
            try (ResultSet rs = adapter.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (rs.next()) {
                    if (column.equals(rs.getString("name"))) {
                        exists = true;
                        break;
                    }
                }
            }
        }
        if (!exists) {
            adapter.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            System.out.println("Added column " + table + "." + column);
        }
    }

    private static boolean isAlreadyExists(SQLException e) {
        if (e.getSQLState() != null && (e.getSQLState().equals("42P07") || e.getSQLState().equals("42S11"))) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("already exists");
    }

    private void ensureSqliteUniqueConstraints(DatabaseAdapter adapter) throws SQLException {
        boolean needsIngredientsMigration = checkNeedsIngredientsMigration(adapter);
        boolean needsFepsMigration = checkNeedsFepsMigration(adapter);

        if (needsIngredientsMigration) {
            recreateIngredientsTableWithConstraint(adapter);
        } else {
            System.out.println("UNIQUE constraint on ingredients already exists");
        }

        if (needsFepsMigration) {
            recreateFepsTableWithConstraint(adapter);
        } else {
            System.out.println("UNIQUE constraint on feps already exists");
        }
    }

    private boolean checkNeedsIngredientsMigration(DatabaseAdapter adapter) throws SQLException {
        try {
            adapter.executeUpdate("INSERT INTO ingredients (recipe_hash, name, percentage) VALUES ('__test__', '__test__', 0)");
            adapter.executeUpdate("INSERT INTO ingredients (recipe_hash, name, percentage) VALUES ('__test__', '__test__', 0)");
            adapter.executeUpdate("DELETE FROM ingredients WHERE recipe_hash = '__test__'");
            return true;
        } catch (SQLException e) {
            adapter.executeUpdate("DELETE FROM ingredients WHERE recipe_hash = '__test__'");
            return false;
        }
    }

    private boolean checkNeedsFepsMigration(DatabaseAdapter adapter) throws SQLException {
        try {
            adapter.executeUpdate("INSERT INTO feps (recipe_hash, name, value, weight) VALUES ('__test__', '__test__', 0, 0)");
            adapter.executeUpdate("INSERT INTO feps (recipe_hash, name, value, weight) VALUES ('__test__', '__test__', 0, 0)");
            adapter.executeUpdate("DELETE FROM feps WHERE recipe_hash = '__test__'");
            return true;
        } catch (SQLException e) {
            adapter.executeUpdate("DELETE FROM feps WHERE recipe_hash = '__test__'");
            return false;
        }
    }

    private void recreateIngredientsTableWithConstraint(DatabaseAdapter adapter) throws SQLException {
        adapter.executeUpdate("CREATE TABLE ingredients_new (" +
                             "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                             "recipe_hash VARCHAR(64) REFERENCES recipes (recipe_hash) ON DELETE CASCADE, " +
                             "name VARCHAR(255) NOT NULL, " +
                             "percentage FLOAT NOT NULL, " +
                             "resource_name VARCHAR(512), " +
                             "UNIQUE (recipe_hash, name))");

        adapter.executeUpdate("INSERT INTO ingredients_new (recipe_hash, name, percentage, resource_name) " +
                             "SELECT recipe_hash, name, MIN(percentage), resource_name FROM ingredients " +
                             "GROUP BY recipe_hash, name");

        adapter.executeUpdate("DROP TABLE ingredients");
        adapter.executeUpdate("ALTER TABLE ingredients_new RENAME TO ingredients");
        System.out.println("Added UNIQUE constraint to ingredients table");
    }

    private void recreateFepsTableWithConstraint(DatabaseAdapter adapter) throws SQLException {
        adapter.executeUpdate("CREATE TABLE feps_new (" +
                             "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                             "recipe_hash VARCHAR(64) REFERENCES recipes (recipe_hash) ON DELETE CASCADE, " +
                             "name VARCHAR(255) NOT NULL, " +
                             "value FLOAT NOT NULL, " +
                             "weight FLOAT NOT NULL, " +
                             "UNIQUE (recipe_hash, name))");

        adapter.executeUpdate("INSERT INTO feps_new (recipe_hash, name, value, weight) " +
                             "SELECT recipe_hash, name, MAX(value), MAX(weight) FROM feps " +
                             "GROUP BY recipe_hash, name");

        adapter.executeUpdate("DROP TABLE feps");
        adapter.executeUpdate("ALTER TABLE feps_new RENAME TO feps");
        System.out.println("Added UNIQUE constraint to feps table");
    }

    public abstract static class Migration {
        final int version;
        final String description;
        /**
         * True for a migration that only backs one optional feature. If it fails, the client still
         * initialises and everything else keeps syncing; the feature reports itself unavailable.
         * A migration that touches the core schema must stay required.
         */
        final boolean optional;

        Migration(int version, String description) {
            this(version, description, false);
        }

        Migration(int version, String description, boolean optional) {
            this.version = version;
            this.description = description;
            this.optional = optional;
        }

        abstract void run(DatabaseAdapter adapter) throws SQLException;
    }
}
