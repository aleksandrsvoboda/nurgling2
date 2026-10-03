package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.iosys.tk.*;
import haven.resutil.WaterTile;
import java.nio.ByteOrder;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import java.util.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/** Draw the actual water geometry/pass on Vulkan; inspect refraction, depth,
 * banks, animation, river/ocean separation and classic fallback. */
public class WaterSurfaceTest {
    static final Coord SIZE=Coord.of(192,192);
    static final VectorFormat RGBA=new VectorFormat(4,NumberFormat.FLOAT32);
    static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static final RawFunction floor=new RawFunction(VEC4,"water_test_floor",1,
        "vec4 water_test_floor(vec3 p) { float cell=mod(floor(p.x/3.0)+floor(p.y/3.0),2.0); return vec4(mix(vec3(.18,.16,.12),vec3(.65,.57,.40),cell),1); }");
    static final ShaderMacro floorShader=p->{floor.define(p.fctx);FragColor.fragcol(p.fctx).mod(in->floor.call(Homo3D.fragmapv.ref()),100);};
    static FastMesh plane(float z,boolean water,float kind,float flow) {
        return plane(z,water,kind,flow,0,32,0);
    }
    static FastMesh plane(float z,boolean water,float kind,float flow,int first,int last,float origin) {
        MeshBuf buf=new MeshBuf();
        MeshBuf.Vec4Layer layer=water?buf.layer(WaterSurface.layer):null;
        int n=32;MeshBuf.Vertex[][] v=new MeshBuf.Vertex[n+1][n+1];
        for(int y=0;y<=n;y++)for(int x=first;x<=last;x++) {
            v[y][x]=buf.new Vertex(Coord3f.of(x-16-origin,y-16,z),Coord3f.zu);
            if(water)layer.set(v[y][x],new float[]{12,kind,flow,flow*.3f});
        }
        for(int y=0;y<n;y++)for(int x=first;x<last;x++) {
            buf.new Face(v[y][x],v[y][x+1],v[y+1][x+1]);
            buf.new Face(v[y][x],v[y+1][x+1],v[y+1][x]);
        }
        return buf.mkmesh();
    }
    static float[] capture(Windeye window,float bed,float kind,float flow,double seconds,boolean front,boolean perspective) throws Exception {
        return capture(window,bed,kind,flow,seconds,front,perspective,-1);
    }
    static float[] capture(Windeye window,float bed,float kind,float flow,double seconds,boolean front,boolean perspective,float foamAlpha) throws Exception {
        return capture(window,bed,kind,flow,seconds,front,perspective,foamAlpha,true);
    }
    static float[] capture(Windeye window,float bed,float kind,float flow,double seconds,boolean front,boolean perspective,float foamAlpha,boolean reflections) throws Exception {
        return capture(window,bed,kind,flow,seconds,front,perspective,foamAlpha,reflections,false);
    }
    static float[] capture(Windeye window,float bed,float kind,float flow,double seconds,boolean front,boolean perspective,float foamAlpha,boolean reflections,boolean wake) throws Exception {
        return capture(window,bed,kind,flow,seconds,front,perspective,foamAlpha,reflections,wake?1:0);
    }
    static float[] capture(Windeye window,float bed,float kind,float flow,double seconds,boolean front,boolean perspective,float foamAlpha,boolean reflections,int wakeMode) throws Exception {
        PView view=new PView(SIZE){protected void basic(){}};
        Texture2D scene=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
        Texture2D output=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
        Texture2D depth=new Texture2D(SIZE,DataBuffer.Usage.STATIC,Texture.DEPTH,null);
        view.depth=depth;
        Projection projection=perspective?Projection.frustum(-.32f,.32f,-.32f,.32f,1,300):Projection.ortho(-16,16,-16,16,1,300);
        projection=new Projection(Transform.makexlate(new Matrix4f(),Coord3f.of(.65f/SIZE.x,-.4f/SIZE.y,0)).mul(projection.fin(Matrix4f.id)));
        Camera cam=perspective?Camera.pointed(Coord3f.o,50,.70f,.5f):new Camera(Transform.makexlate(new Matrix4f(),Coord3f.of(0,0,-50)));
        Pipe base=new BufPipe().prep(new FragColor<>(scene.image(0))).prep(new DepthBuffer<>(depth.image(0)))
            .prep(new States.Viewport(Area.sized(SIZE))).prep(Homo3D.state).prep(projection).prep(cam)
            .prep(new FrameInfo(WaterSurface.epoch+seconds)).prep(new States.Depthtest(States.Depthtest.Test.LE))
            .prep(new Atmos.Env(0,false,true,-1,new float[]{.4f,0,.9165f},new float[]{.5f,.5f,.5f},new float[]{.7f,.8f,1},null));
        view.basic.ostate(p->{for(State state:base.states())if(state!=null)state.apply(p);});
        // Apply source state through RenderTree, exactly like MapMesh.Model.
        FastMesh water=plane(0,true,kind,flow), bottom=plane(-bed,false,0,0), foreground=plane(3,false,0,0);
        FastMesh left=null,right=null;
        if(wakeMode==4) {
            left=plane(0,true,kind,flow,0,16,-16);right=plane(0,true,kind,flow,16,32,16);
            view.basic.add(left,Pipe.Op.compose(Location.xlate(Coord3f.of(-16,0,0)),WaterTile.surfmat));
            view.basic.add(right,Pipe.Op.compose(Location.xlate(Coord3f.of(16,0,0)),WaterTile.surfmat));
        } else view.basic.add(WaterTile.surfmat.apply(water));
        FastMesh foam=plane(.1f,false,0,0);
        Pipe.Op foamMaterial=Pipe.Op.compose(WaterSurface.foam,new BaseColor(new FColor(.7f,.85f,1,foamAlpha)),
            FragColor.blend(new BlendMode(BlendMode.Factor.SRC_ALPHA,BlendMode.Factor.INV_SRC_ALPHA)));
        if(foamAlpha>=0)view.basic.add(foam,foamMaterial);
        WaterSurface renderer=new WaterSurface(view);
        renderer.reflections=reflections;
        if(wakeMode>0 && wakeMode<4)for(int i=0;i<=14;i++) {
            double x=wakeMode==2?9*Math.cos(i*.18):-12+i*1.6;
            double y=wakeMode==2?9*Math.sin(i*.18)-5:0;
            renderer.wakes.sample(1,x,y,0,1.5f,1.8f,WaterSurface.epoch+10-2.24+i*.16);
        }
        require(renderer.sources.slots.size()==(wakeMode==4?2:1),"Map water source not registered");
        require(renderer.foamSources.slots.size()==(foamAlpha>=0?1:0),"Object foam source not registered separately");
        try {
            Render out=window.env().render();
            out.clear(base,FragColor.fragcol,new FColor(.1f,.12f,.15f,1));out.clear(base,1.0);
            Pipe ground=base.copy().prep(wakeMode==5?new BaseColor(new FColor(0,0,0,1)):new RUtils.AdHoc(floorShader));bottom.draw(ground,out);
            if(front)foreground.draw(base.copy().prep(new BaseColor(new FColor(.8f,.04f,.02f,1))),out);
            Pipe hidden=base.copy().prep(WaterTile.surfmat);water.draw(hidden,out);
            if(foamAlpha>=0)foam.draw(base.copy().prep(foamMaterial),out);
            Pipe target=new BufPipe().prep(new FragColor<>(output.image(0))).prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            renderer.run(new GOut(out,target,SIZE),scene.sampler());
            CompletableFuture<float[]> result=new CompletableFuture<>();
            out.pget(output.image(0),RGBA,bytes->{float[] a=new float[SIZE.x*SIZE.y*4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(a);result.complete(a);});
            window.swapbuffers(out,false);window.env().submit(out);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!result.isDone()&&System.nanoTime()<deadline){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);}
            return result.get(1,TimeUnit.SECONDS);
        } finally {renderer.dispose();water.dispose();if(left!=null){left.dispose();right.dispose();}bottom.dispose();foreground.dispose();foam.dispose();view.dispose();scene.dispose();output.dispose();depth.dispose();}
    }
    static double difference(float[] a,float[] b) {double s=0;for(int i=0;i<a.length;i++)if(i%4!=3)s+=Math.abs(a[i]-b[i]);return s/(a.length*.75);}
    static double contrast(float[] a) {
        return contrast(a,0);
    }
    static double contrast(float[] a,int channel) {
        double lo=1e9,hi=-1e9;
        for(int y=32;y<160;y++)for(int x=32;x<160;x++){float c=a[(y*SIZE.x+x)*4+channel];lo=Math.min(lo,c);hi=Math.max(hi,c);}
        return hi-lo;
    }
    static void save(float[] a,String name)throws Exception {
        BufferedImage image=new BufferedImage(SIZE.x,SIZE.y,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<SIZE.y;y++)for(int x=0;x<SIZE.x;x++) {
            int i=(y*SIZE.x+x)*4,rgb=0;
            for(int c=0;c<3;c++)rgb=rgb<<8|Math.round(Math.max(0,Math.min(1,a[i+c]))*255);
            image.setRGB(x,SIZE.y-1-y,rgb);
        }
        new File("build/water-preview").mkdirs();ImageIO.write(image,"png",new File("build/water-preview/"+name+".png"));
    }
    static Map<String,float[]> boundary(MapMesh mesh,float x) {
        RenderTree tree=new RenderTree();tree.add(mesh);
        WaterSurface.Sources sources=new WaterSurface.Sources();sources.syncadd(tree,Rendered.class);
        Map<String,float[]> result=new HashMap<>();
        for(RenderList.Slot<? extends Rendered> slot:sources.slots) {
            FastMesh surface=(FastMesh)slot.obj();
            require(surface.vert.num<65536,"Water chunk overflows 16-bit indices");
            java.nio.FloatBuffer positions=surface.vert.buf(VertexBuf.VertexData.class).data;
            java.nio.FloatBuffer data=null;
            for(VertexBuf.AttribData attribute:surface.vert.bufs)if(attribute.attr==WaterSurface.data)data=((VertexBuf.FloatData)attribute).data;
            require(data!=null,"Water chunk lacks depth/current/profile");
            for(int v=0;v<surface.vert.num;v++)if(Math.abs(positions.get(v*3)-x)<.001) {
                float[] a=new float[4];for(int c=0;c<4;c++)a[c]=data.get(v*4+c);
                result.put(Float.toString(positions.get(v*3+1)),a);
            }
        }
        tree.remove(sources);tree.dispose();return result;
    }
    static void meshSeams() {
        nurgling.NConfig.getGlobalInstance(); // In-memory defaults; never load/save user preferences.
        WaterTile river=new WaterTile(0,"gfx/tiles/water",(m,d)->{},12);
        WaterTile ocean=new WaterTile(1,"gfx/tiles/odeep",(m,d)->{},40);
        MCache map=new MCache(null) {
            public int gettile(Coord c){return c.x<3?0:1;}
            public double getfz(Coord c){return c.x*c.x*.012+c.y*c.y*.007;}
            public Tiler tiler(int id){return id==0?river:ocean;}
        };
        MapMesh left=MapMesh.build(map,new Random(1),Coord.z,Coord.of(3,4));
        MapMesh right=MapMesh.build(map,new Random(1),Coord.of(3,0),Coord.of(3,4));
        Map<String,float[]> a=boundary(left,33),b=boundary(right,0);
        require(a.size()==17 && a.keySet().equals(b.keySet()),"Water chunks have different edge topology");
        for(String key:a.keySet())for(int c=0;c<4;c++)require(Math.abs(a.get(key)[c]-b.get(key)[c])<.0001,"Depth/flow/profile seam at "+key+", channel "+c);
        for(float[] attr:a.values())require(Math.abs(attr[1]-.5)<.0001,"River/ocean transition is not shared at edge");
        left.dispose();right.dispose();System.out.println("Water map meshes: shared depth/current/profile and subdivided edges PASS");
    }
    // Isolate the wave pattern from bed textures and reflections for visual QA.
    static void patternPreview(Windeye window)throws Exception {
        RawFunction sample=new RawFunction(VEC4,"water_pattern_preview",2,
            "vec4 water_pattern_preview(vec4 color,vec2 tc) { vec4 rest=vec4(tc*120.0+vec2(-11550,10175),0,1); vec4 moved=water_position(rest,vec4(30,1,1,0),10.0); if(length(moved.xy-rest.xy)>.0001) return vec4(2); vec3 n=water_normal(tc*120.0,vec4(30,0,1,0),10.0,120.0/192.0); return vec4(vec3(clamp(.5+dot(n.xy,vec2(3.0,2.0)),0.0,1.0)),water_activity(tc*120.0,10.0)); }");
        ShaderMacro shader=p->{WaterSurface.waves.define(p.fctx);NPostFX.shader(sample).modify(p);};
        Texture2D output=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
        try {
            Render out=window.env().render();
            Pipe target=new BufPipe().prep(new FragColor<>(output.image(0))).prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            NPostFX.blit(new GOut(out,target,SIZE),Temporal.one(),new NPostFX.Pass(shader));
            CompletableFuture<float[]> result=new CompletableFuture<>();
            out.pget(output.image(0),RGBA,bytes->{float[] a=new float[SIZE.x*SIZE.y*4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(a);result.complete(a);});
            window.swapbuffers(out,false);window.env().submit(out);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!result.isDone()&&System.nanoTime()<deadline){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);}
            float[] image=result.get(1,TimeUnit.SECONDS);int quiet=0,active=0,intermediate=0;
            for(int i=3;i<image.length;i+=4) {
                require(image[i-3]<=1,"Wave geometry crosses its map-section footprint");
                if(image[i]<.4)quiet++;if(image[i]>.6)active++;
                if(image[i]>.15 && image[i]<.85)intermediate++;
            }
            require(quiet>SIZE.x*SIZE.y*.05,"River has no quieter stretches");
            require(active>SIZE.x*SIZE.y*.05,"Ripple groups have disappeared");
            require(intermediate>SIZE.x*SIZE.y*.75,"Wave activity still switches between two patterns");
            double slopeSignal=0;
            for(int i=0;i<image.length;i+=4)slopeSignal+=(image[i]-.5)*(image[i]-.5);
            slopeSignal=Math.sqrt(slopeSignal/(SIZE.x*SIZE.y));
            require(slopeSignal>.035 && slopeSignal<.25,"Broad waves are invisible or excessively steep: "+slopeSignal);
            System.out.printf("Wave normal signal: %.4f%n",slopeSignal);
            System.out.printf("River activity: quiet %.1f%%, active %.1f%%%n",100.0*quiet/(SIZE.x*SIZE.y),100.0*active/(SIZE.x*SIZE.y));
            save(image,"wave-pattern");
        } finally {output.dispose();}
    }
    static void glintAngles(Windeye window)throws Exception {
        RawFunction sample=new RawFunction(VEC4,"water_glint_test",3,
            "vec4 water_glint_test(vec4 color,vec2 tc,vec4 params) {\n"+
            " vec3 n=water_normal(tc*40.0,vec4(30,params.y,1,0),10.0,40.0/192.0);\n"+
            " vec3 v=vec3(cos(params.x),0,sin(params.x));\n"+
            " vec3 light=normalize(vec3(-v.x+(tc.x-.5)*.12,(tc.y-.5)*.12,v.z));\n"+
            " return vec4(water_glint(n,v,light,vec3(0,0,1),vec3(1,.88,.7)*params.z,params.y,0),water_reflectance(v.z,params.y)); }\n");
        ShaderMacro shader=p->{WaterSurface.waves.define(p.fctx);WaterSurface.shade.define(p.fctx);NPostFX.shader(sample,NPostFX.u(VEC4,0)).modify(p);};
        double maximum=0,gradient=0;
        for(float kind:new float[]{0,1})for(float elevation:new float[]{.08f,.2f,.45f,.75f,1.2f,1.55f}) {
            Texture2D output=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
            try {
                Render out=window.env().render();
                Pipe target=new BufPipe().prep(new FragColor<>(output.image(0))).prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
                NPostFX.blit(new GOut(out,target,SIZE),Temporal.one(),new NPostFX.Pass(shader,new float[]{elevation,kind,4,0}));
                CompletableFuture<float[]> result=new CompletableFuture<>();
                out.pget(output.image(0),RGBA,bytes->{float[] a=new float[SIZE.x*SIZE.y*4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(a);result.complete(a);});
                window.swapbuffers(out,false);window.env().submit(out);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!result.isDone()&&System.nanoTime()<deadline){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);}
                float[] image=result.get(1,TimeUnit.SECONDS);double peak=0;
                for(int i=0;i<image.length;i+=4) {
                    for(int c=0;c<3;c++)require(Float.isFinite(image[i+c])&&image[i+c]>=0&&image[i+c]<(kind==0?.339:.391),"Direct reflection washes out water at elevation "+elevation);
                    require(image[i+3]<(kind==0?.15:.22),"Sky reflection washes out the water body");
                    require(image[i]+image[i+3]<.62,"Combined sun and white-sky reflection clips");
                    peak=Math.max(peak,image[i]);
                    if(i>=4 && (i/4)%SIZE.x!=0)gradient=Math.max(gradient,Math.abs(image[i]-image[i-4]));
                    require(Math.abs(image[i+1]-image[i]*.88)<.0001,"Highlight compression loses sun color");
                }
                require(peak>(elevation<.2f?.002:.015),"Direct reflection disappeared at elevation "+elevation);
                maximum=Math.max(maximum,peak);
                if(kind==0 && elevation==.45f)save(image,"sun-glint");
            }finally{output.dispose();}
        }
        require(gradient<.40,"Unresolved highlight edges sparkle");
        System.out.printf("Water sun reflection: 12 angle/profile cases PASS, peak %.4f, largest adjacent-pixel change %.4f%n",maximum,gradient);
    }
    // Sweep a fixed patch and fixed sun through a full camera orbit. Unlike a
    // highlight-aligned test, this detects the foil-to-flat-blue material flip.
    static void lightingOrbit(Windeye window)throws Exception {
        RawFunction sample=new RawFunction(VEC4,"water_lighting_orbit",3,
            "vec4 water_lighting_orbit(vec4 c,vec2 tc,vec4 p) {\n"+
            " vec3 n=water_normal(tc*64.0,vec4(30,p.z,1,0),10.0,64.0/192.0);\n"+
            " vec3 v=vec3(cos(p.x)*cos(p.y),sin(p.x)*cos(p.y),sin(p.y));\n"+
            " vec3 light=normalize(vec3(-.6,.2,.77)),sky=vec3(.7,.8,1.0);\n"+
            " vec3 body=water_body(mix(vec3(.050,.110,.190),vec3(.020,.220,.180),p.z)*sky,n,light,sky);\n"+
            " vec3 result=mix(body,sky*.3,water_reflectance(dot(n,v),p.z));\n"+
            " vec3 glint=water_glint(n,v,light,vec3(0,0,1),vec3(1,.88,.7),p.z,0);\n"+
            " return vec4(result+glint,glint.r); }\n");
        ShaderMacro shader=p->{WaterSurface.waves.define(p.fctx);WaterSurface.shade.define(p.fctx);NPostFX.shader(sample,NPostFX.u(VEC4,0)).modify(p);};
        double worstMeanRange=0,worstBrightCoverage=0,minDetail=1,peakGlint=0;
        for(float kind:new float[]{0,1})for(float elevation:new float[]{.35f,.75f,1.15f}) {
            double low=1,high=0;
            for(int step=0;step<24;step++) {
                Texture2D output=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
                try {
                    Render out=window.env().render();
                    Pipe target=new BufPipe().prep(new FragColor<>(output.image(0))).prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
                    NPostFX.blit(new GOut(out,target,SIZE),Temporal.one(),new NPostFX.Pass(shader,new float[]{(float)(step*Math.PI/12),elevation,kind,0}));
                    CompletableFuture<float[]> result=new CompletableFuture<>();
                    out.pget(output.image(0),RGBA,bytes->{float[] a=new float[SIZE.x*SIZE.y*4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(a);result.complete(a);});
                    window.swapbuffers(out,false);window.env().submit(out);
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                    while(!result.isDone()&&System.nanoTime()<deadline){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);}
                    float[] pixels=result.get(1,TimeUnit.SECONDS);double mean=0,sq=0;int bright=0;
                    for(int i=0;i<pixels.length;i+=4) {
                        double lum=pixels[i]*.2126+pixels[i+1]*.7152+pixels[i+2]*.0722;
                        require(Double.isFinite(lum)&&lum>=0&&lum<1,"Invalid orbit radiance");
                        mean+=lum;sq+=lum*lum;if(pixels[i+3]>.08)bright++;
                        peakGlint=Math.max(peakGlint,pixels[i+3]);
                    }
                    int count=SIZE.x*SIZE.y;mean/=count;
                    low=Math.min(low,mean);high=Math.max(high,mean);
                    minDetail=Math.min(minDetail,Math.sqrt(Math.max(0,sq/count-mean*mean)));
                    worstBrightCoverage=Math.max(worstBrightCoverage,bright/(double)count);
                    if(kind==0 && elevation==.75f && step%3==0)save(pixels,"lighting-orbit-"+step);
                } finally {output.dispose();}
            }
            worstMeanRange=Math.max(worstMeanRange,high-low);
        }
        System.out.printf("Water orbit: mean range %.5f, minimum wave detail %.5f, bright coverage %.1f%%, glint peak %.4f%n",worstMeanRange,minDetail,worstBrightCoverage*100,peakGlint);
        require(worstMeanRange<.035,"Camera orbit changes the whole water body's brightness");
        require(minDetail>.0025,"Waves disappear outside the sun reflection cone");
        require(worstBrightCoverage<.12,"Bright glints form a silver carpet");
        require(peakGlint>.08,"Local bright glints disappeared");
    }
    // Sample the actual accumulated field along both crests. This catches dark
    // gaps between packets and procedural checker modulation independently of
    // bed textures, reflections and the camera's lighting angle.
    static void wakeContinuity(Windeye window)throws Exception {
        WaterWakes wakes=new WaterWakes();
        try {
            for(int i=0;i<=14;i++)wakes.sample(1,-12+i*1.6,0,0,1.5f,1.8f,10-2.24+i*.16);
            Pipe base=new BufPipe().prep(Homo3D.state).prep(Projection.ortho(-16,16,-16,16,1,100))
                .prep(new Camera(Transform.makexlate(new Matrix4f(),Coord3f.of(0,0,-50))));
            Render out=window.env().render();
            Texture2D.Sampler2D field=wakes.render(new GOut(out,base,SIZE),base,SIZE,10);
            Coord sz=field.tex.sz();
            CompletableFuture<float[]> result=new CompletableFuture<>();
            out.pget(field.tex.image(0),RGBA,bytes->{float[] a=new float[sz.x*sz.y*4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(a);result.complete(a);});
            window.swapbuffers(out,false);window.env().submit(out);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!result.isDone()&&System.nanoTime()<deadline){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);}
            float[] image=result.get(1,TimeUnit.SECONDS);
            double minimum=1,maximumJump=0;
            for(int side:new int[]{-1,1}) {
                float previous=-1;
                for(int i=0;i<48;i++) {
                    double age=.4+i*1.1/47;
                    double x=12.2-7.5*age,y=side*4.330127*age;
                    int px=(int)((x+16)*sz.x/32),py=(int)((y+16)*sz.y/32);
                    float foam=image[(py*sz.x+px)*4+2];
                    minimum=Math.min(minimum,foam);
                    if(previous>=0)maximumJump=Math.max(maximumJump,Math.abs(foam-previous));
                    previous=foam;
                }
            }
            require(minimum>.12,"Wake crest has faint/checker gaps: "+minimum);
            require(maximumJump<.16,"Wake crest breaks into discrete spots: "+maximumJump);
            // Ahead of the moving tip neither arm may extend into a cross.
            for(int y=0;y<sz.y;y++)for(int x=0;x<sz.x;x++)
                if((x+.5)*32/sz.x-16>12.6)
                    require(image[(y*sz.x+x)*4+2]<.015,"Wake extends ahead of the tip as an X");
            System.out.printf("Wake crest continuity PASS: minimum %.4f, adjacent change %.4f%n",minimum,maximumJump);
        } finally {wakes.dispose();}
    }
    public static void main(String[] args)throws Exception {
        Atmos.water=true;
        Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();int exit=0;
        try {
            meshSeams();
            if(args.length>0 && args[0].equals("--cached-resources")) {
                Resource.setcache(ResCache.global);
                for(String name:new String[]{"water","deep"}) {
                    Tileset set=Resource.remote().loadwait("gfx/tiles/"+name).flayer(Tileset.class);
                    Tiler tile;while(true){try{tile=set.tfac().create(1,set);break;}catch(Loading l){l.waitfor();}}
                    require(tile instanceof WaterTile && !((WaterTile)tile).isOcean,"Actual river resource misclassified");
                    System.out.println("Cached "+name+" water depth: "+((WaterTile)tile).depth);
                }
            }
            window.title("Water rendering regression");window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            wakeContinuity(window);
            patternPreview(window);
            glintAngles(window);
            lightingOrbit(window);
            float[] shallow=capture(window,1,0,1,10,false,false),deep=capture(window,70,0,1,10,false,false);
            float[] ocean=capture(window,70,1,0,10,false,false),moving=capture(window,6,0,1,10.4,false,false);
            float[] animatedBase=capture(window,6,0,1,10,false,false);
            float[] riverDeep=capture(window,30,0,1,10,false,false);
            float[] riverDeepBlackBed=capture(window,30,0,1,10,false,false,-1,true,5);
            float[] invisibleFoam=capture(window,6,0,1,10,false,false,0);
            float[] visibleFoam=capture(window,6,0,1,10,false,false,.5f);
            float[] occludedFoam=capture(window,12,1,0,10,true,false,.5f);
            float[] foreground=capture(window,12,1,0,10,true,false),perspective=capture(window,8,0,1,10,false,true);
            float[] noReflections=capture(window,8,0,1,10,false,true,-1,false);
            float[] splitSections=capture(window,8,0,1,10,false,true,-1,true,4);
            require(difference(perspective,splitSections)<.00001,"Separately located water sections overlap or change shading at their boundary");
            System.out.printf("Separate water sections vs continuous mesh PASS: %.8f%n",difference(perspective,splitSections));
            float[] movingNoReflections=capture(window,8,0,1,10.4,false,true,-1,false);
            float[] withWake=capture(window,8,0,1,10,false,true,-1,false,true);
            float[] wakeOccluded=capture(window,12,1,0,10,true,false,-1,true,true);
            save(withWake,"moving-object-wake");save(noReflections,"without-reflections");
            System.out.printf("Wake image difference: %.8f%n",difference(noReflections,withWake));
            require(difference(noReflections,withWake)>.0002,"Moving source leaves no visible wake with reflections off");
            require(difference(foreground,wakeOccluded)<.000001,"Wake draws over foreground geometry");
            save(capture(window,30,0,1,10,false,false,-1,true,true),"wake-top-view");
            save(capture(window,30,0,1,10,false,false,-1,true,2),"wake-turn");
            save(capture(window,30,0,1,11,false,false,-1,true,1),"wake-stopped");
            require(difference(perspective,noReflections)>.0005,"Reflection switch has no visible effect");
            require(difference(noReflections,movingNoReflections)>.0001,"Reflection switch also stops water motion");
            save(noReflections,"without-reflections");
            float[] before=capture(window,8,0,2,13.999,false,false),after=capture(window,8,0,2,14.001,false,false);
            float[] still=capture(window,8,0,0,10,false,false),flowing=capture(window,8,0,3,10,false,false);
            for(float[] image:new float[][]{shallow,deep,ocean,moving,foreground,perspective,before,after})
                for(float v:image) require(Float.isFinite(v)&&v>=0&&v<5,"Invalid water radiance");
            save(shallow,"river-shallow");save(deep,"river-deep");save(ocean,"ocean-deep");save(perspective,"perspective");
            System.out.printf("Water GPU metrics: contrast %.5f -> %.5f, ocean %.5f, animation %.5f, flow %.5f, seam %.5f%n",contrast(shallow),contrast(deep),difference(deep,ocean),difference(animatedBase,moving),difference(still,flowing),difference(before,after));
            require(contrast(shallow)>.35,"Shallow water hides the bed");
            require(contrast(deep)<contrast(shallow)*.35,"Depth absorption is missing");
            require(difference(animatedBase,invisibleFoam)<.000001,"Transparent foam triangles punch holes in water");
            require(difference(animatedBase,visibleFoam)>.05,"Object foam disappeared instead of compositing over water");
            require(difference(foreground,occludedFoam)<.000001,"Object foam draws through foreground geometry");
            System.out.println("Object foam: transparent depth, visible blending and foreground occlusion PASS");
            for(int c=0;c<3;c++) {
                require(contrast(animatedBase,c)<.38,"Shallow river is too transparent in channel "+c);
                require(contrast(animatedBase,c)>.10,"Shallow river has become opaque in channel "+c);
                double bedSignal=0;
                for(int y=32;y<160;y++)for(int x=32;x<160;x++) {
                    int i=(y*SIZE.x+x)*4+c;
                    bedSignal=Math.max(bedSignal,Math.abs(riverDeep[i]-riverDeepBlackBed[i]));
                }
                require(bedSignal<.08,"Deep river exposes the bed in channel "+c+": "+bedSignal);
            }
            save(animatedBase,"river-depth-6");save(riverDeep,"river-depth-30");
            require(difference(deep,ocean)>.01,"Ocean and river are indistinguishable");
            require(difference(animatedBase,moving)>.0005,"Water is static");
            require(difference(before,after)<.006,"Flow reset pops");
            require(difference(still,flowing)>.0001,"River ignores current");
            for(int i=0;i<foreground.length;i+=4)require(Math.abs(foreground[i]-.8)<.001&&Math.abs(foreground[i+1]-.04)<.001,"Water refracts foreground");
            save(shallow,"river-shallow");save(deep,"river-deep");save(ocean,"ocean-deep");save(perspective,"perspective");
            System.out.printf("Water GPU PASS: bed contrast %.3f -> %.3f, ocean difference %.4f, animation %.4f, flow %.4f, seam %.5f%n",contrast(shallow),contrast(deep),difference(deep,ocean),difference(animatedBase,moving),difference(still,flowing),difference(before,after));
        }catch(Throwable failure){failure.printStackTrace();exit=1;}finally{window.dispose();toolkit.dispose();}
        System.exit(exit);
    }
}
