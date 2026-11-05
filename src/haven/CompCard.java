package haven;

import haven.render.*;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Card stand-in for an occasional composite creature, with directional frames. */
final class CompCard extends CardNode<CompCard.Data> {
    private static final Map<String, String> KIND = Cards.table(5);
    private static final Map<String, Defer.Future<Data>> cache = new ConcurrentHashMap<>();
    private static final double FRAME = 0.1;

    static final class Data extends CardNode.Art {
	final Pipe.Op[] stand;
	final Pipe.Op[][] walk;
	final Pipe.Op still;

	Data(Model face, Cards.Ground ground, Pipe.Op[] stand, Pipe.Op[][] walk, Pipe.Op still) {
	    super(face, ground);
	    this.stand = stand;
	    this.walk = walk;
	    this.still = still;
	}
    }

    private final Composite comp;
    private double t;

    private CompCard(Composite comp, Defer.Future<Data> src) {
	super(src);
	this.comp = comp;
    }

    static CompCard of(Composite comp) {
	Gob gob = comp.gob;
	if((gob == null) || !Cards.pick(gob.id, 0x51ed270b2e7f3a1dL, 1000))
	    return(null);
	String nm = comp.baseres.name, pfx = Cards.str(4);
	if(!nm.startsWith(pfx))
	    return(null);
	String path = KIND.get(nm.substring(pfx.length()));
	if(path == null)
	    return(null);
	return(new CompCard(comp, cache.computeIfAbsent(path, p -> Defer.later(() -> load(p)))));
    }

    boolean tick(double dt) {
	t += dt;
	return(tick());
    }

    protected Collection<RenderTree.Node> fallback() {
	return(Collections.singletonList(comp.comp));
    }

    protected Pipe.Op frame(Data art, Matrix4f eye) {
	String pose = comp.current_pose;
	if((art.still != null) && (pose != null) && (pose.contains("dead") || pose.contains("knock")))
	    return(art.still);
	int dir = 0;
	if(eye != null) {
	    /* Screen direction of the owner's forward (+X) axis: 0 faces the viewer, then clockwise. */
	    float fx = eye.m[0], fy = eye.m[1];
	    if(((fx * fx) + (fy * fy)) > 1e-8f) {
		double a = Math.toDegrees(Math.atan2(fy, fx));
		dir = Math.floorMod((int)Math.round((-90 - a) / 45.0), 8);
	    }
	}
	if(comp.gob.getv() > 0) {
	    Pipe.Op[] w = art.walk[dir];
	    return(w[(int)(t / FRAME) % w.length]);
	}
	return(art.stand[dir]);
    }

    private static List<Cards.Pic> upper(List<Cards.Pic> pics) {
	List<Cards.Pic> ret = new ArrayList<>();
	for(Cards.Pic p : pics) {
	    if(p.z >= 0)
		ret.add(p);
	}
	ret.sort(Comparator.comparingInt(p -> p.id));
	return(ret);
    }

    private static Data load(String path) {
	int sl = path.indexOf('/');
	String base = Cards.str(4) + path.substring(0, sl + 1), var = path.substring(sl + 1) + "/";
	String st = Cards.str(6), wk = Cards.str(7);
	try {
	    Cards.Pic[] stand = new Cards.Pic[8];
	    Cards.Pic[][] walk = new Cards.Pic[8][];
	    for(int i = 0; i < 8; i++) {
		stand[i] = Cards.biggest(Cards.read(Cards.fetch(base + var + st + "/" + st + "-" + i)), true);
		List<Cards.Pic> w = upper(Cards.read(walkfile(base + var + wk + "/", wk, path.substring(sl + 1), i)));
		if((stand[i] == null) || w.isEmpty())
		    return(null);
		walk[i] = w.toArray(new Cards.Pic[0]);
	    }
	    Cards.Pic still = opt(base + var + Cards.str(8), true);
	    if((still == null) && path.endsWith("/body"))
		still = first(base + Cards.str(9));
	    Cards.Pic shade = opt(base + Cards.str(10), false);

	    List<Cards.Pic> all = new ArrayList<>(Arrays.asList(stand));
	    for(Cards.Pic[] w : walk)
		all.addAll(Arrays.asList(w));
	    if(still != null)
		all.add(still);
	    int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
	    for(Cards.Pic p : all) {
		x0 = Math.min(x0, p.rx()); y0 = Math.min(y0, p.ry());
		x1 = Math.max(x1, p.rx() + p.w()); y1 = Math.max(y1, p.ry() + p.h());
	    }
	    Coord csz = new Coord(Tex.nextp2(x1 - x0), Tex.nextp2(y1 - y0));
	    Pipe.Op[] sst = new Pipe.Op[8];
	    Pipe.Op[][] wst = new Pipe.Op[8][];
	    for(int i = 0; i < 8; i++) {
		sst[i] = canvas(stand[i], x0, y0, csz);
		wst[i] = new Pipe.Op[walk[i].length];
		for(int o = 0; o < walk[i].length; o++)
		    wst[i][o] = canvas(walk[i][o], x0, y0, csz);
	    }
	    Model face = Cards.face(x0, y0, csz.x, csz.y, 1, 1);
	    return(new Data(face, (shade == null) ? null : new Cards.Ground(shade), sst, wst,
			    (still == null) ? null : canvas(still, x0, y0, csz)));
	} catch(IOException e) {
	    return(null);
	}
    }

    /** Some sets name their frame files after the set instead of the action. */
    private static byte[] walkfile(String dir, String action, String set, int i) throws IOException {
	try {
	    return(Cards.fetch(dir + action + "-" + i));
	} catch(IOException e) {
	    return(Cards.fetch(dir + set + "-" + i));
	}
    }

    /** Biggest picture of an optional file, or null if it is not there. */
    private static Cards.Pic opt(String path, boolean upper) {
	try {
	    return(Cards.biggest(Cards.read(Cards.fetch(path)), upper));
	} catch(IOException e) {
	    return(null);
	}
    }

    /** First upright picture of an optional file, or null. */
    private static Cards.Pic first(String path) {
	try {
	    List<Cards.Pic> u = upper(Cards.read(Cards.fetch(path)));
	    return(u.isEmpty() ? null : u.get(0));
	} catch(IOException e) {
	    return(null);
	}
    }

    /** Draws a picture into a shared-origin canvas so every frame fits one quad. */
    private static Pipe.Op canvas(Cards.Pic p, int x0, int y0, Coord csz) {
	BufferedImage buf = TexI.mkbuf(csz);
	Graphics g = buf.getGraphics();
	g.drawImage(p.img, p.rx() - x0, p.ry() - y0, null);
	g.dispose();
	return(Cards.facest(Cards.tex(buf)));
    }
}
