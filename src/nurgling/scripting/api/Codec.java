package nurgling.scripting.api;

import haven.*;
import haven.res.ui.stackinv.ItemStack;
import nurgling.NGItem;
import nurgling.NInventory;
import nurgling.areas.NArea;
import nurgling.tools.NAlias;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collection;
import java.util.Map;

/**
 * Converts client objects to protocol JSON.
 *
 * Snapshots are plain data: the script gets a copy, never a live reference. Every snapshot
 * carries its handle field ("gob", "item", "wdg", "area") so the script can pass it back.
 */
public final class Codec {

    /** Generic conversion for op return values. Unknown objects become their toString(). */
    public static Object toJson(Object o) {
        if (o == null || o == JSONObject.NULL) return JSONObject.NULL;
        if (o instanceof JSONObject || o instanceof JSONArray) return o;
        if (o instanceof String || o instanceof Boolean || o instanceof Integer || o instanceof Long) return o;
        if (o instanceof Float || o instanceof Double) {
            double d = ((Number) o).doubleValue();
            return (Double.isNaN(d) || Double.isInfinite(d)) ? JSONObject.NULL : d;
        }
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof Enum) return ((Enum<?>) o).name();
        if (o instanceof Coord2d) return coord((Coord2d) o);
        if (o instanceof Coord) return coord((Coord) o);
        if (o instanceof Gob) return gob((Gob) o);
        if (o instanceof WItem) return item((WItem) o);
        if (o instanceof GItem) return item((GItem) o);
        if (o instanceof NArea) return area((NArea) o);
        if (o instanceof Pair) {
            Pair<?, ?> p = (Pair<?, ?>) o;
            return new JSONArray().put(toJson(p.a)).put(toJson(p.b));
        }
        if (o instanceof Map) {
            JSONObject j = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet())
                j.put(String.valueOf(e.getKey()), toJson(e.getValue()));
            return j;
        }
        if (o instanceof Collection) {
            JSONArray a = new JSONArray();
            for (Object e : (Collection<?>) o) a.put(toJson(e));
            return a;
        }
        if (o instanceof Object[]) {
            JSONArray a = new JSONArray();
            for (Object e : (Object[]) o) a.put(toJson(e));
            return a;
        }
        if (o instanceof int[]) {
            JSONArray a = new JSONArray();
            for (int e : (int[]) o) a.put(e);
            return a;
        }
        if (o instanceof Widget) return widget((Widget) o);
        if (o instanceof Indir) {
            Object r = safeIndir((Indir<?>) o);
            return r instanceof Resource ? ((Resource) r).name : JSONObject.NULL;
        }
        if (o instanceof Resource) return ((Resource) o).name;
        return String.valueOf(o);
    }

    private static final java.util.regex.Pattern POSE_RES = java.util.regex.Pattern.compile("<([^(>]+)");

    /**
     * Pose resource names from Composite.current_pose, which is the toString() of the pose list,
     * e.g. "[(<gfx/borka/idle(v16)>, Message(...))]" becomes ["gfx/borka/idle"].
     */
    public static JSONArray poses(String raw) {
        JSONArray res = new JSONArray();
        if (raw == null) return res;
        java.util.regex.Matcher m = POSE_RES.matcher(raw);
        while (m.find()) res.put(m.group(1).trim());
        if (res.isEmpty() && !raw.isEmpty() && !raw.equals("[]")) res.put(raw);
        return res;
    }

    public static JSONArray coord(Coord2d c) {
        return new JSONArray().put(c.x).put(c.y);
    }

    public static JSONArray coord(Coord c) {
        return new JSONArray().put(c.x).put(c.y);
    }

    public static JSONArray rect(Pair<Coord2d, Coord2d> r) {
        return new JSONArray().put(coord(r.a)).put(coord(r.b));
    }

    /** Gob snapshot. Fields that are not loaded yet are null. */
    public static JSONObject gob(Gob g) {
        JSONObject j = new JSONObject();
        j.put("gob", g.id);
        j.put("rc", coord(g.rc));
        j.put("a", g.a);
        String name = null, hash = null;
        long mattr = -1;
        if (g.ngob != null) {
            name = g.ngob.name;
            hash = g.ngob.hash;
            mattr = g.ngob.getModelAttribute();
        }
        j.put("name", name == null ? JSONObject.NULL : name);
        j.put("hash", hash == null ? JSONObject.NULL : hash);
        j.put("attr", mattr);
        JSONArray poses = poses(g.pose());
        j.put("pose", poses.isEmpty() ? JSONObject.NULL : poses.get(0));
        j.put("poses", poses);
        JSONArray ols = new JSONArray();
        for (Gob.Overlay ol : g.ols) {
            if (ol.spr != null && ol.spr.res != null)
                ols.put(ol.spr.res.name);
        }
        j.put("overlays", ols);
        Gob pl = g.glob.oc.getgob(plgobOf(g));
        j.put("dist", pl == null ? JSONObject.NULL : (Object) pl.rc.dist(g.rc));
        return j;
    }

    private static long plgobOf(Gob g) {
        UI ui = UI.getInstance();
        if (ui != null && ui.gui != null && ui.gui.map != null && ui.gui.map.glob == g.glob)
            return ui.gui.map.plgob;
        nurgling.NGameUI gui = nurgling.NUtils.getGameUI();
        return (gui != null && gui.map != null) ? gui.map.plgob : -1;
    }

    public static JSONObject item(GItem gi) {
        return item(findWItem(gi), gi);
    }

    public static JSONObject item(WItem w) {
        return item(w, w.item);
    }

    private static JSONObject item(WItem w, GItem gi) {
        JSONObject j = new JSONObject();
        j.put("item", gi.wdgid());
        String name = (gi instanceof NGItem) ? ((NGItem) gi).name() : null;
        j.put("name", name == null ? JSONObject.NULL : name);
        Resource res = safeRes(gi);
        j.put("res", res == null ? JSONObject.NULL : res.name);
        Float q = (gi instanceof NGItem) ? ((NGItem) gi).quality : null;
        j.put("q", q == null ? JSONObject.NULL : (Object) q.doubleValue());
        j.put("num", gi.num);
        JSONArray cont = new JSONArray();
        if (gi instanceof NGItem) {
            for (NGItem.NContent c : ((NGItem) gi).content()) {
                cont.put(new JSONObject().put("name", c.name()).put("q", c.quality()));
            }
        }
        j.put("contents", cont);
        int stack = 0;
        if (gi.contents instanceof ItemStack)
            stack = ((ItemStack) gi.contents).order.size();
        j.put("stack", stack);
        if (w != null) {
            Coord slot = (w.parent instanceof Inventory) ? w.c.sub(1, 1).div(Inventory.sqsz) : null;
            j.put("slot", slot == null ? JSONObject.NULL : coord(slot));
            j.put("size", coord(w.sz.div(Inventory.sqsz)));
        } else {
            j.put("slot", JSONObject.NULL);
            j.put("size", JSONObject.NULL);
        }
        Widget p = gi.parent;
        j.put("parent", p == null ? JSONObject.NULL : (Object) p.wdgid());
        return j;
    }

    /** The WItem showing a GItem, in an inventory or an item stack; null if none. */
    public static WItem findWItem(GItem gi) {
        Widget p = gi.parent;
        if (p instanceof ItemStack)
            return ((ItemStack) p).wmap.get(gi);
        if (p != null) {
            for (Widget w = p.child; w != null; w = w.next) {
                if (w instanceof WItem && ((WItem) w).item == gi)
                    return (WItem) w;
            }
        }
        return null;
    }

    public static JSONObject inventory(NInventory inv) {
        JSONObject j = new JSONObject();
        j.put("wdg", inv.wdgid());
        Window wnd = inv.getparent(Window.class);
        j.put("window", wnd == null ? JSONObject.NULL : wnd.cap);
        j.put("size", inv.isz == null ? JSONObject.NULL : coord(inv.isz));
        return j;
    }

    public static JSONObject widget(Widget w) {
        JSONObject j = new JSONObject();
        j.put("wdg", w.wdgid());
        j.put("class", w.getClass().getName());
        if (w instanceof Window) j.put("caption", ((Window) w).cap);
        Widget p = w.parent;
        j.put("parent", p == null ? JSONObject.NULL : (Object) p.wdgid());
        j.put("visible", w.visible());
        j.put("c", coord(w.c));
        j.put("sz", coord(w.sz));
        return j;
    }

    public static JSONObject area(NArea a) {
        JSONObject j = new JSONObject();
        j.put("area", a.id);
        j.put("name", a.name);
        JSONArray specs = new JSONArray();
        for (NArea.Specialisation s : a.spec) {
            JSONObject sj = new JSONObject().put("name", s.name);
            if (s.subtype != null) sj.put("subtype", s.subtype);
            specs.put(sj);
        }
        j.put("specs", specs);
        Pair<Coord2d, Coord2d> rc = safeRc(a);
        j.put("rect", rc == null ? JSONObject.NULL : rect(rc));
        j.put("in", a.jin);
        j.put("out", a.jout);
        return j;
    }

    private static Pair<Coord2d, Coord2d> safeRc(NArea a) {
        try {
            return a.getRCArea();
        } catch (NullPointerException e) {
            // Area is in a part of the map that is not loaded.
            return null;
        }
    }

    public static JSONObject alias(NAlias a) {
        return new JSONObject().put("include", new JSONArray(a.keys)).put("exclude", new JSONArray(a.exceptions));
    }

    private static Resource safeRes(GItem gi) {
        try {
            return gi.getres();
        } catch (Loading l) {
            return null;
        }
    }

    private static Object safeIndir(Indir<?> i) {
        try {
            return i.get();
        } catch (Loading l) {
            return null;
        }
    }

    private Codec() {}
}
