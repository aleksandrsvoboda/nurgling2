package nurgling.db;

import nurgling.i18n.L10n;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Turns a database failure into one sentence a player can act on.
 *
 * <p>Every connection problem used to reach the user as nothing at all: the pool logged to stderr
 * and the client simply stopped syncing. The failures are highly distinguishable - a refused port,
 * a wrong password and a missing database are three completely different jobs for whoever has to fix
 * it - so this maps each to its own message instead of one "database unavailable".
 */
public class ConnectionDoctor {

    public enum Problem {
        OK,
        NOT_CONFIGURED,
        UNKNOWN_HOST,
        REFUSED,
        RESET,
        TIMEOUT,
        AUTH_FAILED,
        NO_DATABASE,
        NO_HBA,
        NO_PERMISSION,
        TOO_MANY_CONNECTIONS,
        SSL_REQUIRED,
        UNKNOWN
    }

    /** Outcome of a diagnosis or a probe. */
    public static class Result {
        public final Problem problem;
        /** Localized, one sentence, safe to show. */
        public final String message;
        /** Raw driver text, for the log and for a bug report. Never shown as the primary message. */
        public final String detail;

        /** Whether the session actually negotiated TLS. Only meaningful when {@link #ok()}. */
        public boolean sslInUse;
        public String serverUser = "";
        public String serverVersion = "";
        /** Schema version found, or -1 when the table is absent (a database nobody has migrated). */
        public int schemaVersion = -1;

        Result(Problem problem, String message, String detail) {
            this.problem = problem;
            this.message = message;
            this.detail = detail == null ? "" : detail;
        }

        public boolean ok() {
            return problem == Problem.OK;
        }
    }

    /**
     * Classify a failure.
     *
     * <p>The cause chain is walked before the SQLState is read: pgjdbc reports a refused port, an
     * unresolvable name and a timeout all as {@code 08001}, and the distinction that matters to the
     * user is only in the wrapped IOException.
     */
    public static Result diagnose(Throwable t, DbSettings s) {
        String where = s == null ? "" : s.endpoint();
        String detail = rootMessage(t);

        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.UnknownHostException)
                return new Result(Problem.UNKNOWN_HOST,
                    L10n.get("db.err.unknown_host", s == null ? "" : s.host), detail);
            if (c instanceof java.net.SocketTimeoutException)
                return new Result(Problem.TIMEOUT, L10n.get("db.err.timeout", where), detail);
            if (c instanceof java.net.ConnectException)
                return new Result(Problem.REFUSED, L10n.get("db.err.refused", where), detail);
            /* Must come after ConnectException, which is a subclass. Something accepted the
             * connection and then dropped it, which is a different problem with a different fix
             * from nothing listening at all - most often it is not a PostgreSQL server on that
             * port. Reporting it as "refused" sends people to check a firewall that is fine. */
            if (c instanceof java.net.SocketException)
                return new Result(Problem.RESET, L10n.get("db.err.reset", where), detail);
            if (c == c.getCause())
                break;
        }

        String state = sqlState(t);
        if (state != null) {
            switch (state) {
                case "28P01":
                    return new Result(Problem.AUTH_FAILED, L10n.get("db.err.auth"), detail);
                case "28000":
                    return new Result(Problem.NO_HBA, L10n.get("db.err.no_hba"), detail);
                case "3D000":
                    return new Result(Problem.NO_DATABASE,
                        L10n.get("db.err.no_database", s == null ? "" : s.database), detail);
                case "42501":
                    return new Result(Problem.NO_PERMISSION, L10n.get("db.err.no_permission"), detail);
                case "53300":
                    return new Result(Problem.TOO_MANY_CONNECTIONS,
                        L10n.get("db.err.too_many_connections"), detail);
                default:
                    break;
            }
            /* The server refusing an unencrypted session is a configuration mismatch with a precise
             * fix, so it is worth separating from the generic connection failures it shares a state
             * with. */
            if (state.startsWith("08") && detail.toLowerCase().contains("ssl"))
                return new Result(Problem.SSL_REQUIRED, L10n.get("db.err.ssl_required"), detail);
            if (state.startsWith("08"))
                return new Result(Problem.REFUSED, L10n.get("db.err.refused", where), detail);
        }
        return new Result(Problem.UNKNOWN, L10n.get("db.err.unknown", detail), detail);
    }

    /**
     * Open a throwaway connection and report what happened.
     *
     * <p>Blocking, and deliberately not pooled: this is what the Test button runs, so it must say
     * what a fresh connection would do rather than reuse one the pool already proved works. Call it
     * off the UI thread.
     */
    public static Result probe(DbSettings s) {
        if (s == null || !s.isConfigured())
            return new Result(Problem.NOT_CONFIGURED, L10n.get("db.err.not_configured"), "");

        try (Connection conn = DriverManager.getConnection(s.jdbcUrl(), s.user, s.password)) {
            Result r = inspect(conn);
            return r;
        } catch (SQLException e) {
            return diagnose(e, s);
        }
    }

    /**
     * Identity, TLS state and schema version of a connection that is already open.
     *
     * <p>Used by the pool's own startup so the settings panel can describe a live connection
     * without opening a second one.
     */
    public static Result inspect(Connection conn) {
        Result r = new Result(Problem.OK, "", "");
        readIdentity(conn, r);
        readSsl(conn, r);
        readSchemaVersion(conn, r);
        return r;
    }

    private static void readIdentity(Connection conn, Result r) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_user, version()")) {
            if (rs.next()) {
                r.serverUser = rs.getString(1);
                r.serverVersion = shortVersion(rs.getString(2));
            }
        } catch (SQLException e) {
            /* Cosmetic detail only - a connection that opened is still a connection that works. */
            System.err.println("[ConnectionDoctor] could not read identity: " + e.getMessage());
        }
    }

    /**
     * Ask the server whether this session is actually encrypted.
     *
     * <p>Necessary because {@code sslmode=prefer} - the default, and what every existing config
     * effectively uses - silently falls back to plaintext when the server has no certificate. The
     * driver reports success either way, so the only honest source is the server's own view.
     */
    private static void readSsl(Connection conn, Result r) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
            r.sslInUse = rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            r.sslInUse = false;
        }
    }

    private static void readSchemaVersion(Connection conn, Result r) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            r.schemaVersion = rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            r.schemaVersion = -1;
        }
    }

    /** "PostgreSQL 17.2 on x86_64-..." -> "PostgreSQL 17.2" */
    private static String shortVersion(String full) {
        if (full == null)
            return "";
        int on = full.indexOf(" on ");
        return (on > 0 ? full.substring(0, on) : full).trim();
    }

    private static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException) {
                String state = ((SQLException) c).getSQLState();
                if (state != null && !state.isEmpty())
                    return state;
            }
            if (c == c.getCause())
                break;
        }
        return null;
    }

    private static String rootMessage(Throwable t) {
        Throwable last = t;
        for (Throwable c = t; c != null; c = c.getCause()) {
            last = c;
            if (c == c.getCause())
                break;
        }
        String m = last == null ? null : last.getMessage();
        if (m == null || m.isEmpty())
            m = (t == null) ? "" : String.valueOf(t);
        return m;
    }
}
