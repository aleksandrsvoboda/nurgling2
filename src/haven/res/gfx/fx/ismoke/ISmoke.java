package haven.res.gfx.fx.ismoke;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.res.lib.env.*;
import java.awt.Color;
import java.nio.*;
import java.util.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/* >spr: ISmoke */
/* >rlink: ISmoke */
/* Nurgling: local copy of gfx/fx/ismoke v111. With realistic smoke (a
 * graphics option) the puffs grow as they rise, turn, fade in instead
 * of popping, and are broken up by noise, and there are more of them.
 * When the resource changes version, the server's code is used
 * instead. */
@haven.FromResource(name = "gfx/fx/ismoke", version = 111)
public class ISmoke extends Sprite implements Rendered, Sprite.CDel, TickList.TickNode, TickList.Ticking {
    static final double agestep = 0.1, maxstep = 0.25;
    /* Per puff: age (0..1), seed, turn. */
    static final Attribute ainfo = new Attribute(VEC4, "smokeinfo");
    static final VertexArray.Layout fmt =
	new VertexArray.Layout(new VertexArray.Layout.Input(Homo3D.vertex,     new VectorFormat(3, NumberFormat.FLOAT32), 0,  0, 24),
			       new VertexArray.Layout.Input(Homo3D.normal,     new VectorFormat(3, NumberFormat.SNORM8),  0, 12, 24),
			       new VertexArray.Layout.Input(VertexColor.color, new VectorFormat(4, NumberFormat.UNORM8),  0, 16, 24),
			       new VertexArray.Layout.Input(ainfo,             new VectorFormat(4, NumberFormat.UNORM8),  0, 20, 24));
    Model model = null;
    VertexArray va = null;
    final Material mat;
    final List<Boll> bollar = new ArrayList<Boll>();
    final Random rnd = new Random();
    final Pipe.Op loc;
    final Color col;
    final float sz, den, fadepow, initzv, life, srad;
    final List<RenderTree.Slot> slots = new ArrayList<>(1);
    final Gob gob = (owner instanceof Gob) ? (Gob)owner : owner.fcontext(Gob.class, false);
    boolean spawn = true;

    public static Resource ctxres(Owner owner) {
	Gob gob = owner.context(Gob.class);
	if(gob == null)
	    throw(new RuntimeException("no context resource for owner " + owner));
	Drawable d = gob.getattr(Drawable.class);
	if(d == null)
	    throw(new RuntimeException("no drawable on object " + gob));
	return(d.getres());
    }

    public ISmoke(Owner owner, Resource res, Message sdt) {
	super(owner, res);
	mat = res.layer(Material.Res.class, sdt.uint8()).get();
	sz = sdt.uint8() / 10.0f;
	String locn = sdt.string();
	if(locn.equals(""))
	    loc = null;
	else
	    loc = ctxres(owner).layer(Skeleton.BoneOffset.class, locn).from(null).get();
	col = Utils.col16(sdt.uint16());
	den = sdt.uint8();
	fadepow = sdt.uint8() / 10.0f;
	life = sdt.uint8() / 10.0f;
	int h = sdt.uint8();
	initzv = h / life;
	srad = sdt.uint8() / 10.0f;
    }

    public ISmoke(Owner owner, Resource res, Object... args) {
	super(owner, res);
	int a = 0;
	String fl = (String)args[a++];
	mat = ((fl.indexOf('o') >= 0) ? res : Resource.classres(ISmoke.class)).layer(Material.Res.class, (Integer)args[a++]).get();
	sz = ((Number)args[a++]).floatValue();
	String locn = (String)args[a++];
	if(locn.equals(""))
	    loc = null;
	else
	    loc = res.layer(Skeleton.BoneOffset.class, locn).from(owner.fcontext(EquipTarget.class, false)).get();
	col = (Color)args[a++];
	den = ((Number)args[a++]).floatValue();
	fadepow = ((Number)args[a++]).floatValue();
	life = ((Number)args[a++]).floatValue();
	float h = ((Number)args[a++]).floatValue();
	initzv = h / life;
	srad = ((Number)args[a++]).floatValue();
    }

    /* By Knuth's algorithm */
    public int prandoom(float mean) {
	float L = (float)Math.exp(-mean), p = 1.0f;
	int k = -1;
	while(p > L) {
	    k++;
	    p *= rnd.nextFloat();
	}
	return(k);
    }

    public boolean tick(double ddt) {
	return(!spawn && bollar.isEmpty());
    }
    public void autotick(double ddt) {
	float dt = (float)Math.min(ddt, maxstep);
	if(spawn) {
	    for(int i = 0, n = prandoom(dt * den * (nurgling.render.FireFX.smoke ? 14 : 10)); i < n; i++)
		bollar.add(new Boll(Coord3f.o.sadd(0, rnd.nextFloat() * (float)Math.PI * 2, (float)Math.sqrt(rnd.nextFloat()) * srad)));
	}
	Coord3f nv = Coord3f.o;
	boolean fx = nurgling.render.FireFX.smoke;
	if(gob != null) {
	    nv = Environ.get(gob.glob).wind().mul(0.4f);
	    if(fx)
		nv = nv.add(nurgling.render.FireFX.gust(gob.rc));
	    nv = nv.rot(Coord3f.zu, (float)gob.a);
	} else if(fx) {
	    nv = nurgling.render.FireFX.gust(null);
	}
	for(Iterator<Boll> i = bollar.iterator(); i.hasNext();) {
	    Boll boll = i.next();
	    if(boll.tick(dt, fx ? boll.wind(nv) : nv))
		i.remove();
	}
    }
    public TickList.Ticking ticker() {return(this);}

    public void autogtick(Render g) {
	updpos(g);
    }

    class Boll {
	static final float sv = 0.3f;
	float x, y, z;
	float xv, yv, zv;
	float t = 0;
	final float seed = rnd.nextFloat(), rot0 = rnd.nextFloat(), rotv = (rnd.nextFloat() - 0.5f) * 0.25f;

	Boll(Coord3f pos) {
	    x = pos.x;
	    y = pos.y;
	    z = pos.z;
	    xv = (float)rnd.nextGaussian() * sv;
	    yv = (float)rnd.nextGaussian() * sv;
	    zv = initzv;
	}

	/* Realistic smoke: the plume rises straight near its source
	 * and bends over higher up, and curls as it goes. */
	Coord3f wind(Coord3f nv) {
	    float hf = nurgling.render.FireFX.windheight(z);
	    float sw = 1.6f * hf;
	    float cx = (float)Math.sin((z * 0.13f) + (t * 1.1f) + (seed * 40f)) * sw;
	    float cy = (float)Math.cos((z * 0.11f) + (t * 0.9f) + (seed * 23f)) * sw;
	    return(Coord3f.of((nv.x * hf) + cx, (nv.y * hf) + cy, nv.z));
	}

	public boolean tick(float dt, Coord3f nv) {
	    /* XXX: Apparently, nextGaussian() is a major hotspot.  */
	    float xvd = xv - nv.x, yvd = yv - nv.y, zvd = zv - nv.z;
	    float xa = (-xvd * 0.2f) + ((float)Utils.fgrandoom(rnd) * 0.5f), ya = (-yvd * 0.2f) + ((float)Utils.fgrandoom(rnd) * 0.5f), za = ((-zvd + initzv) * 0.2f) + ((float)Utils.fgrandoom(rnd) * 2.0f);
	    xv += dt * xa;
	    yv += dt * ya;
	    zv += dt * za;
	    x += xv * dt;
	    y += yv * dt;
	    z += zv * dt;
	    t += dt;
	    return(t > life);
	}
    }

    public void draw(Pipe state, Render out) {
	if(model != null)
	    out.draw(state, model);
    }

    public void dispose() {
	if(model != null) {
	    model.dispose();
	    model = null;
	}
	if(va != null) {
	    va.dispose();
	    va = null;
	}
    }

    private void updpos(Render d) {
	int nb = bollar.size();
	if(nb < 1) {
	    dispose();
	    return;
	}
	if((va == null) || (va.bufs[0].size() < nb * fmt.inputs[0].stride)) {
	    if(va != null)
		va.dispose();
	    int n = 3 * nb / 2;
	    va = new VertexArray(fmt, new VertexArray.Buffer(n * fmt.inputs[0].stride, DataBuffer.Usage.STREAM, null)).shared();
	}
	d.update(va.bufs[0], this::fill);
	if((model == null) || (model.n != nb)) {
	    if(model != null)
		model.dispose();
	    model = new Model(Model.Mode.POINTS, va, null, 0, nb);
	    for(RenderTree.Slot slot : this.slots)
		slot.update();
	}
    }

    private FillBuffer fill(VertexArray.Buffer dst, Environment env) {
	byte r = (byte)col.getRed();
	byte g = (byte)col.getGreen();
	byte b = (byte)col.getBlue();
	float a = col.getAlpha() / 255.0f;
	FillBuffer ret = env.fillbuf(dst);
	ByteBuffer buf = ret.push();
	for(Boll boll : bollar) {
	    buf.putFloat(boll.x).putFloat(boll.y).putFloat(boll.z);
	    // XXXRENDER: It would be very nice to be able to specify static vertex parameters for an entire draw call.
	    buf.put((byte)0).put((byte)0).put((byte)127).put((byte)0);
	    byte ca = (byte)(a * (float)Utils.clip(1.0 - Math.pow(boll.t / life, fadepow), 0, 1) * 255);
	    buf.put(r).put(g).put(b).put(ca);
	    float age = (float)Utils.clip(boll.t / life, 0, 1);
	    float rot = boll.rot0 + (boll.rotv * boll.t);
	    buf.put((byte)Math.round(age * 255)).put((byte)Math.round(boll.seed * 255)).put((byte)Math.round((rot - (float)Math.floor(rot)) * 255)).put((byte)0);
	}
	return(ret);
    }

    private static final Uniform bollsz = new Uniform(FLOAT, p -> ((DrawState)p.get(RUtils.adhoc)).sz(), RUtils.adhoc);
    private static final ShaderMacro prog = new ShaderMacro() {
	    public void modify(final ProgramContext prog) {
		final Function pdiv = new Function.Def(FLOAT) {{
		    Expression vec = param(PDir.IN, VEC4).ref();
		    code.add(new Return(div(pick(vec, "x"), pick(vec, "w"))));
		}};
		Homo3D homo = Homo3D.get(prog);
		prog.vctx.ptsz.mod(in -> mul(sub(pdiv.call(homo.pprjxf(add(homo.eyev.depref(), vec4(bollsz.ref(), l(0.0), l(0.0), l(0.0))))),
						 pdiv.call(prog.vctx.posv.depref())),
					     pick(FrameConfig.u_screensize.ref(), "x")),
				   0);
		prog.vctx.ptsz.force();
		Tex2D.get(prog).texcoord().mod(in -> FragmentContext.ptc, 0);
	    }
	};

    static final AutoVarying vinfo = new AutoVarying(VEC4, "s_smokeinfo") {
	    protected Expression root(VertexContext vctx) {
		return(ainfo.ref());
	    }
	};
    static final nurgling.render.RawFunction puff = new nurgling.render.RawFunction(VEC4, "hv_puff", 3,
	"vec4 hv_puff(vec4 col, vec2 pc, vec4 info)\n" +
	"{\n" +
	"    float age = info.x, seed = info.y;\n" +
	"    vec2 d = pc - vec2(0.5);\n" +
	"    float r = length(d) * 2.0;\n" +
	"    float n = hv_ffbm(vec3(d * 2.6 + vec2(seed * 37.0, seed * 11.0), age * 1.3 + seed * 5.0));\n" +
	"    float mask = 1.0 - smoothstep(0.2, 0.95, r + (n - 0.5) * 1.1);\n" +
	"    float a = col.a * mask * (0.45 + 1.0 * n) * smoothstep(0.0, 0.12, age);\n" +
	"    /* Rounded, billowing clumps: lighter on top, each its own shade. */\n" +
	"    float vol = 0.7 + 0.5 * (1.0 - pc.y);\n" +
	"    float var = 0.78 + 0.44 * fract(seed * 7.13);\n" +
	"    vec3 c = col.rgb * vol * var * (0.8 + 0.4 * n) * mix(1.0, 0.85, age);\n" +	"    return(vec4(c, clamp(a, 0.0, 1.0)));\n" +
	"}\n");
    static final nurgling.render.RawFunction turn = new nurgling.render.RawFunction(VEC2, "hv_pturn", 2,
	"vec2 hv_pturn(vec2 pc, float turn)\n" +
	"{\n" +
	"    float a = turn * 6.2831853, s = sin(a), c = cos(a);\n" +
	"    vec2 d = pc - vec2(0.5);\n" +
	"    return(vec2(d.x * c - d.y * s, d.x * s + d.y * c) + vec2(0.5));\n" +
	"}\n");
    static final AutoVarying veye = new AutoVarying(VEC3, "s_smokeeye") {
	    protected Expression root(VertexContext vctx) {
		return(pick(Homo3D.get(vctx.prog).eyev.depref(), "xyz"));
	    }
	};
    /* Soft smoke: puffs fade out where they meet the ground, walls
     * and roofs, instead of cutting through them. */
    private static final ShaderMacro softprog = prog -> {
	FragColor.fragcol(prog.fctx).mod(in -> vec4(pick(in, "rgb"),
						    mul(pick(in, "a"), nurgling.render.Atmos.soft(prog, veye.ref(), mul(bollsz.ref(), l(1.5))))), 950);
    };
    private static final ShaderMacro fxprog = new ShaderMacro() {
	    public void modify(final ProgramContext prog) {
		final Function pdiv = new Function.Def(FLOAT) {{
		    Expression vec = param(PDir.IN, VEC4).ref();
		    code.add(new Return(div(pick(vec, "x"), pick(vec, "w"))));
		}};
		Homo3D homo = Homo3D.get(prog);
		/* Puffs spread out as they rise. */
		prog.vctx.ptsz.mod(in -> mul(sub(pdiv.call(homo.pprjxf(add(homo.eyev.depref(), vec4(bollsz.ref(), l(0.0), l(0.0), l(0.0))))),
						 pdiv.call(prog.vctx.posv.depref())),
					     pick(FrameConfig.u_screensize.ref(), "x"),
					     add(l(0.55), mul(l(1.5), sqrt(pick(ainfo.ref(), "x"))))),
				   0);
		prog.vctx.ptsz.force();
		/* Each puff turns its own way. */
		turn.define(prog.fctx);
		Tex2D.get(prog).texcoord().mod(in -> turn.call(FragmentContext.ptc, pick(vinfo.ref(), "z")), 0);
		nurgling.render.FireFX.noisedef.define(prog.fctx);
		puff.define(prog.fctx);
		FragColor.fragcol(prog.fctx).mod(in -> puff.call(in, FragmentContext.ptc, vinfo.ref()), 900);
	    }
	};

    private static final ShaderMacro fxsoft = ShaderMacro.compose(fxprog, softprog);

    class DrawState extends RUtils.AdHoc {
	DrawState() {super(prog);}
	float sz() {return(sz);}
	public ShaderMacro shader() {
	    if(!nurgling.render.FireFX.smoke)
		return(super.shader());
	    return(nurgling.render.FireFX.soft ? fxsoft : fxprog);
	}
    }
    private final State draw = new DrawState();
    public void added(RenderTree.Slot slot) {
	slot.ostate(Pipe.Op.compose(mat, States.maskdepth, loc,
				    eyesort, /* XXXRENDER disable MSAA */
				    VertexColor.instance, draw));
	slots.add(slot);
    }
    public void removed(RenderTree.Slot slot) {
	slots.remove(slot);
    }

    public void delete() {
	spawn = false;
    }

    public void age() {
	for(double t = 0.0; t < life; t += agestep)
	    autotick(agestep);
    }
}

