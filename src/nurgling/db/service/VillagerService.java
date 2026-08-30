package nurgling.db.service;

import nurgling.db.DatabaseAdapter;
import nurgling.db.DatabaseManager;
import nurgling.db.migration.MigrationManager;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Per-player accounts on a village database.
 *
 * <p>Replaces the only way this was previously possible: running {@code psql} by hand. Because
 * nobody did, every village shares one login - and since that login has to own the schema, it is a
 * superuser, held by people whose only interest is farming. Handing out a revocable account per
 * person is the point of this class.
 *
 * <p>Role names and access levels are spliced into DDL, which cannot take bound parameters, so both
 * are validated against a strict whitelist rather than escaped.
 */
public class VillagerService {
    public static final String ACCESS_ADMIN = "admin";
    public static final String ACCESS_MEMBER = "member";
    public static final String ACCESS_GUEST = "guest";

    private static final SecureRandom RANDOM = new SecureRandom();
    /** No quotes, backslashes or spaces: these end up inside SQL string literals. */
    private static final String PW_ALPHABET =
        "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final DatabaseManager databaseManager;

    public VillagerService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /** One row of the Villagers panel. */
    public static class Villager {
        public String name = "";
        public String access = ACCESS_MEMBER;
        public boolean canLogin = true;
        public String addedBy = "";
        public Timestamp addedAt;
        public Timestamp lastSeen;

        /** True for somebody who has been given a code but has never used it. */
        public boolean neverConnected() {
            return lastSeen == null;
        }
    }

    /** A freshly created or reset account, with the one-time readable password. */
    public static class NewAccount {
        public final String name;
        public final String password;

        public NewAccount(String name, String password) {
            this.name = name;
            this.password = password;
        }
    }

    // ---- capability ---------------------------------------------------------------------

    /**
     * Whether this client may manage villagers at all.
     *
     * <p>{@code CREATEROLE} is a role attribute, not a privilege that arrives through group
     * membership, so this is the only thing that actually answers the question.
     */
    public CompletableFuture<Boolean> isAdminAsync() {
        return databaseManager.executeWithRetry(adapter -> {
            try (ResultSet rs = adapter.executeQuery(
                    "SELECT rolsuper OR rolcreaterole FROM pg_roles WHERE rolname = current_user")) {
                return rs.next() && rs.getBoolean(1);
            }
        }, "check villager admin rights");
    }

    // ---- reading ------------------------------------------------------------------------

    public CompletableFuture<List<Villager>> listAsync() {
        return databaseManager.executeWithRetry(this::list, "list villagers");
    }

    private List<Villager> list(DatabaseAdapter adapter) throws SQLException {
        List<Villager> out = new ArrayList<>();
        /* pg_roles is the truth about who can log in; the villagers table adds what PostgreSQL does
         * not record. Left join, so an account made by hand before this panel existed still shows up
         * instead of being invisible to the person trying to tidy up. */
        /* villagers is an optional migration, so a database whose owner has not applied it yet
         * still lists accounts - just without the columns PostgreSQL does not keep itself. */
        String sql = adapter.tableExists("villagers")
            ? "SELECT r.rolname, r.rolcanlogin, r.rolsuper OR r.rolcreaterole AS is_admin, "
            + "       v.access, v.added_by, v.added_at, v.last_seen "
            + "FROM pg_roles r "
            + "LEFT JOIN villagers v ON v.role_name = r.rolname "
            + "WHERE r.rolcanlogin AND r.rolname NOT LIKE 'pg\\_%' "
            + "ORDER BY r.rolname"
            : "SELECT r.rolname, r.rolcanlogin, r.rolsuper OR r.rolcreaterole AS is_admin, "
            + "       NULL, NULL, NULL, NULL "
            + "FROM pg_roles r "
            + "WHERE r.rolcanlogin AND r.rolname NOT LIKE 'pg\\_%' "
            + "ORDER BY r.rolname";
        try (ResultSet rs = adapter.executeQuery(sql)) {
            while (rs.next()) {
                Villager v = new Villager();
                v.name = rs.getString(1);
                v.canLogin = rs.getBoolean(2);
                boolean admin = rs.getBoolean(3);
                String access = rs.getString(4);
                v.access = admin ? ACCESS_ADMIN : (access == null ? ACCESS_MEMBER : access);
                v.addedBy = rs.getString(5) == null ? "" : rs.getString(5);
                v.addedAt = rs.getTimestamp(6);
                v.lastSeen = rs.getTimestamp(7);
                out.add(v);
            }
        }
        return out;
    }

    /**
     * Record that this client connected.
     *
     * <p>The Villagers panel's "last seen" column is the only way a host can tell who has actually
     * moved onto their own account, which is what makes retiring the old shared login safe.
     */
    public CompletableFuture<Void> touchAsync() {
        return databaseManager.executeWithRetry(adapter -> {
            touch(adapter);
            return (Void) null;
        }, "record last seen");
    }

    private static void touch(DatabaseAdapter adapter) throws SQLException {
        if (!adapter.tableExists("villagers"))
            return;
        adapter.executeUpdate(
            "INSERT INTO villagers (role_name, access, added_at, last_seen) "
          + "VALUES (current_user, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
          + "ON CONFLICT (role_name) DO UPDATE SET last_seen = CURRENT_TIMESTAMP",
            ACCESS_MEMBER);
    }

    // ---- writing ------------------------------------------------------------------------

    /** Create a login role, put it in the right group, and return its one-time password. */
    public CompletableFuture<NewAccount> addAsync(String name, String access) {
        final String role = requireIdentifier(name);
        final String level = requireAccess(access);
        final String password = generatePassword();
        return databaseManager.executeWithRetry(adapter -> {
            ensureGroups(adapter);
            adapter.executeUpdate("CREATE ROLE " + quote(role)
                + " LOGIN PASSWORD " + literal(password));
            grantGroup(adapter, role, level);
            recordVillager(adapter, role, level);
            return new NewAccount(role, password);
        }, "add villager " + role);
    }

    /** Issue a new password, which invalidates whatever code that player is holding. */
    public CompletableFuture<NewAccount> resetPasswordAsync(String name) {
        final String role = requireIdentifier(name);
        final String password = generatePassword();
        return databaseManager.executeWithRetry(adapter -> {
            adapter.executeUpdate("ALTER ROLE " + quote(role) + " PASSWORD " + literal(password));
            /* Re-enable, because revoking sets NOLOGIN - resetting the password of somebody you
             * removed is how you let them back in. */
            adapter.executeUpdate("ALTER ROLE " + quote(role) + " LOGIN");
            if (adapter.tableExists("villagers"))
                adapter.executeUpdate("UPDATE villagers SET revoked = FALSE WHERE role_name = ?", role);
            return new NewAccount(role, password);
        }, "reset password for " + role);
    }

    public CompletableFuture<Void> setAccessAsync(String name, String access) {
        final String role = requireIdentifier(name);
        final String level = requireAccess(access);
        return databaseManager.executeWithRetry(adapter -> {
            ensureGroups(adapter);
            revokeGroups(adapter, role);
            grantGroup(adapter, role, level);
            recordVillager(adapter, role, level);
            return (Void) null;
        }, "set access for " + role);
    }

    /**
     * Take away access without destroying anything.
     *
     * <p>Deliberately not {@code DROP ROLE}: that fails outright whenever the role owns any object,
     * which after a season of play it always does. Revoking group membership and clearing LOGIN
     * stops them connecting, and is reversible if it was a mistake.
     *
     * <p>It does not un-share what they already copied to disk. Hearth secrets, map tiles and areas
     * are on their machine now, and the UI says so rather than implying otherwise.
     */
    /** What actually happened to an account we were asked to delete. */
    public enum DeleteOutcome {
        DELETED,
        DISABLED
    }

    /**
     * Remove an account for good.
     *
     * <p>A role cannot simply be dropped: PostgreSQL refuses while it still owns an object or holds
     * a single grant, and after a season of play that is often the case. So the grants are cleared
     * and anything it owns is handed back first.
     *
     * <p>If the drop still will not go through, the account is shut instead of being left usable,
     * and the caller is told which of the two happened - reporting a delete that did not happen
     * would leave a working login behind.
     *
     * <p>Neither outcome un-shares what that player already synced. Hearth secrets, map tiles and
     * areas are on their machine now, and the panel says so rather than implying otherwise.
     */
    public CompletableFuture<DeleteOutcome> deleteAsync(String name) {
        final String role = requireIdentifier(name);
        return databaseManager.executeWithRetry(adapter -> {
            if (role.equalsIgnoreCase(connectedRole(adapter))) {
                throw new SQLException("You cannot delete the account you are connected as.");
            }
            revokeGroups(adapter, role);
            if (adapter.tableExists("villagers")) {
                adapter.executeUpdate("DELETE FROM villagers WHERE role_name = ?", role);
            }

            java.sql.Connection conn = adapter.getConnection();
            java.sql.Savepoint sp = conn.setSavepoint("nurgling_drop");
            try {
                adapter.executeUpdate("REASSIGN OWNED BY " + quote(role) + " TO CURRENT_USER");
                adapter.executeUpdate("DROP OWNED BY " + quote(role));
                adapter.executeUpdate("DROP ROLE " + quote(role));
                conn.releaseSavepoint(sp);
                return DeleteOutcome.DELETED;
            } catch (SQLException e) {
                conn.rollback(sp);
                System.err.println("[VillagerService] could not drop " + role
                    + " (" + e.getMessage() + "); disabling instead");
                adapter.executeUpdate("ALTER ROLE " + quote(role) + " NOLOGIN");
                return DeleteOutcome.DISABLED;
            }
        }, "delete villager " + role);
    }

    /** The role this client is authenticated as; never deletable from here. */
    private static String connectedRole(DatabaseAdapter adapter) throws SQLException {
        try (ResultSet rs = adapter.executeQuery("SELECT current_user")) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    /** Re-run the grant sweep by hand, for a host fixing up a village made before any of this. */
    public CompletableFuture<Void> repairAsync() {
        return databaseManager.executeWithRetry(adapter -> {
            ensureGroups(adapter);
            MigrationManager.repairPermissions(adapter);
            return (Void) null;
        }, "repair permissions").thenCompose(v -> ensureBookkeepingAsync());
    }

    // ---- helpers ------------------------------------------------------------------------

    /**
     * Create the bookkeeping table, if this account may.
     *
     * <p>Deliberately <em>not</em> a schema migration. A migration would bump the schema version, and
     * every villager still on an older client would then be told the database is too new and stop
     * syncing entirely - an unreasonable price for a table that only records who was added when.
     * Nothing else reads it, so it is created on demand by whoever has the rights, and everything
     * degrades quietly until then.
     */
    public CompletableFuture<Void> ensureBookkeepingAsync() {
        return databaseManager.executeWithRetry(adapter -> {
            if (!adapter.tableExists("villagers")) {
                java.sql.Connection conn = adapter.getConnection();
                java.sql.Savepoint sp = null;
                try {
                    sp = conn.setSavepoint("nurgling_villagers");
                    adapter.executeUpdate(
                        "CREATE TABLE villagers (" +
                        "role_name VARCHAR(64) PRIMARY KEY, " +
                        "access VARCHAR(16) NOT NULL, " +
                        "added_by VARCHAR(64), " +
                        "added_at TIMESTAMP, " +
                        "last_seen TIMESTAMP, " +
                        "revoked BOOLEAN DEFAULT FALSE)");
                    adapter.executeUpdate("GRANT SELECT, INSERT, UPDATE ON villagers TO "
                        + MigrationManager.ROLE_MEMBER_OR_PUBLIC);
                    conn.releaseSavepoint(sp);
                    System.out.println("Created villagers table");
                } catch (SQLException e) {
                    if (sp != null) {
                        try {
                            conn.rollback(sp);
                        } catch (SQLException ignore) {
                        }
                    }
                    /* An ordinary villager cannot create tables, and does not need to. */
                    return (Void) null;
                }
            }
            touch(adapter);
            return (Void) null;
        }, "villager bookkeeping");
    }

    /** Bookkeeping PostgreSQL does not do for us; skipped when the table is not there. */
    private static void recordVillager(DatabaseAdapter adapter, String role, String access)
            throws SQLException {
        if (!adapter.tableExists("villagers"))
            return;
        adapter.executeUpdate(
            "INSERT INTO villagers (role_name, access, added_by, added_at) "
          + "VALUES (?, ?, current_user, CURRENT_TIMESTAMP) "
          + "ON CONFLICT (role_name) DO UPDATE SET access = EXCLUDED.access, revoked = FALSE",
            role, access);
    }

    private static void ensureGroups(DatabaseAdapter adapter) throws SQLException {
        if (!MigrationManager.roleExists(adapter, MigrationManager.ROLE_MEMBER))
            adapter.executeUpdate("CREATE ROLE " + MigrationManager.ROLE_MEMBER + " NOLOGIN");
        if (!MigrationManager.roleExists(adapter, MigrationManager.ROLE_GUEST))
            adapter.executeUpdate("CREATE ROLE " + MigrationManager.ROLE_GUEST + " NOLOGIN");
    }

    private static void grantGroup(DatabaseAdapter adapter, String role, String access)
            throws SQLException {
        if (ACCESS_GUEST.equals(access)) {
            adapter.executeUpdate("GRANT " + MigrationManager.ROLE_GUEST + " TO " + quote(role));
        } else {
            adapter.executeUpdate("GRANT " + MigrationManager.ROLE_MEMBER + " TO " + quote(role));
        }
    }

    private static void revokeGroups(DatabaseAdapter adapter, String role) throws SQLException {
        for (String group : new String[]{MigrationManager.ROLE_MEMBER, MigrationManager.ROLE_GUEST}) {
            try {
                adapter.executeUpdate("REVOKE " + group + " FROM " + quote(role));
            } catch (SQLException ignore) {
                // Not a member of that group; nothing to take away.
            }
        }
    }

    public static String generatePassword() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++)
            sb.append(PW_ALPHABET.charAt(RANDOM.nextInt(PW_ALPHABET.length())));
        return sb.toString();
    }

    /**
     * Accept only a plain lower-case identifier.
     *
     * <p>The name goes into {@code CREATE ROLE}, which takes no bound parameters. Refusing anything
     * unusual is safer than trying to escape it, and costs a villager nothing - they pick a name.
     */
    public static String requireIdentifier(String name) {
        String s = (name == null) ? "" : name.trim().toLowerCase();
        if (!s.matches("[a-z][a-z0-9_]{0,62}"))
            throw new IllegalArgumentException(
                "Name must start with a letter and use only letters, digits and underscores.");
        if (s.startsWith("pg_") || s.equals("postgres"))
            throw new IllegalArgumentException("That name is reserved by PostgreSQL.");
        return s;
    }

    private static String requireAccess(String access) {
        if (ACCESS_MEMBER.equals(access) || ACCESS_GUEST.equals(access))
            return access;
        throw new IllegalArgumentException("Unknown access level: " + access);
    }

    private static String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
