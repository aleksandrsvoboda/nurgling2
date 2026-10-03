package haven.res.gfx.fx.flight;

import haven.*;
import haven.render.*;
import java.util.*;

/* >spr: Flicker */
/* >rlink: Flicker */
/* Nurgling: local copy of gfx/fx/flight v6; adds embers to warm fire
 * lights while realistic fire (a graphics option) is on. When the
 * resource changes version, the server's code is used instead. */
@haven.FromResource(name = "gfx/fx/flight", version = 6)
public class Flicker extends Sprite implements TickList.TickNode, TickList.Ticking {
    public final Random rnd = new Random();
    public final PosLight l;
    public final float[] amb, dif, spc;
    public Pipe.Op pos = null;
    public float min = 0.5f, rt = 0.1f;
    private float s, e, t, a;
    
    float nl() {return((rnd.nextFloat() * (1.0f - min)) + min);}
    float nt() {return(((rnd.nextFloat() - 0.5f) * rt * 0.5f) + rt);}

    public void reset() {
	s = nl();
	e = nl();
	t = nt();
	a = 0;
    }

    public Flicker(Owner owner, Resource res, PosLight l) {
	super(owner, res);
	this.l = l;
	amb = l.amb;
	dif = l.dif;
	spc = l.spc;
    }

    public static Flicker mksprite(Owner owner, Resource res, Message sdt) {
	float x = sdt.float8() * 11;
	float y = sdt.float8() * 11;
	float z = sdt.float8() * 11;
	Indir<Resource> lres = owner.context(Resource.Resolver.class).getres(sdt.uint16());
	Flicker f = new Flicker(owner, res, (PosLight)lres.get().layer(Light.Res.class).make());
	if((x != 0) || (y != 0) || (z != 0))
	    f.pos = Location.xlate(new Coord3f(x, y, z));
	if(!sdt.eom()) f.min = sdt.unorm8();
	if(!sdt.eom()) f.rt  = sdt.float8();
	f.reset();
	return(f);
    }

    public static Flicker mkrlink(Owner owner, Resource res, Object... args) {
	Resource lres = res;
	float x = 0, y = 0, z = 0;
	int a = 0;
	if((args.length > a) && (args[a] instanceof String))
	    lres = res.pool.load((String)args[a++], (Integer)args[a++]).get();
	PosLight l = null;
	int id = -1;
	if((args.length > a) && (args[a] instanceof Integer))
	    id = (Integer)args[a++];
	for(Light.Res lr : lres.layers(Light.Res.class)) {
	    if((id < 0) || (lr.id == id)) {
		l = (PosLight)lr.make();
		break;
	    }
	}
	Flicker f = new Flicker(owner, res, l);
	while(a < args.length) {
	    Object[] opt = (Object[])args[a++];
	    switch((String)opt[0]) {
	    case "x": x = ((Number)opt[1]).floatValue(); break;
	    case "y": y = ((Number)opt[1]).floatValue(); break;
	    case "z": z = ((Number)opt[1]).floatValue(); break;
	    case "min": f.min = ((Number)opt[1]).floatValue(); break;
	    case "rate": f.rt = ((Number)opt[1]).floatValue(); break;
	    }
	}
	if((x != 0) || (y != 0) || (z != 0))
	    f.pos = Location.xlate(new Coord3f(x, y, z));
	f.reset();
	return(f);
    }
    
    public static float[] cmul(float[] dst, float[] src, float f) {
	if(dst == null)
	    dst = new float[src.length];
	for(int i = 0; i < dst.length; i++)
	    dst[i] = src[i] * f;
	return(dst);
    }

    public void added(RenderTree.Slot slot) {
	if(pos != null)
	    slot.ostate(pos);
	slot.add(l);
	if((l != null) && (dif[0] > (dif[2] * 1.5f))) {
	    Gob gob = owner.fcontext(Gob.class, false);
	    slot.add(new nurgling.render.Embers(gob, l));
	}
    }

    public static float[] mul(float[] c, float v) {
	float[] r = new float[c.length];
	for(int i = 0; i < r.length; i++)
	    r[i] = c[i] * v;
	return(r);
    }

    public TickList.Ticking ticker() {return(this);}
    public void autotick(double dt) {
	if((a += dt) > t) {
	    s = e;
	    e = nl();
	    a -= t;
	    t = nt();
	    if(a > t) {
		s = e;
		e = nl();
		a = 0;
	    }
	}
	float c = s + ((a / t) * (e - s));

	l.amb = mul(amb, c);
	l.dif = mul(dif, c);
	l.spc = mul(spc, c);
	// l.al = al / (float)c;
	// l.aq = aq / (float)c;
    }
}

