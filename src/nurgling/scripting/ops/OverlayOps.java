package nurgling.scripting.ops;

import haven.*;
import haven.render.BaseColor;
import haven.render.RenderTree;
import haven.render.States;
import nurgling.GhostAlpha;
import nurgling.NStyle;
import nurgling.scripting.ScriptRun;
import nurgling.scripting.ScriptRuntime;
import nurgling.scripting.api.*;
import nurgling.scripting.ui.ScriptLabel;
import nurgling.scripting.ui.ScriptLines;
import nurgling.scripting.ui.ScriptMinimapLayer;
import nurgling.scripting.ui.ScriptTint;
import nurgling.widgets.NMiniMap;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.util.*;

import static nurgling.scripting.api.ApiOp.Kind.ACTION;

/**
 * World overlays a script draws: gob highlights, rectangles, tile sets, lines, labels, placement
 * ghosts and minimap markers. Every overlay belongs to the run that made it and is removed when
 * the run ends (also on stop). Overlays are created and removed on the UI thread.
 */
public final class OverlayOps {

    private static final String COLOR_DOC = "Colour as \"#rrggbb\", \"#rrggbbaa\" or [r, g, b] / [r, g, b, a] (0-255).";
    private static final int MAX_TILES = 20000;
    private static final int MAX_POINTS = 5000;

    /** One run's overlays: id to the job that removes it (run on the UI thread). */
    private static final class RunOverlays {
        final Map<Integer, Runnable> removers = new LinkedHashMap<>();
        int next = 1;
        ScriptMinimapLayer layer;
    }

    private static final Map<ScriptRun, RunOverlays> runs = new HashMap<>();

    private static RunOverlays of(ScriptRun run) {
        synchronized (runs) {
            RunOverlays ro = runs.get(run);
            if (ro == null) {
                ro = new RunOverlays();
                runs.put(run, ro);
                run.addCleanup(() -> clearAll(run));
            }
            return ro;
        }
    }

    /** Registers an overlay: posts {@code create} to the UI thread and returns its id. */
    private static int add(OpContext c, Runnable create, Runnable remove) {
        return put(c, nextId(c), create, remove);
    }

    private static int nextId(OpContext c) {
        RunOverlays ro = of(c.run);
        synchronized (runs) {
            return ro.next++;
        }
    }

    private static int put(OpContext c, int id, Runnable create, Runnable remove) {
        RunOverlays ro = of(c.run);
        synchronized (runs) {
            ro.removers.put(id, remove);
        }
        ScriptRuntime.onUiThread(c.ui, create);
        return id;
    }

    private static boolean removeOne(OpContext c, int id) {
        Runnable r;
        synchronized (runs) {
            RunOverlays ro = runs.get(c.run);
            r = (ro == null) ? null : ro.removers.remove(id);
        }
        if (r == null) return false;
        ScriptRuntime.onUiThread(c.ui, r);
        return true;
    }

    /** Runs on the UI thread when the run ends. */
    private static void clearAll(ScriptRun run) {
        List<Runnable> todo;
        synchronized (runs) {
            RunOverlays ro = runs.remove(run);
            if (ro == null) return;
            todo = new ArrayList<>(ro.removers.values());
            ro.removers.clear();
        }
        for (Runnable r : todo) r.run();
    }

    // ---- argument helpers ----

    static Color color(Args a, String n, Color def) {
        if (!a.has(n)) return def;
        Object o = a.raw().get(n);
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.startsWith("#")) s = s.substring(1);
            if (s.length() == 6 || s.length() == 8) {
                try {
                    long v = Long.parseLong(s, 16);
                    if (s.length() == 6) return new Color((int) (v >> 16) & 255, (int) (v >> 8) & 255, (int) v & 255);
                    return new Color((int) (v >> 24) & 255, (int) (v >> 16) & 255, (int) (v >> 8) & 255, (int) v & 255);
                } catch (NumberFormatException e) {
                    throw ApiError.badArgs("'" + n + "' is not a colour: " + o);
                }
            }
        } else if (o instanceof JSONArray) {
            JSONArray c = (JSONArray) o;
            if (c.length() == 3 || c.length() == 4) {
                int[] v = new int[4];
                v[3] = 255;
                for (int i = 0; i < c.length(); i++) {
                    if (!(c.get(i) instanceof Number)) throw ApiError.badArgs("'" + n + "' must hold numbers");
                    v[i] = Math.max(0, Math.min(255, c.getInt(i)));
                }
                return new Color(v[0], v[1], v[2], v[3]);
            }
        }
        throw ApiError.badArgs("'" + n + "' must be \"#rrggbb[aa]\" or [r, g, b(, a)]");
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static List<Coord2d> points(Args a, String n) {
        JSONArray arr = a.arr(n);
        if (arr.length() > MAX_POINTS) throw ApiError.badArgs("'" + n + "' has more than " + MAX_POINTS + " points");
        List<Coord2d> res = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.get(i);
            if (o instanceof JSONArray && ((JSONArray) o).length() == 2) {
                JSONArray p = (JSONArray) o;
                res.add(new Coord2d(p.getDouble(0), p.getDouble(1)));
            } else if (o instanceof JSONObject && ((JSONObject) o).has("rc")) {
                JSONArray p = ((JSONObject) o).getJSONArray("rc");
                res.add(new Coord2d(p.getDouble(0), p.getDouble(1)));
            } else {
                throw ApiError.badArgs("'" + n + "' item " + i + " must be [x, y] or a gob");
            }
        }
        return res;
    }

    private static MCache.OverlayInfo fill(Color color) {
        Material mat = new Material(new BaseColor(color), States.maskdepth);
        return new MCache.OverlayInfo() {
            public Collection<String> tags() {
                return Collections.singletonList("show");
            }

            public Material mat() {
                return mat;
            }
        };
    }

    // ---- ops ----

    @ApiOp(name = "overlay.highlight", kind = ACTION,
            doc = "Tints a game object, like the client's item-search highlight. " + COLOR_DOC
                    + " The colour's alpha is the strength of the tint (255 paints a flat silhouette). "
                    + "The highlight lasts while the object stays loaded; it is removed with overlay.remove, overlay.clear or when the run ends.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "The object."),
                    @ApiParam(name = "color", type = "str|list[int]", doc = "Tint colour.", optional = true, def = "\"#e99c54a0\"")},
            returns = "int", returnsDoc = "Overlay id.", errors = {Errors.GOB_GONE},
            example = "oid = nc.overlay.highlight(tree, color=\"#40ff40\")")
    public static Object highlight(OpContext c, Args a) {
        Gob g = a.gob("gob");
        Color col = color(a, "color", alpha(NStyle.border, 160));
        Gob.Overlay ol = new Gob.Overlay(g, new ScriptTint(g, col));
        // Overlay add and remove are both deferred on the gob, in order, so remove is safe even before the add ran.
        return add(c, () -> g.addol(ol), ol::remove);
    }

    @ApiOp(name = "overlay.label", kind = ACTION,
            doc = "Shows outlined text above a game object or at a world position. " + COLOR_DOC,
            params = {
                    @ApiParam(name = "at", type = "Gob|Coord", doc = "An object (the label follows it) or a world position [x, y]."),
                    @ApiParam(name = "text", type = "str", doc = "The text."),
                    @ApiParam(name = "color", type = "str|list[int]", doc = "Text colour.", optional = true, def = "\"#ffffff\""),
                    @ApiParam(name = "height", type = "float", doc = "Height above the ground, in world units.", optional = true, def = "15")},
            returns = "int", returnsDoc = "Overlay id.", errors = {Errors.GOB_GONE},
            example = "nc.overlay.label(barrel, \"water: 87 l\", color=\"#80c0ff\")")
    public static Object label(OpContext c, Args a) {
        String text = a.str("text");
        Color col = color(a, "color", Color.WHITE);
        float h = (float) a.d("height", 15);
        Object at = a.raw().get("at");
        if (at instanceof Number || (at instanceof JSONObject && ((JSONObject) at).has("gob"))) {
            Gob g = a.gob("at");
            Gob.Overlay ol = new Gob.Overlay(g, new ScriptLabel(g, text, col, h));
            return add(c, () -> g.addol(ol), ol::remove);
        }
        Coord2d pos = a.coord2d("at");
        Glob glob = c.gui.map.glob;
        Gob[] v = new Gob[1];
        return add(c, () -> {
            v[0] = glob.oc.new Virtual(pos, 0);
            v[0].addcustomol(new ScriptLabel(v[0], text, col, h));
            glob.oc.add(v[0]);
        }, () -> {
            if (v[0] != null) glob.oc.remove(v[0]);
        });
    }

    @ApiOp(name = "overlay.rect", kind = ACTION,
            doc = "Draws a rectangle on the ground like a saved area: a translucent fill over the tiles it covers and a border at its exact edges, "
                    + "with an optional label in the middle. " + COLOR_DOC + " The fill uses the colour at about a third of its alpha.",
            params = {
                    @ApiParam(name = "rect", type = "Rect", doc = "World rectangle [[x1, y1], [x2, y2]], e.g. from form.pick_area or an area snapshot."),
                    @ApiParam(name = "color", type = "str|list[int]", doc = "Border colour.", optional = true, def = "\"#e99c54\""),
                    @ApiParam(name = "label", type = "str", doc = "Text shown in the middle.", optional = true),
                    @ApiParam(name = "fill", type = "bool", doc = "Fill the covered tiles.", optional = true, def = "true")},
            returns = "int", returnsDoc = "Overlay id.",
            example = "r = nc.form.pick_area()\nnc.overlay.rect(r, color=\"#40c0ff\", label=\"stockpiles\")")
    public static Object rect(OpContext c, Args a) {
        Pair<Coord2d, Coord2d> r = a.rect("rect");
        Color col = color(a, "color", NStyle.border);
        String text = a.str("label", null);
        boolean doFill = a.b("fill", true);
        MCache map = c.gui.map.glob.map;
        Glob glob = c.gui.map.glob;
        Coord tul = r.a.floor(MCache.tilesz);
        Coord tbr = Coord.of((int) Math.ceil(r.b.x / MCache.tilesz.x), (int) Math.ceil(r.b.y / MCache.tilesz.y));
        if (tbr.x <= tul.x) tbr = Coord.of(tul.x + 1, tbr.y);
        if (tbr.y <= tul.y) tbr = Coord.of(tbr.x, tul.y + 1);
        Area tiles = new Area(tul, tbr);
        List<Coord2d> corners = Arrays.asList(r.a, Coord2d.of(r.b.x, r.a.y), r.b, Coord2d.of(r.a.x, r.b.y));
        Object[] h = new Object[3];
        return add(c, () -> {
            if (doFill) {
                MCache.RectOverlay ol = map.new RectOverlay(fill(alpha(col, Math.max(24, col.getAlpha() / 3))), tiles);
                map.add(ol);
                h[0] = ol;
            }
            h[1] = c.gui.map.basic.add(new ScriptLines(map, corners, true, col, 2f));
            if (text != null && !text.isEmpty()) {
                Gob v = glob.oc.new Virtual(r.a.add(r.b).div(2), 0);
                v.addcustomol(new ScriptLabel(v, text, Color.WHITE, 2));
                glob.oc.add(v);
                h[2] = v;
            }
        }, () -> {
            if (h[0] != null) map.remove((MCache.RectOverlay) h[0]);
            if (h[1] != null) ((RenderTree.Slot) h[1]).remove();
            if (h[2] != null) glob.oc.remove((Gob) h[2]);
        });
    }

    @ApiOp(name = "overlay.tiles", kind = ACTION,
            doc = "Fills a set of map tiles with a translucent colour, e.g. to show planned plots or the tiles a bot will dig. " + COLOR_DOC
                    + " At most " + MAX_TILES + " tiles per overlay.",
            params = {
                    @ApiParam(name = "tiles", type = "list[Tile]", doc = "Tile coordinates [[tx, ty], ...] (world position / 11, floored)."),
                    @ApiParam(name = "color", type = "str|list[int]", doc = "Fill colour; its alpha is the opacity.", optional = true, def = "\"#e99c5460\"")},
            returns = "int", returnsDoc = "Overlay id.",
            example = "tc = [[x, y] for x in range(10, 20) for y in range(5, 8)]\nnc.overlay.tiles(tc, color=[64, 192, 255, 80])")
    public static Object tiles(OpContext c, Args a) {
        JSONArray arr = a.arr("tiles");
        if (arr.length() > MAX_TILES) throw ApiError.badArgs("'tiles' has more than " + MAX_TILES + " tiles");
        Color col = color(a, "color", alpha(NStyle.border, 96));
        TreeSet<Coord> set = new TreeSet<>((p, q) -> p.y != q.y ? Integer.compare(p.y, q.y) : Integer.compare(p.x, q.x));
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.get(i);
            if (!(o instanceof JSONArray) || ((JSONArray) o).length() != 2) throw ApiError.badArgs("'tiles' item " + i + " must be [tx, ty]");
            set.add(Coord.of(((JSONArray) o).getInt(0), ((JSONArray) o).getInt(1)));
        }
        // Merge horizontal runs so a block of tiles is a handful of overlays, not one per tile.
        List<Area> runs = new ArrayList<>();
        Coord start = null, last = null;
        for (Coord t : set) {
            if (start != null && t.y == last.y && t.x == last.x + 1) {
                last = t;
                continue;
            }
            if (start != null) runs.add(new Area(start, last.add(1, 1)));
            start = last = t;
        }
        if (start != null) runs.add(new Area(start, last.add(1, 1)));
        MCache map = c.gui.map.glob.map;
        MCache.OverlayInfo info = fill(col);
        List<MCache.RectOverlay> made = new ArrayList<>();
        return add(c, () -> {
            for (Area ar : runs) {
                MCache.RectOverlay ol = map.new RectOverlay(info, ar);
                map.add(ol);
                made.add(ol);
            }
        }, () -> {
            for (MCache.RectOverlay ol : made) map.remove(ol);
            made.clear();
        });
    }

    @ApiOp(name = "overlay.line", kind = ACTION,
            doc = "Draws a line through world points on the ground (a route, a fence plan). It is drawn on top of everything and follows the terrain. " + COLOR_DOC,
            params = {
                    @ApiParam(name = "points", type = "list[Coord]", doc = "World positions [[x, y], ...] (gob snapshots work too); at least two."),
                    @ApiParam(name = "color", type = "str|list[int]", doc = "Line colour.", optional = true, def = "\"#e99c54\""),
                    @ApiParam(name = "closed", type = "bool", doc = "Join the last point back to the first.", optional = true, def = "false"),
                    @ApiParam(name = "width", type = "float", doc = "Line width in pixels.", optional = true, def = "2")},
            returns = "int", returnsDoc = "Overlay id.",
            example = "nc.overlay.line([me.rc, tree.rc, barrel.rc], color=\"#ffff00\")")
    public static Object line(OpContext c, Args a) {
        List<Coord2d> pts = points(a, "points");
        if (pts.size() < 2) throw ApiError.badArgs("'points' needs at least two points");
        Color col = color(a, "color", NStyle.border);
        boolean closed = a.b("closed", false);
        float w = (float) Math.max(1, Math.min(10, a.d("width", 2)));
        MCache map = c.gui.map.glob.map;
        RenderTree.Slot[] slot = new RenderTree.Slot[1];
        return add(c, () -> slot[0] = c.gui.map.basic.add(new ScriptLines(map, pts, closed, col, w)), () -> {
            if (slot[0] != null) slot[0].remove();
        });
    }

    @ApiOp(name = "overlay.ghost", kind = ACTION,
            doc = "Shows a translucent placement ghost of a resource's model at a position, like the build bots' previews. "
                    + "The resource is loaded and checked first; a name that does not exist or has nothing to draw is rejected.",
            params = {
                    @ApiParam(name = "res", type = "str", doc = "Resource name, e.g. \"gfx/terobjs/cupboard\"."),
                    @ApiParam(name = "pos", type = "Coord|Gob", doc = "World position [x, y], or a gob's position."),
                    @ApiParam(name = "angle", type = "float", doc = "Rotation in radians.", optional = true, def = "0")},
            returns = "int", returnsDoc = "Overlay id.", errors = {Errors.NOT_FOUND, Errors.ACTION_FAILED},
            example = "nc.overlay.ghost(\"gfx/terobjs/cupboard\", [x, y], angle=math.pi / 2)")
    public static Object ghost(OpContext c, Args a) throws InterruptedException {
        String name = a.str("res");
        Coord2d pos = a.coord2d("pos");
        double angle = a.d("angle", 0);
        Resource res;
        try {
            res = Resource.remote().loadwaitint(name);
        } catch (Resource.BadResourceException | Resource.LoadException e) {
            throw ApiError.notFound("no resource '" + name + "'");
        }
        if (res.layer(FastMesh.MeshRes.class) == null && res.layer(RenderLink.Res.class) == null && res.layer(Resource.CodeEntry.class) == null)
            throw ApiError.badArgs("resource '" + name + "' has no model to draw");
        Glob glob = c.gui.map.glob;
        Gob v = glob.oc.new Virtual(pos, angle);
        ResDrawable rd = null;
        for (int i = 0; rd == null; i++) {
            try {
                rd = new ResDrawable(v, res);
            } catch (Loading l) {
                if (i >= 100) throw ApiError.failed("resource '" + name + "' did not finish loading");
                c.waitFor(() -> false, 0.1);
            }
        }
        ResDrawable drawable = rd;
        return add(c, () -> {
            v.setattr(new GhostAlpha(v));
            v.setattr(drawable);
            glob.oc.add(v);
        }, () -> glob.oc.remove(v));
    }

    @ApiOp(name = "overlay.marker", kind = ACTION,
            doc = "Puts a temporary marker with a label on the minimap and the map window (it is not saved as a map marker). " + COLOR_DOC,
            params = {
                    @ApiParam(name = "pos", type = "Coord|Gob", doc = "World position [x, y], or a gob's position."),
                    @ApiParam(name = "text", type = "str", doc = "Label under the marker.", optional = true, def = "\"\""),
                    @ApiParam(name = "color", type = "str|list[int]", doc = "Marker colour.", optional = true, def = "\"#e99c54\"")},
            returns = "int", returnsDoc = "Overlay id.",
            example = "nc.overlay.marker(boar, \"boar\", color=\"#ff4040\")")
    public static Object marker(OpContext c, Args a) {
        Coord2d pos = a.coord2d("pos");
        String text = a.str("text", "");
        Color col = color(a, "color", NStyle.border);
        RunOverlays ro = of(c.run);
        ScriptMinimapLayer layer;
        synchronized (runs) {
            if (ro.layer == null) ro.layer = new ScriptMinimapLayer(c.ui);
            layer = ro.layer;
        }
        int id = nextId(c);
        return put(c, id, () -> {
            layer.put(id, pos, text, col);
            NMiniMap.addLayer(layer);
        }, () -> {
            layer.remove(id);
            if (layer.isEmpty()) NMiniMap.removeLayer(layer);
        });
    }

    @ApiOp(name = "overlay.remove", kind = ACTION,
            doc = "Removes one overlay this run created.",
            params = {@ApiParam(name = "id", type = "int", doc = "Overlay id returned by an overlay.* call.")},
            returns = "bool", returnsDoc = "False if there was no such overlay (already removed).",
            example = "nc.overlay.remove(oid)")
    public static Object remove(OpContext c, Args a) {
        return removeOne(c, a.i("id"));
    }

    @ApiOp(name = "overlay.clear", kind = ACTION,
            doc = "Removes every overlay this run created. The client does this by itself when the run ends.",
            returns = "int", returnsDoc = "How many overlays were removed.",
            example = "nc.overlay.clear()")
    public static Object clear(OpContext c, Args a) {
        List<Runnable> todo;
        synchronized (runs) {
            RunOverlays ro = runs.get(c.run);
            if (ro == null) return 0;
            todo = new ArrayList<>(ro.removers.values());
            ro.removers.clear();
        }
        for (Runnable r : todo) ScriptRuntime.onUiThread(c.ui, r);
        return todo.size();
    }

    private OverlayOps() {}
}
