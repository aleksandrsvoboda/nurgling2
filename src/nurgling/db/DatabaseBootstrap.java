package nurgling.db;

import nurgling.NConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Makes the database exist before anything tries to use it.
 *
 * <p>The database name is a constant inside the JDBC URL, so a PostgreSQL that was installed by hand
 * - rather than through the bundled {@code docker-compose.yml}, which creates it as a side effect -
 * has {@code postgres} and nothing else. Connecting then fails with {@code 3D000} before the client
 * gets far enough to create anything, and the only trace is a line on stderr.
 *
 * <p>Everything here is deliberately narrow: it acts on exactly one SQLState, it only ever creates,
 * and it never touches a database it can already reach.
 */
public class DatabaseBootstrap {
    /** PostgreSQL's "database does not exist". The only condition this class reacts to. */
    private static final String NO_SUCH_DATABASE = "3D000";

    /** Tried in order for the connection used to issue {@code CREATE DATABASE}. */
    private static final String[] MAINTENANCE_DATABASES = {"postgres", "template1"};

    public enum Result {
        /** The failure was not a missing database, so nothing was attempted. */
        SKIPPED,
        /** It was missing and has now been created. */
        CREATED,
        /** It was missing and could not be created; {@link #detail} says why. */
        FAILED
    }

    public final Result result;
    /** Plain-language reason, set when {@link #result} is {@link Result#FAILED}. */
    public final String detail;

    private DatabaseBootstrap(Result result, String detail) {
        this.result = result;
        this.detail = detail == null ? "" : detail;
    }

    /**
     * The URL the client connects with.
     *
     * <p>Lives here so the pool and this class cannot drift apart about which database they mean.
     *
     * @param node host, or host:port, exactly as stored in {@code serverNode}
     */
    public static String jdbcUrl(Object node, String database) {
        return "jdbc:postgresql://" + node + "/" + database
             + "?connectTimeout=10&socketTimeout=60";
    }

    /**
     * Create the configured database, but only when that is demonstrably what went wrong.
     *
     * <p>Driven by the failure the pool already has rather than by a fresh probe of its own. That
     * matters more than it looks: {@code DatabaseManager} is built from {@code NCore.tick()}, on the
     * UI thread, so every connection attempt on this path freezes the client for its timeout.
     * Re-asking the server a question it has already answered would double that freeze for everyone
     * whose server is simply unreachable - the case where this class can do nothing anyway.
     *
     * <p>Blocking, but only ever in the one case where a short local round trip is about to fix the
     * problem outright.
     *
     * @param failure why the pool could not connect; anything but {@code 3D000} is left alone
     */
    public static DatabaseBootstrap createIfMissing(SQLException failure) {
        if (failure == null || !NO_SUCH_DATABASE.equals(failure.getSQLState())) {
            return new DatabaseBootstrap(Result.SKIPPED, "");
        }

        Object node = NConfig.get(NConfig.Key.serverNode);
        String user = str(NConfig.get(NConfig.Key.serverUser));
        String password = str(NConfig.get(NConfig.Key.serverPass));
        String database = ConnectionString.DEFAULT_DATABASE;

        if (str(node).isEmpty()) {
            return new DatabaseBootstrap(Result.FAILED, "no server address is configured");
        }

        System.out.println("[DatabaseBootstrap] database '" + database
            + "' does not exist yet; creating it");
        return create(node, user, password, database);
    }

    private static DatabaseBootstrap create(Object node, String user, String password,
                                            String database) {
        String lastError = "";
        for (String maintenance : MAINTENANCE_DATABASES) {
            /* Its own short-lived connection on purpose: CREATE DATABASE cannot run inside a
             * transaction, and every connection the pool hands out has autoCommit switched off. */
            try (Connection conn = DriverManager.getConnection(jdbcUrl(node, maintenance),
                                                               user, password)) {
                try (Statement st = conn.createStatement()) {
                    /* The name is a compile-time constant, not user input, so there is nothing here
                     * to escape - but it is quoted anyway so the statement cannot be reshaped if it
                     * ever stops being one. */
                    st.executeUpdate("CREATE DATABASE \"" + database.replace("\"", "\"\"") + "\"");
                }
                System.out.println("[DatabaseBootstrap] created database '" + database + "'");
                return new DatabaseBootstrap(Result.CREATED, "");
            } catch (SQLException e) {
                lastError = e.getMessage();
                if ("42P04".equals(e.getSQLState())) {
                    // Someone else created it between the probe and now. That is a success.
                    return new DatabaseBootstrap(Result.CREATED, "");
                }
                if ("42501".equals(e.getSQLState())) {
                    return new DatabaseBootstrap(Result.FAILED,
                        "the account '" + user + "' may not create databases. Either grant it"
                      + " CREATEDB, or run: createdb " + database);
                }
                if (NO_SUCH_DATABASE.equals(e.getSQLState())) {
                    // This maintenance database is missing too; try the next one.
                    continue;
                }
                return new DatabaseBootstrap(Result.FAILED, e.getMessage());
            }
        }
        return new DatabaseBootstrap(Result.FAILED,
            "could not reach a maintenance database to create '" + database + "' (" + lastError + ")");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
