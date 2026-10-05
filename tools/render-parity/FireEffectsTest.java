package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.iosys.tk.Toolkit;
import haven.iosys.tk.Windeye;
import java.util.concurrent.*;
import java.nio.ByteOrder;
import static haven.render.sl.Type.*;

/** Production shader readback and deterministic particle trajectory checks. */
public class FireEffectsTest {
    static void require(boolean value, String message) { if(!value) throw new AssertionError(message); }
    static final Coord SIZE = Coord.of(256,256);
    static float[] capture(Windeye window, VolumeFire.Cache cache, int frame, float angle, boolean occlude, int steps, String file) throws Exception {
        return capture(window,cache,frame,angle,occlude,steps,file,new float[]{1,1,1});
    }
    static float[] capture(Windeye window, VolumeFire.Cache cache, int frame, float angle, boolean occlude, int steps, String file,float[] tint) throws Exception {
        return capture(window,cache,frame,angle,occlude,steps,file,tint,Temporal.one());
    }
    static float[] capture(Windeye window, VolumeFire.Cache cache, int frame, float angle, boolean occlude, int steps, String file,float[] tint,Texture2D.Sampler2D pigment) throws Exception {
        VectorFormat rgba = new VectorFormat(4,NumberFormat.FLOAT32);
        Texture2D output = new Texture2D(SIZE,DataBuffer.Usage.STATIC,rgba,null);
        Texture2D depth = new Texture2D(SIZE,DataBuffer.Usage.STATIC,new VectorFormat(1,NumberFormat.FLOAT32),(image,env)-> {
            if(image.level!=0) return null;
            FillBuffer fill=env.fillbuf(image); java.nio.ByteBuffer b=fill.push();
            for(int y=0;y<SIZE.y;y++) for(int x=0;x<SIZE.x;x++) b.putFloat(occlude && x<SIZE.x/2 ? .15f : 1f);
            return fill;
        });
        try {
            Matrix4f inv=new Matrix4f(); float c=(float)Math.cos(angle),s=(float)Math.sin(angle);
            inv.m[0]=.65f*c; inv.m[1]=.65f*s; inv.m[6]=.65f;
            inv.m[8]=1.5f*s; inv.m[9]=-1.5f*c;
            inv.m[12]=inv.m[13]=inv.m[14]=.5f; inv.m[15]=1;
            Render out = window.env().render();
            Pipe pipe = new BufPipe().prep(new FragColor<>(output.image(0)))
                .prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            while(true) {
                try {
                    NPostFX.blit(new GOut(out,pipe,SIZE),Temporal.one(),new NPostFX.Pass(VolumeFire.shader,
                        cache.frames[frame],cache.frames[(frame+1)%cache.frames.length],depth.sampler(),inv,
                        new float[]{0,0,0},new float[]{1,1,1},new float[]{.35f,steps,1.0f},pigment,tint));
                    break;
                } catch(Loading loading) {loading.waitfor();}
            }
            CompletableFuture<float[]> future = new CompletableFuture<>();
            out.pget(output.image(0),rgba,bytes -> {
                float[] data = new float[SIZE.x*SIZE.y*4];
                bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(data); future.complete(data);
            });
            window.swapbuffers(out,false); window.env().submit(out);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!future.isDone() && System.nanoTime()<deadline) {
                Render pump=window.env().render(); window.swapbuffers(pump,false); window.env().submit(pump); Thread.sleep(10);
            }
            float[] result=future.get(1,TimeUnit.SECONDS);
            if(file!=null) {
                java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(SIZE.x,SIZE.y,java.awt.image.BufferedImage.TYPE_INT_RGB);
                for(int y=0;y<SIZE.y;y++) for(int x=0;x<SIZE.x;x++) {
                    int i=((SIZE.y-1-y)*SIZE.x+x)*4;
                    int r=Math.round(Utils.clip(result[i],0,1)*255),g=Math.round(Utils.clip(result[i+1],0,1)*255),b=Math.round(Utils.clip(result[i+2],0,1)*255);
                    image.setRGB(x,y,(r<<16)|(g<<8)|b);
                }
                javax.imageio.ImageIO.write(image,"png",new java.io.File(file));
            }
            return result;
        } finally { output.dispose(); depth.dispose(); }
    }
    static void particles() {
        PosLight light = new PosLight(java.awt.Color.ORANGE,new Coord3f(4,5,6));
        Embers emitter = new Embers(null,light);
        emitter.rnd.setSeed(1234);
        for(Embers.Profile profile : new Embers.Profile[]{Embers.TORCH,Embers.CAMPFIRE,Embers.DEFAULT}) {
            emitter.profile=profile;
            float maxHeight=0, maxRadius=0;
            for(int i=0;i<1000;i++) {
                Embers.Ember ember=emitter.new Ember();
                while(!ember.tick(1f/60f,new Coord3f(1.2f,0,0))) {
                    maxHeight=Math.max(maxHeight,ember.z);
                    maxRadius=Math.max(maxRadius,(float)Math.hypot(ember.x,ember.y));
                }
            }
            require(maxHeight<(profile==Embers.TORCH ? 2.5f : 5.5f),"Sparks climb above the local plume: "+maxHeight);
            require(maxRadius<3.0f,"Sparks detach sideways: "+maxRadius);
            System.out.printf("Embers rate %.1f: max height %.2f, radius %.2f%n",profile.rate,maxHeight,maxRadius);
        }
        FireFX.fire=true;
        int most=0;
        for(int i=0;i<40;i++) { emitter.autotick(.25); most=Math.max(most,emitter.embers.size()); }
        require(most>0 && most<16,"Unbounded or absent emission");
        FireFX.fire=false; emitter.autotick(.01);
        require(emitter.embers.isEmpty(),"Disabling fire leaves sparks alive");
        require(Embers.profile("gfx/terobjs/torchstand")==Embers.TORCH &&
                Embers.profile("gfx/terobjs/fireplace")==Embers.CAMPFIRE,"Wrong source profile");
    }
    public static void main(String[] args) throws Exception {
        particles();
        double minSpeed=100,maxSpeed=0;
        for(int i=0;i<20000;i++) {
            double time=i*.005;
            double speed=(VolumeFire.animationTime(time+.001,12.3)-VolumeFire.animationTime(time,12.3))/.001;
            minSpeed=Math.min(minSpeed,speed);maxSpeed=Math.max(maxSpeed,speed);
        }
        require(minSpeed>18 && maxSpeed<30 && maxSpeed-minSpeed>5,"Fire tempo freezes, reverses or stays uniform");
        require(VolumeFire.fireResource("gfx/terobjs/pow") && !VolumeFire.fireResource("gfx/terobjs/boostspeed"),"Non-fire effect replaced");
        require(Embers.profile("gfx/terobjs/pow")==Embers.CAMPFIRE && Embers.profile("gfx/terobjs/candelabrum")==Embers.TORCH,"Actual game resource profiles");
        require(VolumeFire.screenBounds(Matrix4f.id,new float[]{3,3,0},new float[]{1,1,1},SIZE)==null,"Offscreen flame incorrectly visible");
        Toolkit toolkit=Toolkit.toolkits().get("vulkan").open(); Windeye window=toolkit.window(); int exit=0;
        try {
            window.title("Fire emission regression"); window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            VolumeFire.Cache cache=new VolumeFire.Cache();
            float[] front=capture(window,cache,0,0,false,48,"build/fire-volume-gpu-front.png");
            float[] side=capture(window,cache,0,1.57f,false,48,"build/fire-volume-gpu-side.png");
            float[] blocked=capture(window,cache,0,0,true,48,"build/fire-volume-gpu-depth.png");
            float[] low=capture(window,cache,0,0,false,24,null);
            float[] later=capture(window,cache,24,0,false,48,"build/fire-volume-gpu-later.png");
            float[] blue=capture(window,cache,0,0,false,64,"build/fire-volume-gpu-blue.png",new float[]{.1f,.6f,1f});
            float[] green=capture(window,cache,0,0,false,64,"build/fire-volume-gpu-green.png",new float[]{.1f,1f,.15f});
            double blueR=0,blueB=0,greenR=0,greenG=0;
            for(int i=0;i<blue.length;i+=4) {blueR+=blue[i];blueB+=blue[i+2];greenR+=green[i];greenG+=green[i+1];}
            require(blueB>blueR*2 && greenG>greenR*2,"Lost blue/green source hue");
            if(args.length>0 && args[0].equals("--cached-resources")) {
                Resource.setcache(ResCache.global);
                Resource resource=Resource.remote().loadwait("gfx/terobjs/pow");
                RenderTree tree=new RenderTree();
                VolumeFire.Sources sources=new VolumeFire.Sources();
                sources.syncadd(tree,Rendered.class);
                int checked=0;
                for(FastMesh.MeshRes mesh:resource.layers(FastMesh.MeshRes.class)) {
                    Pipe material=new BufPipe();
                    while(true) {try {mesh.mat.get().apply(material);break;} catch(Loading loading){loading.waitfor();}}
                    if(material.get(haven.resutil.TexAnim.slot)==null) continue;
                    RenderTree.Slot slot=tree.add(mesh.m,mesh.mat.get());
                    require(sources.slots.contains(slot),"Flame missed during cold scene registration: "+mesh.id);
                    require(VolumeFire.candidate(mesh.m,slot.state()),"Actual flame material not detected: "+mesh.id);
                    slot.remove();
                    require(sources.slots.isEmpty(),"Removed flame retained in scene");
                    float[] pixels=capture(window,cache,0,0,false,64,"build/fire-real-material-"+mesh.id+".png",new float[]{1,1,1},VolumeFire.sourceTexture(material));
                    double red=0,grn=0,blu=0;
                    for(int i=0;i<pixels.length;i+=4){red+=pixels[i];grn+=pixels[i+1];blu+=pixels[i+2];}
                    if(mesh.id==2) require(red>blu*2,"Ordinary flame changed hue");
                    if(mesh.id==4) require(grn>red*2,"Green flame lost its hue");
                    if(mesh.id==5) require(blu>red*2,"Blue flame lost its hue");
                    checked++;
                }
                require(checked==3,"Did not check all three real flame materials");
                tree.remove(sources);tree.dispose();
                System.out.println("Cold scene registration and original campfire materials: PASS (orange, green, blue)");
                Resource candle=Resource.remote().loadwait("gfx/terobjs/candelabrum");
                FastMesh candleFlame=candle.flayer(FastMesh.MeshRes.class,1).m;
                float[][] cb=VolumeFire.bounds(candleFlame);
                float burner=cb[0][2]+cb[1][2]*(4f/96f);
                float wick=candle.flayer(FastMesh.MeshRes.class,0).m.pbounds().z;
                require(Math.abs(burner-wick)<.01f,"Candle flame floats above wick");
                for(FastMesh.MeshRes mesh:resource.layers(FastMesh.MeshRes.class)) if(mesh.id==2) {
                    float[][] fb=VolumeFire.bounds(mesh.m);
                    require(Math.abs(fb[0][2]+fb[1][2]*(4f/96f)-mesh.m.nbounds().z)<.01f,"Campfire burner lifted off fuel");
                }
                System.out.println("Candle wick and campfire fuel anchors: PASS");
            }
            double rotation=0,animation=0,error=0; int visible=0;
            for(int i=0;i<front.length;i+=4) {
                for(int k=0;k<4;k++) require(Float.isFinite(front[i+k]) && front[i+k]>=0 && front[i+k]<=1.001f,"Unbounded fire output");
                if(front[i+3]>.1f) visible++;
                if((i/4)%SIZE.x<SIZE.x/2) require(blocked[i+3]<.001f,"Volume leaks through foreground depth");
                rotation+=Math.abs(front[i]-side[i]); animation+=Math.abs(front[i]-later[i]); error+=Math.abs(front[i]-low[i]);
            }
            require(visible>500,"Volume is invisible");
            require(rotation>5 && animation>5,"Volume looks flat or animation frozen");
            require(error/(SIZE.x*SIZE.y)<.02,"Low-cost raymarch loses appearance");
            System.out.printf("Volume fire GPU: PASS (%d visible pixels; rotation %.1f, animation %.1f, low-cost error %.5f; depth clipping)%n",visible,rotation,animation,error/(SIZE.x*SIZE.y));
        } catch(Throwable failure) { failure.printStackTrace();exit=1; }
        finally {window.dispose();toolkit.dispose();}
        System.exit(exit);
    }
}
