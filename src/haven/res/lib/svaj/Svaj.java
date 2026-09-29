/* Preprocessed source code */
package haven.res.lib.svaj;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;
import static haven.render.sl.Function.PDir.*;

@haven.FromResource(name = "lib/svaj", version = 25)
public class Svaj extends State implements InstanceBatch.AttribState {
    private static final float[] nildat = {0, 0, 0, 0};
    public static final Slot<Svaj> slot = new Slot<Svaj>(Slot.Type.GEOM, Svaj.class)
	.instanced(new Instancable<Svaj>() {
		public Instancer<Svaj> instid(Svaj st) {return(instancer);}
	    });
    public static final InstancedUniform u_zharmonic = new InstancedUniform.Vec4("zharm", p -> {
	    Svaj st = p.get(slot);
	    return((st == null) ? nildat : st.zharmonic);
	}, slot);
    public static final InstancedUniform u_charmonic = new InstancedUniform.Vec4("charm", p -> {
	    Svaj st = p.get(slot);
	    return((st == null) ? nildat : st.charmonic);
	}, slot);
    public static final InstancedUniform u_origin = new InstancedUniform.Vec4("s_orig", p -> {
	    Svaj st = p.get(slot);
	    Coord3f o = (st == null) ? Coord3f.o : st.origin;
	    return(new float[] {o.x, o.y, o.z, ((st != null) && st.tree) ? 1 : 0});
	}, slot);
    public final float[] zharmonic, charmonic;
    public final Coord3f origin;
    /* Nurgling: whether this is a tree, which alone bends in gusts. */
    public boolean tree = false;

    public Svaj(Coord3f zhvec, float zhfreq, Coord3f chvec, float chfreq, Coord3f origin, boolean tree) {
	this(zhvec, zhfreq, chvec, chfreq, origin);
	this.tree = tree;
    }

    public Svaj(Coord3f zhvec, float zhfreq, Coord3f chvec, float chfreq, Coord3f origin) {
	this.zharmonic = new float[] {zhvec.x, zhvec.y, zhvec.z, zhfreq * (float)Math.PI * 2};
	this.charmonic = new float[] {chvec.x, chvec.y, chvec.z, chfreq * (float)Math.PI * 2};
	this.origin = origin;
    }

    public void apply(Pipe buf) {
	buf.put(slot, this);
    }

    public static final Function svaja = new Function.Def(VEC4, "svaja") {{
	Expression in = param(IN, VEC4).ref();
	Expression off = code.local(VEC3, sub(pick(in, "xyz"), pick(u_origin.ref(), "xyz"))).ref();
	code.add(new Return(add(in,
				vec4(mul(pick(u_zharmonic.ref(), "xyz"),
					 sin(mul(FrameInfo.u_time.ref(), pick(u_zharmonic.ref(), "w"))),
					 max(pick(off, "z"),
					     l(0.0))),
				     l(0.0)),
				vec4(mul(pick(u_charmonic.ref(), "xyz"),
					 sin(mul(FrameInfo.u_time.ref(), pick(u_charmonic.ref(), "w"))),
					 max(sub(length(pick(off, "xy")), l(5.0)),
					     l(0.0))),
				     l(0.0)))));
    }};

    public static final ShaderMacro sh =  prog -> {
	Homo3D homo = Homo3D.get(prog);
	homo.mapv.mod(svaja::call, 0);
    };

    /* Nurgling: with swaying on (a graphics option), trees also bend
     * in the wind's gusts, more towards their tops, and their
     * canopies flutter while it blows; the same wind as smoke. */
    static final nurgling.render.RawFunction svajw = new nurgling.render.RawFunction(VEC4, "hv_svajw", 3,
	"vec4 hv_svajw(vec4 v, vec4 orig, float t)\n" +
	"{\n" +
	"    vec3 off = v.xyz - orig.xyz;\n" +
	"    vec2 w = hv_gust(orig.xy, t);\n" +
	"    float g = hv_gusty(orig.xy, t);\n" +
	"    float h = max(off.z, 0.0);\n" +
	"    float bend = h * h * 0.00005 * (0.6 + 0.4 * sin(t * 0.9 + orig.x * 0.01));\n" +
	"    float fl = sin(t * 7.0 + dot(v.xy, vec2(0.7, 1.3))) * g * h * 0.004;\n" +
	"    vec2 wd = w / max(length(w), 0.001);\n" +
	"    /* Only trees (orig.w = 1) bend in gusts. */\n" +
	"    v.xy += (w * bend + vec2(-wd.y, wd.x) * fl) * orig.w;\n" +
	"    return(v);\n" +
	"}\n");
    public static final ShaderMacro shw = prog -> {
	Homo3D homo = Homo3D.get(prog);
	homo.mapv.mod(svaja::call, 0);
	nurgling.render.FireFX.winddef.define(prog.vctx);
	svajw.define(prog.vctx);
	homo.mapv.mod(in -> svajw.call(in, u_origin.ref(), FrameInfo.time()), 1);
    };

    public ShaderMacro shader() {
	return(nurgling.render.Atmos.sway ? shw : sh);
    }

    private static final Instancer<Svaj> instancer = new Instancer<Svaj>() {
	    final Svaj instanced = new Svaj(Coord3f.o, 0, Coord3f.o, 0, null) {
		    private final ShaderMacro shader = ShaderMacro.compose(Instancer.mkinstanced, sh);
		    private final ShaderMacro shaderw = ShaderMacro.compose(Instancer.mkinstanced, shw);
		    public ShaderMacro shader() {return(nurgling.render.Atmos.sway ? shaderw : shader);}
		};

	    public Svaj inststate(Svaj uinst, InstanceBatch bat) {
		return(instanced);
	    }
	};

    public InstancedAttribute[] attribs() {
	return(new InstancedAttribute[] {u_zharmonic.attrib, u_charmonic.attrib, u_origin.attrib});
    }
}

/* >spr: SvajOl */
