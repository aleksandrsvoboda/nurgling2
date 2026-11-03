package nurgling.scripting.ops;

import haven.*;
import nurgling.NUI;
import nurgling.areas.NArea;
import nurgling.pf.NHitBoxD;
import nurgling.scripting.ScriptConnection;
import nurgling.scripting.ScriptEvents;
import nurgling.scripting.ScriptRuntime;
import nurgling.scripting.ScriptServer;
import nurgling.scripting.api.*;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/**
 * Game objects (gobs) of the run's own session.
 *
 * Also publishes the {@code gob.new} and {@code gob.gone} events (hooks in NGob and OCache).
 */
public final class GobOps {

    private static final String FILTER_DOC =
            "Filters combine (all must hold). Only objects whose resource has loaded are matched by `name`; "
                    + "the player's own object is never included (use `gobs.player`). "
                    + "To react to objects appearing or vanishing, subscribe to the `gob.new` event ({gob, name, rc}, sent once the object's resource is known; call `gobs.get` for the full snapshot) "
                    + "and `gob.gone` ({gob, name}).";

    @ApiOp(name = "gobs.get", kind = QUERY,
            doc = "A fresh snapshot of one game object, or null if it no longer exists in this session "
                    + "(out of view, destroyed, picked up). Never raises GobGone; use it to test whether a handle is still valid.",
            params = {@ApiParam(name = "gob", type = "Gob", doc = "Gob handle (snapshot) or id.")},
            returns = "Gob|null", returnsDoc = "Snapshot: gob, name, hash, rc, a, pose, attr, overlays, dist.",
            example = "tree = nc.gobs.get(tree)\nif tree is None:\n    print(\"it fell\")")
    public static Object get(OpContext c, Args a) {
        Gob g = c.gui.map.glob.oc.getgob(a.gobId("gob"));
        return g == null ? null : Codec.gob(g);
    }

    @ApiOp(name = "gobs.find", kind = QUERY,
            doc = "Finds game objects in the loaded part of the world. " + FILTER_DOC + " "
                    + "Without `radius` and `area` every loaded object is considered. "
                    + "`name` matches the resource name by substring (\"trees/\", \"gfx/terobjs/chest\"). "
                    + "Raises NotFound if `area` is given but is not loaded around the player (more than ~1000 units away or hidden): walk there first, "
                    + "an unloaded area would otherwise look empty.",
            params = {
                    @ApiParam(name = "name", type = "Alias", doc = "Resource-name pattern(s), e.g. \"trees/\" or {\"include\": [\"trees/\"], \"exclude\": [\"stump\"]}.", optional = true),
                    @ApiParam(name = "area", type = "Area", doc = "Only objects inside this area's rectangle.", optional = true),
                    @ApiParam(name = "radius", type = "float", doc = "Only objects closer than this to `near` (world units; a tile is 11).", optional = true),
                    @ApiParam(name = "near", type = "Coord|Gob", doc = "Centre for `radius` and for sorting; defaults to the player position.", optional = true),
                    @ApiParam(name = "pose", type = "Alias", doc = "Pose resource pattern(s), e.g. \"knocked\" or \"dead\"; objects without a pose never match.", optional = true),
                    @ApiParam(name = "attr", type = "int", doc = "Model attribute (crop stage, container fill marker, ...) must equal this. Note markers >= 128 read negative.", optional = true),
                    @ApiParam(name = "exclude", type = "list[int]", doc = "Gob ids to skip (e.g. ones already processed).", optional = true),
                    @ApiParam(name = "limit", type = "int", doc = "At most this many results (after sorting); 0 = no limit.", optional = true, def = "0"),
                    @ApiParam(name = "sort", type = "str", doc = "\"dist\": nearest to `near` first; \"none\": cache order.", optional = true, def = "\"dist\"", values = {"dist", "none"})},
            returns = "list[Gob]", returnsDoc = "Gob snapshots, nearest first unless sort=\"none\".",
            errors = {Errors.NO_SUCH_AREA, Errors.NOT_FOUND, Errors.GOB_GONE},
            example = "trees = nc.gobs.find(name=\"trees/\", radius=200, limit=5)\nfor t in trees:\n    print(t[\"name\"], round(t[\"dist\"]))")
    public static Object find(OpContext c, Args a) {
        return query(c, a, a.i("limit", 0));
    }

    @ApiOp(name = "gobs.nearest", kind = QUERY,
            doc = "The nearest game object matching the same filters as `gobs.find`, or null. " + FILTER_DOC,
            params = {
                    @ApiParam(name = "name", type = "Alias", doc = "Resource-name pattern(s).", optional = true),
                    @ApiParam(name = "area", type = "Area", doc = "Only objects inside this area.", optional = true),
                    @ApiParam(name = "radius", type = "float", doc = "Only objects closer than this to `near`.", optional = true),
                    @ApiParam(name = "near", type = "Coord|Gob", doc = "Point to measure from; defaults to the player position.", optional = true),
                    @ApiParam(name = "pose", type = "Alias", doc = "Pose resource pattern(s).", optional = true),
                    @ApiParam(name = "attr", type = "int", doc = "Model attribute must equal this.", optional = true),
                    @ApiParam(name = "exclude", type = "list[int]", doc = "Gob ids to skip.", optional = true)},
            returns = "Gob|null", errors = {Errors.NO_SUCH_AREA, Errors.NOT_FOUND, Errors.GOB_GONE},
            example = "chest = nc.gobs.nearest(name=\"gfx/terobjs/chest\", radius=100)")
    public static Object nearest(OpContext c, Args a) {
        List<Gob> res = query(c, a, 1);
        return res.isEmpty() ? null : res.get(0);
    }

    private static List<Gob> query(OpContext c, Args a, int limit) {
        NAlias name = a.alias("name", null);
        NAlias pose = a.alias("pose", null);
        Pair<Coord2d, Coord2d> rect = null;
        if (a.has("area")) {
            NArea ar = a.area("area");
            rect = ar.getRCArea();
            if (rect == null)
                throw ApiError.notFound("area '" + ar.name + "' is not loaded around the player (too far away or hidden)");
        }
        Gob pl = c.gui.map.player();
        Coord2d near = a.has("near") ? a.coord2d("near") : (pl == null ? null : pl.rc);
        double radius = a.d("radius", -1);
        if (radius >= 0 && near == null) throw ApiError.notFound("no player position to measure the radius from");
        boolean hasAttr = a.has("attr");
        long attr = hasAttr ? a.l("attr") : 0;
        Set<Long> exclude = new HashSet<>();
        if (a.has("exclude")) {
            JSONArray ex = a.arr("exclude");
            for (int i = 0; i < ex.length(); i++) {
                Object o = ex.get(i);
                if (o instanceof Number) exclude.add(((Number) o).longValue());
                else if (o instanceof JSONObject && ((JSONObject) o).has("gob")) exclude.add(((JSONObject) o).getLong("gob"));
                else throw ApiError.badArgs("'exclude' must be a list of gob ids or gob snapshots");
            }
        }
        String sort = a.str("sort", "dist");
        if (!sort.equals("dist") && !sort.equals("none")) throw ApiError.badArgs("sort must be \"dist\" or \"none\"");
        long plid = c.gui.map.plgob;

        List<Gob> res = new ArrayList<>();
        OCache oc = c.gui.map.glob.oc;
        synchronized (oc) {
            for (Gob g : oc) {
                if (g instanceof OCache.Virtual || g instanceof MapView.Plob || g.attr.isEmpty() || g.id == plid)
                    continue;
                if (g.getClass().getName().contains("GlobEffector")) continue;
                if (exclude.contains(g.id)) continue;
                if (name != null && (g.ngob == null || !NParser.checkName(g.ngob.name, name))) continue;
                if (rect != null && !(g.rc.x >= rect.a.x && g.rc.y >= rect.a.y && g.rc.x <= rect.b.x && g.rc.y <= rect.b.y))
                    continue;
                if (radius >= 0 && g.rc.dist(near) > radius) continue;
                if (pose != null) {
                    String p = g.pose();
                    if (p == null || !NParser.checkName(p, pose)) continue;
                }
                if (hasAttr && (g.ngob == null || g.ngob.getModelAttribute() != attr)) continue;
                res.add(g);
            }
        }
        if (sort.equals("dist") && near != null) {
            final Coord2d from = near;
            res.sort(Comparator.comparingDouble(g -> g.rc.dist(from)));
        }
        if (limit > 0 && res.size() > limit) res = new ArrayList<>(res.subList(0, limit));
        return res;
    }

    @ApiOp(name = "gobs.player", kind = QUERY,
            doc = "The player's own game object. For character state (stamina, hand, speed) use `player.get`.",
            returns = "Gob", errors = {Errors.NOT_FOUND},
            example = "me = nc.gobs.player()\nprint(me[\"rc\"])")
    public static Object player(OpContext c, Args a) {
        Gob pl = c.gui.map.player();
        if (pl == null) throw ApiError.notFound("the player object is not loaded");
        return pl;
    }

    @ApiOp(name = "gobs.lifted", kind = QUERY,
            doc = "The object the player is carrying overhead (a lifted log, barrel, chest...), or null.",
            returns = "Gob|null",
            example = "barrel = nc.gobs.lifted()\nif barrel is not None:\n    print(\"carrying\", barrel[\"name\"])")
    public static Object lifted(OpContext c, Args a) {
        long plid = c.gui.map.plgob;
        if (plid == -1) return null;
        OCache oc = c.gui.map.glob.oc;
        synchronized (oc) {
            for (Gob g : oc) {
                Following fl = g.getattr(Following.class);
                if (fl != null && fl.tgt == plid) return g;
            }
        }
        return null;
    }

    @ApiOp(name = "gobs.hitbox", kind = QUERY,
            doc = "The object's collision box as the client's pathfinder sees it, or null if it has none (or its resource has not loaded). "
                    + "`begin`/`end` are the box corners in the object's own frame; `corners` are the four world-space corners after rotation; "
                    + "`parts` lists one corner set per part for objects with several solid parts (gates, passages).",
            params = {@ApiParam(name = "gob", type = "Gob", doc = "Gob handle or id.")},
            returns = "dict|null", returnsDoc = "{begin: [x, y], end: [x, y], a, corners: [[x, y] x4], parts: [[[x, y] x4], ...]}",
            errors = {Errors.GOB_GONE},
            example = "hb = nc.gobs.hitbox(chest)\nprint(hb[\"corners\"] if hb else \"no hitbox\")")
    public static Object hitbox(OpContext c, Args a) {
        Gob g = a.gob("gob");
        if (g.ngob == null || g.ngob.hitBox == null) return null;
        JSONObject j = new JSONObject();
        j.put("begin", Codec.coord(g.ngob.hitBox.begin));
        j.put("end", Codec.coord(g.ngob.hitBox.end));
        j.put("a", g.a);
        j.put("corners", corners(new NHitBoxD(g)));
        JSONArray parts = new JSONArray();
        if (g.ngob.hitBoxParts != null) {
            for (NHitBoxD p : NHitBoxD.solid(g)) parts.put(corners(p));
        }
        j.put("parts", parts);
        return j;
    }

    private static JSONArray corners(NHitBoxD b) {
        JSONArray res = new JSONArray();
        for (Coord2d p : b.c) {
            if (p != null) res.put(Codec.coord(p));
        }
        return res;
    }

    // ------------------------------------------------------------------ events

    /** The script-side session that both runs on {@code glob} and wants {@code kind}; null when nobody listens. */
    private static NUI listener(Glob glob, String kind) {
        ScriptServer s = ScriptRuntime.server();
        if (s == null || glob == null) return null;
        for (ScriptConnection conn : s.connections()) {
            for (ScriptConnection.Subscription sub : conn.subscriptions()) {
                if (sub.run.ui != null && sub.run.ui.sess != null && sub.run.ui.sess.glob == glob && sub.wants(kind))
                    return sub.run.ui;
            }
        }
        return null;
    }

    /**
     * Called by NGob the first time a gob's resource name is known. Publishes {@code gob.new} {gob, name, rc}.
     * Runs while the gob's attributes are being set, so it must not take the OCache lock (no Codec.gob here).
     */
    public static void onGobNamed(Gob g, String name) {
        NUI ui = listener(g.glob, "gob.new");
        if (ui == null) return;
        ScriptEvents.publish(ui, "gob.new", new JSONObject().put("gob", g.id).put("name", name).put("rc", Codec.coord(g.rc)));
    }

    /** Called by OCache when a gob is removed. Publishes {@code gob.gone} {gob, name}. */
    public static void onGobRemoved(Glob glob, Gob g) {
        // Only objects that were announced (had a name): the pathfinder's temporary helper objects never are.
        String name = (g.ngob == null) ? null : g.ngob.name;
        if (name == null) return;
        NUI ui = listener(glob, "gob.gone");
        if (ui == null) return;
        ScriptEvents.publish(ui, "gob.gone", new JSONObject().put("gob", g.id).put("name", name));
    }

    private GobOps() {}
}
