package nurgling.web;

import haven.*;
import nurgling.NGItem;
import nurgling.NISBox;
import nurgling.NMapView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Turns the live widget tree into view models for the browser.
 *
 * The rule is "state, not wire arguments": each pass reads the Java widgets after they have
 * applied the server's messages, so no server argument format is decoded here. Only visible
 * widgets are read. Must be called with the UI locked ({@code synchronized(ui)}).
 */
public class UiMirror {
    private final Map<Widget, String> synth = new WeakHashMap<>();
    private int nextsynth = 1;
    /** key -> widget, rebuilt every pass; commands resolve their targets through it. */
    private volatile Map<String, WeakReference<Widget>> bykey = new HashMap<>();
    private Map<String, WeakReference<Widget>> building;
    /** Server type names by widget id, from UiEvents; used to label widgets we have no view for. */
    private final Map<Integer, String> types = Collections.synchronizedMap(new HashMap<>());

    public void noteType(int id, String type) {types.put(id, type);}
    public void forget(int id) {types.remove(id);}

    public Widget resolve(String key) {
	if(key == null)
	    return(null);
	WeakReference<Widget> r = bykey.get(key);
	return((r == null) ? null : r.get());
    }

    String key(UI ui, Widget w) {
	int id = ui.widgetid(w);
	String k;
	if(id >= 0) {
	    k = "s" + id;
	} else {
	    k = synth.get(w);
	    if(k == null)
		synth.put(w, k = "c" + (nextsynth++));
	}
	if(building != null)
	    building.put(k, new WeakReference<>(w));
	return(k);
    }

    /** Builds the view models of everything visible, by entity key. */
    public Map<String, JSONObject> snapshot(UI ui) {
	building = new HashMap<>();
	Map<String, JSONObject> out = new LinkedHashMap<>();
	try {
	    visit(ui, ui.root, out);
	    GameUI gui = ui.gui;
	    if(gui != null) {
		if(gui.chat != null) {
		    for(Widget ch = gui.chat.child; ch != null; ch = ch.next)
			findchannels(ui, ch, out);
		}
		if(gui.vhand != null) {
		    JSONObject hand = new JSONObject().put("t", "hand").put("k", "hand").put("item", item(ui, gui.vhand, null));
		    out.put("hand", hand);
		}
		if((gui.prog != null) && gui.prog.visible)
		    out.put("prog", new JSONObject().put("t", "prog").put("k", "prog").put("v", r3(gui.prog.prog)));
		if((gui.map instanceof NMapView) && ((NMapView)gui.map).isAreaSelectionMode.get())
		    out.put("mode", new JSONObject().put("t", "mode").put("k", "mode").put("areasel", true));
		JSONObject place = placing(gui.map);
		if(place != null)
		    out.put("place", place);
		JSONObject fight = fight(ui, gui);
		if(fight != null)
		    out.put("fight", fight);
		out.put("attrs", attrs(gui));
		if((gui.speedget != null) && (gui.speedget.parent != null))
		    out.put("speed", new JSONObject().put("t", "speed").put("k", "speed").put("cur", gui.speedget.cur).put("max", gui.speedget.max));
		out.put("belt", belt(gui));
		if(gui instanceof nurgling.NGameUI) {
		    nurgling.NGameUI ng = (nurgling.NGameUI)gui;
		    Inventory binv = (ng.nbp != null) ? ng.nbp.getBeltInventory() : null;
		    if(binv != null) {
			JSONArray items = new JSONArray();
			for(Widget ch = binv.child; ch != null; ch = ch.next) {
			    if(ch instanceof WItem)
				items.put(item(ui, (WItem)ch, binv));
			}
			out.put("beltinv", new JSONObject().put("t", "beltinv").put("k", "beltinv").put("inv", key(ui, binv))
				.put("cw", binv.isz.x).put("ch", binv.isz.y).put("items", items));
		    }
		}
		JSONObject cal = cal(gui);
		if(cal != null)
		    out.put("cal", cal);
		JSONObject toggles = new JSONObject().put("t", "toggles").put("k", "toggles");
		toggles.put("inv", (gui.maininv != null) && gui.maininv.tvisible());
		toggles.put("equ", (gui.equwnd != null) && gui.equwnd.visible);
		toggles.put("chr", (gui.chrwdg != null) && gui.chrwdg.visible);
		out.put("toggles", toggles);
	    }
	} finally {
	    bykey = building;
	    building = null;
	}
	return(out);
    }

    private void findchannels(UI ui, Widget w, Map<String, JSONObject> out) {
	if(w instanceof ChatUI.Channel) {
	    ChatUI.Channel ch = (ChatUI.Channel)w;
	    String k = key(ui, ch);
	    JSONArray lines = new JSONArray();
	    List<ChatUI.Channel.RenderedMessage> msgs = ch.rmsgs;
	    for(int i = Math.max(0, msgs.size() - 80); i < msgs.size(); i++) {
		String txt = msgtext(msgs.get(i));
		if(txt != null)
		    lines.put(txt);
	    }
	    String name;
	    try {
		name = ch.name();
	    } catch(RuntimeException e) {
		name = "?";
	    }
	    out.put(k, new JSONObject().put("t", "chat").put("k", k).put("name", name)
		    .put("send", ch instanceof ChatUI.EntryChannel).put("lines", lines));
	    return;
	}
	for(Widget ch = w.child; ch != null; ch = ch.next)
	    findchannels(ui, ch, out);
    }

    private static String msgtext(ChatUI.Channel.RenderedMessage rm) {
	try {
	    Text t = rm.data().get();
	    return((t == null) ? null : t.text);
	} catch(Loading e) {
	    return(null);
	}
    }

    private void visit(UI ui, Widget w, Map<String, JSONObject> out) {
	if(!w.visible)
	    return;
	if((w instanceof MapView) || (w instanceof ChatUI) || (w instanceof ItemDrag))
	    return;
	if(w instanceof Window) {
	    String k = key(ui, w);
	    out.put(k, window(ui, (Window)w, k));
	    return;
	}
	if(w instanceof FlowerMenu) {
	    FlowerMenu fm = (FlowerMenu)w;
	    String k = key(ui, w);
	    JSONArray opts = new JSONArray();
	    /* nurgling's menu keeps its own petals; the stock ones stay empty. */
	    if((w instanceof nurgling.NFlowerMenu) && (((nurgling.NFlowerMenu)w).nopts != null)) {
		for(nurgling.NFlowerMenu.NPetal p : ((nurgling.NFlowerMenu)w).nopts)
		    opts.put(p.name);
	    } else if(fm.opts != null) {
		for(FlowerMenu.Petal p : fm.opts)
		    opts.put(p.name);
	    }
	    out.put(k, new JSONObject().put("t", "flower").put("k", k).put("opts", opts));
	    return;
	}
	if(w instanceof Charlist) {
	    String k = key(ui, w);
	    JSONArray chars = new JSONArray();
	    for(Charlist.Char c : ((Charlist)w).chars)
		chars.put(c.name);
	    out.put(k, new JSONObject().put("t", "charlist").put("k", k).put("chars", chars));
	    return;
	}
	if(w instanceof IMeter) {
	    IMeter m = (IMeter)w;
	    String k = key(ui, w);
	    JSONArray ms = new JSONArray();
	    for(LayerMeter.Meter mt : m.meters)
		ms.put(new JSONObject().put("a", r3(mt.a)).put("c", hex(mt.c)));
	    out.put(k, new JSONObject().put("t", "meter").put("k", k).put("name", (m.name == null) ? "" : m.name)
		    .put("icon", resname(m.bg)).put("m", ms).put("tip", tiptext(m)));
	    return;
	}
	if(w instanceof Buff) {
	    Buff b = (Buff)w;
	    String k = key(ui, w);
	    JSONObject o = new JSONObject().put("t", "buff").put("k", k).put("r", resname(b.res));
	    try {
		Double a = b.ameteri.get();
		if(a != null)
		    o.put("a", r3(a));
	    } catch(Loading e) {
	    }
	    out.put(k, o);
	    return;
	}
	for(Widget ch = w.child; ch != null; ch = ch.next)
	    visit(ui, ch, out);
    }

    private JSONObject window(UI ui, Window wnd, String k) {
	JSONObject o = new JSONObject().put("t", "wnd").put("k", k);
	o.put("cap", (wnd.cap == null) ? "" : wnd.cap);
	IButton cb = closebtn(wnd);
	o.put("close", (cb != null) && cb.visible);
	o.put("x", wnd.c.x).put("y", wnd.c.y);
	Area ca = wnd.ca();
	JSONArray els = new JSONArray();
	for(Widget ch = wnd.child; ch != null; ch = ch.next)
	    element(ui, wnd, ch, els);
	/* Lay out in the window's content area: shift everything so the content box's corner is 0,0. */
	o.put("ox", ca.ul.x).put("oy", ca.ul.y);
	o.put("w", ca.sz().x).put("h", ca.sz().y);
	o.put("els", els);
	return(o);
    }

    /** The window frame's own close button, if it has one; only those windows can be closed. */
    static IButton closebtn(Window wnd) {
	if(wnd.deco instanceof Window.DefaultDeco)
	    return(((Window.DefaultDeco)wnd.deco).cbtn);
	if(wnd.deco instanceof nurgling.NWindowDeco)
	    return(((nurgling.NWindowDeco)wnd.deco).cbtn);
	return(null);
    }

    private void element(UI ui, Window wnd, Widget w, JSONArray els) {
	if(!w.visible)
	    return;
	if(w instanceof Window) {
	    /* Nested windows are rare; they show as their own panel. */
	    return;
	}
	/* The frame, caption and close button: the browser draws its own. */
	if(w instanceof Window.Deco)
	    return;
	Coord p = w.parentpos(wnd);
	JSONObject e = null;
	boolean descend = true;
	if(w instanceof Button) {
	    Button b = (Button)w;
	    e = el("btn", ui, w, p).put("text", (b.text == null) ? "" : b.text.text);
	    descend = false;
	} else if(w instanceof ACheckBox) {
	    e = el("chk", ui, w, p).put("on", ((ACheckBox)w).state()).put("text", checklabel(w));
	    descend = false;
	} else if(w instanceof Label) {
	    String t = ((Label)w).texts;
	    e = el("lbl", ui, w, p).put("text", (t == null) ? "" : t);
	    descend = false;
	} else if(w instanceof TextEntry) {
	    e = el("text", ui, w, p).put("value", ((TextEntry)w).text());
	    descend = false;
	} else if(w instanceof Inventory) {
	    Inventory inv = (Inventory)w;
	    JSONArray items = new JSONArray();
	    for(Widget ch = inv.child; ch != null; ch = ch.next) {
		if(ch instanceof WItem)
		    items.put(item(ui, (WItem)ch, inv));
	    }
	    e = el("inv", ui, w, p).put("cw", inv.isz.x).put("ch", inv.isz.y).put("items", items);
	    descend = false;
	} else if(w instanceof Equipory) {
	    Equipory eq = (Equipory)w;
	    JSONArray slots = new JSONArray();
	    for(Coord c : Equipory.ecoords)
		slots.put(new JSONArray().put(c.x).put(c.y));
	    JSONArray items = new JSONArray();
	    for(Widget ch = eq.child; ch != null; ch = ch.next) {
		if(ch instanceof WItem) {
		    JSONObject it = item(ui, (WItem)ch, null);
		    Coord ep = ch.c.sub(1, 1);
		    for(int i = 0; i < Equipory.ecoords.length; i++) {
			if(Equipory.ecoords[i].equals(ep))
			    it.put("slot", i);
		    }
		    items.put(it);
		}
	    }
	    e = el("equ", ui, w, p).put("slots", slots).put("cell", Inventory.sqsz.x).put("items", items);
	    descend = false;
	} else if(w instanceof ISBox) {
	    /* One clean row in the browser; nurgling's own amount field and Take button are not drawn. */
	    e = el("isbox", ui, w, p);
	    descend = false;
	    if(w instanceof NISBox) {
		NISBox b = (NISBox)w;
		int n = b.calcCount(), free = b.calcFreeSpace();
		e.put("r", resname(b.itemres)).put("n", n).put("cap", ((n >= 0) && (free >= 0)) ? (n + free) : -1);
	    }
	} else if(w instanceof WItem) {
	    e = el("item", ui, w, p).put("item", item(ui, (WItem)w, null));
	    descend = false;
	} else if(w instanceof ListWidget) {
	    e = list(ui, (ListWidget<?>)w, p);
	    descend = false;
	} else if(w instanceof nurgling.widgets.NMakewindow) {
	    nurgling.widgets.NMakewindow mk = (nurgling.widgets.NMakewindow)w;
	    e = el("make", ui, w, p).put("rcp", (mk.rcpnm == null) ? "" : mk.rcpnm)
		.put("in", specs(mk.inputs)).put("out", specs(mk.outputs));
	} else if(w.child == null) {
	    int id = ui.widgetid(w);
	    String type = (id >= 0) ? types.get(id) : null;
	    if(type != null)
		e = el("box", ui, w, p).put("type", type);
	}
	if(e != null)
	    els.put(e);
	if(descend) {
	    for(Widget ch = w.child; ch != null; ch = ch.next)
		element(ui, wnd, ch, els);
	}
    }

    private JSONObject el(String type, UI ui, Widget w, Coord p) {
	return(new JSONObject().put("e", type).put("k", key(ui, w)).put("x", p.x).put("y", p.y).put("w", w.sz.x).put("h", w.sz.y));
    }

    JSONObject item(UI ui, WItem wi, Inventory inv) {
	GItem it = wi.item;
	JSONObject o = new JSONObject().put("k", key(ui, it));
	o.put("r", resname(it.res));
	if(it instanceof NGItem) {
	    NGItem ng = (NGItem)it;
	    if(ng.name() != null)
		o.put("n", ng.name());
	    if(ng.quality != null)
		o.put("q", Math.round(ng.quality * 10) / 10.0);
	}
	if(it.num >= 0)
	    o.put("num", it.num);
	if(it.meter > 0)
	    o.put("m", it.meter);
	if(it.contents != null)
	    o.put("stack", true);
	if(inv != null) {
	    Coord cell = wi.c.sub(1, 1).div(Inventory.sqsz);
	    Coord cells = wi.sz.add(Inventory.sqsz.div(2)).div(Inventory.sqsz);
	    o.put("cx", cell.x).put("cy", cell.y).put("cw", Math.max(1, cells.x)).put("chh", Math.max(1, cells.y));
	}
	return(o);
    }

    private static JSONArray specs(List<nurgling.widgets.NMakewindow.Spec> specs) {
	JSONArray out = new JSONArray();
	for(nurgling.widgets.NMakewindow.Spec sp : specs) {
	    JSONObject o = new JSONObject().put("r", resname(sp.res)).put("c", sp.count);
	    if(sp.name != null)
		o.put("n", sp.name);
	    out.put(o);
	}
	return(out);
    }

    /* ---- generic lists: items are only drawn, so their text comes from the item objects ---- */

    private static final Map<Class<?>, java.lang.reflect.Method[]> listmeths = new HashMap<>();
    private static java.lang.reflect.Method[] listmeths(Class<?> cl) {
	synchronized(listmeths) {
	    if(listmeths.containsKey(cl))
		return(listmeths.get(cl));
	    java.lang.reflect.Method items = null, item = null;
	    for(Class<?> c = cl; (c != null) && ((items == null) || (item == null)); c = c.getSuperclass()) {
		try {
		    if(items == null) {items = c.getDeclaredMethod("listitems"); items.setAccessible(true);}
		} catch(NoSuchMethodException | SecurityException e) {
		}
		try {
		    if(item == null) {item = c.getDeclaredMethod("listitem", int.class); item.setAccessible(true);}
		} catch(NoSuchMethodException | SecurityException e) {
		}
	    }
	    java.lang.reflect.Method[] ret = ((items != null) && (item != null)) ? new java.lang.reflect.Method[] {items, item} : null;
	    listmeths.put(cl, ret);
	    return(ret);
	}
    }

    /** The items of a list widget, by index; null when they cannot be read. */
    static List<Object> listitems(ListWidget<?> lw) {
	java.lang.reflect.Method[] m = listmeths(lw.getClass());
	if(m == null)
	    return(null);
	try {
	    int n = (Integer)m[0].invoke(lw);
	    List<Object> ret = new ArrayList<>(n);
	    for(int i = 0; i < n; i++)
		ret.add(m[1].invoke(lw, i));
	    return(ret);
	} catch(IllegalAccessException | java.lang.reflect.InvocationTargetException | ClassCastException e) {
	    return(null);
	}
    }

    static String itemtext(Object it) {
	if(it == null)
	    return("");
	if(it instanceof String)
	    return((String)it);
	for(String fn : new String[] {"name", "text", "title", "nm"}) {
	    try {
		Object v = it.getClass().getField(fn).get(it);
		if(v instanceof String)
		    return((String)v);
		if(v instanceof Text)
		    return(((Text)v).text);
	    } catch(NoSuchFieldException | IllegalAccessException | SecurityException e) {
	    }
	}
	for(String mn : new String[] {"name", "getName", "text"}) {
	    try {
		Object v = it.getClass().getMethod(mn).invoke(it);
		if(v instanceof String)
		    return((String)v);
	    } catch(NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException | SecurityException e) {
	    }
	}
	String s = it.toString();
	/* Object.toString's "Class@hash" says nothing to a player. */
	return(s.matches(".*@[0-9a-f]+$") ? it.getClass().getSimpleName() : s);
    }

    private JSONObject list(UI ui, ListWidget<?> lw, Coord p) {
	JSONObject e = el("list", ui, lw, p);
	List<Object> items = listitems(lw);
	JSONArray texts = new JSONArray();
	int sel = -1;
	if(items != null) {
	    for(int i = 0; i < items.size(); i++) {
		texts.put(itemtext(items.get(i)));
		if(items.get(i) == lw.sel)
		    sel = i;
	    }
	}
	return(e.put("items", texts).put("sel", sel));
    }

    /* ---- building placement ---- */

    private static JSONObject placing(MapView map) {
	if((map == null) || (map.placing == null) || !map.placing.done())
	    return(null);
	MapView.Plob pl;
	try {
	    pl = map.placing.get();
	} catch(Loading e) {
	    return(null);
	}
	JSONObject o = new JSONObject().put("t", "place").put("k", "place");
	if(pl.ngob != null) {
	    if(pl.ngob.name != null)
		o.put("r", pl.ngob.name);
	    if(pl.ngob.hitBox != null) {
		nurgling.NHitBox hb = pl.ngob.hitBox;
		o.put("hb", new JSONArray().put(hb.begin.x).put(hb.begin.y).put(hb.end.x).put(hb.end.y));
	    }
	}
	return(o);
    }

    /* ---- combat ---- */

    private JSONObject fight(UI ui, GameUI gui) {
	Fightview fv = gui.fv;
	if((fv == null) || (fv.parent == null) || fv.lsrel.isEmpty())
	    return(null);
	double now = Utils.rtime();
	JSONObject o = new JSONObject().put("t", "fight").put("k", "fight");
	JSONArray rels = new JSONArray();
	for(Fightview.Relation r : fv.lsrel) {
	    JSONObject ro = new JSONObject().put("gob", r.gobid).put("ip", r.ip).put("oip", r.oip).put("cur", r == fv.current);
	    JSONArray ob = new JSONArray();
	    for(Widget b = r.buffs.child; b != null; b = b.next) {
		if(b instanceof Buff)
		    ob.put(buffjs((Buff)b));
	    }
	    JSONArray rb = new JSONArray();
	    for(Widget b = r.relbuffs.child; b != null; b = b.next) {
		if(b instanceof Buff)
		    rb.put(buffjs((Buff)b));
	    }
	    rels.put(ro.put("buffs", ob).put("relbuffs", rb));
	}
	o.put("rels", rels);
	JSONArray mine = new JSONArray();
	for(Widget b = fv.buffs.child; b != null; b = b.next) {
	    if(b instanceof Buff)
		mine.put(buffjs((Buff)b));
	}
	o.put("buffs", mine);
	if(now < fv.atkct)
	    o.put("cd", r3((fv.atkct - now))).put("cdt", r3(fv.atkct - fv.atkcs));
	Fightsess fs = gui.fsess;
	if((fs != null) && (fs.parent != null)) {
	    JSONArray acts = new JSONArray();
	    for(int i = 0; i < fs.actions.length; i++) {
		Fightsess.Action a = fs.actions[i];
		JSONObject ao = new JSONObject().put("i", i);
		if(a != null) {
		    ao.put("r", resname(a.res));
		    if(now < a.ct)
			ao.put("cd", r3(a.ct - now)).put("cdt", r3(a.ct - a.cs));
		}
		acts.put(ao);
	    }
	    o.put("acts", acts).put("fs", key(ui, fs));
	}
	return(o.put("fv", key(ui, fv)));
    }

    private static JSONObject buffjs(Buff b) {
	JSONObject o = new JSONObject().put("r", resname(b.res));
	try {
	    Double a = b.ameteri.get();
	    if(a != null)
		o.put("a", r3(a));
	} catch(Loading e) {
	}
	return(o);
    }

    /* ---- belt: nurgling shows numbelts toolbars of 12 slots, belt i starting at slot i*12 ---- */

    private static JSONObject belt(GameUI gui) {
	int n = 1;
	Object nb = nurgling.NConfig.get(nurgling.NConfig.Key.numbelts);
	if(nb instanceof Number)
	    n = Math.max(1, Math.min(12, ((Number)nb).intValue()));
	JSONArray slots = new JSONArray();
	for(int i = 0; i < n * 12; i++) {
	    GameUI.BeltSlot bs = gui.belt[i];
	    String r = null;
	    try {
		if(bs instanceof GameUI.ResBeltSlot)
		    r = ((GameUI.ResBeltSlot)bs).getres().name;
		else if(bs instanceof GameUI.PagBeltSlot)
		    r = ((GameUI.PagBeltSlot)bs).pag.res().name;
	    } catch(Loading e) {
	    }
	    slots.put((r == null) ? JSONObject.NULL : r);
	}
	return(new JSONObject().put("t", "belt").put("k", "belt").put("n", n).put("slots", slots));
    }

    /* ---- calendar ---- */

    private static JSONObject cal(GameUI gui) {
	Astronomy a = gui.ui.sess.glob.ast;
	if(a == null)
	    return(null);
	int ph = (int)Math.floor(a.mp * Astronomy.phase.length + 0.5) % Astronomy.phase.length;
	/* Astronomy.Season is package-private: its names and lengths, in its order. */
	String[] sn = {"Spring", "Summer", "Autumn", "Winter"};
	int[] sl = {30, 105, 30, 15};
	int si = Math.max(0, Math.min(3, a.is));
	return(new JSONObject().put("t", "cal").put("k", "cal").put("hh", a.hh).put("mm", a.mm)
	       .put("night", a.night).put("season", sn[si]).put("sday", a.scday + 1).put("slen", sl[si])
	       .put("left", a.srday).put("moon", Astronomy.phase[ph]).put("yt", r3(a.yt)));
    }

    /* ---- character attributes ---- */

    static final String[][] ATTRS = {
	{"str", "Strength"}, {"agi", "Agility"}, {"int", "Intelligence"}, {"con", "Constitution"}, {"prc", "Perception"},
	{"csm", "Charisma"}, {"dex", "Dexterity"}, {"wil", "Will"}, {"psy", "Psyche"},
	{"unarmed", "Unarmed Combat"}, {"melee", "Melee Combat"}, {"ranged", "Marksmanship"}, {"explore", "Exploration"},
	{"stealth", "Stealth"}, {"sewing", "Sewing"}, {"smithing", "Smithing"}, {"masonry", "Masonry"},
	{"carpentry", "Carpentry"}, {"cooking", "Cooking"}, {"farming", "Farming"}, {"survive", "Survival"}, {"lore", "Lore"},
    };

    private static JSONObject attrs(GameUI gui) {
	JSONArray a = new JSONArray();
	Glob glob = gui.ui.sess.glob;
	for(String[] at : ATTRS) {
	    Glob.CAttr c = glob.getcattr(at[0]);
	    a.put(new JSONObject().put("id", at[0]).put("n", at[1]).put("b", c.base).put("c", c.comp));
	}
	return(new JSONObject().put("t", "attrs").put("k", "attrs").put("a", a));
    }

    static String resname(Indir<Resource> res) {
	if(res == null)
	    return(null);
	try {
	    Resource r = res.get();
	    return((r == null) ? null : r.name);
	} catch(Loading e) {
	    return(null);
	}
    }

    private static String tiptext(Widget w) {
	Object tt = w.tooltip;
	if(tt instanceof String)
	    return((String)tt);
	if(tt instanceof Text)
	    return(((Text)tt).text);
	return("");
    }

    private static Field cblbl = null;
    private static boolean cblblmissing = false;
    private static String checklabel(Widget w) {
	if(!(w instanceof CheckBox) || cblblmissing)
	    return("");
	try {
	    if(cblbl == null) {
		cblbl = CheckBox.class.getDeclaredField("lbl");
		cblbl.setAccessible(true);
	    }
	    Object t = cblbl.get(w);
	    return((t instanceof Text) ? ((Text)t).text : "");
	} catch(NoSuchFieldException | IllegalAccessException | SecurityException e) {
	    cblblmissing = true;
	    return("");
	}
    }

    static String hex(Color c) {
	return(String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()));
    }

    static double r3(double v) {
	return(Math.round(v * 1000) / 1000.0);
    }
}
