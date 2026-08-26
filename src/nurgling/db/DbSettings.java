package nurgling.db;

import nurgling.NConfig;

/**
 * The effective PostgreSQL connection settings, in one place.
 *
 * <p>Connection details used to live in a single {@code serverNode} string that silently had to
 * contain "host:port", with the database name spliced into the URL as a constant and no way to ask
 * for TLS at all. Host, port, database and encryption are now four separate settings.
 *
 * <p>Everyone who already has a working village has {@code serverNode} on disk and nothing else, so
 * {@link #resolve} reads either shape. That fallback is deliberately exact rather than convenient:
 * a {@code serverNode} carrying no port resolves to {@link #LEGACY_PORT}, because that is the port
 * such a config reaches today and an upgrade must not move somebody's database.
 */
public class DbSettings {
    public static final String DEFAULT_DATABASE = "nurgling_db";

    /** Port {@code etc/db/docker-compose.yml} publishes, and the default for a fresh config. */
    public static final int DEFAULT_PORT = 5436;

    /** Where a legacy {@code serverNode} with no port has always ended up: the JDBC default. */
    public static final int LEGACY_PORT = 5432;

    public static final String SSL_DISABLE = "disable";
    public static final String SSL_PREFER = "prefer";
    public static final String SSL_REQUIRE = "require";

    public final String host;
    public final int port;
    public final String database;
    public final String sslmode;
    public final String user;
    public final String password;

    public DbSettings(String host, int port, String database, String sslmode,
                      String user, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.sslmode = sslmode;
        this.user = user;
        this.password = password;
    }

    /** Settings for the current configuration, legacy keys included. */
    public static DbSettings fromConfig() {
        return resolve(NConfig.get(NConfig.Key.dbHost),
                       NConfig.get(NConfig.Key.dbPort),
                       NConfig.get(NConfig.Key.dbName),
                       NConfig.get(NConfig.Key.dbSsl),
                       NConfig.get(NConfig.Key.serverNode),
                       NConfig.get(NConfig.Key.serverUser),
                       /* Not a config key any more - the password lives in its own file so it does
                        * not travel with everything else people share. */
                       DbCredentials.password());
    }

    /**
     * Work out the effective settings. Kept free of {@link NConfig} so the fallback rules are
     * testable without a running client.
     *
     * @param legacyNode the old {@code serverNode} value; consulted only when no explicit host is set
     */
    public static DbSettings resolve(Object host, Object port, Object database, Object sslmode,
                                     Object legacyNode, Object user, Object password) {
        String h = str(host);
        int p = intOr(port, 0);

        if (h.isEmpty()) {
            String node = str(legacyNode);
            if (!node.isEmpty()) {
                /* No explicit host: this config predates the split, so the old combined value is the
                 * whole truth about where to connect - INCLUDING its port, which is taken
                 * unconditionally rather than only when dbPort looks unset. dbPort is never unset:
                 * it carries a default, so a "use the legacy port only if dbPort is empty" rule
                 * silently sent every pre-split village to the new default port instead of the one
                 * it had been using. */
                HostPort hp = parseNode(node);
                h = hp.host;
                p = hp.port;
            }
        }
        if (p <= 0)
            p = DEFAULT_PORT;

        String db = str(database);
        if (db.isEmpty())
            db = DEFAULT_DATABASE;

        return new DbSettings(h, p, db, normalizeSsl(sslmode), str(user), str(password));
    }

    /** Host and port as carried by a legacy {@code serverNode}. */
    public static class HostPort {
        public final String host;
        public final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Split a legacy {@code serverNode} into host and port.
     *
     * <p>Handles the three shapes people actually have on disk: {@code host}, {@code host:port} and
     * a bracketed IPv6 literal. A bare IPv6 address is left whole - its colons are part of the
     * address, and guessing otherwise would turn a working config into an unreachable one.
     */
    public static HostPort parseNode(String node) {
        String s = node == null ? "" : node.trim();
        if (s.isEmpty())
            return new HostPort("", LEGACY_PORT);

        // Someone pasting a connection URL is a common enough slip to be worth absorbing.
        int scheme = s.indexOf("://");
        if (scheme >= 0)
            s = s.substring(scheme + 3);
        int at = s.lastIndexOf('@');
        if (at >= 0)
            s = s.substring(at + 1);
        int slash = s.indexOf('/');
        if (slash >= 0)
            s = s.substring(0, slash);
        s = s.trim();
        if (s.isEmpty())
            return new HostPort("", LEGACY_PORT);

        if (s.charAt(0) == '[') {
            int close = s.indexOf(']');
            if (close < 0)
                return new HostPort(s, LEGACY_PORT);
            String h = s.substring(1, close);
            String rest = s.substring(close + 1);
            if (rest.startsWith(":")) {
                int p = digits(rest.substring(1));
                if (p > 0)
                    return new HostPort(h, p);
            }
            return new HostPort(h, LEGACY_PORT);
        }

        int colon = s.lastIndexOf(':');
        // More than one colon means a bare IPv6 literal, which carries no port.
        if (colon > 0 && s.indexOf(':') == colon) {
            int p = digits(s.substring(colon + 1));
            if (p > 0)
                return new HostPort(s.substring(0, colon), p);
        }
        return new HostPort(s, LEGACY_PORT);
    }

    /** The JDBC URL. Never contains the password - that goes to the driver separately. */
    public String jdbcUrl() {
        String h = host;
        // JDBC needs an IPv6 literal bracketed so its colons are not read as a port separator.
        if (h.indexOf(':') >= 0 && !h.startsWith("["))
            h = "[" + h + "]";
        String url = "jdbc:postgresql://" + h + ":" + port + "/" + database
                   + "?connectTimeout=10&socketTimeout=60";
        /* "Prefer" is already the driver's behaviour with no sslmode at all, so it is left off
         * rather than spelled out. That keeps the URL byte-identical to what every existing village
         * has been connecting with, which is worth more than the symmetry of always naming it. */
        if (!SSL_PREFER.equals(sslmode))
            url += "&sslmode=" + sslmode;
        return url;
    }

    /** Just the network endpoint - what to say when the failure is below PostgreSQL. */
    public String endpoint() {
        return host + ":" + port;
    }

    /** Safe to log and to show in an error: identifies the server, carries no secret. */
    public String describe() {
        return host + ":" + port + "/" + database;
    }

    public boolean isConfigured() {
        return !host.isEmpty();
    }

    /** Accept only the three modes the settings UI offers; anything else falls back to prefer. */
    public static String normalizeSsl(Object mode) {
        String s = str(mode).toLowerCase();
        if (s.equals(SSL_DISABLE) || s.equals(SSL_REQUIRE))
            return s;
        return SSL_PREFER;
    }

    private static int digits(String s) {
        if (s.isEmpty() || s.length() > 5)
            return 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9')
                return 0;
        }
        int v = Integer.parseInt(s);
        return (v > 0 && v <= 65535) ? v : 0;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static int intOr(Object o, int fallback) {
        if (o instanceof Number)
            return ((Number) o).intValue();
        if (o != null) {
            String s = o.toString().trim();
            if (!s.isEmpty()) {
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException ignore) {
                    /* A hand-edited config with junk in it falls back rather than refusing to start. */
                }
            }
        }
        return fallback;
    }
}
