package nurgling.web;

import haven.*;
import nurgling.contextmenu.GobContextAction;
import nurgling.contextmenu.GobContextRegistry;
import nurgling.contextmenu.TileContextAction;
import nurgling.contextmenu.TileContextRegistry;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.actions.PathFinder;
import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;
import nurgling.sessions.BotExecutor;
import nurgling.sessions.ThreadLocalUI;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * Nurgling's own features for the browser: the bot list, starting and stopping bots, and
 * walking with the pathfinder. Kept separate from the scripting API on purpose.
 */
public class WebOps {
    public static class Run {
	public final String id, name;
	public final Thread thread;
	public final long started = System.currentTimeMillis();

	Run(String id, String name, Thread thread) {this.id = id; this.name = name; this.thread = thread;}
    }

    private final List<Run> runs = Collections.synchronizedList(new ArrayList<>());

    public JSONObject call(UI ui, JSONObject cmd) {
	JSONObject res = new JSONObject().put("t", "opres").put("rid", cmd.opt("rid"));
	JSONObject args = cmd.optJSONObject("args");
	if(args == null)
	    args = new JSONObject();
	String name = cmd.optString("name");
	if(!(ui instanceof NUI) || (((NUI)ui).gui == null))
	    return(res.put("ok", false).put("err", "not in game yet"));
	NUI nui = (NUI)ui;
	NUI prev = ThreadLocalUI.get();
	ThreadLocalUI.set(nui);
	try {
	    switch(name) {
	    case "bots.list":
		return(res.put("ok", true).put("data", botlist()));
	    case "bots.start":
		return(start(nui, args.optString("id"), res));
	    case "bots.stop":
		stop(args.optString("id"));
		return(res.put("ok", true));
	    case "menu.list":
		return(menulist(nui, args.optString("key", ""), res));
	    case "menu.use":
		return(menuuse(nui, args.optString("key"), res));
	    case "ctx.list":
	    case "ctx.run":
		return(ctx(nui, name.equals("ctx.run"), args, res));
	    case "walk": {
		Coord2d to = Coord2d.of(args.optDouble("x"), args.optDouble("y"));
		Thread t;
		synchronized(nui) {
		    t = BotExecutor.runAsync("Web: walk", gui -> new PathFinder(to).run(gui));
		}
		return(res.put("ok", t != null));
	    }
	    default:
		return(res.put("ok", false).put("err", "unknown op " + name));
	    }
	} finally {
	    if(prev != null)
		ThreadLocalUI.set(prev);
	    else
		ThreadLocalUI.clear();
	}
    }

    /* ---- the action menu (scm): the browser walks the folders itself and uses leaves ---- */

    /** Every pagina with its parent, ancestors included (as MenuGrid.cons climbs them). */
    private static Map<MenuGrid.Pagina, List<MenuGrid.Pagina>> menutree(MenuGrid scm) {
	Map<MenuGrid.Pagina, List<MenuGrid.Pagina>> kids = new HashMap<>();
	Set<MenuGrid.Pagina> seen = new HashSet<>();
	List<MenuGrid.Pagina> all;
	synchronized(scm.paginae) {
	    all = new ArrayList<>(scm.paginae);
	}
	for(MenuGrid.Pagina p : all) {
	    MenuGrid.Pagina c = p;
	    try {
		while((c != null) && seen.add(c)) {
		    MenuGrid.Pagina par = c.parent();
		    kids.computeIfAbsent(par, k -> new ArrayList<>()).add(c);
		    c = par;
		}
	    } catch(Loading e) {
		/* Still loading: it shows up on the next listing. */
	    }
	}
	return(kids);
    }

    private static String pagkey(MenuGrid.Pagina p) {
	try {
	    return(p.res().name);
	} catch(Loading e) {
	    return(null);
	}
    }

    private static MenuGrid.Pagina findpag(Map<MenuGrid.Pagina, List<MenuGrid.Pagina>> tree, String key) {
	for(List<MenuGrid.Pagina> l : tree.values()) {
	    for(MenuGrid.Pagina p : l) {
		if(key.equals(pagkey(p)))
		    return(p);
	    }
	}
	return(null);
    }

    private static JSONObject menulist(NUI nui, String key, JSONObject res) {
	MenuGrid scm = nui.gui.menu;
	if(scm == null)
	    return(res.put("ok", false).put("err", "no action menu yet"));
	Map<MenuGrid.Pagina, List<MenuGrid.Pagina>> tree;
	synchronized(nui) {
	    tree = menutree(scm);
	}
	MenuGrid.Pagina dir = key.isEmpty() ? null : findpag(tree, key);
	JSONArray items = new JSONArray();
	for(MenuGrid.Pagina p : tree.getOrDefault(dir, Collections.emptyList())) {
	    String k = pagkey(p);
	    if(k == null)
		continue;
	    String nm;
	    try {
		nm = p.button().name();
	    } catch(Loading e) {
		nm = k.substring(k.lastIndexOf('/') + 1);
	    }
	    items.put(new JSONObject().put("key", k).put("name", nm).put("dir", tree.containsKey(p)));
	}
	JSONArray path = new JSONArray();
	try {
	    for(MenuGrid.Pagina p = dir; p != null; p = p.parent())
		path.put(0, new JSONObject().put("key", pagkey(p)).put("name", p.button().name()));
	} catch(Loading e) {
	}
	return(res.put("ok", true).put("data", new JSONObject().put("items", items).put("path", path)));
    }

    private static JSONObject menuuse(NUI nui, String key, JSONObject res) {
	MenuGrid scm = nui.gui.menu;
	if((scm == null) || (key == null))
	    return(res.put("ok", false).put("err", "no action menu yet"));
	synchronized(nui) {
	    MenuGrid.Pagina p = findpag(menutree(scm), key);
	    if(p == null)
		return(res.put("ok", false).put("err", "that action is gone"));
	    p.button().use(new MenuGrid.Interaction());
	}
	return(res.put("ok", true));
    }

    /* ---- nurgling's own context menu (Ctrl+right-click on a gob or the ground) ---- */

    private static JSONObject ctx(NUI nui, boolean run, JSONObject args, JSONObject res) {
	MapView map = nui.gui.map;
	Gob gob = args.has("gob") ? map.glob.oc.getgob(args.optLong("gob")) : null;
	Coord2d mc = Coord2d.of(args.optDouble("x"), args.optDouble("y"));
	JSONArray labels = new JSONArray();
	if(gob != null) {
	    List<GobContextAction> acts = GobContextRegistry.getActionsFor(gob);
	    if(run) {
		int i = args.optInt("i", -1);
		if((i < 0) || (i >= acts.size()))
		    return(res.put("ok", false).put("err", "that entry is gone"));
		GobContextAction a = acts.get(i);
		synchronized(nui) {
		    if(a.isUiAction())
			a.performUi(gob);
		    else
			BotExecutor.runAsync(a.label(), a.create(gob));
		}
		return(res.put("ok", true));
	    }
	    for(GobContextAction a : acts)
		labels.put(a.label());
	    return(res.put("ok", true).put("data", new JSONObject().put("gob", gob.id).put("items", labels)));
	}
	List<TileContextAction> acts = TileContextRegistry.getActionsFor(mc);
	if(run) {
	    int i = args.optInt("i", -1);
	    if((i < 0) || (i >= acts.size()))
		return(res.put("ok", false).put("err", "that entry is gone"));
	    TileContextAction a = acts.get(i);
	    synchronized(nui) {
		BotExecutor.runAsync(a.label(), a.create(mc));
	    }
	    return(res.put("ok", true));
	}
	for(TileContextAction a : acts)
	    labels.put(a.label());
	return(res.put("ok", true).put("data", new JSONObject().put("items", labels)));
    }

    private static JSONArray botlist() {
	JSONArray out = new JSONArray();
	for(BotDescriptor d : BotRegistry.allowedInBotMenu()) {
	    out.put(new JSONObject().put("id", d.id).put("name", d.getDisplayName())
		    .put("group", (d.group() == null) ? "" : d.group())
		    .put("type", d.type.name()).put("desc", (d.getDescription() == null) ? "" : d.getDescription()));
	}
	return(out);
    }

    private JSONObject start(NUI nui, String id, JSONObject res) {
	BotDescriptor d = BotRegistry.byId(id);
	if(d == null)
	    return(res.put("ok", false).put("err", "no such bot"));
	nurgling.actions.Action action = d.instantiate(new HashMap<>());
	String runid = Long.toString(System.nanoTime(), 36);
	Thread t;
	/* The UI lock first, as a menu click takes it: BotsInterruptWidget.tick holds it while
	 * taking its own list lock. */
	synchronized(nui) {
	    t = BotExecutor.runWithSupports(d.getDisplayName(), action, d.disStacks, null);
	}
	if(t == null)
	    return(res.put("ok", false).put("err", "could not start"));
	runs.add(new Run(runid, d.getDisplayName(), t));
	return(res.put("ok", true).put("data", new JSONObject().put("run", runid)));
    }

    private void stop(String id) {
	synchronized(runs) {
	    for(Run r : runs) {
		if(id.isEmpty() || r.id.equals(id))
		    r.thread.interrupt();
	    }
	}
    }

    /** How many bots started from the browser are still running. */
    public int running() {
	synchronized(runs) {
	    runs.removeIf(r -> !r.thread.isAlive());
	    return(runs.size());
	}
    }

    /** The bots started from the browser that are still running. */
    public JSONObject status() {
	JSONArray list = new JSONArray();
	synchronized(runs) {
	    runs.removeIf(r -> !r.thread.isAlive());
	    for(Run r : runs)
		list.put(new JSONObject().put("id", r.id).put("name", r.name).put("since", r.started));
	}
	return(new JSONObject().put("t", "bots").put("k", "bots").put("runs", list));
    }
}
