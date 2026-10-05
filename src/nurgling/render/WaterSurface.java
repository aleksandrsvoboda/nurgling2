package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/** Forward water resolve, before atmosphere/tonemapping. Refraction reads an immutable
 * copy of THIS frame's scene; water writes the real depth for subsequent effects.
 * No compute, tessellation, ray-tracing or vendor extensions are required. */
public class WaterSurface extends RenderContext.PostProcessor {
    /** Server outdoor light is black in enclosed maps; use the raw value so
     * brightness adjustments and Debug time cannot turn cave waves into swell. */
    static boolean sheltered(java.awt.Color outdoorDiffuse) {
        return outdoorDiffuse!=null && (outdoorDiffuse.getRGB()&0xffffff)==0;
    }
    public static boolean ocean(String name) {
        return name.equals("gfx/tiles/owater") || name.equals("gfx/tiles/odeep") ||
            name.equals("gfx/tiles/odeeper") || name.startsWith("gfx/tiles/ocean");
    }
    public static final Attribute data = new Attribute(VEC4, "waterdata");
    public static final MeshBuf.LayerID<MeshBuf.Vec4Layer> layer = new MeshBuf.V4LayerID(data);
    public static final class Marker extends State {
        static final Slot<Marker> slot = new Slot<>(Slot.Type.DRAW, Marker.class);
        public void apply(Pipe p) {p.put(slot, this);}
        public ShaderMacro shader() {return null;}
    }
    public static final Marker marker = new Marker();
    public static final ShaderMacro hidden = p -> p.fctx.mainmod(b -> b.add(new Discard()), -100);
    /** Object foam must not put transparent triangles into the opaque depth copy. */
    public static final class Foam extends State {
        static final Slot<Foam> slot=new Slot<>(Slot.Type.DRAW,Foam.class);
        public void apply(Pipe p){p.put(slot,this);}
        public ShaderMacro shader(){return Atmos.water?hidden:null;}
    }
    public static final Foam foam=new Foam();
    /** Transparent precipitation must not be included in refracted scene color. */
    public static final class Precipitation extends State {
        static final Slot<Precipitation> slot=new Slot<>(Slot.Type.DRAW,Precipitation.class);
        public void apply(Pipe p){p.put(slot,this);}
        public ShaderMacro shader(){return Atmos.water?hidden:null;}
    }
    public static final Precipitation precipitation=new Precipitation();
    public static Pipe.Op foamMaterial(String texture) {
        return "gfx/fx/oarsplash".equals(texture)?foam:p->p.put(Foam.slot,null);
    }
    static String source(String name) {
        try(InputStream in=WaterSurface.class.getResourceAsStream("assets/"+name)) {
            if(in==null) throw new IOException("Missing water shader "+name);
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] block=new byte[8192]; int n;
            while((n=in.read(block))>0) out.write(block,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        } catch(IOException e) {throw new RuntimeException(e);}
    }
    static final RawFunction waves=new RawFunction(VEC4,"water_position",4,source("water-waves.glsl"));
    static final RawFunction shade=new RawFunction(VEC4,"water_color",17,source("water-shade.glsl"));
    static final RawFunction foamColor=new RawFunction(VEC4,"water_foam",5,
        "vec4 water_foam(vec4 color,vec3 ep,sampler2D depths,vec4 pp,vec4 pr) {\n"+
        " vec2 uv=(ep.xy*pr.xy/(pp.z>.5?1.0:max(-ep.z,.001))+pr.zw)*.5+.5;\n"+
        " if(texture(depths,uv).r < -ep.z-.025) discard;\n"+
        " return color; }\n");
    static final AutoVarying vdata=new AutoVarying(VEC4) {
        protected Expression root(VertexContext ctx) {return data.ref();}
    };
    static final AutoVarying rest=new AutoVarying(VEC3) {
        protected Expression root(VertexContext ctx) {
            return pick(Homo3D.get(ctx.prog).plocxf(vec4(Homo3D.vertex.ref(),l(1))),"xyz");
        }
    };
    static class WaterPass extends State {
        static final Slot<WaterPass> slot=new Slot<>(Slot.Type.DRAW,WaterPass.class);
        final Texture2D.Sampler2D color, depth, wakes;
        final float[] pp, pr;
        final boolean surface;
        final float reflections;
        final float[] rainfall;
        final float[] waveStrength;
        WaterPass(Texture2D.Sampler2D color,Texture2D.Sampler2D depth,Texture2D.Sampler2D wakes,float[] pp,float[] pr,boolean surface,boolean reflections,float rain,boolean ripples,boolean sheltered) {
            this.color=color;this.depth=depth;this.pp=pp;this.pr=pr;this.surface=surface;
            this.wakes=wakes;
            this.reflections=reflections?1:0;
            this.rainfall=new float[]{rain,ripples?1:0};
            this.waveStrength=sheltered?new float[]{0,0}:new float[]{1,1};
        }
        public ShaderMacro shader() {return surface?shader:foamShader;}
        public void apply(Pipe p) {p.put(slot,this);}
    }
    static final Uniform color=new Uniform(SAMPLER2D,p->p.get(WaterPass.slot).color,WaterPass.slot);
    static final Uniform depthtex=new Uniform(SAMPLER2D,p->p.get(WaterPass.slot).depth,WaterPass.slot);
    static final Uniform pp=new Uniform(VEC4,p->p.get(WaterPass.slot).pp,WaterPass.slot);
    static final Uniform pr=new Uniform(VEC4,p->p.get(WaterPass.slot).pr,WaterPass.slot);
    static final Uniform reflect=new Uniform(FLOAT,p->p.get(WaterPass.slot).reflections,WaterPass.slot);
    static final Uniform rainfall=new Uniform(VEC2,p->p.get(WaterPass.slot).rainfall,WaterPass.slot);
    static final Uniform waveStrength=new Uniform(VEC2,p->p.get(WaterPass.slot).waveStrength,WaterPass.slot);
    static final Uniform wakeField=new Uniform(SAMPLER2D,p->p.get(WaterPass.slot).wakes,WaterPass.slot);
    static final Uniform camera=new Uniform(MAT4,Homo3D::camxf,Homo3D.cam);
    static final Uniform sky=new Uniform(SAMPLERCUBE,p->haven.resutil.WaterTile.waterSky(),FrameInfo.slot);
    // Unlike FrameInfo's modulo clock this has no 50-minute discontinuity.
    static final double epoch=Utils.rtime();
    static final Uniform time=new Uniform(FLOAT,p->{FrameInfo f=p.get(FrameInfo.slot);return(float)((f==null?Utils.rtime():f.time)-epoch);},FrameInfo.slot);
    static final ShaderMacro shader=prog->{
        waves.define(prog.vctx); waves.define(prog.fctx); shade.define(prog.fctx);
        Homo3D h=Homo3D.get(prog);
        h.mapv.mod(in->waves.call(in,data.ref(),time.ref(),pick(waveStrength.ref(),"x")),10);
        FragColor.fragcol(prog.fctx).mod(in->shade.call(rest.ref(),Homo3D.frageyev.ref(),vdata.ref(),
            time.ref(),camera.ref(),color.ref(),depthtex.ref(),pp.ref(),pr.ref(),sky.ref(),
            GroundRelief.usun.ref(),Atmos.usuncol.ref(),Atmos.uskycol.ref(),rainfall.ref(),reflect.ref(),wakeField.ref(),waveStrength.ref()),2000);
    };
    static final ShaderMacro foamShader=prog->{
        foamColor.define(prog.fctx);
        FragColor.fragcol(prog.fctx).mod(in->foamColor.call(in,Homo3D.frageyev.ref(),
            depthtex.ref(),pp.ref(),pr.ref()),2000);
    };
    static final RawFunction rainColor=new RawFunction(VEC4,"water_rain_color",2,
        "vec4 water_rain_color(vec4 color,vec3 sky) {\n"+
        " float light=clamp(dot(max(sky,vec3(0)),vec3(.2126,.7152,.0722)),0.0,1.2);\n"+
        " return vec4(vec3(.72,.86,1.0)*(.16+.84*light),clamp(color.a*1.65,0.0,1.0)); }\n");
    static final ShaderMacro rainShader=prog->{
        rainColor.define(prog.fctx);
        FragColor.fragcol(prog.fctx).mod(in->rainColor.call(in,Atmos.uskycol.ref()),2000);
    };
    static final RUtils.AdHoc rainMaterial=new RUtils.AdHoc(rainShader);
    static final class RainSources implements RenderList<Rendered> {
        final Set<RenderList.Slot<? extends Rendered>> slots=new HashSet<>();
        public void add(RenderList.Slot<? extends Rendered> s) {
            // DynSprite installs its state after list registration.
            if(s.obj() instanceof haven.res.gfx.fx.rain.Rain.DropSprite ||
               s.obj() instanceof haven.res.gfx.fx.rain.Rain.SplashSprite)
                synchronized(slots){slots.add(s);}
        }
        public void remove(RenderList.Slot<? extends Rendered> s){synchronized(slots){slots.remove(s);}}
        public void update(RenderList.Slot<? extends Rendered> s){remove(s);add(s);}
        public void update(Pipe group,int[] mask){}
    }
    static final class Sources implements RenderList<Rendered> {
        final Set<RenderList.Slot<? extends Rendered>> slots=new HashSet<>();
        final boolean foam;
        Sources(){this(false);}
        Sources(boolean foam){this.foam=foam;}
        public void add(RenderList.Slot<? extends Rendered> s) {
            if(foam?s.state().get(Foam.slot)!=null:s.state().get(Marker.slot)!=null)
                synchronized(slots){slots.add(s);}
        }
        public void remove(RenderList.Slot<? extends Rendered> s) {synchronized(slots){slots.remove(s);}}
        public void update(RenderList.Slot<? extends Rendered> s) {remove(s);add(s);}
        public void update(Pipe group,int[] mask) {}
    }
    final PView view;
    final WaterWakes wakes=new WaterWakes();
    public boolean reflections=true;
    public boolean rainRipples=false;
    public float rainIntensity=0;
    public boolean sheltered=false;
    final SceneFX.Depth depth;
    final Sources sources=new Sources();
    final Sources foamSources=new Sources(true);
    final RainSources rainSources=new RainSources();
    Texture2D.Sampler2D result, sceneDepth;
    public WaterSurface(PView view) {
        this.view=view;depth=new SceneFX.Depth(view);sources.syncadd(view.tree,Rendered.class);
        foamSources.syncadd(view.tree,Rendered.class);
        rainSources.syncadd(view.tree,Rendered.class);
    }
    public int order() {return -210;}
    public void run(GOut g,Texture2D.Sampler2D in) {
        Texture2D.Sampler2D ds=depth.samp();
        List<RenderList.Slot<? extends Rendered>> copy;
        synchronized(sources.slots){copy=new ArrayList<>(sources.slots);}
        boolean noFoam;
        synchronized(foamSources.slots){noFoam=foamSources.slots.isEmpty();}
        boolean noRain;
        synchronized(rainSources.slots){noRain=rainSources.slots.stream().noneMatch(s->{
            Model model=((haven.res.lib.vertspr.DynSprite)s.obj()).model;
            return model!=null && model.n>0;
        });}
        if(ds==null || (copy.isEmpty() && noFoam && noRain)) {g.image(new TexRaw(in,true),Coord.z,g.sz());return;}
        Coord size=in.tex.sz();
        if(!NPostFX.fits(result,size,in.tex.ifmt.cf)) {
            if(result!=null) result.dispose();
            result=NPostFX.mktarget(size,in.tex.ifmt.cf);
        }
        if(sceneDepth==null || !sceneDepth.tex.sz().equals(size)) {
            if(sceneDepth!=null) sceneDepth.dispose();
            sceneDepth=new Texture2D(size,DataBuffer.Usage.STATIC,new VectorFormat(1,NumberFormat.FLOAT32),null).sampler();
            sceneDepth.swrap(Texture.Wrapping.CLAMP).twrap(Texture.Wrapping.CLAMP);
        }
        float[][] projection=depth.projparams();
        NPostFX.blit(NPostFX.target(g,sceneDepth),in,new NPostFX.Pass(SceneFX.ld_sh,ds,projection[0]));
        GOut target=NPostFX.target(g,result);
        target.image(new TexRaw(in,true),Coord.z,size);
        Matrix4f matrix=Homo3D.prjxf(view.basic.state());
        boolean ortho=projection[0][2]>.5f;
        // Include lens offsets/TAA jitter in both projection and reconstruction.
        float[] screen={matrix.m[0],matrix.m[5],ortho?matrix.m[12]:-matrix.m[8],ortho?matrix.m[13]:-matrix.m[9]};
        FrameInfo frame=view.basic.state().get(FrameInfo.slot);
        Texture2D.Sampler2D wakeTexture=wakes.render(g,view.basic.state(),size,frame==null?Utils.rtime():frame.time);
        WaterPass pass=new WaterPass(in,sceneDepth,wakeTexture,projection[0],screen,true,reflections,rainIntensity,rainRipples,sheltered);
        WaterPass foamUniforms=new WaterPass(in,sceneDepth,wakeTexture,projection[0],screen,false,false,0,false,sheltered);
        try(Locked lock=view.tree.lock()) {
            // Map cuts may have unloaded while the snapshot targets were prepared.
            synchronized(sources.slots){copy=new ArrayList<>(sources.slots);}
            for(RenderList.Slot<? extends Rendered> s:copy) {
                Pipe st=s.state().copy();
                haven.resutil.WaterTile.clearSurface(st);
                st.put(FragColor.slot,new FragColor<>(result.tex.image(0)));
                st.put(FragColor.blend,null);
                st.put(States.depthbias,null);
                st.put(States.maskdepth.slot,null);
                st.prep(pass);
                st.prep(States.asynccompile);
                s.obj().draw(st,g.out);
            }
            List<RenderList.Slot<? extends Rendered>> foamCopy;
            synchronized(foamSources.slots){foamCopy=new ArrayList<>(foamSources.slots);}
            // Draw against opaque scene depth, not the newly displaced water.
            // Flat legacy foam skirts must not be sliced by wave crests.
            foamCopy.sort(Comparator.comparingDouble(s->
                Homo3D.camxf(s.state()).mul(Homo3D.locxf(s.state())).m[14]));
            for(RenderList.Slot<? extends Rendered> s:foamCopy) {
                Pipe st=s.state().copy();
                st.put(Foam.slot,null);
                st.put(FragColor.slot,new FragColor<>(result.tex.image(0)));
                st.put(States.depthtest,null);
                st.put(States.depthbias,null);
                st.prep(States.maskdepth);
                st.prep(FragColor.blend(new BlendMode(BlendMode.Factor.SRC_ALPHA,BlendMode.Factor.INV_SRC_ALPHA)));
                // The foam variant retains the object's geometry and animation.
                st.put(WaterPass.slot,foamUniforms);
                st.prep(States.asynccompile);
                s.obj().draw(st,g.out);
            }
            // Replay the existing particles once, after water has written real
            // depth. No refraction/tinting of airborne drops or extra emission.
            List<RenderList.Slot<? extends Rendered>> rainCopy;
            synchronized(rainSources.slots){rainCopy=new ArrayList<>(rainSources.slots);}
            for(RenderList.Slot<? extends Rendered> s:rainCopy) {
                Pipe st=s.state().copy();
                st.put(Precipitation.slot,null);
                st.put(Light.lighting,null);
                st.put(FragColor.slot,new FragColor<>(result.tex.image(0)));
                st.prep(new States.Depthtest(States.Depthtest.Test.LE));
                st.prep(States.maskdepth);
                st.prep(FragColor.blend(new BlendMode(BlendMode.Factor.SRC_ALPHA,BlendMode.Factor.INV_SRC_ALPHA,
                    BlendMode.Factor.ZERO,BlendMode.Factor.ONE)));
                st.prep(rainMaterial);
                st.prep(States.asynccompile);
                s.obj().draw(st,g.out);
            }
        }
        g.image(new TexRaw(result,true),Coord.z,g.sz());
    }
    public void dispose() {
        view.tree.remove(sources);
        view.tree.remove(foamSources);
        view.tree.remove(rainSources);
        wakes.dispose();
        if(result!=null) result.dispose();
        if(sceneDepth!=null) sceneDepth.dispose();
        super.dispose();
    }
}
