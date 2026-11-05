package nurgling.web;

import haven.*;
import nurgling.plugins.UiEvents;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The state bridge of one client: publishes the UI and the world to browsers over a WebSocket
 * and applies their commands. Works on a headless seat and on the normal desktop client alike.
 *
 * One flush thread computes the current state about ten times a second (sooner after server
 * traffic), diffs it against what was sent, and broadcasts only the changes. A browser that
 * connects gets the full current state first.
 */
public class WebBridge implements WebServer.Handler, UiEvents.Listener {
    private static final long PERIOD = 100, MINGAP = 40;
    private final Supplier<UI> uis;
    public final String key;
    private final UiMirror mirror = new UiMirror();
    private final WorldFeed world = new WorldFeed();
    private final WebOps ops = new WebOps();
    private final CommandGate gate = new CommandGate(mirror, ops);
    private final ResServer res = new ResServer();
    private final MapTiles maptiles;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final Thread flusher;
    private WebServer server;
    private volatile boolean stopped = false;
    private boolean triggered = false;
    /* Flush-thread state: what every connected browser has been sent. */
    private Map<String, String> sentui = new HashMap<>();
    private Map<Long, String> sentgobs = new HashMap<>();
    private Map<Long, WorldFeed.GridMsg> sentgrids = new HashMap<>();
    private UI lastui = null;

    private static class Client {
	final WsConn conn;
	volatile boolean fresh = true;

	Client(WsConn conn) {this.conn = conn;}
    }

    public WebBridge(Supplier<UI> uis, String key) {
	this.uis = uis;
	this.key = key;
	this.maptiles = new MapTiles(uis);
	UiEvents.addListener(this);
	flusher = new Thread(this::flushloop, "Web bridge flush");
	flusher.setDaemon(true);
	flusher.start();
    }

    public WebServer listen(InetAddress bind, int port) throws IOException {
	server = new WebServer(bind, port, this);
	return(server);
    }

    public void stop() {
	stopped = true;
	UiEvents.removeListener(this);
	if(server != null)
	    server.close();
	for(Client c : clients)
	    c.conn.close();
	trigger();
    }

    public boolean viewed() {return(!clients.isEmpty());}

    /* ---- HTTP / WebSocket ---- */

    public WebServer.Response http(WebServer.Request req) throws IOException {
	String p = req.path;
	if(p.equals("/") || p.equals("/index.html"))
	    return(WebServer.asset("index.html"));
	if(p.startsWith("/app/"))
	    return(WebServer.asset(p.substring(5)));
	if(p.equals("/res/img"))
	    return(res.image(req.q("n")));
	if(p.equals("/map/grid"))
	    return(maptiles.grid(req.q("s"), req.q("l"), req.q("x"), req.q("y")));
	if(p.equals("/health"))
	    return(WebServer.Response.json(new JSONObject().put("ok", true).put("viewers", clients.size()).put("bots", ops.running())
					   .put("ingame", (uis.get() != null) && (uis.get().gui != null)).toString()));
	return(null);
    }

    public boolean wsallowed(WebServer.Request req) {
	return(req.path.equals("/ws") && WebServer.keyeq(key, req.q("k")));
    }

    public boolean open(WebServer.Request req, WsConn conn) {
	if(!wsallowed(req))
	    return(false);
	Client cl = new Client(conn);
	conn.onmessage(m -> message(cl, m));
	conn.onclose(() -> {
		clients.remove(cl);
	    });
	clients.add(cl);
	trigger();
	return(true);
    }

    private void message(Client cl, String m) {
	JSONObject cmd;
	try {
	    cmd = new JSONObject(m);
	} catch(JSONException e) {
	    return;
	}
	if(cmd.optString("t").equals("ping")) {
	    cl.conn.send(new JSONObject().put("t", "pong").put("ts", cmd.opt("ts")).toString());
	    return;
	}
	UI ui = uis.get();
	if(ui == null)
	    return;
	JSONObject reply = gate.apply(ui, cmd);
	if(reply != null)
	    cl.conn.send(reply.toString());
	trigger();
    }

    /* ---- UiEvents: server traffic makes the next flush come sooner ---- */

    public void onNewWidget(UI ui, int id, String type, Widget wdg, Object[] cargs) {
	mirror.noteType(id, type);
	poke(ui);
    }
    public void onAddWidget(UI ui, int id, Widget wdg, int parent, Widget pwdg, Object[] pargs) {poke(ui);}
    public void onDestroyWidget(UI ui, int id, Widget wdg) {
	mirror.forget(id);
	poke(ui);
    }
    public void onUiMsg(UI ui, int id, Widget wdg, String msg, Object[] args) {
	if((wdg instanceof GameUI) && (msg.equals("err") || msg.equals("msg")) && (args.length > 0) && (args[0] instanceof String) && (ui == uis.get()))
	    broadcast(new JSONObject().put("t", "toast").put("err", msg.equals("err")).put("text", args[0]).toString());
	poke(ui);
    }

    private void poke(UI ui) {
	if(!clients.isEmpty() && (ui == uis.get()))
	    trigger();
    }

    private void trigger() {
	synchronized(this) {
	    triggered = true;
	    notifyAll();
	}
    }

    private void broadcast(String msg) {
	for(Client c : clients) {
	    if(!c.fresh)
		c.conn.send(msg);
	}
    }

    /* ---- the flush loop ---- */

    private void flushloop() {
	long last = 0;
	while(!stopped) {
	    synchronized(this) {
		try {
		    long now = System.currentTimeMillis();
		    long wait = triggered ? Math.max(0, (last + MINGAP) - now) : Math.max(1, (last + PERIOD) - now);
		    if(clients.isEmpty())
			wait = 1000;
		    if(wait > 0)
			wait(wait);
		} catch(InterruptedException e) {
		    return;
		}
		triggered = false;
	    }
	    if(clients.isEmpty() || stopped)
		continue;
	    last = System.currentTimeMillis();
	    try {
		pass();
	    } catch(RuntimeException e) {
		/* A widget caught mid-change can throw while being read; the next pass sees it settled. */
		new Warning(e, "web bridge: flush failed").issue();
	    }
	}
    }

    private void pass() {
	UI ui = uis.get();
	if(ui != lastui) {
	    /* A new UI (login, character switch, reconnect): every browser starts over. */
	    sentui = new HashMap<>();
	    sentgobs = new HashMap<>();
	    sentgrids = new HashMap<>();
	    lastui = ui;
	    broadcast(new JSONObject().put("t", "reset").toString());
	}
	if(ui == null)
	    return;
	Map<String, String> curui = new LinkedHashMap<>();
	Map<String, JSONObject> snap;
	synchronized(ui) {
	    snap = mirror.snapshot(ui);
	}
	snap.put("bots", ops.status());
	JSONObject seg = MapTiles.where(ui);
	if(seg != null)
	    snap.put("seg", seg);
	for(Map.Entry<String, JSONObject> e : snap.entrySet())
	    curui.put(e.getKey(), e.getValue().toString());

	Map<Long, String> curgobs = new HashMap<>();
	Map<Long, WorldFeed.GridMsg> curgrids = sentgrids;
	GameUI gui = ui.gui;
	if((gui != null) && (gui.map != null)) {
	    for(Map.Entry<Long, JSONObject> e : world.gobs(gui.map).entrySet())
		curgobs.put(e.getKey(), e.getValue().toString());
	    curgrids = world.grids(gui.map.glob.map, sentgrids);
	} else {
	    curgrids = new HashMap<>();
	}

	/* Deltas for browsers that are up to date. */
	String uid = delta("ui", sentui, curui, true);
	String gd = delta("gobs", sentgobs, curgobs, false);
	List<String> gridup = new ArrayList<>();
	JSONArray griddel = new JSONArray();
	for(Map.Entry<Long, WorldFeed.GridMsg> e : curgrids.entrySet()) {
	    WorldFeed.GridMsg prev = sentgrids.get(e.getKey());
	    if((prev == null) || (prev.seq != e.getValue().seq))
		gridup.add(e.getValue().json);
	}
	for(Long id : sentgrids.keySet()) {
	    if(!curgrids.containsKey(id))
		griddel.put(Long.toString(id));
	}
	for(Client c : clients) {
	    if(c.fresh)
		continue;
	    for(String g : gridup)
		c.conn.send(g);
	    if(griddel.length() > 0)
		c.conn.send(new JSONObject().put("t", "griddel").put("ids", griddel).toString());
	    if(uid != null)
		c.conn.send(uid);
	    if(gd != null)
		c.conn.send(gd);
	}
	/* The full state for browsers that just connected. */
	for(Client c : clients) {
	    if(!c.fresh)
		continue;
	    c.conn.send(new JSONObject().put("t", "reset").toString());
	    for(WorldFeed.GridMsg g : curgrids.values())
		c.conn.send(g.json);
	    c.conn.send(full("ui", curui));
	    c.conn.send(full("gobs", curgobs));
	    c.fresh = false;
	}
	sentui = curui;
	sentgobs = curgobs;
	sentgrids = curgrids;
    }

    private static <K> String delta(String type, Map<K, String> prev, Map<K, String> cur, boolean strkeys) {
	StringBuilder up = new StringBuilder();
	for(Map.Entry<K, String> e : cur.entrySet()) {
	    if(!e.getValue().equals(prev.get(e.getKey()))) {
		if(up.length() > 0)
		    up.append(',');
		up.append(e.getValue());
	    }
	}
	JSONArray del = new JSONArray();
	for(K k : prev.keySet()) {
	    if(!cur.containsKey(k))
		del.put(strkeys ? (Object)k.toString() : (Object)k);
	}
	if((up.length() == 0) && (del.length() == 0))
	    return(null);
	return("{\"t\":\"" + type + "\",\"up\":[" + up + "],\"del\":" + del + "}");
    }

    private static <K> String full(String type, Map<K, String> cur) {
	return("{\"t\":\"" + type + "\",\"up\":[" + String.join(",", cur.values()) + "],\"del\":[]}");
    }
}
