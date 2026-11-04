package nurgling.scripting.api;

import haven.*;
import nurgling.NInventory;
import nurgling.areas.NArea;
import nurgling.tools.NAlias;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed, validated access to an op's arguments.
 *
 * Handles are accepted in two shapes: the bare id (a number) or the snapshot object the client
 * returned earlier ({"gob": 123, ...}). Everything is resolved against the run's own session.
 */
public class Args {
    private final JSONObject j;
    private final OpContext ctx;

    public Args(JSONObject j, OpContext ctx) {
        this.j = (j == null) ? new JSONObject() : j;
        this.ctx = ctx;
    }

    public JSONObject raw() { return j; }

    public boolean has(String n) { return j.has(n) && !j.isNull(n); }

    private Object need(String n) {
        if (!has(n)) throw ApiError.badArgs("missing argument '" + n + "'");
        return j.get(n);
    }

    public String str(String n) {
        Object o = need(n);
        if (!(o instanceof String)) throw ApiError.badArgs("'" + n + "' must be a string");
        return (String) o;
    }

    public String str(String n, String def) { return has(n) ? str(n) : def; }

    public long l(String n) {
        Object o = need(n);
        if (!(o instanceof Number)) throw ApiError.badArgs("'" + n + "' must be a number");
        return ((Number) o).longValue();
    }

    public int i(String n) { return (int) l(n); }

    public int i(String n, int def) { return has(n) ? i(n) : def; }

    public double d(String n) {
        Object o = need(n);
        if (!(o instanceof Number)) throw ApiError.badArgs("'" + n + "' must be a number");
        return ((Number) o).doubleValue();
    }

    public double d(String n, double def) { return has(n) ? d(n) : def; }

    public boolean b(String n, boolean def) {
        if (!has(n)) return def;
        Object o = j.get(n);
        if (!(o instanceof Boolean)) throw ApiError.badArgs("'" + n + "' must be a bool");
        return (Boolean) o;
    }

    public JSONObject obj(String n) {
        Object o = need(n);
        if (!(o instanceof JSONObject)) throw ApiError.badArgs("'" + n + "' must be an object");
        return (JSONObject) o;
    }

    public JSONArray arr(String n) {
        Object o = need(n);
        if (!(o instanceof JSONArray)) throw ApiError.badArgs("'" + n + "' must be a list");
        return (JSONArray) o;
    }

    public List<String> strs(String n) {
        if (!has(n)) return new ArrayList<>();
        Object o = j.get(n);
        List<String> res = new ArrayList<>();
        if (o instanceof String) {
            res.add((String) o);
            return res;
        }
        if (!(o instanceof JSONArray)) throw ApiError.badArgs("'" + n + "' must be a string or a list of strings");
        for (Object e : (JSONArray) o) res.add(String.valueOf(e));
        return res;
    }

    /** A plain JSON value converted to Java collections (for bot settings). */
    public Map<String, Object> map(String n) {
        if (!has(n)) return new HashMap<>();
        return obj(n).toMap();
    }

    /**
     * An alias: "name", ["a","b"], or {"include":[...], "exclude":[...]}.
     * Matching is the client's usual substring match on resource names and display names.
     */
    public NAlias alias(String n) {
        Object o = need(n);
        if (o instanceof String) return new NAlias((String) o);
        if (o instanceof JSONArray) return new NAlias(strs(n), new ArrayList<String>());
        if (o instanceof JSONObject) {
            JSONObject a = (JSONObject) o;
            List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
            if (a.has("include")) for (Object e : a.getJSONArray("include")) inc.add(String.valueOf(e));
            if (a.has("exclude")) for (Object e : a.getJSONArray("exclude")) exc.add(String.valueOf(e));
            if (inc.isEmpty()) throw ApiError.badArgs("'" + n + "' needs at least one include pattern");
            return new NAlias(inc, exc);
        }
        throw ApiError.badArgs("'" + n + "' must be a string, a list, or {include, exclude}");
    }

    public NAlias alias(String n, NAlias def) { return has(n) ? alias(n) : def; }

    /** A world coordinate [x, y], or a gob (its position). */
    public Coord2d coord2d(String n) {
        Object o = need(n);
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            if (a.length() != 2) throw ApiError.badArgs("'" + n + "' must be [x, y]");
            return new Coord2d(a.getDouble(0), a.getDouble(1));
        }
        if (o instanceof JSONObject && ((JSONObject) o).has("gob")) return gob(n).rc;
        if (o instanceof JSONObject && ((JSONObject) o).has("rc")) {
            JSONArray a = ((JSONObject) o).getJSONArray("rc");
            return new Coord2d(a.getDouble(0), a.getDouble(1));
        }
        throw ApiError.badArgs("'" + n + "' must be [x, y] or a gob");
    }

    /** An integer pair [x, y] (tiles, inventory slots). */
    public Coord coord(String n) {
        JSONArray a = arr(n);
        if (a.length() != 2) throw ApiError.badArgs("'" + n + "' must be [x, y]");
        return new Coord(a.getInt(0), a.getInt(1));
    }

    /** A rectangle [[x1, y1], [x2, y2]] in world coordinates. */
    public Pair<Coord2d, Coord2d> rect(String n) {
        JSONArray a = arr(n);
        if (a.length() != 2) throw ApiError.badArgs("'" + n + "' must be [[x1, y1], [x2, y2]]");
        JSONArray p = a.getJSONArray(0), q = a.getJSONArray(1);
        Coord2d ul = new Coord2d(Math.min(p.getDouble(0), q.getDouble(0)), Math.min(p.getDouble(1), q.getDouble(1)));
        Coord2d br = new Coord2d(Math.max(p.getDouble(0), q.getDouble(0)), Math.max(p.getDouble(1), q.getDouble(1)));
        return new Pair<>(ul, br);
    }

    private long handleId(String n, String key) {
        Object o = need(n);
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof JSONObject && ((JSONObject) o).has(key)) return ((JSONObject) o).getLong(key);
        throw ApiError.badArgs("'" + n + "' must be a " + key + " handle (an id or a snapshot)");
    }

    public long gobId(String n) { return handleId(n, "gob"); }

    public Gob gob(String n) {
        long id = gobId(n);
        Gob g = ctx.gui.map.glob.oc.getgob(id);
        if (g == null) throw new ApiError(Errors.GOB_GONE, "gob " + id + " no longer exists");
        return g;
    }

    public Widget widget(String n) {
        int id = (int) handleId(n, has(n) && j.get(n) instanceof JSONObject && ((JSONObject) j.get(n)).has("item") ? "item" : "wdg");
        Widget w = ctx.ui.getwidget(id);
        if (w == null) throw new ApiError(Errors.WIDGET_GONE, "widget " + id + " no longer exists");
        return w;
    }

    public GItem gitem(String n) {
        int id = (int) handleId(n, "item");
        Widget w = ctx.ui.getwidget(id);
        if (!(w instanceof GItem)) throw new ApiError(Errors.ITEM_GONE, "item " + id + " no longer exists");
        return (GItem) w;
    }

    public WItem item(String n) {
        GItem gi = gitem(n);
        WItem w = Codec.findWItem(gi);
        if (w == null) throw new ApiError(Errors.ITEM_GONE, "item " + gi.wdgid() + " is not shown in any inventory");
        return w;
    }

    /**
     * An inventory: "main" (the player's), "hand"-less window caption string, a widget id,
     * or an inventory snapshot. Omitted means the main inventory.
     */
    public NInventory inventory(String n) {
        if (!has(n)) return ctx.gui.getInventory();
        Object o = j.get(n);
        if (o instanceof String) {
            String s = (String) o;
            if (s.equals("main")) return ctx.gui.getInventory();
            NInventory inv = ctx.gui.getInventory(s);
            if (inv == null) throw ApiError.notFound("no open inventory in a window called '" + s + "'");
            return inv;
        }
        Widget w = widget(n);
        if (w instanceof NInventory) return (NInventory) w;
        NInventory inv = findChild(w, NInventory.class);
        if (inv == null) throw ApiError.badArgs("'" + n + "' is not an inventory");
        return inv;
    }

    /** A window by caption or handle. */
    public Window window(String n) {
        Object o = need(n);
        if (o instanceof String) {
            Window w = ctx.gui.getWindow((String) o);
            if (w == null) throw ApiError.notFound("no window called '" + o + "'");
            return w;
        }
        Widget w = widget(n);
        if (w instanceof Window) return (Window) w;
        Window p = w.getparent(Window.class);
        if (p == null) throw ApiError.badArgs("'" + n + "' is not a window");
        return p;
    }

    /** An area by id, name, or snapshot. */
    public NArea area(String n) {
        Object o = need(n);
        NArea a = null;
        if (o instanceof Number) a = Areas.byId(ctx, ((Number) o).intValue());
        else if (o instanceof String) a = Areas.byName(ctx, (String) o);
        else if (o instanceof JSONObject && ((JSONObject) o).has("area")) a = Areas.byId(ctx, ((JSONObject) o).getInt("area"));
        else throw ApiError.badArgs("'" + n + "' must be an area id, name or snapshot");
        if (a == null) throw new ApiError(Errors.NO_SUCH_AREA, "no area " + o);
        return a;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Widget> T findChild(Widget root, Class<T> cl) {
        for (Widget w = root.child; w != null; w = w.next) {
            if (cl.isInstance(w)) return (T) w;
            T r = findChild(w, cl);
            if (r != null) return r;
        }
        return null;
    }

    /** Rejects arguments the op does not declare, catching typos early. */
    public void checkKnown(ApiOp op) {
        for (String k : j.keySet()) {
            boolean ok = false;
            for (ApiParam p : op.params()) {
                if (p.name().equals(k)) { ok = true; break; }
            }
            if (!ok) throw ApiError.badArgs(op.name() + " has no argument '" + k + "'");
        }
        for (ApiParam p : op.params()) {
            if (!p.optional() && !has(p.name()))
                throw ApiError.badArgs(op.name() + " needs argument '" + p.name() + "'");
        }
    }
}
