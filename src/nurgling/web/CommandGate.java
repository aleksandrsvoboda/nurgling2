package nurgling.web;

import haven.*;
import nurgling.NMapView;
import nurgling.NUI;
import nurgling.areas.NArea;
import nurgling.sessions.ThreadLocalUI;
import org.json.JSONObject;

import static haven.OCache.posres;

/**
 * Applies what the browser asks for to the real widgets, as if the player had clicked them.
 * Targets are resolved through the last mirror pass, so only widgets the browser was shown
 * can be reached. Everything runs with the UI locked, the same way a menu click does.
 */
public class CommandGate {
    private final UiMirror mirror;
    private final WebOps ops;

    public CommandGate(UiMirror mirror, WebOps ops) {
	this.mirror = mirror;
	this.ops = ops;
    }

    /** Returns a reply to send back, or null. */
    public JSONObject apply(UI ui, JSONObject cmd) {
	String t = cmd.optString("t");
	if(t.equals("op"))
	    return(ops.call(ui, cmd));
	NUI prev = ThreadLocalUI.get();
	if(ui instanceof NUI)
	    ThreadLocalUI.set((NUI)ui);
	try {
	    synchronized(ui) {
		applyLocked(ui, t, cmd);
	    }
	} finally {
	    if(prev != null)
		ThreadLocalUI.set(prev);
	    else
		ThreadLocalUI.clear();
	}
	return(null);
    }

    private <T> T target(JSONObject cmd, Class<T> cl) {
	Widget w = mirror.resolve(cmd.optString("k", null));
	return(cl.isInstance(w) ? cl.cast(w) : null);
    }

    private static int mods(JSONObject cmd) {
	return(cmd.optInt("mods", 0) & KeyMatch.MODS);
    }

    private void applyLocked(UI ui, String t, JSONObject cmd) {
	switch(t) {
	case "btn": {
	    Button b = target(cmd, Button.class);
	    if(b != null)
		b.click();
	    break;
	}
	case "chk": {
	    ACheckBox c = target(cmd, ACheckBox.class);
	    if(c != null)
		c.click();
	    break;
	}
	case "text": {
	    TextEntry e = target(cmd, TextEntry.class);
	    if(e != null) {
		String v = cmd.optString("v", "");
		e.settext(v);
		if(cmd.optBoolean("go"))
		    e.activate(v);
	    }
	    break;
	}
	case "wclose": {
	    Window w = target(cmd, Window.class);
	    /* Press the frame's own close button, exactly as a click on it does. */
	    IButton cb = (w == null) ? null : UiMirror.closebtn(w);
	    if((cb != null) && cb.visible)
		cb.click();
	    break;
	}
	case "flower": {
	    FlowerMenu fm = target(cmd, FlowerMenu.class);
	    if(fm instanceof nurgling.NFlowerMenu) {
		nurgling.NFlowerMenu nfm = (nurgling.NFlowerMenu)fm;
		int i = cmd.optInt("i", -1);
		nfm.nchoose(((i >= 0) && (nfm.nopts != null) && (i < nfm.nopts.length)) ? nfm.nopts[i] : null);
	    } else if(fm != null) {
		int i = cmd.optInt("i", -1);
		fm.choose(((i >= 0) && (fm.opts != null) && (i < fm.opts.length)) ? fm.opts[i] : null);
	    }
	    break;
	}
	case "play": {
	    Charlist cl = target(cmd, Charlist.class);
	    if(cl != null)
		cl.wdgmsg("play", cmd.optString("name"));
	    break;
	}
	case "chat": {
	    ChatUI.EntryChannel ch = target(cmd, ChatUI.EntryChannel.class);
	    String text = cmd.optString("text", "").trim();
	    if((ch != null) && !text.isEmpty())
		ch.send(text);
	    break;
	}
	case "item": {
	    GItem it = target(cmd, GItem.class);
	    if(it == null)
		break;
	    switch(cmd.optString("a")) {
	    case "take": it.wdgmsg("take", Coord.z); break;
	    case "transfer": it.wdgmsg("transfer", Coord.z, cmd.optBoolean("all") ? -1 : 1); break;
	    case "drop": it.wdgmsg("drop", Coord.z, cmd.optBoolean("all") ? -1 : 1); break;
	    case "iact": it.wdgmsg("iact", Coord.z, mods(cmd)); break;
	    case "itemact": it.wdgmsg("itemact", mods(cmd)); break;
	    }
	    break;
	}
	case "invdrop": {
	    Inventory inv = target(cmd, Inventory.class);
	    if(inv != null)
		inv.wdgmsg("drop", Coord.of(cmd.optInt("x"), cmd.optInt("y")));
	    break;
	}
	case "equdrop": {
	    Equipory eq = target(cmd, Equipory.class);
	    if(eq != null)
		eq.wdgmsg("drop", cmd.optInt("slot"));
	    break;
	}
	case "isbox": {
	    ISBox b = target(cmd, ISBox.class);
	    if(b == null)
		break;
	    switch(cmd.optString("a")) {
	    case "take": b.wdgmsg("xfer2", -1, mods(cmd)); break;
	    case "put": b.wdgmsg("xfer2", 1, mods(cmd)); break;
	    case "taken":
	    case "putn": {
		/* As nurgling's Take button: one transfer per item, never more than the pile has. */
		int n = Math.max(1, Math.min(cmd.optInt("n", 1), 1000));
		boolean take = cmd.optString("a").equals("taken");
		if(b instanceof nurgling.NISBox) {
		    nurgling.NISBox nb = (nurgling.NISBox)b;
		    int have = nb.calcCount();
		    if(take) {
			if(have >= 0)
			    n = Math.min(n, have);
			nb.transfer(n);
		    } else {
			nb.put(n);
		    }
		} else {
		    for(int i = 0; i < n; i++)
			b.wdgmsg("xfer2", take ? -1 : 1, 1);
		}
		break;
	    }
	    case "drop": b.wdgmsg("drop"); break;
	    case "iact": b.wdgmsg("iact"); break;
	    }
	    break;
	}
	case "click":
	case "itemact":
	case "drop":
	    mapcmd(ui, t, cmd);
	    break;
	case "toggle":
	    toggle(ui, cmd.optString("w"));
	    break;
	case "door": {
	    /* Enter a house the way the bots do: right-click its door mesh. */
	    GameUI gui = ui.gui;
	    if((gui == null) || (gui.map == null))
		break;
	    Gob h = gui.map.glob.oc.getgob(cmd.optLong("gob"));
	    int mesh = cmd.optInt("mesh", 16);
	    if((h == null) || (mesh < 16) || (mesh > 18))
		break;
	    Coord rc = h.rc.floor(posres);
	    gui.map.wdgmsg("click", Coord.z, rc, 3, 0, 1, (int)h.id, rc, 0, mesh);
	    break;
	}
	case "list": {
	    ListWidget<?> lw = target(cmd, ListWidget.class);
	    if(lw != null)
		pick(lw, cmd.optInt("i", -1));
	    break;
	}
	case "place": {
	    GameUI gui = ui.gui;
	    if((gui == null) || (gui.map == null) || (gui.map.placing == null))
		break;
	    Coord mc = Coord2d.of(cmd.optDouble("x"), cmd.optDouble("y")).floor(posres);
	    int a = (int)Math.round(cmd.optDouble("a", 0) * 32768 / Math.PI);
	    gui.map.wdgmsg("place", mc, a, cmd.optInt("btn", 1), mods(cmd));
	    break;
	}
	case "fight":
	    fight(ui, cmd);
	    break;
	case "speed": {
	    GameUI gui = ui.gui;
	    if((gui != null) && (gui.speedget != null) && (gui.speedget.parent != null)) {
		int sp = cmd.optInt("v", -1);
		if((sp >= 0) && (sp <= gui.speedget.max))
		    gui.speedget.set(sp);
	    }
	    break;
	}
	case "belt": {
	    /* Use a slot (as its key would), or put the held item on it, or clear it. */
	    GameUI gui = ui.gui;
	    int i = cmd.optInt("i", -1);
	    if((gui == null) || (i < 0) || (i >= gui.belt.length))
		break;
	    switch(cmd.optString("a", "use")) {
	    case "use":
		if(gui.belt[i] != null)
		    gui.belt[i].use(new MenuGrid.Interaction(1, mods(cmd)));
		break;
	    case "set":
		gui.wdgmsg("setbelt", i, 0);
		break;
	    case "clear":
		gui.wdgmsg("setbelt", i, null);
		break;
	    }
	    break;
	}
	case "game":
	    if(cmd.optString("a").equals("chars") && (ui.gui != null))
		ui.gui.act("lo", "cs");
	    else if(cmd.optString("a").equals("logout")) {
		if(ui.gui != null)
		    ui.gui.act("lo");
		else if(ui.sess != null)
		    ui.sess.close();
	    }
	    break;
	case "areasel":
	    areasel(ui, cmd);
	    break;
	}
    }

    private void mapcmd(UI ui, String t, JSONObject cmd) {
	GameUI gui = ui.gui;
	if((gui == null) || (gui.map == null))
	    return;
	MapView map = gui.map;
	Coord mc = Coord2d.of(cmd.optDouble("x"), cmd.optDouble("y")).floor(posres);
	int mods = mods(cmd);
	Object[] gobargs = null;
	if(cmd.has("gob")) {
	    Gob gob = map.glob.oc.getgob(cmd.optLong("gob"));
	    if(gob != null)
		gobargs = new Object[] {0, (int)gob.id, gob.rc.floor(posres), 0, -1};
	}
	Object[] args;
	if(t.equals("click"))
	    args = new Object[] {Coord.z, mc, cmd.optInt("btn", 1), mods};
	else
	    args = new Object[] {Coord.z, mc, mods};
	if((gobargs != null) && !t.equals("drop"))
	    args = Utils.extend(args, gobargs);
	map.wdgmsg(t, args);
    }

    @SuppressWarnings("unchecked")
    private static <T> void pick(ListWidget<T> lw, int i) {
	java.util.List<Object> items = UiMirror.listitems(lw);
	if((items != null) && (i >= 0) && (i < items.size()))
	    lw.change((T)items.get(i));
    }

    private static void fight(UI ui, JSONObject cmd) {
	GameUI gui = ui.gui;
	if(gui == null)
	    return;
	switch(cmd.optString("a")) {
	case "use": {
	    Fightsess fs = gui.fsess;
	    int i = cmd.optInt("i", -1);
	    if((fs == null) || (fs.parent == null) || (i < 0) || (i >= fs.actions.length))
		break;
	    /* A tap is a key press and release in one. */
	    fs.wdgmsg("use", i, 1, mods(cmd));
	    fs.wdgmsg("rel", i);
	    break;
	}
	case "target": {
	    Fightview fv = gui.fv;
	    if((fv != null) && (fv.parent != null))
		fv.wdgmsg("click", (int)cmd.optLong("gob"), 1);
	    break;
	}
	}
    }

    private static void toggle(UI ui, String which) {
	GameUI gui = ui.gui;
	if(gui == null)
	    return;
	Window w = null;
	if(which.equals("inv") && (gui.maininv != null)) {
	    for(Widget p = gui.maininv.parent; p != null; p = p.parent) {
		if(p instanceof Window) {
		    w = (Window)p;
		    break;
		}
	    }
	} else if(which.equals("equ")) {
	    w = gui.equwnd;
	} else if(which.equals("chr")) {
	    w = gui.chrwdg;
	}
	if(w != null)
	    gui.togglewnd(w);
    }

    /** A bot asked "select an area": the browser dragged a rectangle, in tile coordinates (inclusive). */
    private static void areasel(UI ui, JSONObject cmd) {
	GameUI gui = ui.gui;
	if((gui == null) || !(gui.map instanceof NMapView))
	    return;
	NMapView map = (NMapView)gui.map;
	if(!map.isAreaSelectionMode.get())
	    return;
	Coord a = Coord.of(cmd.optInt("ax"), cmd.optInt("ay")), b = Coord.of(cmd.optInt("bx"), cmd.optInt("by"));
	map.areaSpace = new NArea.Space(a, b);
	map.isAreaSelectionMode.set(false);
    }
}
