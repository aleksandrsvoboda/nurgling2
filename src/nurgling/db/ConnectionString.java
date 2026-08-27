package nurgling.db;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * A {@code postgresql://} connection string, which is how a villager is handed their account.
 *
 * <p>The alternative is telling somebody three values over chat, and one of them is a trap: the
 * settings field labelled "Host" is spliced straight into the JDBC URL, so it has to carry the port
 * as {@code host:port}. Send a bare address instead and the client quietly tries 5432 while the
 * database is somewhere else, with nothing on screen to say so. A connection string carries the port
 * with it, so it cannot be dropped on the way.
 *
 * <p>Deliberately the standard PostgreSQL format rather than a private one: an admin who already has
 * a connection string from somewhere else can paste that, and anyone can read what they are sending.
 */
public class ConnectionString {
    /** Mirrors the database name spliced into the JDBC URL in {@code SimpleConnectionPool}. */
    public static final String DEFAULT_DATABASE = "nurgling_db";

    /** Host, or host:port - exactly the shape {@code NConfig.Key.serverNode} expects. */
    public final String node;
    public final String database;
    public final String user;
    public final String password;

    public ConnectionString(String node, String database, String user, String password) {
        this.node = n(node);
        this.database = n(database);
        this.user = n(user);
        this.password = n(password);
    }

    /** Thrown when a pasted string cannot be used, with a reason worth putting on screen. */
    public static class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }

    /**
     * Build the string to hand to a villager.
     *
     * @param node the admin's own {@code serverNode}; they are connected, so it is already right
     */
    public static String build(String node, String database, String user, String password) {
        return "postgresql://" + enc(user) + ":" + enc(password)
             + "@" + n(node) + "/" + n(database);
    }

    public static ConnectionString parse(String text) throws FormatException {
        String s = (text == null) ? "" : text.trim();
        if (s.isEmpty())
            throw new FormatException("Paste a connection string first.");

        int scheme = s.indexOf("://");
        if (scheme < 0 || !s.regionMatches(true, 0, "postgres", 0, 8))
            throw new FormatException("That is not a postgresql:// connection string.");
        s = s.substring(scheme + 3);

        // Query parameters are not used here; the client has no setting they map onto.
        int q = s.indexOf('?');
        if (q >= 0)
            s = s.substring(0, q);

        String database = "";
        int slash = s.indexOf('/');
        if (slash >= 0) {
            database = s.substring(slash + 1);
            s = s.substring(0, slash);
        }

        String user = "";
        String password = "";
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            String userinfo = s.substring(0, at);
            s = s.substring(at + 1);
            int colon = userinfo.indexOf(':');
            if (colon >= 0) {
                user = dec(userinfo.substring(0, colon));
                password = dec(userinfo.substring(colon + 1));
            } else {
                user = dec(userinfo);
            }
        }

        String node = s.trim();
        if (node.isEmpty())
            throw new FormatException("That connection string has no address in it.");
        if (user.isEmpty())
            throw new FormatException("That connection string has no username in it.");
        return new ConnectionString(node, database, user, password);
    }

    /**
     * Split a stored {@code serverNode} into host and port for display as two fields.
     *
     * <p>The value stays one config key: presenting it as two boxes is a UI concern, and inventing a
     * second key would mean inventing a default for it, which is how a port silently stops matching
     * the one a village has been using.
     *
     * <p>A bare IPv6 literal is left whole - its colons belong to the address, and guessing
     * otherwise turns a working setting into an unreachable one.
     *
     * @return {@code {host, port}}; port is empty when the value carries none
     */
    public static String[] splitNode(String node) {
        String s = n(node);
        if (s.isEmpty())
            return new String[]{"", ""};

        if (s.charAt(0) == '[') {
            int close = s.indexOf(']');
            if (close >= 0 && s.length() > close + 1 && s.charAt(close + 1) == ':') {
                String port = s.substring(close + 2);
                if (isPort(port))
                    return new String[]{s.substring(0, close + 1), port};
            }
            return new String[]{s, ""};
        }

        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            String port = s.substring(colon + 1);
            if (isPort(port))
                return new String[]{s.substring(0, colon), port};
        }
        return new String[]{s, ""};
    }

    /**
     * Put the two fields back into one stored value.
     *
     * <p>An empty port is left off entirely rather than filled in, so the value stays exactly what
     * it was before it was ever shown as two boxes and the driver keeps applying its own default.
     */
    public static String joinNode(String host, String port) {
        String h = n(host);
        String p = n(port);
        if (h.isEmpty())
            return "";
        return p.isEmpty() ? h : h + ":" + p;
    }

    private static boolean isPort(String s) {
        if (s.isEmpty() || s.length() > 5)
            return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9')
                return false;
        }
        int v = Integer.parseInt(s);
        return v > 0 && v <= 65535;
    }

    private static String enc(String s) {
        try {
            /* A generated password is alphanumeric, but an admin may be re-sharing one that is not,
             * and a stray @ or : would otherwise cut the string in the wrong place. */
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    private static String n(String s) {
        return s == null ? "" : s.trim();
    }
}
