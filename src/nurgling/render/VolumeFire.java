package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.resutil.TexAnim;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static haven.render.sl.Type.*;

/** Shared baked combustion volumes, ray marched against this frame's scene depth.
 * Pure Vulkan/core texture operations: no CUDA, RTX or vendor-specific extension.
 */
public class VolumeFire extends RenderContext.PostProcessor {
    /** Only replace known combustion resources, never arbitrary scrolling effects. */
    public static boolean fireResource(String name) {
        return name.startsWith("gfx/terobjs/") &&
            (name.endsWith("/pow") || name.contains("fire") || name.contains("torch") ||
             name.contains("candelabrum") || name.contains("candle") || name.contains("brazier") || name.contains("cauldron") ||
             name.contains("kiln") || name.contains("smelter") || name.contains("crucible") || name.contains("oven"));
    }
    public static final class Surface extends State {
        static final Slot<Surface> slot=new Slot<>(Slot.Type.DRAW,Surface.class);
        public void apply(Pipe p) {p.put(slot,this);}
        public ShaderMacro shader() {return FireFX.fire ? FireFX.volumeSurface : null;}
    }
    public static final Surface surface=new Surface();
    private static final Map<Gob,Coord3f> anchors=Collections.synchronizedMap(new WeakHashMap<>());
    static Coord3f emberAnchor(Gob gob) {return anchors.get(gob);}
    static final class Cache {
        final Texture3D.Sampler3D[] frames;
        Cache() {
            try(InputStream raw=VolumeFire.class.getResourceAsStream("assets/fire-volume.bin.gz")) {
                if(raw==null) throw new IOException("Missing fire volume cache");
                DataInputStream data=new DataInputStream(new GZIPInputStream(raw));
                if(data.readInt()!=0x4e464952) throw new IOException("Bad fire volume cache");
                int w=data.readInt(),h=data.readInt(),d=data.readInt(),count=data.readInt();
                if(w!=64 || h!=64 || d!=96 || count!=64) throw new IOException("Unexpected fire volume dimensions");
                frames=new Texture3D.Sampler3D[count];
                for(int i=0;i<count;i++) {
                    byte[] pixels=new byte[w*h*d*2]; data.readFully(pixels);
                    Texture3D tex=new Texture3D(w,h,d,DataBuffer.Usage.STATIC,new VectorFormat(2,NumberFormat.UNORM8),
                        (image,env)-> {
                            if(image.level!=0) return null;
                            FillBuffer fill=env.fillbuf(image); fill.push().put(pixels); return fill;
                        });
                    Texture3D.Sampler3D sampler=tex.sampler();
                    sampler.minfilter(Texture.Filter.LINEAR).magfilter(Texture.Filter.LINEAR).wrapmode(Texture.Wrapping.CLAMP);
                    frames[i]=sampler;
                }
            } catch(IOException e) { throw new RuntimeException("Cannot load baked fire",e); }
        }
    }
    private static class Shared { static final Cache cache=new Cache(); }
    static final RawFunction march = new RawFunction(VEC4,"hv_volume_fire",11,
        "vec4 hv_volume_fire(vec4 ignored, vec2 tc, sampler3D frame0, sampler3D frame1, sampler2D depth, mat4 inverseMVP, vec3 lower, vec3 span, vec3 params, sampler2D sourceColor, vec3 tint)\n" +
        "{\n" +
        "    vec2 ndc = tc * 2.0 - 1.0;\n" +
        "    float sceneZ = texelFetch(depth, clamp(ivec2(tc * vec2(textureSize(depth, 0))), ivec2(0), textureSize(depth, 0)-1), 0).r;\n" +
        "    vec4 start4 = inverseMVP * vec4(ndc, -1.0, 1.0);\n" +
        "    vec4 end4 = inverseMVP * vec4(ndc, min(sceneZ, 0.999999) * 2.0 - 1.0, 1.0);\n" +
        "    vec3 start = (start4.xyz / start4.w - lower) / span;\n" +
        "    vec3 end = (end4.xyz / end4.w - lower) / span;\n" +
        "    vec3 ray = end - start;\n" +
        "    float rayLength = length(ray);\n" +
        "    if(rayLength < 0.000001) return vec4(0.0);\n" +
        "    vec3 dir = ray / rayLength;\n" +
        "    vec3 safeDir = mix(vec3(0.000001), dir, greaterThan(abs(dir), vec3(0.000001)));\n" +
        "    vec3 a = (vec3(0.0) - start) / safeDir, b = (vec3(1.0) - start) / safeDir;\n" +
        "    vec3 near3 = min(a,b), far3 = max(a,b);\n" +
        "    float enter = max(0.0, max(near3.x, max(near3.y, near3.z)));\n" +
        "    float leave = min(rayLength, min(far3.x, min(far3.y, far3.z)));\n" +
        "    if(leave <= enter) return vec4(0.0);\n" +
        "    float stepSize = (leave - enter) / params.y;\n" +
        "    vec3 pigment = vec3(0.0);\n" +
        "    for(int y=0;y<4;y++) for(int x=0;x<4;x++) {\n" +
        "        vec4 texel = textureLod(sourceColor,(vec2(x,y)+0.5)/4.0,0.0);\n" +
        "        pigment += texel.rgb * texel.a * tint;\n" +
        "    }\n" +
        "    pigment /= max(max(pigment.r,pigment.g),max(pigment.b,0.001));\n" +
        "    float blue = smoothstep(0.08,0.28,pigment.b-pigment.r);\n" +
        "    float green = smoothstep(0.08,0.35,pigment.g-max(pigment.r,pigment.b)) * (1.0-blue);\n" +
        "    vec3 sum = vec3(0.0);\n" +
        "    float transmission = 1.0;\n" +
        "    for(int i=0;i<64;i++) {\n" +
        "        if(float(i) >= params.y || transmission < 0.015) break;\n" +
        "        vec3 pos = start + dir * (enter + (float(i) + 0.5) * stepSize);\n" +
        "        vec2 field = mix(texture(frame0,pos).rg, texture(frame1,pos).rg, params.x);\n" +
        "        float density = max(field.r - 0.025, 0.0);\n" +
        "        float heat = clamp(field.g,0.0,1.0);\n" +
        "        float alpha = 1.0 - exp(-density * stepSize * 17.0);\n" +
        "        float temperature = clamp(heat * (0.48 + 0.52 * smoothstep(0.03,0.8,density)) - pos.z * 0.16,0.0,1.0);\n" +
        "        vec3 color = mix(vec3(0.75,0.025,0.002),vec3(1.0,0.18,0.008),smoothstep(0.15,0.48,temperature));\n" +
        "        color = mix(color,vec3(1.0,0.48,0.035),smoothstep(0.43,0.68,temperature));\n" +
        "        color = mix(color,vec3(1.0,0.86,0.45),smoothstep(0.62,0.80,temperature));\n" +
        "        color = mix(color,mix(vec3(0.025,0.16,0.8),vec3(0.28,0.85,1.0),heat),blue);\n" +
        "        color = mix(color,mix(vec3(0.03,0.5,0.015),vec3(0.45,1.0,0.18),heat),green);\n" +
        "        sum += transmission * alpha * color * params.z;\n" +
        "        transmission *= 1.0-alpha;\n" +
        "    }\n" +
        "    return vec4(sum,1.0-transmission);\n" +
        "}\n");
    static final ShaderMacro shader=NPostFX.shader(march,NPostFX.u(SAMPLER3D,0),NPostFX.u(SAMPLER3D,1),
        NPostFX.u(SAMPLER2D,2),NPostFX.u(MAT4,3),NPostFX.u(VEC3,4),NPostFX.u(VEC3,5),NPostFX.u(VEC3,6),
        NPostFX.u(SAMPLER2D,7),NPostFX.u(VEC3,8));
    private static final Pipe.Op blend=FragColor.blend(new BlendMode(BlendMode.Factor.ONE,
        BlendMode.Factor.INV_SRC_ALPHA,BlendMode.Factor.ZERO,BlendMode.Factor.ONE));
    final PView view;
    final SceneFX.Depth depth;
    static final class Sources implements RenderList<Rendered> {
        final Set<RenderList.Slot<? extends Rendered>> slots=new HashSet<>();
        public void add(RenderList.Slot<? extends Rendered> slot) {
            // RenderTree notifies lists BEFORE ResourceMesh.added installs Surface.
            // Registration must use resource identity, never that not-yet-applied state.
            if(slot.obj() instanceof FastMesh.ResourceMesh && fireResource(((FastMesh.ResourceMesh)slot.obj()).res.name))
                synchronized(slots) { slots.add(slot); }
        }
        public void remove(RenderList.Slot<? extends Rendered> slot) { synchronized(slots) { slots.remove(slot); } }
        public void update(RenderList.Slot<? extends Rendered> slot) { remove(slot); add(slot); }
        public void update(Pipe group,int[] mask) {}
    }
    final Sources list=new Sources();
    public VolumeFire(PView view) {
        this.view=view; depth=new SceneFX.Depth(view);
        // Load the small shared cache once, before suppressing any legacy surfaces.
        Cache cache=Shared.cache;
        list.syncadd(view.tree,Rendered.class);
    }
    public int order() {return -120;}
    static Texture2D.Sampler2D sourceTexture(Pipe state) {
        TexRender.TexDraw draw=state.get(TexRender.TexDraw.slot);
        if(draw!=null) return draw.tex.img;
        ColorTex color=state.get(ColorTex.slot);
        return color==null?null:color.data;
    }
    static boolean candidate(Rendered object,Pipe state) {
        return object instanceof FastMesh && state.get(Surface.slot)!=null && state.get(TexAnim.slot)!=null && sourceTexture(state)!=null && state.get(Light.lighting)==null;
    }
    static float[][] bounds(FastMesh mesh) {
        Coord3f n=mesh.nbounds(),p=mesh.pbounds();
        float width=Math.max(.3f,Math.max(p.x-n.x,p.y-n.y))*1.4f;
        float originalHeight=Math.max(.5f,p.z-n.z);
        float height=originalHeight*1.65f;
        float origin=n.z;
        if(mesh instanceof FastMesh.ResourceMesh && originalHeight<3 && width<3) {
            // Compact flames can be authored ABOVE the wick (the candelabrum is
            // 2.43 units too high). Anchor to the fixture's top in the same space.
            Resource resource=((FastMesh.ResourceMesh)mesh).res;
            float fixture=Float.NEGATIVE_INFINITY;
            for(FastMesh.MeshRes part:resource.layers(FastMesh.MeshRes.class)) {
                if(part.m==mesh) continue;
                Coord3f a=part.m.nbounds(),b=part.m.pbounds();
                float cx=(n.x+p.x)*.5f,cy=(n.y+p.y)*.5f;
                if(b.z<=n.z+.05f && b.z>=n.z-originalHeight*3 &&
                   cx>=a.x-.2f && cx<=b.x+.2f && cy>=a.y-.2f && cy<=b.y+.2f)
                    fixture=Math.max(fixture,b.z);
            }
            if(Float.isFinite(fixture)) origin=fixture;
        }
        // The baked burner starts at z=4/96; put that layer ON the source.
        float base=origin-height*(4f/96f);
        return new float[][]{{(n.x+p.x-width)*.5f,(n.y+p.y-width)*.5f,base},{width,width,height}};
    }
    private static double noise(long index) {
        long value=index*0x9e3779b97f4a7c15L;
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return ((value^(value>>>31))>>>11)*0x1.0p-53;
    }
    static double animationTime(double seconds,double phase) {
        double x=seconds*1.7+phase,integer=Math.floor(x),fraction=x-integer;
        double blend=fraction*fraction*(3-2*fraction);
        double irregular=noise((long)integer)*(1-blend)+noise((long)integer+1)*blend;
        // Continuous, aperiodic tempo. Its slope stays positive (18.9..29.1 fps),
        // so turbulence speeds up/slows down without freezes or reversing.
        return seconds*24+phase+irregular*2;
    }
    static Area screenBounds(Matrix4f mvp,float[] lo,float[] span,Coord size) {
        float minx=Float.POSITIVE_INFINITY,miny=Float.POSITIVE_INFINITY,maxx=Float.NEGATIVE_INFINITY,maxy=Float.NEGATIVE_INFINITY;
        for(int i=0;i<8;i++) {
            float[] c=mvp.mul4(new float[]{lo[0]+((i&1)!=0?span[0]:0),lo[1]+((i&2)!=0?span[1]:0),lo[2]+((i&4)!=0?span[2]:0),1});
            if(c[3]<=.001f) return Area.sized(size);
            float x=c[0]/c[3],y=c[1]/c[3];
            minx=Math.min(minx,x); miny=Math.min(miny,y); maxx=Math.max(maxx,x); maxy=Math.max(maxy,y);
        }
        int x0=Math.max(0,(int)Math.floor((minx+1)*.5f*size.x)-1),x1=Math.min(size.x,(int)Math.ceil((maxx+1)*.5f*size.x)+1);
        int y0=Math.max(0,(int)Math.floor((miny+1)*.5f*size.y)-1),y1=Math.min(size.y,(int)Math.ceil((maxy+1)*.5f*size.y)+1);
        return (x1<=x0 || y1<=y0)?null:new Area(Coord.of(x0,y0),Coord.of(x1,y1));
    }
    public void run(GOut g,Texture2D.Sampler2D in) {
        g.image(new TexRaw(in,true),Coord.z,g.sz());
        Texture2D.Sampler2D dep=depth.samp();
        if(dep==null) return;
        List<RenderList.Slot<? extends Rendered>> copy;
        synchronized(list.slots) { copy=new ArrayList<>(list.slots); }
        try(Locked lock=view.tree.lock()) {
            // Back to front preserves transmission when two flame volumes overlap.
            copy.removeIf(s -> !candidate(s.obj(),s.state()));
            copy.sort(Comparator.comparingDouble(s -> Homo3D.camxf(s.state()).mul(Homo3D.locxf(s.state())).m[14]));
            int count=copy.size();
            Set<String> drawn=new HashSet<>();
            for(RenderList.Slot<? extends Rendered> source:copy) {
                Pipe st=source.state(); FastMesh mesh=(FastMesh)source.obj();
                float[][] bounds=bounds(mesh);
                Matrix4f loc=Homo3D.locxf(st);
                // Several crossed surfaces can describe a single resource flame.
                if(!drawn.add(Arrays.toString(loc.m)+Arrays.deepToString(bounds))) continue;
                Gob gob=Gob.from(st.get(Clickable.slot));
                if(gob!=null) anchors.put(gob,loc.mul4(new Coord3f(bounds[0][0]+bounds[1][0]*.5f,
                    bounds[0][1]+bounds[1][1]*.5f,bounds[0][2]+bounds[1][2]*.60f)));
                Matrix4f mvp=Homo3D.prjxf(st).mul(Homo3D.camxf(st)).mul(Homo3D.locxf(st));
                Matrix4f inv=mvp.invert(); if(inv==null) continue;
                Area rect=screenBounds(mvp,bounds[0],bounds[1],g.sz()); if(rect==null) continue;
                // Different world positions de-synchronize copies without a per-fire simulation.
                double t=animationTime(Utils.rtime(),loc.m[12]*.13+loc.m[13]*.17);
                long index=(long)Math.floor(t); float frac=(float)(t-Math.floor(t));
                Texture3D.Sampler3D[] frames=Shared.cache.frames;
                int f=Math.floorMod(index,frames.length);
                int pixels=rect.sz().x*rect.sz().y;
                int steps=(count>12 || pixels<2500)?32:64;
                BaseColor base=st.get(BaseColor.slot);
                float[] tint=base==null?new float[]{1,1,1}:new float[]{base.color.r,base.color.g,base.color.b};
                g.usestate(blend); g.usestate(new States.Scissor(rect));
                NPostFX.blit(g,Temporal.one(),new NPostFX.Pass(shader,frames[f],frames[(f+1)%frames.length],dep,inv,
                    bounds[0],bounds[1],new float[]{frac,steps,in.tex.ifmt.cf==NumberFormat.FLOAT16?1.15f:1f},sourceTexture(st),tint));
            }
        }
    }
    public void dispose() { view.tree.remove(list); synchronized(list.slots){list.slots.clear();} anchors.clear(); super.dispose(); }
}
