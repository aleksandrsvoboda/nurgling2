package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/** Nested, world-stable sun/moon maps. One direct-light visibility term for every material. */
public final class DirectionalShadows implements Disposable {
    private ShadowMap near, far;
    private final ShadowMap.ShadowList casters;
    private final RenderList.Adapter master;
    public DirectionalShadows(RenderList.Adapter master) {
        this.master = master;
        casters = new ShadowMap.ShadowList(master);
        near = new ShadowMap(Coord.of(2048,2048),220,5000,.20f,3);
        far = new ShadowMap(Coord.of(2048,2048),750,5000,.45f,3);
    }
    public RenderList.Adapter master() { return master; }
    public Sun update(DirLight light, Coord3f center) {
        Coord3f dir = new Coord3f(-light.dir[0],-light.dir[1],-light.dir[2]);
        Coord3f origin = center.add(dir.neg().mul(1000));
        near = near.setpos(origin,dir);
        far = far.setpos(origin,dir);
        return new Sun(near,far,light);
    }
    public void draw(Render out) { near.update(out,casters); far.update(out,casters); }
    public void dispose() {
        master.remove(casters);
        casters.dispose(); near.dispose(); far.dispose();
    }

    public static final State.Slot<Sun> slot = new State.Slot<>(State.Slot.Type.DRAW,Sun.class);
    public static final class Sun extends State {
        public final ShadowMap near, far;
        final DirLight light;
        Sun(ShadowMap near, ShadowMap far, DirLight light) { this.near=near; this.far=far; this.light=light; }
        public ShaderMacro shader() { return shader; }
        public void apply(Pipe pipe) { pipe.put(slot,this); }
    }
    private static final Uniform nt = new Uniform(MAT4,p -> p.get(slot).near.eyetotex(p.get(Homo3D.cam)),slot,Homo3D.cam);
    private static final Uniform ft = new Uniform(MAT4,p -> p.get(slot).far.eyetotex(p.get(Homo3D.cam)),slot,Homo3D.cam);
    private static final Uniform nm = new Uniform(SAMPLER2D,p -> p.get(slot).near.lsamp,slot);
    private static final Uniform fm = new Uniform(SAMPLER2D,p -> p.get(slot).far.lsamp,slot);
    private static final Uniform index = new Uniform(INT,p -> {
        Light.LightList lights=p.get(Light.lights);
        return lights == null ? -1 : lights.index(p.get(slot).light);
    },slot,Light.lights);

    /* Depth comparisons are bilinearly filtered; raw depths never are. Gradients
     * are calculated before cascade selection, outside non-uniform flow control. */
    static final String FILTER =
        "vec2 hv_sgradient(vec3 p) {\n" +
        " vec3 x=dFdx(p), y=dFdy(p); float d=x.x*y.y-x.y*y.x;\n" +
        " return abs(d)>1e-12 ? clamp(vec2(y.y*x.z-x.y*y.z,x.x*y.z-y.x*x.z)/d,vec2(-8),vec2(8)) : vec2(0);\n" +
        "}\n" +
        "float hv_scompare(sampler2D map, ivec2 at, vec3 p, vec2 grad, vec2 texel, float bias) {\n" +
        " if(any(lessThan(at,ivec2(0))) || any(greaterThanEqual(at,textureSize(map,0)))) return 1.0;\n" +
        " float receiver=p.z+dot(grad,(vec2(at)+0.5)*texel-p.xy)-bias;\n" +
        " return step(receiver,texelFetch(map,at,0).r);\n" +
        "}\n" +
        "float hv_sbilinear(sampler2D map, vec2 uv, vec3 p, vec2 grad, vec2 texel, float bias) {\n" +
        " vec2 q=uv/texel-0.5, f=fract(q); ivec2 at=ivec2(floor(q));\n" +
        " return mix(mix(hv_scompare(map,at,p,grad,texel,bias),hv_scompare(map,at+ivec2(1,0),p,grad,texel,bias),f.x),\n" +
        " mix(hv_scompare(map,at+ivec2(0,1),p,grad,texel,bias),hv_scompare(map,at+ivec2(1),p,grad,texel,bias),f.x),f.y);\n" +
        "}\n" +
        "float hv_sfilter(sampler2D map, vec3 p, vec2 grad, vec2 par) {\n" +
        " if(any(lessThanEqual(p,vec3(0))) || any(greaterThanEqual(p,vec3(1)))) return 1.0;\n" +
        " vec2 texel=1.0/vec2(textureSize(map,0));\n" +
        " float bias=par.y+min(par.y*2.0,dot(abs(grad),texel)*0.1);\n" +
        " float blockers=0.0, separation=0.0;\n" +
        " for(int i=0;i<12;i++) {\n" +
        "  float a=float(i)*2.39996323; vec2 off=vec2(cos(a),sin(a))*sqrt((float(i)+0.5)/12.0)*14.0*texel;\n" +
        "  ivec2 at=ivec2(floor((p.xy+off)/texel));\n" +
        "  if(any(lessThan(at,ivec2(0))) || any(greaterThanEqual(at,textureSize(map,0)))) continue;\n" +
        "  float receiver=p.z+dot(grad,(vec2(at)+0.5)*texel-p.xy);\n" +
        "  float z=texelFetch(map,at,0).r;\n" +
        "  if(z<receiver-bias) { separation+=receiver-z; blockers+=1.0; }\n" +
        " }\n" +
        " float radius=blockers>0.0 ? clamp(separation/blockers*5000.0*0.030/par.x,1.25,12.0) : 1.25;\n" +
        " float lit=0.0;\n" +
        " for(int i=0;i<16;i++) {\n" +
        "  float a=float(i)*2.39996323; vec2 off=vec2(cos(a),sin(a))*sqrt((float(i)+0.5)/16.0)*radius*texel;\n" +
        "  lit+=hv_sbilinear(map,p.xy+off,p,grad,texel,bias);\n" +
        " }\n" +
        " return lit/16.0;\n" +
        "}\n";
    static final RawFunction filter = new RawFunction(FLOAT,"hv_sfilter",4,FILTER);
    static final RawFunction visibility = new RawFunction(FLOAT,"hv_sunvisibility",4,
        "float hv_sunvisibility(sampler2D nearMap,sampler2D farMap,vec3 np,vec3 fp) {\n" +
        " vec2 ng=hv_sgradient(np), fg=hv_sgradient(fp);\n" +
        " float edge=max(abs(np.x*2.0-1.0),abs(np.y*2.0-1.0));\n" +
        " float blend=smoothstep(0.65,0.90,edge);\n" +
        " if(np.z<=0.0 || np.z>=1.0) blend=1.0;\n" +
        " float close=1.0, distant=1.0;\n" +
        " if(blend<1.0) close=hv_sfilter(nearMap,np,ng,vec2(440.0/2048.0,0.20/5000.0));\n" +
        " if(blend>0.0) distant=hv_sfilter(farMap,fp,fg,vec2(1500.0/2048.0,0.45/5000.0));\n" +
        " float farEdge=max(abs(fp.x*2.0-1.0),abs(fp.y*2.0-1.0));\n" +
        " distant=mix(distant,1.0,smoothstep(0.90,1.0,farEdge));\n" +
        " return mix(close,distant,blend);\n" +
        "}\n");
    private static AutoVarying coords(Uniform transform) {
        return new AutoVarying(VEC4) {
            public Expression root(VertexContext vctx) { return mul(transform.ref(),Homo3D.get(vctx.prog).eyev.depref()); }
        };
    }
    private static final AutoVarying nc=coords(nt), fc=coords(ft);
    private static final ShaderMacro shader = prog -> {
        Phong ph=prog.getmod(Phong.class);
        if(ph==null || !ph.pfrag) return;
        filter.define(prog.fctx); visibility.define(prog.fctx);
        ValBlock.Value visible=prog.fctx.uniform.new Value(FLOAT) {
            public Expression root() { return visibility.call(nm.ref(),fm.ref(),div(pick(nc.ref(),"xyz"),pick(nc.ref(),"w")),div(pick(fc.ref(),"xyz"),pick(fc.ref(),"w"))); }
            protected void cons2(Block block) {
                tgt=new Variable.Global(FLOAT).ref(); block.add(ass(tgt,init));
            }
        };
        visible.force();
        ph.dolight.mod(() -> ph.dolight.dcalc.add(new If(eq(index.ref(),ph.dolight.i),
            stmt(amul(ph.dolight.lvl.tgt,visible.ref()))),ph.dolight.dcurs),0);
    };
}
