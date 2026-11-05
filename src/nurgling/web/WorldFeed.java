package nurgling.web;

import haven.*;
import nurgling.pf.NHitBoxD;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * What the map shows: ground grids (tile ids and heights) and the objects around the player.
 * Coordinates are world units of this session (gob.rc); a tile is 11 units, a grid 100x100 tiles.
 */
public class WorldFeed {
    /** Objects farther than this from the player are not sent. */
    public static final double RADIUS = 60 * MCache.tilesz.x;

    /** gob id -> its view model, for everything in range. */
    public Map<Long, JSONObject> gobs(MapView map) {
	Map<Long, JSONObject> out = new HashMap<>();
	Gob pl = map.player();
	if(pl == null)
	    return(out);
	Coord2d pc = pl.rc;
	OCache oc = map.glob.oc;
	List<Gob> near = new ArrayList<>();
	synchronized(oc) {
	    for(Gob g : oc) {
		if((g.rc != null) && (g.rc.dist(pc) <= RADIUS))
		    near.add(g);
	    }
	}
	for(Gob g : near) {
	    JSONObject o = gob(g, map.plgob);
	    if(o != null)
		out.put(g.id, o);
	}
	return(out);
    }

    private static JSONObject gob(Gob g, long plgob) {
	String res = (g.ngob != null) ? g.ngob.name : null;
	if(res == null)
	    return(null);
	JSONObject o = new JSONObject();
	o.put("id", g.id).put("r", res);
	o.put("x", r1(g.rc.x)).put("y", r1(g.rc.y)).put("a", Math.round(g.a * 100) / 100.0);
	if(g.id == plgob)
	    o.put("me", true);
	Moving mv = g.getattr(Moving.class);
	if(mv instanceof LinMove) {
	    LinMove lm = (LinMove)mv;
	    if(lm.v != null) {
		o.put("vx", r1(lm.v.x)).put("vy", r1(lm.v.y));
		/* Where the walk ends, once the server has said how long it takes. */
		if(!Double.isNaN(lm.e) && (lm.s != null)) {
		    Coord2d end = lm.s.add(lm.v.mul(lm.e));
		    o.put("tx", r1(end.x)).put("ty", r1(end.y));
		}
	    }
	}
	/* Aggro rings: the same per-animal settings and rule as the desktop overlay (shown, alive). */
	if(res.startsWith("gfx/kritter/")) {
	    nurgling.conf.NAreaRad rad = nurgling.conf.NAreaRad.get(res);
	    if((rad != null) && rad.vis && (rad.radius > 0) && !nurgling.conf.NAreaRad.isDownOrDead(g))
		o.put("ar", rad.radius).put("ad", rad.dangerous);
	}
	GobIcon icon = g.getattr(GobIcon.class);
	if(icon != null) {
	    String ic = UiMirror.resname(icon.res);
	    if(ic != null)
		o.put("ic", ic);
	}
	if(isportal(res))
	    o.put("portal", true);
	JSONArray doors = doors(g, res);
	if(doors != null)
	    o.put("doors", doors);
	if((g.ngob != null) && (g.ngob.hitBox != null)) {
	    NHitBoxD hb = new NHitBoxD(g);
	    JSONArray c = new JSONArray();
	    for(Coord2d p : hb.c) {
		if(p == null) {
		    c = null;
		    break;
		}
		c.put(r1(p.x)).put(r1(p.y));
	    }
	    if(c != null)
		o.put("hb", c);
	}
	return(o);
    }

    /* ---- entrances, as the bots use them (NUtils.openDoorOnAGob, ChunkNavExecutor) ---- */

    private static final String[] BUILDINGS = {"stonemansion", "logcabin", "timberhouse", "stonestead", "greathall", "stonetower", "windmill", "primitivetent"};
    private static final String[] PORTALS = {"cellar", "stairs", "ladder", "minehole", "cavein", "caveout", "-door"};

    /** Entered with a plain right-click on the object itself. */
    static boolean isportal(String res) {
	for(String p : PORTALS) {
	    if(res.contains(p))
		return(true);
	}
	return(false);
    }

    static boolean isbuilding(String res) {
	if(!res.startsWith("gfx/terobjs/arch/") || res.contains("-door"))
	    return(false);
	for(String b : BUILDINGS) {
	    if(res.contains(b))
		return(true);
	}
	return(false);
    }

    /** The doors of a house: [x, y, mesh] each. The door is the house's mesh 16 (a great hall's 16-18)
     *  on the +x edge of its hitbox; clicking that mesh enters. */
    static JSONArray doors(Gob g, String res) {
	if(!isbuilding(res) || (g.ngob == null) || (g.ngob.hitBox == null))
	    return(null);
	nurgling.NHitBox hb = g.ngob.hitBox;
	Coord2d a = Coord2d.of(hb.end.x, hb.begin.y).rot(g.a).add(g.rc);
	Coord2d b = Coord2d.of(hb.end.x, hb.end.y).rot(g.a).add(g.rc);
	JSONArray out = new JSONArray();
	if(res.contains("greathall")) {
	    Coord2d d18 = a.add(b.sub(a).div(4)), d16 = b.add(a.sub(b).div(4)), d17 = b.sub(a).div(2).add(a);
	    out.put(door(d18, 18)).put(door(d17, 17)).put(door(d16, 16));
	} else {
	    out.put(door(b.sub(a).div(2).add(a), 16));
	}
	return(out);
    }

    private static JSONArray door(Coord2d c, int mesh) {
	return(new JSONArray().put(r1(c.x)).put(r1(c.y)).put(mesh));
    }

    public static class GridMsg {
	public final long id;
	public final int seq;
	public final String json;

	GridMsg(long id, int seq, String json) {this.id = id; this.seq = seq; this.json = json;}
    }

    /** The grids loaded now, by grid id; only grids not already in {@code have} at the same seq are encoded. */
    public Map<Long, GridMsg> grids(MCache map, Map<Long, GridMsg> have) {
	List<MCache.Grid> cur;
	synchronized(map.grids) {
	    cur = new ArrayList<>(map.grids.values());
	}
	Map<Long, GridMsg> out = new HashMap<>();
	for(MCache.Grid g : cur) {
	    GridMsg prev = have.get(g.id);
	    if((prev != null) && (prev.seq == g.seq)) {
		out.put(g.id, prev);
		continue;
	    }
	    GridMsg m = encode(map, g);
	    if(m != null)
		out.put(g.id, m);
	}
	return(out);
    }

    private static GridMsg encode(MCache map, MCache.Grid g) {
	int n = MCache.cmaps.x * MCache.cmaps.y;
	ByteBuffer tb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
	ByteBuffer zb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
	Set<Integer> used = new TreeSet<>();
	for(int i = 0; i < n; i++) {
	    int t = g.tiles[i];
	    tb.putShort((short)t);
	    zb.putFloat(g.z[i]);
	    used.add(t);
	}
	JSONObject sets = new JSONObject();
	for(int t : used) {
	    String name = map.tilesetname(t);
	    /* A tileset still loading: try again next pass rather than send a hole. */
	    if(name == null)
		return(null);
	    sets.put(Integer.toString(t), name);
	}
	/* Cliffs: the tiles the minimap darkens (a ridge broken by a height step). */
	byte[] cliff = new byte[(n + 7) / 8];
	boolean anycliff = false;
	for(int y = 0; y < MCache.cmaps.y; y++) {
	    for(int x = 0; x < MCache.cmaps.x; x++) {
		try {
		    if(haven.resutil.Ridges.brokenp(map, g.ul.add(x, y))) {
			int i = (y * MCache.cmaps.x) + x;
			cliff[i >> 3] |= (byte)(1 << (i & 7));
			anycliff = true;
		    }
		} catch(Loading e) {
		    /* A neighbour grid not loaded yet: that edge tile shows no cliff. */
		}
	    }
	}
	JSONObject o = new JSONObject().put("t", "grid").put("id", Long.toString(g.id))
	    .put("gx", g.gc.x).put("gy", g.gc.y).put("ux", g.ul.x).put("uy", g.ul.y)
	    .put("tiles", Base64.getEncoder().encodeToString(tb.array()))
	    .put("z", Base64.getEncoder().encodeToString(zb.array()))
	    .put("sets", sets);
	if(anycliff)
	    o.put("cliff", Base64.getEncoder().encodeToString(cliff));
	return(new GridMsg(g.id, g.seq, o.toString()));
    }

    static double r1(double v) {
	return(Math.round(v * 10) / 10.0);
    }
}
