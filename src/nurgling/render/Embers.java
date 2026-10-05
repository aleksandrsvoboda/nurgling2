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
 * emitter follows the light attachment and local position. Source-specific
 * rates and lifetimes keep torch sparks compact and campfire sparks a little wider.
 * Embers float up on the heat, drift with the wind, flutter,
 * and cool from yellow-white through orange to dark red before they
 * go out. Drawn as small glowing points; in an HDR scene they are
 * bright against the scene.
 */
public class Embers implements RenderTree.Node, Rendered, TickList.TickNode, TickList.Ticking {
    static final VertexArray.Layout fmt =
	new VertexArray.Layout(new VertexArray.Layout.Input(Homo3D.vertex,     new VectorFormat(3, NumberFormat.FLOAT32), 0,  0, 16),
			       new VertexArray.Layout.Input(VertexColor.color, new VectorFormat(4, NumberFormat.UNORM8),  0, 12, 16));
    static final float SIZE = 0.60f;
    final Random rnd = new Random();
    static final class Profile {
        final float rate, radius, speed, life;
        Profile(float rate, float radius, float speed, float life) {
            this.rate=rate; this.radius=radius; this.speed=speed; this.life=life;
        }
    }
    static final Profile TORCH = new Profile(3.0f, .22f, 3.2f, 1.05f);
    static final Profile CAMPFIRE = new Profile(9.0f, .85f, 4.8f, 1.45f);
    static final Profile DEFAULT = new Profile(4.0f, .35f, 3.8f, 1.15f);
    static Profile profile(String name) {
        if(name.contains("torch") || name.contains("candle") || name.contains("candelabrum") || name.contains("lamp")) return TORCH;
        if(name.endsWith("/pow") || name.contains("fireplace") || name.contains("campfire") || name.contains("bonfire")) return CAMPFIRE;
        return DEFAULT;
    }
    final PosLight source;
    Profile profile = DEFAULT;
    final Gob gob;
    final List<Ember> embers = new ArrayList<>();
    final List<RenderTree.Slot> slots = new ArrayList<>(1);
    VertexArray va = null;
    Model model = null;
    float acc = 0;

    public Embers(Gob gob, PosLight source) {
	this.gob = gob;
	this.source = source;
    }

    class Ember {
	float x, y, z, xv, yv, zv, t, life, ph;

	Ember() {
	    float a = rnd.nextFloat() * (float)Math.PI * 2, r = (float)Math.sqrt(rnd.nextFloat()) * profile.radius;
	    x = (float)Math.cos(a) * r;
	    y = (float)Math.sin(a) * r;
	    z = rnd.nextFloat() * profile.radius * .3f;
	    xv = (float)rnd.nextGaussian() * .35f;
	    yv = (float)rnd.nextGaussian() * .35f;
	    zv = profile.speed * (.65f + rnd.nextFloat() * .35f);
	    life = profile.life * (.65f + rnd.nextFloat() * .35f);
	    ph = rnd.nextFloat() * 20f;
	}

	boolean tick(float dt, Coord3f wind) {
	    // Cooling particles lose lift; weak drag keeps them close to the flame.
	    float f = 1 - (t / life);
	    xv += dt * ((wind.x - xv) * 1.8f + (float)Math.sin(t * 7 + ph) * .5f);
	    yv += dt * ((wind.y - yv) * 1.8f + (float)Math.cos(t * 6 + ph) * .5f);
	    zv += dt * (1.2f * f - 2.0f - zv * .5f);
	    x += xv * dt;
	    y += yv * dt;
	    z += zv * dt;
	    t += dt;
	    return(t > life);
	}
    }

    public TickList.Ticking ticker() {return(this);}

    public void autotick(double ddt) {
        if(!FireFX.fire) {
            embers.clear(); acc = 0;
            return;
        }
        if(gob != null) {
            try {
                Drawable drawable = gob.getattr(Drawable.class);
                if(drawable != null) profile = profile(drawable.getres().name);
            } catch(Loading loading) { /* Use a compact emitter until its resource is ready. */ }
        }
        Coord3f wind = Coord3f.o;
        if(gob != null) {
            try {
                wind = Environ.get(gob.glob).wind().add(FireFX.gust(gob.rc)).mul(.08f);
                float length = wind.abs();
                if(length > 1.2f) wind = wind.mul(1.2f / length);
                wind = wind.rot(Coord3f.zu, (float)gob.a);
            } catch(Loading loading) { /* No wind data yet. */ }
        }
        // Bounded substeps keep trajectories stable across uneven client frames.
        float remaining = (float)Math.min(Math.max(ddt, 0), .25);
        while(remaining > 0) {
            float dt = Math.min(remaining, 1f / 60f);
            remaining -= dt;
            acc += dt * profile.rate;
            while(acc >= 1) {
                acc -= 1;
                embers.add(new Ember());
            }
            for(Iterator<Ember> i = embers.iterator(); i.hasNext();) {
                if(i.next().tick(dt, wind)) i.remove();
            }
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
        Coord3f origin=new Coord3f(source.pos[0],source.pos[1],source.pos[2]);
        Coord3f anchor=VolumeFire.emberAnchor(gob);
        if(anchor!=null && !slots.isEmpty()) {
            Matrix4f inverse=Homo3D.locxf(slots.get(0).state()).invert();
            if(inverse!=null) origin=inverse.mul4(anchor);
        }
	for(Ember e : embers) {
	    // The slot already has the resource/bone transform; the light has its own offset too.
	    buf.putFloat(origin.x + e.x).putFloat(origin.y + e.y).putFloat(origin.z + e.z);
	    /* Temperature (cooling with age) and a twinkle. */
	    float age = e.t / e.life;
	    float tw = 0.75f + 0.25f * (float)Math.sin((e.t * 23f) + e.ph);
	    buf.put((byte)Math.round(Utils.clip(1 - age, 0, 1) * 255)).put((byte)Math.round(tw * 255)).put((byte)0);
	    buf.put((byte)Math.round(Utils.clip(age / .12f, 0, 1) * (float)Math.pow(1 - age, .65) * 255));
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
	"    float core = exp(-r2 * 8.0), halo = exp(-r2 * 4.0) * 0.10;\n" +
	"    float T = col.r;\n" +
	"    vec3 c = mix(vec3(0.55, 0.06, 0.01), vec3(1.0, 0.45, 0.08), smoothstep(0.0, 0.5, T));\n" +
	"    c = mix(c, vec3(1.0, 0.85, 0.55), smoothstep(0.6, 1.0, T));\n" +
	"    float a = min(core + halo, 1.0) * clamp(col.a, 0.0, 1.0);\n" +
	"    if(a < 0.004)\n" +
	"        discard;\n" +
	"    c *= col.g;\n" +
	"    if(hdr > 0.5)\n" +
	"        return(vec4(c * (1.0 + 0.25 * T * core), a));\n" +
	"    return(vec4(c, a));\n" +
	"}\n");

    private static ShaderMacro mkprog(boolean hdr) {
	return(prog -> {
		Function pdiv = new Function.Def(FLOAT) {{
		    Expression vec = param(Function.PDir.IN, VEC4).ref();
		    code.add(new Return(div(pick(vec, "x"), pick(vec, "w"))));
		}};
		Homo3D homo = Homo3D.get(prog);
		prog.vctx.ptsz.mod(in -> clamp(mul(sub(pdiv.call(homo.pprjxf(add(homo.eyev.depref(), vec4(l(SIZE), l(0.0), l(0.0), l(0.0))))),
						 pdiv.call(prog.vctx.posv.depref())),
					     pick(FrameConfig.u_screensize.ref(), "x")), l(4.5), l(10.0)), 0);
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
