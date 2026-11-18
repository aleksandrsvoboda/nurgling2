package nurgling.sessions.multilogin;

import nurgling.NUtils;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The named groups for "Log in to several": each is a set of accounts with one character picked on
 * each. Kept with the other global login files (multi_login.nurgling.json, next to the character
 * tags) and written on every change. Holds no passwords: logins use the login screen's saved tokens.
 */
public final class LoginGroups {

    private static final String FILE = "multi_login.nurgling.json";

    /** One group: account -> the character to play on it, in the order they were ticked. */
    public static final class Group {
        public String name;
        public final Map<String, String> picks = new LinkedHashMap<>();

        Group(String name) {
            this.name = name;
        }
    }

    private final List<Group> groups = new ArrayList<>();
    private int selected = 0;
    /** Most logins in flight at once; 0 = all. */
    private int maxAtOnce = 0;

    LoginGroups() {
        load();
        if (groups.isEmpty())
            groups.add(new Group(defaultName(1)));
    }

    private static String defaultName(int n) {
        String base = nurgling.i18n.L10n.get("multilogin.group_default");
        return (n <= 1) ? base : base + " " + n;
    }

    public synchronized List<Group> list() {
        return new ArrayList<>(groups);
    }

    public synchronized Group selected() {
        return groups.get(Math.max(0, Math.min(selected, groups.size() - 1)));
    }

    public synchronized void select(Group g) {
        int i = groups.indexOf(g);
        if (i >= 0 && i != selected) {
            selected = i;
            save();
        }
    }

    public synchronized Group byName(String name) {
        for (Group g : groups) {
            if (g.name.equals(name))
                return g;
        }
        return null;
    }

    /** A new empty group, selected. */
    public synchronized Group add() {
        String name = defaultName(1);
        for (int n = 2; byName(name) != null; n++)
            name = defaultName(n);
        Group g = new Group(name);
        groups.add(g);
        selected = groups.size() - 1;
        save();
        return g;
    }

    /** Removes a group; the last one is always kept. */
    public synchronized void remove(Group g) {
        if (groups.size() <= 1)
            return;
        groups.remove(g);
        selected = Math.min(selected, groups.size() - 1);
        save();
    }

    /** Renames a group; false if the name is empty or taken. */
    public synchronized boolean rename(Group g, String name) {
        String n = name.trim();
        if (n.isEmpty() || n.equals(g.name) || byName(n) != null)
            return false;
        g.name = n;
        save();
        return true;
    }

    /** Picks {@code character} on {@code account} in the group, or unticks the account ({@code character} null). */
    public synchronized void pick(Group g, String account, String character) {
        if (character == null)
            g.picks.remove(account);
        else
            g.picks.put(account, character);
        save();
    }

    /** A copy of the group's picks, in tick order. */
    public synchronized Map<String, String> picks(Group g) {
        return new LinkedHashMap<>(g.picks);
    }

    public synchronized int maxAtOnce() {
        return maxAtOnce;
    }

    public synchronized void maxAtOnce(int n) {
        if (n != maxAtOnce) {
            maxAtOnce = Math.max(0, n);
            save();
        }
    }

    private void load() {
        String content = NFileUtils.readWithBackupFallback(NUtils.getDataFile(FILE));
        if (content == null || content.isEmpty())
            return;
        try {
            JSONObject main = new JSONObject(content);
            JSONArray ga = main.optJSONArray("groups");
            if (ga != null) {
                for (int i = 0; i < ga.length(); i++) {
                    JSONObject go = ga.optJSONObject(i);
                    if (go == null)
                        continue;
                    String name = go.optString("name", "").trim();
                    if (name.isEmpty() || byName(name) != null)
                        continue;
                    Group g = new Group(name);
                    JSONArray pa = go.optJSONArray("picks");
                    if (pa != null) {
                        for (int j = 0; j < pa.length(); j++) {
                            JSONObject po = pa.optJSONObject(j);
                            if (po == null)
                                continue;
                            String acc = po.optString("account", "");
                            String chr = po.optString("character", "");
                            if (!acc.isEmpty() && !chr.isEmpty())
                                g.picks.put(acc, chr);
                        }
                    }
                    groups.add(g);
                }
            }
            selected = main.optInt("selected", 0);
            maxAtOnce = Math.max(0, main.optInt("max", 0));
        } catch (JSONException e) {
            System.err.println("[LoginGroups] corrupt " + FILE + ", starting empty: " + e.getMessage());
            groups.clear();
        }
    }

    private void save() {
        JSONObject main = new JSONObject();
        main.put("version", 1);
        JSONArray ga = new JSONArray();
        for (Group g : groups) {
            JSONObject go = new JSONObject();
            go.put("name", g.name);
            /* An array of pairs, so the tick order survives the round trip. */
            JSONArray pa = new JSONArray();
            for (Map.Entry<String, String> e : g.picks.entrySet())
                pa.put(new JSONObject().put("account", e.getKey()).put("character", e.getValue()));
            go.put("picks", pa);
            ga.put(go);
        }
        main.put("groups", ga);
        main.put("selected", selected);
        main.put("max", maxAtOnce);
        try {
            NFileUtils.writeAtomically(NUtils.getDataFile(FILE), main.toString());
        } catch (IOException e) {
            System.err.println("[LoginGroups] failed to save " + FILE + ": " + e.getMessage());
        }
    }
}
