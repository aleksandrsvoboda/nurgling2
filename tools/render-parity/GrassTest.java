package nurgling.render;

import haven.*;
import haven.render.*;
import haven.iosys.tk.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

public class GrassTest {
    static final Coord SIZE=Coord.of(384,384);
    static final VectorFormat RGBA=new VectorFormat(4,NumberFormat.FLOAT32);
    static void require(boolean v,String reason){if(!v)throw new AssertionError(reason);}
    static final Grass.Terrain terrain=new Grass.Terrain(){
        public boolean grass(double x,double y){return x<33;}
        public float height(double x,double y){return (float)(x*.02+y*.01);}
    };
    static FloatBuffer attr(FastMesh mesh,haven.render.sl.Attribute name) {
        for(VertexBuf.AttribData data:mesh.vert.bufs)if(data.attr==name)return ((VertexBuf.FloatData)data).data;
        throw new AssertionError("Missing blade attribute");
    }
    static void geometry() {
        require(Grass.eligible("gfx/tiles/grass")&&!Grass.eligible("gfx/tiles/dirt")&&!Grass.eligible("gfx/tiles/ballbrick"),"Terrain filtering");
        FastMesh a=Grass.build(Coord.z,terrain),b=Grass.build(Coord.z,terrain);
        require(a!=null&&a.vert.num<65536,"Empty or oversized patch");
        FloatBuffer roots=attr(a,Grass.root),other=attr(b,Grass.root),shapes=attr(a,Grass.shape);
        require(roots.equals(other),"Grass changes layout on rebuild");
        for(int i=0;i<roots.capacity();i+=4) {
            float x=roots.get(i),y=-roots.get(i+1),z=roots.get(i+2),h=roots.get(i+3);
            require(x<33&&h>0&&h<=Grass.MAX_HEIGHT,"Grass crosses paving or knee-height cap");
            require(Math.abs(z-terrain.height(x,y)-.015)<.0001,"Floating root on sloped terrain");
            require(shapes.get(i)>=0&&shapes.get(i)<=1,"Invalid height weights");
        }
        require(Grass.build(Coord.of(1,0),terrain)==null,"Geometry on non-grass terrain");
        int quiet=0,dense=0;for(int y=-100;y<100;y++)for(int x=-100;x<100;x++) {
            double d=Grass.density(x,y);if(d<.08)quiet++;if(d>.5)dense++;
        }
        require(quiet>2000&&dense>1000,"No clustered coverage with gaps");
        Grass.Trail slow=new Grass.Trail(),fast=new Grass.Trail();
        double now=WaterSurface.epoch+10;
        for(int i=0;i<=100;i++)slow.sample(Coord3f.of(i*.06f,0,0),now+i*.01);
        for(int i=0;i<=10;i++)fast.sample(Coord3f.of(i*.6f,0,0),now+i*.1);
        require(slow.points.size()==8&&fast.points.size()==8,"Trail depends on frame count");
        Iterator<float[]> highFps=slow.points.iterator(),lowFps=fast.points.iterator();
        while(highFps.hasNext()) {
            float[] high=highFps.next(),low=lowFps.next();
            for(int component=0;component<4;component++)require(Math.abs(high[component]-low[component])<.0001,"Contact position/time changes with FPS");
        }
        slow.sample(Coord3f.of(6,0,0),now+3);require(slow.points.isEmpty(),"Grass does not recover after stopping");
        fast.sample(Coord3f.of(100,0,0),now+1.1);require(fast.points.isEmpty(),"Teleport leaves a cross-map bend");
        require(NGfx.classic.with("animatedgrass",true).grass&&!NGfx.effective(NGfx.classic.with("animatedgrass",true),false).grass,"Preference/backend isolation");
        System.out.printf("Grass geometry PASS: %d blades, deterministic clusters, grass mask, height cap, rooted slope, distance trail and recovery%n",a.vert.num/8);
        a.dispose();b.dispose();
    }
    static float[] capture(Windeye window,double time,boolean contact,boolean blocked) throws Exception {
        PView view=new PView(SIZE){protected void basic(){}};
        Texture2D scene=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null),output=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
        Texture2D depth=new Texture2D(SIZE,DataBuffer.Usage.STATIC,Texture.DEPTH,null);view.depth=depth;
        Grass grass=new Grass(view);FastMesh mesh=Grass.build(Coord.z,terrain);
        grass.patches.put(Coord.z,new Grass.Patch(Coord.z,new MapMesh[0],mesh));grass.eye=Coord3f.of(22,-22,0);
        FloatBuffer roots=attr(mesh,Grass.root);int contactIndex=(mesh.vert.num/16)*4;
        float cx=roots.get(contactIndex),cy=-roots.get(contactIndex+1),cz=roots.get(contactIndex+2);
        if(contact)for(int i=0;i<=8;i++)grass.trail.sample(Coord3f.of(cx-3.2f+i*.8f,cy,cz),WaterSurface.epoch+10-.3+i*.035);
        Pipe base=new BufPipe().prep(Homo3D.state).prep(Projection.ortho(-29,29,-29,29,1,250))
            .prep(Camera.pointed(Coord3f.of(22,-22,0),100,.80f,0))
            .prep(new FrameInfo(WaterSurface.epoch+time))
            .prep(new Atmos.Env(0,false,false,-1,new float[]{0,0,1},new float[]{.7f,.7f,.6f},new float[]{.65f,.75f,.8f},null));
        view.basic.ostate(p->{for(State state:base.states())if(state!=null)state.apply(p);});
        Pipe input=new BufPipe().prep(new FragColor<>(scene.image(0))).prep(new DepthBuffer<>(depth.image(0))).prep(new States.Viewport(Area.sized(SIZE)));
        Pipe target=new BufPipe().prep(new FragColor<>(output.image(0))).prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true) {
                Render out=window.env().render();out.clear(input,FragColor.fragcol,new FColor(.07f,.10f,.025f,1));out.clear(input,blocked?0:1);
                grass.run(new GOut(out,target,SIZE),scene.sampler());
                CompletableFuture<float[]> read=new CompletableFuture<>();
                out.pget(output.image(0),RGBA,bytes->{float[] p=new float[SIZE.x*SIZE.y*4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(p);read.complete(p);});
                window.swapbuffers(out,false);window.env().submit(out);
                while(!read.isDone()&&System.nanoTime()<deadline){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);}
                float[] pixels=read.get(1,TimeUnit.SECONDS);
                require(System.nanoTime()<deadline,"Grass pipeline failed to compile");
                if(((haven.render.vk.VkRender)out).pendingDraws()==0)return pixels;
            }
        }finally{grass.dispose();view.dispose();scene.dispose();output.dispose();depth.dispose();}
    }
    static double difference(float[] a,float[] b){double d=0;for(int i=0;i<a.length;i++)d+=Math.abs(a[i]-b[i]);return d/a.length;}
    static void save(float[] p,String name)throws Exception {
        BufferedImage image=new BufferedImage(SIZE.x,SIZE.y,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<SIZE.y;y++)for(int x=0;x<SIZE.x;x++) {
            int at=(y*SIZE.x+x)*4,rgb=0;for(int c=0;c<3;c++)rgb=rgb<<8|Math.round(Math.max(0,Math.min(1,p[at+c]))*255);
            image.setRGB(x,SIZE.y-y-1,rgb);
        }
        new File("build/grass-preview").mkdirs();ImageIO.write(image,"png",new File("build/grass-preview/"+name+".png"));
    }
    public static void main(String[] args)throws Exception {
        geometry();Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();int status=0;
        try {
            window.title("Grass Vulkan regression");window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            float[] still=capture(window,10,false,false),wind=capture(window,10.8,false,false),bend=capture(window,10,true,false);
            float[] recovered=capture(window,12,true,false),noTrail=capture(window,12,false,false),blocked=capture(window,10,true,true);
            save(still,"grass");save(bend,"bent");
            System.out.printf("Grass differences: wind %.8f, contact %.8f%n",difference(still,wind),difference(still,bend));
            require(difference(still,wind)>.0001,"Wind not animated");
            require(difference(still,bend)>.00001,"Player does not bend grass");
            require(difference(recovered,noTrail)<.000001,"Grass fails to recover");
            for(int i=0;i<blocked.length;i+=4)require(Math.abs(blocked[i]-.07)<.00001&&Math.abs(blocked[i+1]-.10)<.00001&&Math.abs(blocked[i+3]-1)<.00001,"Grass renders through foreground");
            save(still,"grass");save(bend,"bent");
            if(Arrays.asList(args).contains("--preview"))for(int i=0;i<32;i++)save(capture(window,10+i/16.0,true,false),String.format("frame-%02d",i));
            System.out.printf("Grass Vulkan PASS: wind %.6f, bending %.6f, recovery, depth occlusion%n",difference(still,wind),difference(still,bend));
        }catch(Throwable failure){failure.printStackTrace();status=1;}finally{window.dispose();toolkit.dispose();}
        System.exit(status);
    }
}
