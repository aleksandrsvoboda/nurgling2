package nurgling.conf;

import nurgling.NConfig;
import nurgling.profiles.ConfigFactory;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Record of which shared hearth secrets have already been replayed to the game server.
 * <p>
 * Kinship is per character, not per account: the fact that my main has already added Sigrid says
 * nothing about my alt, so entries are keyed by <em>my</em> character first and the other
 * character second. The stored value is the secret that was sent, so a friend rotating their
 * secret automatically re-qualifies for a send on the next pull.
 * <p>
 * The file is per world, alongside {@link NKinNotes}.
 */
public class NKinSecretCache {
    private static final String FILE = "kin_secrets_applied.nurgling.json";
    private static final Map<String, NKinSecretCache> insts = new HashMap<>();

    private final String path;
    /** my character -> (their character -> secret already sent) */
    private final Map<String, Map<String, String>> applied = new HashMap<>();
    /**
     * My characters that must not have a hearth secret generated for them. Set when the player
     * clears their secret by hand: that is the one unambiguous statement that this character is
     * meant to have none, and it has to outlive the session or the next login would undo it.
     */
    private final Set<String> noautogen = new HashSet<>();

    /** One instance per world, shared by every session on it. */
    public static synchronized NKinSecretCache get(String genus) {
        String key = (genus == null) ? "" : genus;
        NKinSecretCache ret = insts.get(key);
        if (ret == null)
            insts.put(key, ret = new NKinSecretCache(genus));
        return (ret);
    }

    private NKinSecretCache(String genus) {
        NConfig cfg = ConfigFactory.getConfig(genus);
        this.path = cfg.getProfileAwarePath(FILE);
        load();
    }

    private void load() {
        String content = NFileUtils.readWithBackupFallback(path);
        if ((content == null) || content.isEmpty())
            return;
        try {
            JSONObject root = new JSONObject(content);
            JSONObject chars = root.optJSONObject("applied");
            if (chars == null)
                return;
            for (String mine : chars.keySet()) {
                JSONObject theirs = chars.optJSONObject(mine);
                if (theirs == null)
                    continue;
                Map<String, String> row = new HashMap<>();
                for (String other : theirs.keySet()) {
                    String secret = theirs.optString(other, "");
                    if (!secret.isEmpty())
                        row.put(other, secret);
                }
                if (!row.isEmpty())
                    applied.put(mine, row);
            }
            JSONArray no = root.optJSONArray("noautogen");
            if (no != null) {
                for (int i = 0; i < no.length(); i++) {
                    String name = no.optString(i, "");
                    if (!name.isEmpty())
                        noautogen.add(name);
                }
            }
        } catch (org.json.JSONException e) {
            System.err.println("[NKinSecretCache] corrupt " + FILE + ", starting empty: " + e.getMessage());
            applied.clear();
            noautogen.clear();
        }
    }

    private void save() {
        JSONObject chars = new JSONObject();
        for (Map.Entry<String, Map<String, String>> e : applied.entrySet())
            chars.put(e.getKey(), new JSONObject(e.getValue()));
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("applied", chars);
        root.put("noautogen", new JSONArray(noautogen));
        try {
            NFileUtils.writeAtomically(path, root.toString());
        } catch (IOException e) {
            System.err.println("[NKinSecretCache] failed to save " + FILE + ": " + e.getMessage());
        }
    }

    /** True if this character has already sent exactly this secret for that other character. */
    public synchronized boolean isApplied(String myChar, String otherChar, String secret) {
        Map<String, String> row = applied.get(key(myChar));
        return ((row != null) && secret.equals(row.get(otherChar)));
    }

    /** Remember that this character replayed that secret. */
    public synchronized void markApplied(String myChar, String otherChar, String secret) {
        if ((otherChar == null) || otherChar.isEmpty() || (secret == null) || secret.isEmpty())
            return;
        Map<String, String> row = applied.computeIfAbsent(key(myChar), k -> new HashMap<>());
        if (secret.equals(row.get(otherChar)))
            return;
        row.put(otherChar, secret);
        save();
    }

    /**
     * Record a whole pull at once. A pull can be hundreds of entries, and saving per entry would
     * rewrite the file hundreds of times, so callers batch and flush here.
     */
    public synchronized void markAllApplied(String myChar, Map<String, String> sent) {
        if ((sent == null) || sent.isEmpty())
            return;
        Map<String, String> row = applied.computeIfAbsent(key(myChar), k -> new HashMap<>());
        boolean changed = false;
        for (Map.Entry<String, String> e : sent.entrySet()) {
            String other = e.getKey(), secret = e.getValue();
            if ((other == null) || other.isEmpty() || (secret == null) || secret.isEmpty())
                continue;
            if (secret.equals(row.get(other)))
                continue;
            row.put(other, secret);
            changed = true;
        }
        if (changed)
            save();
    }

    /** Forget everything this character has sent, so the next pull re-sends the whole list. */
    public synchronized void forget(String myChar) {
        if (applied.remove(key(myChar)) != null)
            save();
    }

    /** True if this character opted out of having a hearth secret generated for it. */
    public synchronized boolean isAutogenSuppressed(String myChar) {
        return (noautogen.contains(key(myChar)));
    }

    /** Record that this character is meant to have no hearth secret. */
    public synchronized void suppressAutogen(String myChar) {
        if (noautogen.add(key(myChar)))
            save();
    }

    /** Undo the opt-out, for when the player sets a secret by hand after having cleared one. */
    public synchronized void allowAutogen(String myChar) {
        if (noautogen.remove(key(myChar)))
            save();
    }

    private static String key(String myChar) {
        return ((myChar == null) ? "" : myChar);
    }
}
