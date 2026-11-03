package nurgling.scripting.ops;

import haven.*;
import nurgling.actions.PathFinder;
import nurgling.pf.Graph;
import nurgling.scripting.api.*;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedList;

import static haven.MCache.cmaps;
import static haven.MCache.tilesz;
import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/** Terrain, reachability and coordinate conversion. */
public final class MapOps {

    /** Max tiles one map.tiles call scans (a 100x100 grid). */
    private static final int MAX_TILES = 10000;

    /** The tileset resource name at tile {@code tc}, or null if its grid is not loaded. */
    private static String tileName(MCache map, Coord tc) {
        Coord gc = tc.div(cmaps);
        synchronized (map.grids) {
            if (!map.grids.containsKey(gc)) return null;
        }
        return map.tilesetname(map.gettile(tc));
    }

    @ApiOp(name = "map.tile", kind = QUERY,
            doc = "The terrain under a world position as its tile resource name, e.g. \"gfx/tiles/grass\", \"gfx/tiles/water\", "
                    + "\"gfx/tiles/rocks/...\". Null if that part of the map is not loaded.",
            params = {@ApiParam(name = "pos", type = "Coord|Gob", doc = "World position, or a gob for its position.")},
            returns = "str|null", errors = {Errors.GOB_GONE},
            example = "if \"water\" in (nc.map.tile(nc.gobs.player()) or \"\"):\n    print(\"swimming\")")
    public static Object tile(OpContext c, Args a) {
        return tileName(c.gui.map.glob.map, a.coord2d("pos").floor(tilesz));
    }

    @ApiOp(name = "map.tiles", kind = QUERY,
            doc = "The tiles covering a world rectangle, optionally only those whose resource name matches `name`. "
                    + "At most 10000 tiles (100x100) per call; split larger rectangles. Tiles in unloaded grids are left out. "
                    + "`tile` is the tile coordinate (world position / 11, floored) and `rc` its centre in world units.",
            params = {
                    @ApiParam(name = "rect", type = "Rect", doc = "World rectangle [[x1, y1], [x2, y2]]."),
                    @ApiParam(name = "name", type = "Alias", doc = "Tile resource pattern(s), e.g. \"water\" or [\"grass\", \"moor\"].", optional = true)},
            returns = "list[dict]", returnsDoc = "[{tile: [x, y], rc: [x, y], name}]", errors = {Errors.BAD_ARGS},
            example = "me = nc.gobs.player()[\"rc\"]\nwater = nc.map.tiles([[me[0]-110, me[1]-110], [me[0]+110, me[1]+110]], name=\"water\")")
    public static Object tiles(OpContext c, Args a) {
        Pair<Coord2d, Coord2d> r = a.rect("rect");
        NAlias name = a.alias("name", null);
        Coord ul = r.a.floor(tilesz), br = r.b.floor(tilesz);
        long n = (long) (br.x - ul.x + 1) * (long) (br.y - ul.y + 1);
        if (n > MAX_TILES) throw ApiError.badArgs("rect covers " + n + " tiles; at most " + MAX_TILES + " per call");
        MCache map = c.gui.map.glob.map;
        JSONArray res = new JSONArray();
        for (int y = ul.y; y <= br.y; y++) {
            for (int x = ul.x; x <= br.x; x++) {
                Coord tc = Coord.of(x, y);
                String nm = tileName(map, tc);
                if (nm == null || (name != null && !NParser.checkName(nm, name))) continue;
                res.put(new JSONObject()
                        .put("tile", Codec.coord(tc))
                        .put("rc", Codec.coord(tc.mul(tilesz).add(tilesz.div(2))))
                        .put("name", nm));
            }
        }
        return res;
    }

    private static boolean isGob(Args a, String n) {
        return a.raw().opt(n) instanceof JSONObject && ((JSONObject) a.raw().get(n)).has("gob")
                || a.raw().opt(n) instanceof Number;
    }

    @ApiOp(name = "map.reachable", kind = QUERY,
            doc = "Whether the client's pathfinder finds a walkable route from the player to a game object (to a spot next to it) or to a position. "
                    + "Runs the pathfinder once without moving; it only knows the loaded area around the player, so far targets read as unreachable.",
            params = {@ApiParam(name = "target", type = "Gob|Coord", doc = "A gob (handle or id) or a world position [x, y].")},
            returns = "bool", errors = {Errors.GOB_GONE},
            example = "if nc.map.reachable(chest):\n    nc.actions.path_to(chest)")
    public static Object reachable(OpContext c, Args a) throws InterruptedException {
        if (isGob(a, "target")) return PathFinder.isAvailable(a.gob("target"));
        return PathFinder.isAvailable(a.coord2d("target"));
    }

    @ApiOp(name = "map.path_length", kind = QUERY,
            doc = "How far the player would walk to reach a gob or position along the pathfinder's route, in world units "
                    + "(0 when already there), or null when there is no route. Never shorter than the straight-line distance.",
            params = {@ApiParam(name = "target", type = "Gob|Coord", doc = "A gob (handle or id) or a world position [x, y].")},
            returns = "float|null", errors = {Errors.GOB_GONE},
            example = "trees = nc.gobs.find(name=\"trees/\", radius=300)\nbest = min(trees, key=lambda t: nc.map.path_length(t) or 1e9)")
    public static Object pathLength(OpContext c, Args a) throws InterruptedException {
        if (isGob(a, "target")) {
            double d = PathFinder.walkLength(a.gob("target"));
            return Double.isInfinite(d) ? null : (Object) d;
        }
        Coord2d pos = a.coord2d("target");
        Gob pl = c.gui.map.player();
        if (pl == null) return null;
        LinkedList<Graph.Vertex> path = new PathFinder(pos).construct(true);
        if (path == null) return PathFinder.isAvailable(pos) ? (Object) 0.0 : null;
        double len = 0;
        Coord2d at = pl.rc;
        for (Graph.Vertex v : path) {
            Coord2d next = nurgling.pf.Utils.pfGridToWorld(v.pos);
            len += at.dist(next);
            at = next;
        }
        return len;
    }

    @ApiOp(name = "map.to_global", kind = QUERY,
            doc = "Converts a world position of this session into a session-independent one: the map grid's id and the offset inside it "
                    + "(world units, 0..1100). World coordinates (`rc`) are only valid within one session and change after a relog; "
                    + "global ones can be stored and shared between characters. Null if the position's grid is not loaded.",
            params = {@ApiParam(name = "pos", type = "Coord|Gob", doc = "World position, or a gob for its position.")},
            returns = "dict|null", returnsDoc = "{grid, x, y}", errors = {Errors.GOB_GONE},
            example = "spot = nc.map.to_global(nc.gobs.player())\n# later, maybe in another session:\npos = nc.map.from_global(spot[\"grid\"], spot[\"x\"], spot[\"y\"])")
    public static Object toGlobal(OpContext c, Args a) {
        Coord2d pos = a.coord2d("pos");
        MCache map = c.gui.map.glob.map;
        Coord gc = pos.floor(tilesz).div(cmaps);
        MCache.Grid g;
        synchronized (map.grids) {
            g = map.grids.get(gc);
        }
        if (g == null) return null;
        Coord2d off = pos.sub(g.ul.mul(tilesz));
        return new JSONObject().put("grid", g.id).put("x", off.x).put("y", off.y);
    }

    @ApiOp(name = "map.from_global", kind = QUERY,
            doc = "Converts a global position (grid id + offset, from `map.to_global`) back into this session's world coordinates, "
                    + "or null if that grid is not loaded here.",
            params = {
                    @ApiParam(name = "grid", type = "int", doc = "Map grid id."),
                    @ApiParam(name = "x", type = "float", doc = "Offset inside the grid, world units."),
                    @ApiParam(name = "y", type = "float", doc = "Offset inside the grid, world units.")},
            returns = "Coord|null",
            example = "pos = nc.map.from_global(spot[\"grid\"], spot[\"x\"], spot[\"y\"])\nif pos:\n    nc.actions.path_to(pos)")
    public static Object fromGlobal(OpContext c, Args a) {
        MCache.Grid g = c.gui.map.glob.map.findGrid(a.l("grid"));
        if (g == null) return null;
        return g.ul.mul(tilesz).add(a.d("x"), a.d("y"));
    }

    private MapOps() {}
}
