package nurgling.db;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

/**
 * One pasteable string carrying everything needed to join a village database.
 *
 * <p>Joining used to mean copying four fields out of a chat message, two of which were not on
 * screen anywhere: the port hid inside the host field, and the database name was a constant spliced
 * into the URL. All six values travel together here, so the joiner fills in one box.
 *
 * <p>An invite <em>is</em> a password. One per player, never a village-wide one - that is what makes
 * revoking a single person mean anything.
 */
public class InviteCode {
    public static final String PREFIX = "NURG1";

    public final String village;
    public final String host;
    public final int port;
    public final String database;
    public final String user;
    public final String password;
    public final String sslmode;
    public final String role;
    /**
     * PEM of the server certificate to pin.
     *
     * <p>Always empty for now: nothing writes it yet, because pinning needs the setup flow to hand
     * the certificate back, which arrives with the host wizard. It is read here from the start so
     * that when codes do carry one, every client already in the wild understands the format instead
     * of rejecting it.
     */
    public final String ca;

    public InviteCode(String village, String host, int port, String database,
                      String user, String password, String sslmode, String role, String ca) {
        this.village = n(village);
        this.host = n(host);
        this.port = port;
        this.database = n(database).isEmpty() ? DbSettings.DEFAULT_DATABASE : n(database);
        this.user = n(user);
        this.password = n(password);
        this.sslmode = DbSettings.normalizeSsl(sslmode);
        this.role = n(role);
        this.ca = n(ca);
    }

    /** Thrown when a pasted string is not a code we can use, with a reason worth showing. */
    public static class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }

    public String encode() {
        JSONObject o = new JSONObject();
        o.put("v", 1);
        if (!village.isEmpty()) o.put("n", village);
        o.put("h", host);
        o.put("p", port);
        o.put("d", database);
        o.put("u", user);
        o.put("pw", password);
        o.put("ssl", sslmode);
        if (!role.isEmpty()) o.put("role", role);
        if (!ca.isEmpty()) o.put("ca", ca);

        byte[] payload = o.toString().getBytes(StandardCharsets.UTF_8);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        return PREFIX + "." + body + "." + Long.toHexString(crc(payload));
    }

    /**
     * Parse a pasted code.
     *
     * <p>Also accepts a plain {@code postgresql://} URI, which is the universal way to write a
     * PostgreSQL connection - anything a host already has on hand pastes straight in.
     */
    public static InviteCode decode(String text) throws FormatException {
        String s = (text == null) ? "" : text.trim();
        if (s.isEmpty())
            throw new FormatException("Paste an invite code first.");

        if (s.regionMatches(true, 0, "postgres", 0, 8) && s.contains("://"))
            return fromUri(s);

        if (!s.regionMatches(true, 0, PREFIX + ".", 0, PREFIX.length() + 1))
            throw new FormatException("That does not look like an invite code.");

        String[] parts = s.split("\\.");
        if (parts.length != 3)
            throw new FormatException("That invite code is incomplete - copy the whole thing.");

        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new FormatException("That invite code is damaged - copy it again.");
        }
        /* The checksum is here to separate "you pasted half of it" from "your host gave you the
         * wrong password", which otherwise both arrive as an authentication failure much later. */
        if (!Long.toHexString(crc(payload)).equalsIgnoreCase(parts[2]))
            throw new FormatException("That invite code is damaged - copy it again.");

        try {
            JSONObject o = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            int v = o.optInt("v", 0);
            if (v > 1)
                throw new FormatException("That invite was made by a newer Nurgling. Update through the launcher.");
            return new InviteCode(o.optString("n", ""), o.optString("h", ""),
                                  o.optInt("p", DbSettings.DEFAULT_PORT),
                                  o.optString("d", DbSettings.DEFAULT_DATABASE),
                                  o.optString("u", ""), o.optString("pw", ""),
                                  o.optString("ssl", DbSettings.SSL_PREFER),
                                  o.optString("role", ""), o.optString("ca", ""));
        } catch (JSONException e) {
            throw new FormatException("That invite code is damaged - copy it again.");
        }
    }

    private static InviteCode fromUri(String uri) throws FormatException {
        String s = uri.substring(uri.indexOf("://") + 3);

        String query = "";
        int q = s.indexOf('?');
        if (q >= 0) {
            query = s.substring(q + 1);
            s = s.substring(0, q);
        }

        String db = "";
        int slash = s.indexOf('/');
        if (slash >= 0) {
            db = s.substring(slash + 1);
            s = s.substring(0, slash);
        }

        String user = "";
        String pass = "";
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            String userinfo = s.substring(0, at);
            s = s.substring(at + 1);
            int colon = userinfo.indexOf(':');
            if (colon >= 0) {
                user = dec(userinfo.substring(0, colon));
                pass = dec(userinfo.substring(colon + 1));
            } else {
                user = dec(userinfo);
            }
        }

        DbSettings.HostPort hp = DbSettings.parseNode(s);
        if (hp.host.isEmpty())
            throw new FormatException("That connection string has no host in it.");

        String ssl = DbSettings.SSL_PREFER;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equalsIgnoreCase("sslmode"))
                ssl = DbSettings.normalizeSsl(dec(pair.substring(eq + 1)));
        }
        return new InviteCode("", hp.host, hp.port, db, user, pass, ssl, "", "");
    }

    public DbSettings toSettings() {
        return new DbSettings(host, port, database, sslmode, user, password);
    }

    private static long crc(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        return c.getValue();
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
