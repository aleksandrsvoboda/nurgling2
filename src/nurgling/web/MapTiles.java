package nurgling.web;

import haven.*;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The explored map beyond the loaded grids, from the client's map store (what the minimap and
 * the world map draw). One image is a 100x100 rendering of a segment grid at a zoom level: level
 * 0 is one grid, level l covers 2^l x 2^l grids, so far zooms stay as cheap as near ones.
 */
public class MapTiles {
    private static final int MAXCACHE = 1500;
    private static final long TTL = 60000;
    private final Supplier<UI> uis;
    private final Map<String, Object[]> cache = new LinkedHashMap<String, Object[]>(256, 0.75f, true) {
	protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) {
	    return(size() > MAXCACHE);
	}
    };

    public MapTiles(Supplier<UI> uis) {
	this.uis = uis;
    }

    private static MapFile file(UI ui) {
	if((ui == null) || (ui.gui == null) || (ui.gui.mapfile == null))
	    return(null);
	return(ui.gui.mapfile.file);
    }

    /** Which segment the player is in, and its offset: segment tile = session tile + (ox, oy). */
    public static JSONObject where(UI ui) {
	MapFile file = file(ui);
	if((file == null) || (ui.gui.map == null))
	    return(null);
	Gob pl = ui.gui.map.player();
	if(pl == null)
	    return(null);
	if(!file.lock.readLock().tryLock())
	    return(null);
	try {
	    MCache.Grid g = ui.gui.map.glob.map.getgridt(pl.rc.floor(MCache.tilesz));
	    MapFile.GridInfo info = file.gridinfo.get(g.id);
	    if(info == null)
		return(null);
	    Coord off = info.sc.sub(g.gc).mul(MCache.cmaps);
	    return(new JSONObject().put("t", "seg").put("k", "seg").put("id", Long.toString(info.seg)).put("ox", off.x).put("oy", off.y));
	} catch(Loading e) {
	    return(null);
	} finally {
	    file.lock.readLock().unlock();
	}
    }

    public WebServer.Response grid(String segs, String ls, String xs, String ys) throws IOException {
	long seg;
	int lvl, x, y;
	try {
	    seg = Long.parseLong(segs);
	    lvl = Integer.parseInt(ls);
	    x = Integer.parseInt(xs);
	    y = Integer.parseInt(ys);
	} catch(NumberFormatException | NullPointerException e) {
	    return(WebServer.Response.text(400, "bad grid"));
	}
	if((lvl < 0) || (lvl > 6) || ((x & ((1 << lvl) - 1)) != 0) || ((y & ((1 << lvl) - 1)) != 0))
	    return(WebServer.Response.text(400, "bad level"));
	String key = seg + "/" + lvl + "/" + x + "/" + y;
	long now = System.currentTimeMillis();
	synchronized(cache) {
	    Object[] hit = cache.get(key);
	    if((hit != null) && (now - (Long)hit[0] < TTL))
		return(png((byte[])hit[1]));
	}
	MapFile file = file(uis.get());
	if(file == null)
	    return(WebServer.Response.text(503, "no map yet"));
	MapFile.DataGrid g;
	file.lock.readLock().lock();
	try {
	    MapFile.Segment s = file.segments.get(seg);
	    if(s == null)
		return(null);
	    g = s.grid(lvl, Coord.of(x, y)).get();
	} catch(Loading e) {
	    /* Still being read from disk: the browser asks again shortly. */
	    return(WebServer.Response.text(503, "loading").header("Retry-After", "1"));
	} finally {
	    file.lock.readLock().unlock();
	}
	if(g == null)
	    return(null);
	byte[] png;
	try {
	    ByteArrayOutputStream buf = new ByteArrayOutputStream();
	    ImageIO.write(g.render(Coord.of(x, y).mul(MCache.cmaps)), "png", buf);
	    png = buf.toByteArray();
	} catch(Loading e) {
	    return(WebServer.Response.text(503, "loading").header("Retry-After", "1"));
	}
	synchronized(cache) {
	    cache.put(key, new Object[] {now, png});
	}
	return(png(png));
    }

    private static WebServer.Response png(byte[] png) {
	return(new WebServer.Response(200, "image/png", png).header("Cache-Control", "max-age=60"));
    }
}
