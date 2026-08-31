package nurgling.cookbook;

import nurgling.db.DatabaseAdapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * An immutable count of what is sitting in the tracked containers, keyed by item name.
 *
 * <p>{@code storageitems} holds one row per physical item - stack members are expanded by their index
 * when written - so a count is a plain {@code COUNT(*)}, not a sum of stack sizes.
 *
 * <p>Built on the database worker and then only read, so the UI thread never touches a connection.
 */
public class Pantry {
    private final Map<String, Integer> counts;
    private final Map<String, Double> bestQuality;
    /** normalised name -> canonical name, so lookups survive punctuation and case drift */
    private final Map<String, String> byNorm;

    private Pantry(Map<String, Integer> counts, Map<String, Double> bestQuality,
                   Map<String, String> byNorm) {
        this.counts = Collections.unmodifiableMap(counts);
        this.bestQuality = Collections.unmodifiableMap(bestQuality);
        this.byNorm = Collections.unmodifiableMap(byNorm);
    }

    public static Pantry empty() {
        return new Pantry(new HashMap<String, Integer>(), new HashMap<String, Double>(),
                new HashMap<String, String>());
    }

    /** Build from counts already in hand, for tools that read the database themselves. */
    public static Pantry of(Map<String, Integer> counts, Map<String, Double> bestQuality) {
        Map<String, String> byNorm = new HashMap<>();
        for (String name : counts.keySet())
            byNorm.put(ItemUniverse.normalize(name), name);
        return new Pantry(new HashMap<>(counts), new HashMap<>(bestQuality), byNorm);
    }

    public static Pantry load(DatabaseAdapter adapter) throws SQLException {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Double> quality = new HashMap<>();
        Map<String, String> byNorm = new HashMap<>();
        String sql = "SELECT name, COUNT(*) AS cnt, MAX(quality) AS best "
                + "FROM storageitems GROUP BY name";
        try (ResultSet rs = adapter.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name == null || name.isEmpty())
                    continue;
                counts.put(name, rs.getInt("cnt"));
                quality.put(name, rs.getDouble("best"));
                byNorm.put(ItemUniverse.normalize(name), name);
            }
        }
        return new Pantry(counts, quality, byNorm);
    }

    /** How many of this exact item are held, matching loosely on punctuation and case. */
    public int count(String itemName) {
        Integer direct = counts.get(itemName);
        if (direct != null)
            return direct;
        String canon = byNorm.get(ItemUniverse.normalize(itemName));
        if (canon == null)
            return 0;
        Integer res = counts.get(canon);
        return (res == null) ? 0 : res;
    }

    public boolean has(String itemName) {
        return count(itemName) > 0;
    }

    /** The first of these item names that is actually in stock, or null when none is. */
    public String firstHeld(Set<String> itemNames) {
        for (String name : itemNames) {
            if (has(name))
                return name;
        }
        return null;
    }

    public double bestQuality(String itemName) {
        Double direct = bestQuality.get(itemName);
        if (direct != null)
            return direct;
        String canon = byNorm.get(ItemUniverse.normalize(itemName));
        if (canon == null)
            return 0;
        Double res = bestQuality.get(canon);
        return (res == null) ? 0 : res;
    }


    public int distinctItems() {
        return counts.size();
    }
}
