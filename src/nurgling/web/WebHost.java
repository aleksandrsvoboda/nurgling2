package nurgling.web;

import haven.Bootstrap;
import haven.Config;
import haven.MainFrame;
import haven.Utils;
import nurgling.NConfig;
import nurgling.conf.NLoginData;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * The LAN portal: pick a saved account (or log in with a password), and the host starts one
 * headless {@link WebSeat} process for it and forwards {@code /seat/<account>/...} to it.
 *
 * Seats run on the desktop client's own profile: the same "Haven and Hearth" folder (nurgling's
 * settings, areas, bot settings) and the same preferences, so the client's saved accounts work
 * and a password login here is remembered there too. One host PIN, shown in this window, guards
 * everything: without it nobody on the network can use the saved accounts. A seat keeps running
 * when the browser goes away (bots keep working); it ends on logout, on Stop, or with the host.
 *
 * Run: java -cp hafen.jar;... nurgling.web.WebHost   (-Dnurgling.webhost.port=8090,
 * -Dnurgling.webhost.dir=..., -Dnurgling.webhost.xmx=1g). Never expose this port to the internet.
 */
public class WebHost implements WebServer.Handler {
    /** Saved logins are kept per auth server, under its host name, as the login screen does. */
    static final String CONFNAME = "game.havenandhearth.com";
    private final Path dir;
    private final Map<String, Seat> seats = new HashMap<>();
    private final String xmx;
    private final String pin;

    static class Seat {
	final String acct, name;
	final Path dir;
	volatile Process proc;
	volatile int port;
	volatile String key;
	volatile String state = "stopped";

	Seat(String acct, String name, Path dir) {this.acct = acct; this.name = name; this.dir = dir;}

	boolean alive() {return((proc != null) && proc.isAlive());}
    }

    public WebHost(Path dir, String xmx, String pin) {
	this.dir = dir;
	this.xmx = xmx;
	this.pin = pin;
    }

    /** Settings a headless process must have before anything haven loads: no screen to measure, and
     *  never the renderer choice (a killed process would leave a "Vulkan crashed" marker behind and
     *  the desktop client would fall back to OpenGL). */
    static void headlessprops() {
	if(System.getProperty("haven.uiscale") == null)
	    System.setProperty("haven.uiscale", "1.0");
	if(System.getProperty("haven.toolkit") == null)
	    System.setProperty("haven.toolkit", "none");
    }

    public static void main(String[] args) throws IOException {
	headlessprops();
	nurgling.headless.Headless.setHeadless(true);
	MainFrame.setupres();
	NConfig nconfig = new NConfig();
	nconfig.read();
	MainFrame.config = nconfig;
	Path base = Optional.ofNullable(System.getProperty("nurgling.webhost.dir")).map(Paths::get)
	    .orElseGet(() -> Config.localdir().resolve("webhost"));
	Files.createDirectories(base);
	Integer asked = Integer.getInteger("nurgling.webhost.port");
	String bind = System.getProperty("nurgling.webhost.bind", "0.0.0.0");
	WebHost host = new WebHost(base, System.getProperty("nurgling.webhost.xmx", "1g"), hostpin(base));
	Runtime.getRuntime().addShutdownHook(new Thread(host::stopall, "Web host shutdown"));
	int idle = Integer.getInteger("nurgling.webhost.idle", 0);
	if(idle > 0) {
	    Thread reaper = new Thread(() -> host.reaploop(idle), "Web host idle seats");
	    reaper.setDaemon(true);
	    reaper.start();
	    System.out.println("Seats with nobody watching and no bot running stop after " + idle + " minutes.");
	}
	WebServer srv = null;
	/* Other programs like 8090 too; without an explicit port, take the next free one. */
	int first = (asked != null) ? asked : 8090, last = (asked != null) ? asked : 8110;
	for(int port = first; srv == null; port++) {
	    try {
		srv = new WebServer(InetAddress.getByName(bind), port, host);
	    } catch(BindException e) {
		if(port >= last)
		    throw(new BindException("port " + port + " is in use; pick another with -Dnurgling.webhost.port=<port>"));
		System.out.println("Port " + port + " is in use by another program, trying " + (port + 1));
	    }
	}
	System.out.println("Nurgling web host is running (profiles in " + base + "). Open on a phone, tablet or PC:");
	for(String a : lanaddrs()) {
	    int sp = a.indexOf(' ');
	    System.out.println("  http://" + a.substring(0, sp) + ":" + srv.port() + "/" + a.substring(sp));
	}
	System.out.println();
	System.out.println("  Host PIN: " + host.pin + "   (asked once per device; it unlocks your saved accounts)");
	System.out.println();
	System.out.println("LAN only. Do not forward this port to the internet.");
	synchronized(host) {
	    try {
		while(true)
		    host.wait();
	    } catch(InterruptedException e) {
	    }
	}
    }

    /** Every private IPv4 address of this PC: Wi-Fi, Ethernet, and also VPN adapters, so all are listed. */
    private static List<String> lanaddrs() {
	List<String> ret = new ArrayList<>();
	try {
	    for(NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
		if(!nif.isUp() || nif.isLoopback() || nif.isVirtual())
		    continue;
		for(InetAddress a : Collections.list(nif.getInetAddresses())) {
		    if((a instanceof Inet4Address) && a.isSiteLocalAddress())
			ret.add(a.getHostAddress() + "  (" + nif.getDisplayName() + ")");
		}
	    }
	} catch(SocketException e) {
	}
	if(ret.isEmpty())
	    ret.add("localhost");
	return(ret);
    }

    /* ---- routing ---- */

    public WebServer.Upstream upstream(WebServer.Request req) {
	if(!req.path.startsWith("/seat/"))
	    return(null);
	String rest = req.path.substring(6);
	int sl = rest.indexOf('/');
	if(sl < 0)
	    return(null);
	String acct = rest.substring(0, sl);
	Seat s;
	synchronized(seats) {
	    s = seats.get(acct);
	}
	if((s == null) || !s.alive() || !"running".equals(s.state))
	    return(null);
	String target = rest.substring(sl);
	if(req.rawquery != null)
	    target += "?" + req.rawquery;
	return(new WebServer.Upstream(s.port, target));
    }

    public boolean open(WebServer.Request req, WsConn conn) {
	return(false);
    }

    public WebServer.Response http(WebServer.Request req) throws IOException {
	String p = req.path;
	if(p.equals("/") || p.equals("/index.html"))
	    return(WebServer.asset("host.html"));
	if(p.startsWith("/seat/") && !p.endsWith("/") && (p.indexOf('/', 6) < 0))
	    return(new WebServer.Response(302, "text/plain", new byte[0]).header("Location", p + "/"));
	if(p.startsWith("/seat/"))
	    return(WebServer.Response.text(503, "That seat is not running. Log in again from the portal."));
	if(p.startsWith("/app/"))
	    return(WebServer.asset(p.substring(5)));
	if(p.equals("/api/accounts") && req.method.equals("POST"))
	    return(WebServer.Response.json(accounts(body(req)).toString()));
	if(p.equals("/api/login") && req.method.equals("POST"))
	    return(WebServer.Response.json(login(body(req)).toString()));
	if(p.equals("/api/stop") && req.method.equals("POST"))
	    return(WebServer.Response.json(stop(body(req)).toString()));
	return(null);
    }

    private static JSONObject body(WebServer.Request req) {
	try {
	    return(new JSONObject(new String(req.body, StandardCharsets.UTF_8)));
	} catch(JSONException e) {
	    return(new JSONObject());
	}
    }

    private boolean pinok(JSONObject req) {
	return(WebServer.keyeq(pin, req.optString("pin").trim()));
    }

    /** The desktop client's saved accounts (names only), with which of them run as seats now. */
    private JSONObject accounts(JSONObject req) {
	if(!pinok(req))
	    return(err("Wrong host PIN. It is shown in the web host window on the PC."));
	JSONArray out = new JSONArray();
	for(Map.Entry<String, String> a : saved().entrySet()) {
	    Seat s;
	    synchronized(seats) {
		s = seats.get(acctid(a.getKey()));
	    }
	    boolean on = (s != null) && s.alive() && "running".equals(s.state);
	    out.put(new JSONObject().put("name", a.getKey()).put("running", on));
	}
	return(new JSONObject().put("ok", true).put("accounts", out));
    }

    /** Saved logins, read the way the login screen reads them and never written: haven's token
     *  store, most recent first, then legacy nurgling entries with a password. Value: the legacy
     *  password, or null for a token. */
    static LinkedHashMap<String, String> saved() {
	LinkedHashMap<String, String> ret = new LinkedHashMap<>();
	for(String nm : Utils.getprefsl("saved-tokens@" + CONFNAME, new String[] {})) {
	    if(Bootstrap.gettoken(nm, CONFNAME) != null)
		ret.put(nm, null);
	}
	Object legacy = NConfig.get(NConfig.Key.credentials);
	if(legacy instanceof List) {
	    for(Object o : (List<?>)legacy) {
		if(!(o instanceof NLoginData))
		    continue;
		NLoginData d = (NLoginData)o;
		if((d.name != null) && !d.name.isEmpty() && !d.isTokenUsed && (d.pass != null) && !d.pass.isEmpty() && !ret.containsKey(d.name))
		    ret.put(d.name, d.pass);
	    }
	}
	return(ret);
    }

    /* ---- login: start or rejoin a seat ---- */

    private static String acctid(String acct) {
	return(acct.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_"));
    }

    private JSONObject login(JSONObject req) {
	if(!pinok(req))
	    return(err("Wrong host PIN. It is shown in the web host window on the PC."));
	String acct = req.optString("acct").trim();
	String pass = req.optString("pass");
	if(acct.isEmpty())
	    return(err("Enter your account name."));
	if(pass.isEmpty()) {
	    Map<String, String> sv = saved();
	    if(!sv.containsKey(acct))
		return(err("That account is not saved in the client. Enter its password; it is remembered after that."));
	    if(sv.get(acct) != null)
		pass = sv.get(acct);
	}
	String id = acctid(acct);
	Seat s;
	synchronized(seats) {
	    s = seats.get(id);
	    if(s == null)
		seats.put(id, s = new Seat(id, acct, dir.resolve("seats").resolve(id)));
	}
	synchronized(s) {
	    if(s.alive() && "running".equals(s.state))
		return(ok(s));
	    try {
		Files.createDirectories(s.dir);
	    } catch(IOException e) {
		return(err("Cannot create the seat folder: " + e.getMessage()));
	    }
	    String e = start(s, acct, pass);
	    if(e != null)
		return(err(e));
	    return(ok(s));
	}
    }

    private JSONObject stop(JSONObject req) {
	if(!pinok(req))
	    return(err("Wrong host PIN."));
	Seat s;
	synchronized(seats) {
	    s = seats.get(acctid(req.optString("acct")));
	}
	if(s == null)
	    return(err("No such seat."));
	synchronized(s) {
	    if(s.proc != null)
		s.proc.destroy();
	    s.state = "stopped";
	}
	return(new JSONObject().put("ok", true));
    }

    private static JSONObject ok(Seat s) {
	return(new JSONObject().put("ok", true).put("url", "/seat/" + s.acct + "/#k=" + s.key));
    }

    private static JSONObject err(String msg) {
	return(new JSONObject().put("ok", false).put("err", msg));
    }

    /* ---- seat processes ---- */

    private String start(Seat s, String acct, String pass) {
	int port;
	try(ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
	    port = probe.getLocalPort();
	} catch(IOException e) {
	    return("No free port: " + e.getMessage());
	}
	String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
	List<String> cmd = new ArrayList<>();
	cmd.add(java);
	cmd.add("-Xmx" + xmx);
	for(String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
	    /* Same profile as the host and the desktop client, so -Dhaven.* (prefspec included) passes on. */
	    if(a.startsWith("--add-") || a.startsWith("-Djava.library.path") || a.startsWith("-Dnurgling.web.server=") || a.startsWith("-Dhaven."))
		cmd.add(a);
	}
	cmd.add("-Dnurgling.web.port=" + port);
	cmd.add("-Dnurgling.web.parent=" + ProcessHandle.current().pid());
	cmd.add("-cp");
	cmd.add(System.getProperty("java.class.path"));
	cmd.add(WebSeat.class.getName());
	ProcessBuilder pb = new ProcessBuilder(cmd);
	String key = WebServer.newkey();
	Map<String, String> env = pb.environment();
	env.put("NURGLING_WEB_USER", acct);
	env.put("NURGLING_WEB_PASS", pass);
	env.put("NURGLING_WEB_KEY", key);
	/* Never pipes: an unread pipe fills up and freezes a long-lived child. */
	pb.redirectErrorStream(true);
	pb.redirectOutput(ProcessBuilder.Redirect.to(s.dir.resolve("seat.log").toFile()));
	Process proc;
	try {
	    proc = pb.start();
	} catch(IOException e) {
	    return("Cannot start the seat: " + e.getMessage());
	}
	s.proc = proc;
	s.port = port;
	s.key = key;
	s.state = "starting";
	/* Wait until it answers, or exits (a wrong password exits with WebSeat.EXIT_AUTH). */
	long end = System.currentTimeMillis() + 90000;
	while(System.currentTimeMillis() < end) {
	    try {
		if(proc.waitFor(300, TimeUnit.MILLISECONDS)) {
		    s.state = "stopped";
		    if(proc.exitValue() == WebSeat.EXIT_AUTH)
			return("Login failed: check the account name and password.");
		    if(proc.exitValue() == WebSeat.EXIT_CONN)
			return("Could not reach the game's login server, or no saved login; see " + s.dir.resolve("seat.log"));
		    return("The seat exited (code " + proc.exitValue() + "); see " + s.dir.resolve("seat.log"));
		}
	    } catch(InterruptedException e) {
		Thread.currentThread().interrupt();
		return("Interrupted.");
	    }
	    if(healthy(port)) {
		s.state = "running";
		return(null);
	    }
	}
	proc.destroy();
	s.state = "stopped";
	return("The seat did not start in time; see " + s.dir.resolve("seat.log"));
    }

    /** Stops seats nobody has watched for {@code minutes} while no browser-started bot runs. */
    private void reaploop(int minutes) {
	Map<String, Long> idlesince = new HashMap<>();
	while(true) {
	    synchronized(this) {
		try {
		    wait(60000);
		} catch(InterruptedException e) {
		    return;
		}
	    }
	    List<Seat> cur;
	    synchronized(seats) {
		cur = new ArrayList<>(seats.values());
	    }
	    long now = System.currentTimeMillis();
	    for(Seat s : cur) {
		if(!s.alive() || !"running".equals(s.state)) {
		    idlesince.remove(s.acct);
		    continue;
		}
		JSONObject h = health(s.port);
		boolean idle = (h != null) && (h.optInt("viewers") == 0) && (h.optInt("bots") == 0);
		if(!idle) {
		    idlesince.remove(s.acct);
		} else {
		    long since = idlesince.computeIfAbsent(s.acct, k -> now);
		    if(now - since >= minutes * 60000L) {
			System.out.println("web host: stopping idle seat " + s.acct);
			s.proc.destroy();
			s.state = "stopped";
			idlesince.remove(s.acct);
		    }
		}
	    }
	}
    }

    private static JSONObject health(int port) {
	try {
	    HttpURLConnection c = (HttpURLConnection)URI.create("http://127.0.0.1:" + port + "/health").toURL().openConnection();
	    c.setConnectTimeout(1000);
	    c.setReadTimeout(2000);
	    try(InputStream in = c.getInputStream()) {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] b = new byte[1024];
		for(int n; (n = in.read(b)) > 0;)
		    buf.write(b, 0, n);
		return(new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8)));
	    } finally {
		c.disconnect();
	    }
	} catch(IOException | JSONException e) {
	    return(null);
	}
    }

    private static boolean healthy(int port) {
	try {
	    HttpURLConnection c = (HttpURLConnection)URI.create("http://127.0.0.1:" + port + "/health").toURL().openConnection();
	    c.setConnectTimeout(500);
	    c.setReadTimeout(1000);
	    int st = c.getResponseCode();
	    c.disconnect();
	    return(st == 200);
	} catch(IOException e) {
	    return(false);
	}
    }

    private void stopall() {
	synchronized(seats) {
	    for(Seat s : seats.values()) {
		if(s.proc != null)
		    s.proc.destroy();
	    }
	}
    }

    /* ---- the host PIN: one for the whole host, shown in its window ---- */

    private static String hostpin(Path base) throws IOException {
	String fixed = System.getProperty("nurgling.webhost.pin");
	if((fixed != null) && !fixed.trim().isEmpty())
	    return(fixed.trim());
	Path f = base.resolve("host-pin.txt");
	if(Files.exists(f)) {
	    String p = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim();
	    if(!p.isEmpty())
		return(p);
	}
	String p = String.format("%06d", new java.security.SecureRandom().nextInt(1000000));
	Files.write(f, p.getBytes(StandardCharsets.UTF_8));
	return(p);
    }
}
