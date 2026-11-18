package nurgling.render.remembered;

import haven.*;
import haven.render.*;
import java.util.*;
import java.util.function.Predicate;

import static haven.MCache.cutn;
import static haven.MCache.cutsz;
import static haven.MCache.tilesz;

/**
 * Remembered ground drawn at full detail: the recalled cache's own cut
 * meshes, built the way live terrain is, for the grids the far ground hands
 * over as close enough for their tiles to show. Every cut the live terrain
 * has in the scene is left to it, so the two never draw the same ground.
 */
public class NearGround implements RenderTree.Node {
    /** Cut meshes asked for and not yet built, at most. */
    static final int MAXBUILD = 12;

    private RenderTree.Slot slot;
    private final Map<Coord, Pair<MapMesh, RenderTree.Slot>> cuts = new HashMap<>();
    /** Cuts whose mesh threw something other than Loading: never asked again. */
    private final Set<Coord> failed = new HashSet<>();
    private final Map<Coord, MapMesh> built = new HashMap<>();

    public void added(RenderTree.Slot slot) {
	this.slot = slot;
    }

    public void removed(RenderTree.Slot slot) {
	this.slot = null;
	cuts.clear();
    }

    public int drawn() {
	return(cuts.size());
    }

    /**
     * Builds the cuts of {@code grids} (session grid coords, most wanted
     * first) that the live terrain does not draw, and answers the grids all
     * of whose cuts are built.
     */
    public Set<Coord> prepare(MCache map, List<Coord> grids, Predicate<Coord> livecut) {
	Set<Coord> ready = new HashSet<>();
	built.clear();
	int pending = 0;
	for(Coord gc : grids) {
	    MCache.Grid g;
	    synchronized(map.grids) {
		g = map.grids.get(gc);
	    }
	    if(g == null)
		continue;
	    boolean all = true;
	    for(int y = 0; y < cutn.y; y++) {
		for(int x = 0; x < cutn.x; x++) {
		    Coord cc = gc.mul(cutn).add(x, y);
		    if(failed.contains(cc))
			continue;
		    /* Cuts under the live terrain are built too, though not
		     * drawn: when it lets go of one, ours is there to take over. */
		    boolean live = livecut.test(cc);
		    if(!g.cuts[x + (y * cutn.x)].mesh.isReady() && (pending >= MAXBUILD)) {
			all &= live;
			continue;
		    }
		    try {
			built.put(cc, map.getcut(cc));
		    } catch(Loading l) {
			all &= live;
			pending++;
		    } catch(RuntimeException e) {
			/* A recorded tileset that no longer loads, or a record
			 * the mesher cannot read: leave the cut out rather than
			 * take the game's UI thread down with it. */
			new Warning(e, "remembered ground: cannot build cut " + cc).issue();
			failed.add(cc);
		    }
		}
	    }
	    if(all)
		ready.add(gc);
	}
	return(ready);
    }

    /** Puts the built cuts of {@code shown} in the scene and takes every
     * other cut out of it. Cuts are added before the ones they replace go. */
    public void show(Set<Coord> shown, Predicate<Coord> livecut) {
	if(slot == null)
	    return;
	Set<Coord> keep = new HashSet<>();
	for(Map.Entry<Coord, MapMesh> e : built.entrySet()) {
	    Coord cc = e.getKey();
	    if(!shown.contains(cc.div(cutn)) || livecut.test(cc))
		continue;
	    keep.add(cc);
	    MapMesh mesh = e.getValue();
	    Pair<MapMesh, RenderTree.Slot> cur = cuts.get(cc);
	    if((cur == null) || (cur.a != mesh)) {
		Coord2d pc = cc.mul(cutsz).mul(tilesz);
		RenderTree.Slot ns = slot.add(mesh, Location.xlate(new Coord3f((float)pc.x, -(float)pc.y, 0)));
		cuts.put(cc, new Pair<>(mesh, ns));
		if(cur != null)
		    cur.b.remove();
	    }
	}
	for(Iterator<Map.Entry<Coord, Pair<MapMesh, RenderTree.Slot>>> i = cuts.entrySet().iterator(); i.hasNext();) {
	    Map.Entry<Coord, Pair<MapMesh, RenderTree.Slot>> e = i.next();
	    if(!keep.contains(e.getKey())) {
		e.getValue().b.remove();
		i.remove();
	    }
	}
    }

    /** Takes out of the scene, at once, every cut the live terrain has
     * just put in: two meshes over one piece of ground fight. */
    public void yield(Predicate<Coord> livecut) {
	for(Iterator<Map.Entry<Coord, Pair<MapMesh, RenderTree.Slot>>> i = cuts.entrySet().iterator(); i.hasNext();) {
	    Map.Entry<Coord, Pair<MapMesh, RenderTree.Slot>> e = i.next();
	    if(livecut.test(e.getKey())) {
		e.getValue().b.remove();
		i.remove();
	    }
	}
    }

    /** The grids with a cut in the scene. */
    public Set<Coord> drawngrids() {
	Set<Coord> ret = new HashSet<>();
	for(Coord cc : cuts.keySet())
	    ret.add(cc.div(cutn));
	return(ret);
    }

    public void clear() {
	for(Pair<MapMesh, RenderTree.Slot> c : cuts.values())
	    c.b.remove();
	cuts.clear();
	built.clear();
	failed.clear();
    }
}
