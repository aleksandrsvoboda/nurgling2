package haven;

import haven.render.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Render node that shows the owner's normal nodes until its card data has loaded,
 * then replaces them with an upright camera-facing card (and an optional ground
 * decal) for as long as cards are enabled. Falls back to the normal nodes for good
 * if the data cannot be loaded.
 */
public abstract class CardNode<A extends CardNode.Art> implements RenderTree.Node {
    private static final float MINCOS = 0.35f;

    public static class Art {
	public final Model face;
	public final Cards.Ground ground;

	public Art(Model face, Cards.Ground ground) {
	    this.face = face;
	    this.ground = ground;
	}
    }

    private final Defer.Future<A> src;
    private volatile A art;
    private volatile boolean dead;
    private final Map<RenderTree.Slot, View> views = new ConcurrentHashMap<>();

    /*
     * A view is set up entirely inside added(), and only published (ready) once that is
     * done; from then on it is only changed by tick(), so no locking is needed.
     */
    private static final class View {
	volatile boolean ready;
	List<RenderTree.Slot> parts;
	RenderTree.Slot face, ground;
	float ux = Float.NaN, uy, sz;
	Location loc;
	Pipe.Op fr;
	float[] gm;
    }

    protected CardNode(Defer.Future<A> src) {
	this.src = src;
    }

    /** The owner's normal render nodes. */
    protected abstract Collection<? extends RenderTree.Node> fallback();

    /**
     * Texture state for the face this frame.
     * @param eye object-to-eye transform, or null if not yet known
     */
    protected abstract Pipe.Op frame(A art, Matrix4f eye);

    private boolean wanted() {
	return((art != null) && Cards.enabled());
    }

    public void added(RenderTree.Slot slot) {
	View v = new View();
	views.put(slot, v);
	if(!wanted() || !showface(slot, v))
	    showparts(slot, v);
	v.ready = true;
    }

    public void removed(RenderTree.Slot slot) {
	views.remove(slot);
    }

    /** @return true while the card is what is being shown. */
    public boolean tick() {
	if(dead)
	    return(false);
	if(art == null) {
	    if(!src.done())
		return(false);
	    A a;
	    try {
		a = src.get();
	    } catch(Defer.DeferredException e) {
		a = null;
	    }
	    if(a == null) {
		dead = true;
		return(false);
	    }
	    art = a;
	}
	boolean want = wanted();
	for(Map.Entry<RenderTree.Slot, View> e : views.entrySet()) {
	    RenderTree.Slot slot = e.getKey();
	    View v = e.getValue();
	    if(!v.ready)
		continue;
	    if(want && (v.face == null)) {
		if(showface(slot, v))
		    hideparts(v);
	    } else if(!want && (v.face != null)) {
		if(showparts(slot, v))
		    hideface(v);
	    }
	    if(v.face != null)
		orient(slot, v);
	}
	return(want);
    }

    /** Adds the face; false if it could not be added yet. */
    private boolean showface(RenderTree.Slot slot, View v) {
	A a = art;
	Matrix4f[] le = mats(slot);
	Facing f = (le == null) ? null : facing(v, le[1]);
	Location loc = (f == null) ? new Location(Matrix4f.id) : f.loc;
	Pipe.Op fr = frame(a, (le == null) ? null : le[1]);
	try {
	    v.face = slot.add(a.face, Pipe.Op.compose(fr, loc));
	} catch(RenderTree.SlotRemoved e) {
	    return(false);
	} catch(Loading e) {
	    /* Texture still being prepared; retry next tick. */
	    return(false);
	}
	v.fr = fr;
	v.loc = loc;
	if(f != null)
	    f.commit(v);
	Cards.seen();
	return(true);
    }

    /** Adds the owner's normal nodes; false (with nothing added) if they could not be added yet. */
    private boolean showparts(RenderTree.Slot slot, View v) {
	List<RenderTree.Slot> ch = new ArrayList<>();
	try {
	    for(RenderTree.Node p : fallback())
		ch.add(slot.add(p));
	} catch(RenderTree.SlotRemoved e) {
	    return(false);
	} catch(Loading e) {
	    remove(ch);
	    return(false);
	}
	v.parts = ch;
	return(true);
    }

    private void hideparts(View v) {
	if(v.parts != null)
	    remove(v.parts);
	v.parts = null;
    }

    private void hideface(View v) {
	List<RenderTree.Slot> rm = new ArrayList<>(2);
	if(v.face != null)
	    rm.add(v.face);
	if(v.ground != null)
	    rm.add(v.ground);
	remove(rm);
	v.face = v.ground = null;
	v.ux = Float.NaN;
	v.loc = null;
	v.fr = null;
	v.gm = null;
    }

    private static void remove(Collection<RenderTree.Slot> slots) {
	for(RenderTree.Slot s : slots) {
	    try {
		s.remove();
	    } catch(RenderTree.SlotRemoved e) {
	    }
	}
    }

    /** {local-to-world, local-to-eye}, or null while there is no camera. */
    private static Matrix4f[] mats(RenderTree.Slot slot) {
	Pipe st = slot.state();
	if(st.get(Homo3D.cam) == null)
	    return(null);
	Matrix4f L = Homo3D.locxf(st);
	return(new Matrix4f[] {L, Homo3D.camxf(st).mul(L)});
    }

    private static final class Facing {
	final float ux, uy, sz;
	final Location loc;

	Facing(float ux, float uy, float sz, Location loc) {
	    this.ux = ux; this.uy = uy; this.sz = sz; this.loc = loc;
	}

	void commit(View v) {
	    v.ux = ux; v.uy = uy; v.sz = sz;
	}
    }

    /**
     * New face transform if it moved noticeably, else null. Does not touch the view.
     * The face is kept parallel to the screen (aligned with the view direction, not
     * turned towards the eye point), so every face on screen shares one orientation.
     */
    private static Facing facing(View v, Matrix4f E) {
	/* Rows of the object-to-eye rotation: screen-right and towards-viewer, in object space. */
	float rx = E.m[0], ry = E.m[4];
	float bx = E.m[2], by = E.m[6], bz = E.m[10];
	float rl = (float)Math.sqrt((rx * rx) + (ry * ry));
	float bl = (float)Math.sqrt((bx * bx) + (by * by) + (bz * bz));
	if((rl < 1e-6f) || (bl < 1e-6f))
	    return(null);
	float ux = rx / rl, uy = ry / rl;
	float hx = -uy, hy = ux;
	if(((bx * hx) + (by * hy)) < 0) {
	    hx = -hx; hy = -hy;
	}
	float sz = 1.0f / Math.max((float)Math.sqrt((bx * bx) + (by * by)) / bl, MINCOS);
	if(!Float.isNaN(v.ux) && (Math.abs(ux - v.ux) + Math.abs(uy - v.uy) + Math.abs(sz - v.sz)) <= 1e-3f)
	    return(null);
	return(new Facing(ux, uy, sz, new Location(new Matrix4f(ux, hx, 0,  0,
								uy, hy, 0,  0,
								0,  0,  sz, 0,
								0,  0,  0,  1))));
    }

    private void orient(RenderTree.Slot slot, View v) {
	RenderTree.Slot face = v.face;
	if(face == null)
	    return;
	Matrix4f[] le = mats(slot);
	if(le == null)
	    return;
	Matrix4f L = le[0], E = le[1];
	A a = art;
	try {
	    Facing f = facing(v, E);
	    Pipe.Op fr = frame(a, E);
	    if((f != null) || (fr != v.fr)) {
		Location loc = (f != null) ? f.loc : v.loc;
		face.cstate(Pipe.Op.compose(fr, loc));
		v.loc = loc;
		v.fr = fr;
		if(f != null)
		    f.commit(v);
	    }
	} catch(RenderTree.SlotRemoved e) {
	    return;
	} catch(Loading e) {
	    /* New frame's texture not ready yet; keep the current one and retry next tick. */
	}
	try {
	    if(a.ground != null) {
		/* Undo the owner's rotation so the decal keeps its world orientation. */
		Matrix4f Li = L.invert();
		float s = (float)Math.sqrt((L.m[0] * L.m[0]) + (L.m[1] * L.m[1]) + (L.m[2] * L.m[2]));
		float[] gm = {Li.m[0] * s, Li.m[4] * s, Li.m[1] * s, Li.m[5] * s, Li.m[2] * s, Li.m[6] * s, 0.3f / s};
		boolean ch = (v.gm == null);
		for(int i = 0; !ch && (i < gm.length); i++)
		    ch = Math.abs(gm[i] - v.gm[i]) > 1e-3f;
		if(ch) {
		    Pipe.Op st = Pipe.Op.compose(a.ground.st, new Location(new Matrix4f(gm[0], gm[1], 0, 0,
											 gm[2], gm[3], 0, 0,
											 gm[4], gm[5], 1, gm[6],
											 0,     0,     0, 1)));
		    if(v.ground == null)
			v.ground = slot.add(a.ground.model, st);
		    else
			v.ground.cstate(st);
		    v.gm = gm;
		}
	    }
	} catch(RenderTree.SlotRemoved e) {
	} catch(Loading e) {
	    /* Decal texture not ready yet; retry next tick. */
	}
    }
}
