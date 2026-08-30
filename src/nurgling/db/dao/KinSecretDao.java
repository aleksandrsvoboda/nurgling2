package nurgling.db.dao;

import nurgling.db.DatabaseAdapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data Access Object for shared hearth secrets.
 * <p>
 * One row per character per world: the primary key is (profile, char_name), so a character that
 * changes its hearth secret updates its own row rather than adding a second one. Rows are scoped
 * by profile (the genus / world hash) the same way areas and routes are.
 */
public class KinSecretDao {

    /** One published hearth secret. */
    public static class KinSecret {
        public final String charName;
        public final String secret;
        public final Timestamp updatedAt;

        public KinSecret(String charName, String secret, Timestamp updatedAt) {
            this.charName = charName;
            this.secret = secret;
            this.updatedAt = updatedAt;
        }
    }

    /**
     * Every secret published for a world, ordered by character name so a pull is deterministic.
     * Rows with an empty secret are skipped: they are only reachable if some other client wrote
     * one, and replaying an empty secret to the game server is pointless.
     */
    public List<KinSecret> loadByProfile(DatabaseAdapter adapter, String profile) throws SQLException {
        List<KinSecret> ret = new java.util.ArrayList<>();
        String sql = "SELECT char_name, secret, updated_at FROM kin_secrets WHERE profile = ? ORDER BY char_name";
        try (ResultSet rs = adapter.executeQuery(sql, profile)) {
            while (rs.next()) {
                String secret = rs.getString("secret");
                if ((secret == null) || secret.isEmpty())
                    continue;
                ret.add(new KinSecret(rs.getString("char_name"), secret, rs.getTimestamp("updated_at")));
            }
        }
        return ret;
    }

    /**
     * Insert or replace this character's secret. Every column is supplied, which keeps the
     * adapter-provided upsert correct on both back ends (ON CONFLICT DO UPDATE on Postgres,
     * INSERT OR REPLACE on SQLite).
     */
    public void upsert(DatabaseAdapter adapter, String profile, String charName, String secret) throws SQLException {
        List<String> columns = List.of("profile", "char_name", "secret", "updated_at");
        String sql = adapter.getBatchUpsertSql("kin_secrets", columns,
                                               List.of("profile", "char_name"),
                                               List.of("secret", "updated_at"));
        adapter.executeUpdate(sql, profile, charName, secret, new Timestamp(System.currentTimeMillis()));
    }

    /** Drop this character's row; used when the hearth secret is cleared. */
    public void delete(DatabaseAdapter adapter, String profile, String charName) throws SQLException {
        adapter.executeUpdate("DELETE FROM kin_secrets WHERE profile = ? AND char_name = ?", profile, charName);
    }

    /** The secret currently published for one character, or null if there is no row. */
    public String get(DatabaseAdapter adapter, String profile, String charName) throws SQLException {
        try (ResultSet rs = adapter.executeQuery(
                "SELECT secret FROM kin_secrets WHERE profile = ? AND char_name = ?", profile, charName)) {
            return rs.next() ? rs.getString("secret") : null;
        }
    }

    /** Convenience view of {@link #loadByProfile} as char name -> secret. */
    public Map<String, String> loadMapByProfile(DatabaseAdapter adapter, String profile) throws SQLException {
        Map<String, String> ret = new LinkedHashMap<>();
        for (KinSecret ks : loadByProfile(adapter, profile))
            ret.put(ks.charName, ks.secret);
        return ret;
    }
}
