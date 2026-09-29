package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.res.lib.env.Environ;
import java.nio.*;
import java.util.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/*
 * Glowing embers rising from a fire (realistic fire option). One
 * emitter sits at each flickering fire light; bigger lights throw
 * more. Embers float up on the heat, drift with the wind, flutter,
 * and cool from yellow-white through orange to dark red before they
 * go out. Drawn as small glowing points; in an HDR scene they are
 * bright enough to bloom.
 */
public class Embers implements RenderTree.Node, Rendered, TickList.TickNode, TickList.Ticking {
    static final VertexArray.Layout fmt =
	new VertexArray.Layout(new VertexArray.Layout.Input(Homo3D.vertex,     new VectorFormat(3, NumberFormat.FLOAT32), 0,  0, 16),
			       new VertexArray.Layout.Input(VertexColor.color, new VectorFormat(4, NumberFormat.UNORM8),  0, 12, 16));
    static final float SIZE = 0.45f;
    final Random rnd = new Random();
    final float rate;
    final Gob gob;
    final List<Ember> embers = new ArrayList<>();
    final List<RenderTree.Slot> slots = new ArrayList<>(1);
    VertexArray va = null;
    Model model = null;
    float acc = 0;

    public Embers(Gob gob, float rate) {
	this.gob = gob;
	this.rate = rate;
    }

    class Ember {
	float x, y, z, xv, yv, zv, t, life, ph;

	Ember() {
	    float a = rnd.nextFloat() * (float)Math.PI * 2, r = (float)Math.sqrt(rnd.nextFloat()) * 1.5f;
	    x = (float)Math.cos(a) * r;
	    y = (float)Math.sin(a) * r;
	    z = rnd.nextFloat() * 1.5f;
	    xv = (float)rnd.nextGaussian() * 3.0f;
	    yv = (float)rnd.nextGaussian() * 3.0f;
	    zv = 4f + rnd.nextFloat() * 8f;
	    life = 0.9f + rnd.nextFloat() * 1.6f;
	    ph = rnd.nextFloat() * 20f;
	}

	boolean tick(float dt, Coord3f wind) {
	    /* Buoyancy that fades as the ember cools, drag towards the
	     * wind, and a flutter. */
	    float f = 1 - (t / life);
	    float fl = (float)Math.sin((t * 9f) + ph) * 10f;
	    xv += dt * (((wind.x - xv) * 0.8f) + (fl * 0.6f) + ((float)Utils.fgrandoom(rnd) * 4f));
	    yv += dt * (((wind.y - yv) * 0.8f) + ((float)Math.cos((t * 7f) + ph) * 8f) + ((float)Utils.fgrandoom(rnd) * 4f));
	    zv += dt * ((6f * f) - (zv * 0.6f));
	    x += xv * dt;
	    y += yv * dt;
	    z += zv * dt;
	    t += dt;
	    return(t > life);
	}
    }

    public TickList.Ticking ticker() {return(this);}

    public void autotick(double ddt) {
	float dt = (float)Math.min(ddt, 0.25);
	if(FireFX.fire) {
	    acc += dt * rate;
	    while(acc >= 1) {
		acc -= 1;
		embers.add(new Ember());
	    }
	}
	Coord3f wind = FireFX.smoke ? FireFX.gust(null) : Coord3f.o;
	if(gob != null) {
	    try {
		wind = Environ.get(gob.glob).wind().mul(0.6f);
		if(FireFX.smoke)
		    wind = wind.add(FireFX.gust(gob.rc));
		wind = wind.rot(Coord3f.zu, (float)gob.a);
	    } catch(RuntimeException e) {
		wind = Coord3f.o;
	    }
	}
	for(Iterator<Ember> i = embers.iterator(); i.hasNext();) {
	    if(i.next().tick(dt, wind))
		i.remove();
	}
    }

    public void autogtick(Render g) {
	int n = embers.size();
	if(n < 1) {
	    dispose();
	    return;
	}
	if((va == null) || (va.bufs[0].size() < n * fmt.inputs[0].stride)) {
	    if(va != null)
		va.dispose();
	    va = new VertexArray(fmt, new VertexArray.Buffer(Math.max(16, n * 2) * fmt.inputs[0].stride, DataBuffer.Usage.STREAM, null)).shared();
	}
	g.update(va.bufs[0], this::fill);
	if((model == null) || (model.n != n)) {
	    if(model != null)
		model.dispose();
	    model = new Model(Model.Mode.POINTS, va, null, 0, n);
	    for(RenderTree.Slot slot : slots)
		slot.update();
	}
    }

    private FillBuffer fill(VertexArray.Buffer dst, Environment env) {
	FillBuffer ret = env.fillbuf(dst);
	ByteBuffer buf = ret.push();
	for(Ember e : embers) {
	    buf.putFloat(e.x).putFloat(e.y).putFloat(e.z);
	    /* Temperature (cooling with age) and a twinkle. */
	    float age = e.t / e.life;
	    float tw = 0.75f + 0.25f * (float)Math.sin((e.t * 23f) + e.ph);
	    buf.put((byte)Math.round(Utils.clip(1 - age, 0, 1) * 255)).put((byte)Math.round(tw * 255)).put((byte)0);
	    buf.put((byte)Math.round(Utils.clip(1.4 - (1.4 * age), 0, 1) * 255));
	}
	return(ret);
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

    /* col.r = temperature, col.g = twinkle, col.a = fade. */
    static final RawFunction glow = new RawFunction(VEC4, "hv_ember", 3,
	"vec4 hv_ember(vec4 col, vec2 pc, float hdr)\n" +
	"{\n" +
	"    vec2 d = pc - vec2(0.5);\n" +
	"    float r2 = dot(d, d) * 4.0;\n" +
	"    float core = exp(-r2 * 9.0), halo = exp(-r2 * 3.0) * 0.35;\n" +
	"    float T = col.r;\n" +
	"    vec3 c = mix(vec3(0.55, 0.06, 0.01), vec3(1.0, 0.45, 0.08), smoothstep(0.0, 0.5, T));\n" +
	"    c = mix(c, vec3(1.0, 0.85, 0.55), smoothstep(0.6, 1.0, T));\n" +
	"    float a = clamp((core + halo) * col.a, 0.0, 1.0);\n" +
	"    if(a < 0.004)\n" +
	"        discard;\n" +
	"    c *= col.g;\n" +
	"    if(hdr > 0.5)\n" +
	"        return(vec4(min(c * (1.0 + 5.0 * T * core) / max(a, 0.03), vec3(40.0)), a));\n" +
	"    return(vec4(c, a));\n" +
	"}\n");

    private static ShaderMacro mkprog(boolean hdr) {
	return(prog -> {
		Function pdiv = new Function.Def(FLOAT) {{
		    Expression vec = param(Function.PDir.IN, VEC4).ref();
		    code.add(new Return(div(pick(vec, "x"), pick(vec, "w"))));
		}};
		Homo3D homo = Homo3D.get(prog);
		prog.vctx.ptsz.mod(in -> mul(sub(pdiv.call(homo.pprjxf(add(homo.eyev.depref(), vec4(l(SIZE), l(0.0), l(0.0), l(0.0))))),
						 pdiv.call(prog.vctx.posv.depref())),
					     pick(FrameConfig.u_screensize.ref(), "x")), 0);
		prog.vctx.ptsz.force();
		glow.define(prog.fctx);
		FragColor.fragcol(prog.fctx).mod(in -> glow.call(in, FragmentContext.ptc, l(hdr ? 1.0 : 0.0)), 100);
	    });
    }
    private static final ShaderMacro[] progs = {mkprog(false), mkprog(true)};

    static class DrawState extends State {
	public static final State.Slot<DrawState> slot = new State.Slot<>(State.Slot.Type.DRAW, DrawState.class);
	public ShaderMacro shader() {return(progs[FireFX.hdr ? 1 : 0]);}
	public void apply(Pipe p) {p.put(slot, this);}
    }
    private static final DrawState draw = new DrawState();

    public void added(RenderTree.Slot slot) {
	slot.ostate(Pipe.Op.compose(States.maskdepth, Rendered.eyesort, VertexColor.instance, draw));
	slots.add(slot);
    }

    public void removed(RenderTree.Slot slot) {
	slots.remove(slot);
    }
}
