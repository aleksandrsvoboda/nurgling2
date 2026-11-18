package nurgling.sessions.multilogin;

import haven.UILoop;

import nurgling.conf.NSavedAccounts;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Log in to several": the saved groups and the run in progress. A run outlives the login screen it
 * was started from (the window switches into a session halfway through), so it is kept here, on the
 * {@link nurgling.sessions.SessionManager}, rather than in a widget. A finished run stays until the
 * next one starts, so its outcome can still be read.
 */
public final class MultiLogin {

    private LoginGroups groups;
    private volatile LoginRun run;

    /** Created on first use: the data folder isn't known when the session manager is. */
    public synchronized LoginGroups groups() {
        if (groups == null)
            groups = new LoginGroups();
        return groups;
    }

    /** The current or last run, or null. */
    public LoginRun run() {
        return run;
    }

    public boolean running() {
        LoginRun r = run;
        return (r != null) && r.running();
    }

    /** Logs every account of {@code picks} in at once and plays its character. */
    public synchronized LoginRun start(UILoop loop, Map<String, String> picks) {
        List<LoginRun.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, String> e : picks.entrySet())
            entries.add(LoginRun.entry(e.getKey(), e.getValue()));
        return begin(new LoginRun(loop, entries, groups().maxAtOnce()));
    }

    /** Logs the accounts in only to read their character lists, then closes them again. */
    public synchronized LoginRun readCharacters(UILoop loop, List<String> accounts) {
        List<LoginRun.Entry> entries = new ArrayList<>();
        for (String a : accounts)
            entries.add(LoginRun.entry(a, null));
        return begin(new LoginRun(loop, entries, groups().maxAtOnce()));
    }

    private LoginRun begin(LoginRun r) {
        if (running())
            throw new IllegalStateException("a login run is already going");
        run = r;
        r.start();
        return r;
    }

    /** The group's picks that can run now: accounts with a saved login token that aren't logged in already. */
    public Map<String, String> runnable(LoginGroups.Group g, String confname) {
        Set<String> online = new HashSet<>();
        for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            if (ctx.username != null && ctx.isConnected())
                online.add(ctx.username.toLowerCase(Locale.ROOT));
        }
        Map<String, Boolean> token = new HashMap<>();
        for (NSavedAccounts.Account a : NSavedAccounts.list(confname))
            token.put(a.name, a.token);
        Map<String, String> ret = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : groups().picks(g).entrySet()) {
            if (Boolean.TRUE.equals(token.get(e.getKey())) && !online.contains(e.getKey().toLowerCase(Locale.ROOT)))
                ret.put(e.getKey(), e.getValue());
        }
        return ret;
    }

    public void stop() {
        LoginRun r = run;
        if (r != null)
            r.stop();
    }
}
