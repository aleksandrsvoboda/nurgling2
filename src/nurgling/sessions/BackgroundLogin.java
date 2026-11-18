package nurgling.sessions;

import haven.*;
import nurgling.NUI;
import nurgling.conf.NCharTags;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 * Logs an account in without the login screen: the same authentication and connection steps
 * {@link Bootstrap} runs behind the login form, then a UI for the new session that is built off the
 * window's loop and runs headless from the start, like a session that was switched away from. The
 * window does not change; the session shows up in the session tabs and can be switched to as usual.
 *
 * Several of these can run at once, one thread each.
 */
public final class BackgroundLogin {

    /** A login that did not get as far as a session; the message is for the user. */
    public static final class Failed extends Exception {
        /** True when the server refused the saved login (it is then forgotten, as the login screen does). */
        public final boolean refused;

        Failed(String msg, boolean refused) {
            super(msg);
            this.refused = refused;
        }
    }

    private BackgroundLogin() {
    }

    /** The auth server name the login screen keys its saved logins by. */
    public static String confname() {
        return Bootstrap.authserv.get().host;
    }

    /**
     * Logs {@code account} in with its saved login token and starts its session in the background.
     *
     * @param loop the window's loop: the new UI shares its window, audio and renderer, so switching to
     *             the session later just shows it
     */
    public static SessionContext login(UILoop loop, String account) throws Failed, InterruptedException {
        NamedSocketAddress server = Bootstrap.authserv.get();
        String confname = server.host;
        byte[] token = Bootstrap.gettoken(account, confname);
        if (token == null)
            throw new Failed("no saved login - log in once by hand with Remember me", false);
        NamedSocketAddress defserv = new NamedSocketAddress(server.host, Bootstrap.gameport.get());

        Session.User acct;
        byte[] cookie;
        List<NamedSocketAddress> hosts;
        SocketAddress authaddr;
        try (AuthClient auth = new AuthClient(server)) {
            authaddr = auth.address();
            AuthClient.Credentials creds = new AuthClient.TokenCred(account, token);
            try {
                acct = creds.tryauth(auth);
            } catch (AuthClient.Credentials.AuthException e) {
                /* The login screen forgets a refused token too; it can't work again. */
                Bootstrap.settoken(account, confname, null);
                throw new Failed("the server refused the saved login (" + e.getMessage() + ") - log in once by hand with Remember me", true);
            }
            cookie = auth.getcookie();
            if (Connection.encrypt.get())
                acct.alias(auth.getalias());
            hosts = auth.gethosts(defserv);
        } catch (IOException e) {
            throw new Failed("could not reach the login server: " + e.getMessage(), false);
        }
        if (Thread.interrupted())
            throw new InterruptedException();

        Session sess = null;
        try {
            for (InetSocketAddress addr : Bootstrap.gameaddrs(server, defserv, hosts, authaddr)) {
                try {
                    sess = Session.connect(addr, acct, Connection.encrypt.get(), cookie);
                    break;
                } catch (Connection.SessionConnError e) {
                    /* Not reachable at this address; try the next, as the login screen does. */
                }
            }
        } catch (Connection.SessionError e) {
            throw new Failed(e.getMessage(), false);
        } catch (IOException e) {
            throw new Failed("could not locate the game server", false);
        }
        if (sess == null)
            throw new Failed("could not connect to the game server", false);

        NRemoteUI.ensureLoopHooks(loop);
        NRemoteUI rui = new NRemoteUI(sess, true);
        NUI ui = new NUI(loop.wnd, loop.audioroot(), new Coord(loop.wnd.size()), rui);
        /* What a session switched away from still has: its loop and the window's renderer. Views
         * only bind to the renderer when first drawn. */
        ui.loop = loop;
        ui.env = loop.env;
        SessionContext ctx = rui.startBackground(ui);
        NCharTags.setUsed(account, System.currentTimeMillis());
        return ctx;
    }
}
