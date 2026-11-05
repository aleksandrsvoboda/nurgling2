package nurgling.web;

import haven.*;
import nurgling.NConfig;
import nurgling.headless.Headless;
import nurgling.headless.NHeadlessLoop;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * One player's seat: a headless client (no window, no GPU) whose state is published to the
 * browser by a {@link WebBridge}. Started by {@link WebHost}, one process per seat.
 *
 * Configuration comes from the environment so secrets never appear on a command line:
 * NURGLING_WEB_USER, NURGLING_WEB_PASS (optional when the client has a saved token), NURGLING_WEB_KEY.
 * Properties: nurgling.web.port, nurgling.web.bind (default 127.0.0.1), nurgling.web.server.
 * Logins use the desktop client's token store, and a password login saves a token there, as
 * "Remember me" does.
 * The character is picked in the browser from the character list.
 */
public class WebSeat {
    public static final int EXIT_OK = 0, EXIT_AUTH = 5, EXIT_CONFIG = 4, EXIT_CONN = 3;

    public static void main(String[] args) throws InterruptedException {
	WebHost.headlessprops();
	Headless.setHeadless(true);
	/* A seat never outlives its web host: however the host ends (window closed, killed), the seat goes too. */
	Long parent = Long.getLong("nurgling.web.parent");
	if(parent != null) {
	    Optional<ProcessHandle> ph = ProcessHandle.of(parent);
	    if(!ph.isPresent() || !ph.get().isAlive())
		System.exit(EXIT_OK);
	    ph.get().onExit().thenRun(() -> {
		    System.out.println("web seat: the web host is gone; stopping");
		    System.exit(EXIT_OK);
		});
	}
	MainFrame.setupres();
	NConfig nconfig = new NConfig();
	nconfig.read();
	MainFrame.config = nconfig;
	Widget.initnames();

	String user = System.getenv("NURGLING_WEB_USER");
	String pass = System.getenv("NURGLING_WEB_PASS");
	String key = System.getenv("NURGLING_WEB_KEY");
	String server = System.getProperty("nurgling.web.server", "game.havenandhearth.com");
	int port = Integer.getInteger("nurgling.web.port", 0);
	String bind = System.getProperty("nurgling.web.bind", "127.0.0.1");
	if((user == null) || user.isEmpty()) {
	    System.err.println("web seat: NURGLING_WEB_USER is not set");
	    System.exit(EXIT_CONFIG);
	}
	if((key == null) || key.isEmpty()) {
	    key = WebServer.newkey();
	    System.out.println("web seat: access key " + key);
	}

	Login login;
	try {
	    login = login(server, user, pass);
	} catch(AuthClient.Credentials.AuthException e) {
	    System.err.println("web seat: login refused: " + e.getMessage());
	    System.exit(EXIT_AUTH);
	    return;
	} catch(IOException e) {
	    System.err.println("web seat: cannot log in: " + e.getMessage());
	    System.exit(EXIT_CONN);
	    return;
	}
	System.out.println("web seat: logged in as " + login.user.name);

	final UI[] cur = {null};
	WebBridge bridge = new WebBridge(() -> cur[0], key);
	try {
	    WebServer ws = bridge.listen(InetAddress.getByName(bind), port);
	    System.out.println("web seat: listening on " + bind + ":" + ws.port());
	} catch(IOException e) {
	    System.err.println("web seat: cannot listen: " + e.getMessage());
	    System.exit(EXIT_CONFIG);
	}

	int code = EXIT_OK;
	try {
	    Session sess = Session.connect(new InetSocketAddress(InetAddress.getByName(server), 1870), login.user, Connection.encrypt.get(), login.cookie);
	    RemoteUI rui = new RemoteUI(sess);
	    NHeadlessLoop loop = new NHeadlessLoop(Coord.of(1600, 900));
	    loop.start();
	    try {
		UI.Runner task = rui;
		while(task != null) {
		    UI ui = loop.newui(task);
		    cur[0] = ui;
		    register(sess, ui);
		    task = task.run(ui);
		}
	    } finally {
		nurgling.sessions.SessionContext ctx = nurgling.sessions.SessionManager.getInstance().findBySession(sess);
		if(ctx != null)
		    nurgling.sessions.SessionManager.getInstance().removeSessionSilently(ctx.sessionId);
		cur[0] = null;
		loop.newui(null);
		loop.dispose();
	    }
	    System.out.println("web seat: session ended");
	} catch(Connection.SessionError e) {
	    System.err.println("web seat: session error: " + e.getMessage());
	    code = EXIT_CONN;
	} catch(IOException e) {
	    System.err.println("web seat: connection error: " + e.getMessage());
	    code = EXIT_CONN;
	}
	bridge.stop();
	System.exit(code);
    }

    /** The seat's session is a nurgling session like a desktop one: area, planning and the other
     *  database syncs walk SessionManager's sessions, so an unregistered session gets no areas. */
    private static void register(Session sess, UI ui) {
	if(!(ui instanceof nurgling.NUI))
	    return;
	nurgling.sessions.SessionManager sm = nurgling.sessions.SessionManager.getInstance();
	nurgling.sessions.SessionContext ctx = sm.findBySession(sess);
	if(ctx == null)
	    sm.addSessionAsActive(sess, (nurgling.NUI)ui);
	else
	    ctx.ui = (nurgling.NUI)ui;
    }

    static class Login {
	final Session.User user;
	final byte[] cookie;

	Login(Session.User user, byte[] cookie) {this.user = user; this.cookie = cookie;}
    }

    /** Password login when a password is given (a token is then saved in the client's store, as
     *  "Remember me" does), else the client's saved token for this account. */
    static Login login(String server, String user, String pass) throws IOException {
	AuthClient cl = new AuthClient(new NamedSocketAddress(server, 1871));
	try {
	    AuthClient.Credentials cred;
	    boolean havepass = (pass != null) && !pass.isEmpty();
	    byte[] tok = havepass ? null : Bootstrap.gettoken(user, WebHost.CONFNAME);
	    if(havepass)
		cred = new AuthClient.NativeCred(user, pass);
	    else if(tok != null)
		cred = new AuthClient.TokenCred(user, tok);
	    else
		throw(new IOException("no password and no saved login for " + user));
	    Session.User acct = cred.tryauth(cl);
	    byte[] cookie = cl.getcookie();
	    if(havepass) {
		try {
		    Bootstrap.settoken(cred.authname(), WebHost.CONFNAME, cl.gettoken());
		} catch(IOException | RuntimeException e) {
		    /* Without a token the next login just asks for the password again. */
		    System.err.println("web seat: could not save a login token: " + e.getMessage());
		}
	    }
	    return(new Login(acct, cookie));
	} finally {
	    cl.close();
	}
    }
}
