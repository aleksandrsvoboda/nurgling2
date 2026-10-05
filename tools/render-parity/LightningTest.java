package nurgling.render;

import haven.*;
import haven.render.*;
import haven.iosys.tk.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteOrder;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class LightningTest {
    static final Coord SIZE=Coord.of(320,320);
    static final VectorFormat RGBA=new VectorFormat(4,NumberFormat.FLOAT32);
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void geometry() {
        for(boolean perspective:new boolean[]{false,true})for(float zoom:new float[]{90,220,500})
            for(float pitch:new float[]{.20f,.55f,1.25f,(float)Math.PI/2})for(float angle:new float[]{0,.8f,2.1f}) {
                Matrix4f camera=Camera.pointed(Coord3f.o,zoom*3,pitch,angle).fin(Matrix4f.id);
                Matrix4f projection=(perspective?Projection.frustum(-.6f,.6f,-.5f,.5f,1,5000):
                    Projection.ortho(-zoom,zoom,-zoom*.75f,zoom*.75f,1,5000)).fin(Matrix4f.id);
                Coord3f impact=Coord3f.of(12,-18,0);
                Coord3f top=Lightning.cloudTop(impact,camera,projection);
                require(top!=null&&top.z>impact.z,"Cloud endpoint below ground/missing");
                float[] screen=projection.mul(camera).mul4(top.to4a(1));
                require(screen[3]>.1f&&screen[1]/screen[3]>1.20f,"Lightning starts inside the screen");
                require(Math.abs(screen[0]/screen[3])<.95f&&Math.abs(screen[2])<screen[3],"Cloud endpoint misses the upper edge or near plane");
                Lightning.Bolt fitted=new Lightning.Bolt(impact,top,10,27);
                require(fitted.paths.get(0).points[0].equals(top)&&fitted.paths.get(0).points[128].equals(impact),"Fitted strike lost its endpoints");
            }
        System.out.println("Lightning cloud endpoint PASS: 2 projections, 3 zooms, 4 elevations and 3 azimuths");
        for(int seed=0;seed<100;seed++) {
            Lightning.Bolt bolt=new Lightning.Bolt(Coord3f.o,100,10,seed);
            require(bolt.paths.size()>=12&&bolt.paths.size()<=16,"Missing branching channels");
            int segments=0;for(Lightning.Path path:bolt.paths)segments+=path.points.length-1;
            require(segments>=416&&segments<=528,"Unbounded branching geometry");
            Lightning.Path trunk=bolt.paths.get(0);
            require(trunk.points[trunk.points.length-1].equals(Coord3f.o),"Strike does not meet its impact");
            for(Lightning.Path path:bolt.paths)for(Coord3f p:path.points)
                require(Float.isFinite(p.x)&&Float.isFinite(p.y)&&Float.isFinite(p.z)&&p.z>=0,"Invalid branching geometry");
            for(float angle:new float[]{0,.8f,1.6f,3.2f}) {
                Matrix4f camera=Camera.pointed(Coord3f.of(0,0,40),200,.55f,angle).fin(Matrix4f.id);
                float[] vertices=Lightning.vertices(bolt,camera);
                require(vertices.length==(segments*6+6)*7,"Incorrect ribbon/corona layout");
                for(float v:vertices)require(Float.isFinite(v),"Invalid camera-facing ribbon");
                require(Arrays.equals(vertices,Lightning.vertices(bolt,camera)),"Stationary bolt topology jitters every frame");
            }
        }
        DirLight light=new DirLight(new FColor(1,1,1),Coord3f.zu),dark=new DirLight(new FColor(0,0,0),Coord3f.zu);
        require(!Lightning.canStrike(0,light)&&!Lightning.canStrike(3,dark)&&!Lightning.canStrike(3,null),"Lightning in dry weather/underground");
        require(!Lightning.canStrike(.2f,light)&&!Lightning.canStrike(1,light)&&!Lightning.canStrike(1.79f,light),"Lightning outside heavy rain");
        require(Lightning.canStrike(1.8f,light)&&Lightning.canStrike(3,light),"Heavy rain cannot produce lightning");
        Lightning.Bolt first=new Lightning.Bolt(Coord3f.o,100,10,3),other=new Lightning.Bolt(Coord3f.o,100,10,4);
        require(!Arrays.equals(first.paths.get(0).points,other.paths.get(0).points)&&!Arrays.equals(first.timing,other.timing),"Strikes repeat their shape/timing");
        NGfx.Settings on=NGfx.classic.with("enabled",true).with("lightningbolts",true);
        require(on.lightning&&Boolean.TRUE.equals(on.map().get("lightningbolts")),"Lightning preference not saved");
        require(!NGfx.effective(on,false).lightning&&!NGfx.classic.lightning,"Lightning enabled in classic/GL");
        Random random=new Random(7);
        for(int i=0;i<100;i++){double gap=Lightning.interval(3,true,random);require(gap>=2&&gap<3,"Debug storm interval");}
        System.out.println("Lightning geometry/lifecycle gates PASS: 100 seeds, 4 views, 416-528 segments, heavy rain only, independent opt-in");
    }
    static float[] capture(Windeye window,float age,boolean blocked,float azimuth) throws Exception {
        PView view=new PView(SIZE){protected void basic(){}};
        Lightning lightning=new Lightning(view);
        Texture2D target=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
        Texture2D depth=new Texture2D(SIZE,DataBuffer.Usage.STATIC,Texture.DEPTH,null);
        try {
            Projection projection=Projection.ortho(-65,65,-65,65,1,600);
            Camera camera=Camera.pointed(Coord3f.of(0,0,48),220,.42f,azimuth);
            Pipe scene=new BufPipe().prep(projection).prep(camera);
            Matrix4f m=projection.fin(Matrix4f.id);
            float[] params={m.m[10],m.m[14],1,0};
            Coord3f impact=Coord3f.o;
            lightning.bolt=new Lightning.Bolt(impact,Lightning.cloudTop(impact,camera.fin(Matrix4f.id),m),10,27);
            Pipe output=new BufPipe().prep(new FragColor<>(target.image(0))).prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true) {
                Render out=window.env().render();
                out.clear(output,FragColor.fragcol,new FColor(.015f,.023f,.04f,1));
                out.clear(new BufPipe().prep(new States.Viewport(Area.sized(SIZE))).prep(new DepthBuffer<>(Utils.el(depth.images()))),blocked?0:1);
                lightning.draw(new GOut(out,output,SIZE),scene,depth.sampler(),params,10+age);
                CompletableFuture<float[]> result=new CompletableFuture<>();
                out.pget(target.image(0),RGBA,bytes->{float[] pixels=new float[SIZE.x*SIZE.y*4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(pixels);result.complete(pixels);});
                window.swapbuffers(out,false);window.env().submit(out);
                while(!result.isDone()&&System.nanoTime()<deadline){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);}
                float[] pixels=result.get(1,TimeUnit.SECONDS);
                require(System.nanoTime()<deadline,"Lightning pipeline failed to become ready");
                if(((haven.render.vk.VkRender)out).pendingDraws()==0)return pixels;
            }
        }finally{lightning.dispose();view.dispose();target.dispose();depth.dispose();}
    }
    static double energy(float[] pixels) {
        double energy=0;
        for(int i=0;i<pixels.length;i+=4) {
            for(int c=0;c<4;c++)require(Float.isFinite(pixels[i+c]),"Nonfinite lightning pixel");
            require(Math.abs(pixels[i+3]-1)<.00001,"Lightning changed scene alpha");
            energy+=Math.max(0,pixels[i]-.015)+Math.max(0,pixels[i+1]-.023)+Math.max(0,pixels[i+2]-.04);
        }
        return energy;
    }
    static void save(float[] pixels,String name)throws Exception {
        BufferedImage image=new BufferedImage(SIZE.x,SIZE.y,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<SIZE.y;y++)for(int x=0;x<SIZE.x;x++) {
            int at=(y*SIZE.x+x)*4,rgb=0;
            for(int c=0;c<3;c++)rgb=rgb<<8|Math.round(Math.min(1,Math.max(0,pixels[at+c]))*255);
            image.setRGB(x,SIZE.y-1-y,rgb);
        }
        new File("build/lightning-preview").mkdirs();ImageIO.write(image,"png",new File("build/lightning-preview/"+name+".png"));
    }
    public static void main(String[] args)throws Exception {
        geometry();Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();int exit=0;
        try {
            window.title("Lightning rendering regression");window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            float[] timing=new Lightning.Bolt(Coord3f.o,105,10,27).timing;
            float peakAge=.07f*timing[0],dimAge=.14f*timing[0],returnAge=timing[1]*timing[0];
            float[] peak=capture(window,peakAge,false,.7f),dim=capture(window,dimAge,false,.7f),restrike=capture(window,returnAge,false,.7f);
            float[] blocked=capture(window,peakAge,true,.7f),expired=capture(window,.95f,false,.7f),side=capture(window,peakAge,false,2.1f);
            require(energy(peak)>100&&energy(side)>100,"Bolt invisible at some camera angles");
            require(energy(peak)>energy(dim)*2&&energy(restrike)>energy(dim)*2,"Return strokes not animated");
            require(energy(blocked)<.1&&energy(expired)<.1,"Bolt shines through occluders or survives its lifetime");
            int lit=0;for(int i=0;i<peak.length;i+=4)if(peak[i+2]>.10)lit++;
            require(lit>100&&lit<SIZE.x*SIZE.y*.12,"Lightning is missing or a fullscreen flash");
            int upperLit=0;
            for(int y=SIZE.y-3;y<SIZE.y;y++)for(int x=0;x<SIZE.x;x++)if(peak[(y*SIZE.x+x)*4+2]>.10)upperLit++;
            require(upperLit>0,"Visible channel does not enter from the upper screen edge");
            save(peak,"strike");save(restrike,"return-stroke");save(side,"other-angle");
            if(Arrays.asList(args).contains("--preview"))for(int i=0;i<30;i++)save(capture(window,i/30f,false,.7f),String.format("frame-%02d",i));
            System.out.printf("Lightning Vulkan PASS: peak %.1f, dim %.1f, return %.1f; coverage %.2f%%, depth occlusion, expiry, alpha%n",energy(peak),energy(dim),energy(restrike),lit*100.0/(SIZE.x*SIZE.y));
        }catch(Throwable failure){failure.printStackTrace();exit=1;}finally{window.dispose();toolkit.dispose();}
        System.exit(exit);
    }
}
