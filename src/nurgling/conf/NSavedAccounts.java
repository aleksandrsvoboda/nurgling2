package nurgling.conf;

import haven.Bootstrap;
import haven.Utils;
import nurgling.NConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Saved logins for the login screen. Only haven's own login tokens are written from now on (the
 * same store "Remember me" has always used); the old nurgling {@link NLoginData} list is legacy and
 * only read, so entries saved by older versions keep working until their account logs in once and
 * receives a token, at which point the plaintext password is dropped.
 */
public class NSavedAccounts {
    public static class Account {
        public final String name;
        /** true: a haven login token is stored; false: a legacy plaintext password. */
        public final boolean token;
        /** Legacy password, only for {@code token == false}. */
        public final String pass;
        /** Last successful login from this client (epoch ms), 0 if unknown. */
        public final long used;

        public Account(String name, boolean token, String pass, long used) {
            this.name = name;
            this.token = token;
            this.pass = pass;
            this.used = used;
        }
    }

    /**
     * Moves tokens that older versions kept in the nurgling list into haven's token store, then drops
     * every legacy entry whose account now has a haven token.
     */
    public static void migrate(String confname) {
        ArrayList<NLoginData> legacy = legacy();
        boolean changed = false;
        for (Iterator<NLoginData> it = legacy.iterator(); it.hasNext(); ) {
            NLoginData d = it.next();
            if ((d.name == null) || d.name.isEmpty()) {
                it.remove();
                changed = true;
                continue;
            }
            if ((Bootstrap.gettoken(d.name, confname) == null) && d.isTokenUsed && (d.token != null) && (d.token.length > 0))
                Bootstrap.settoken(d.name, confname, d.token);
            if (Bootstrap.gettoken(d.name, confname) != null) {
                it.remove();
                changed = true;
            }
        }
        if (changed)
            NConfig.set(NConfig.Key.credentials, legacy);
    }

    /** Saved accounts in the order they were first saved here. */
    public static List<Account> list(String confname) {
        List<Account> ret = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        /* Haven keeps its token list most-recent-first, so walking it backwards gets the oldest
         * account first - the closest thing to "order added" the first time this runs. */
        List<String> tokens = new ArrayList<>(Utils.getprefsl("saved-tokens@" + confname, new String[]{}));
        Collections.reverse(tokens);
        for (String nm : tokens) {
            if ((Bootstrap.gettoken(nm, confname) != null) && seen.add(nm))
                ret.add(new Account(nm, true, null, NCharTags.used(nm)));
        }
        for (NLoginData d : legacy()) {
            if (!d.isTokenUsed && (d.pass != null) && !d.pass.isEmpty() && seen.add(d.name))
                ret.add(new Account(d.name, false, d.pass, NCharTags.used(d.name)));
        }
        /* accindex appends names it has not seen before, so this both sorts and records the order. */
        ret.sort(Comparator.comparingInt(a -> NCharTags.accindex(a.name)));
        return (ret);
    }

    /** Persists a new display order for the saved accounts, as dragged on the login screen. */
    public static void reorder(List<String> names) {
        NCharTags.setAccOrder(names);
    }

    /** Forgets an account everywhere: haven's token and any legacy entry. */
    public static void remove(String confname, String name) {
        Bootstrap.settoken(name, confname, null);
        ArrayList<NLoginData> legacy = legacy();
        if (legacy.removeIf(d -> name.equals(d.name)))
            NConfig.set(NConfig.Key.credentials, legacy);
        NCharTags.forgetUsed(name);
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<NLoginData> legacy() {
        Object o = NConfig.get(NConfig.Key.credentials);
        return ((o instanceof ArrayList) ? (ArrayList<NLoginData>) o : new ArrayList<>());
    }
}
