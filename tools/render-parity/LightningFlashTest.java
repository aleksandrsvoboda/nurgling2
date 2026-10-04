package nurgling.render;

import haven.*;
import haven.render.*;
import haven.iosys.tk.*;
import java.nio.ByteOrder;
import java.util.concurrent.*;

/** Real GPU regression for the scene illumination that accompanies a discharge. */
public class LightningFlashTest {
    static final Coord SIZE=Coord.of(320,320);
    static final VectorFormat RGBA=new VectorFormat(4,NumberFormat.FLOAT32);
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static float[] capture(Windeye window,float age,boolean empty,boolean active)throws Exception {
        PView view=new PView(SIZE){protected void basic(){}};
        Lightning lightning=new Lightning(view);
        Texture2D input=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
        Texture2D output=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
        Texture2D depth=new Texture2D(SIZE,DataBuffer.Usage.STATIC,Texture.DEPTH,null);
        try {
            if(active)lightning.bolt=new Lightning.Bolt(Coord3f.o,100,10,27);
            Pipe src=new BufPipe().prep(new FragColor<>(input.image(0)))
                .prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            Pipe dst=new BufPipe().prep(new FragColor<>(output.image(0)))
                .prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true) {
                Render out=window.env().render();
                out.clear(src,FragColor.fragcol,new FColor(.04f,.07f,.11f,1));
                GOut pattern=new GOut(out,src,SIZE);
                pattern.chcolor(75,95,120,255);pattern.frect(Coord.of(160,0),Coord.of(160,320));
                out.clear(new BufPipe().prep(new States.Viewport(Area.sized(SIZE)))
                    .prep(new DepthBuffer<>(Utils.el(depth.images()))),empty?1:.5);
                GOut g=new GOut(out,dst,SIZE);
                g.image(new TexRaw(input.sampler(),true),Coord.z,SIZE);
                lightning.drawFlash(g,input.sampler(),depth.sampler(),10+age);
                CompletableFuture<float[]> result=new CompletableFuture<>();
                out.pget(output.image(0),RGBA,bytes->{
                    float[] pixels=new float[SIZE.x*SIZE.y*4];
                    bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(pixels);result.complete(pixels);
                });
                window.swapbuffers(out,false);window.env().submit(out);
                while(!result.isDone()&&System.nanoTime()<deadline) {
                    Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);
                }
                float[] pixels=result.get(1,TimeUnit.SECONDS);
                require(System.nanoTime()<deadline,"Flash pipeline warmup timed out");
                if(((haven.render.vk.VkRender)out).pendingDraws()==0)return pixels;
            }
        } finally {lightning.dispose();view.dispose();input.dispose();output.dispose();depth.dispose();}
    }
    static float brightness(float[] pixels,int x) {
        int i=(SIZE.y/2*SIZE.x+x)*4;
        return (pixels[i]+pixels[i+1]+pixels[i+2])/3;
    }
    static void same(float[] a,float[] b,String message) {
        for(int i=0;i<a.length;i++)require(Math.abs(a[i]-b[i])<.0001,message);
    }
    public static void main(String[] args)throws Exception {
        Toolkit tk=Toolkit.toolkits().get("vulkan").open();Windeye window=tk.window();int exit=0;
        try {
            window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            float[] timing=new Lightning.Bolt(Coord3f.o,100,10,27).timing;
            float[] baseline=capture(window,0,false,false);
            float[] peak=capture(window,.07f*timing[0],false,true);
            float[] dim=capture(window,.14f*timing[0],false,true);
            float[] restrike=capture(window,timing[1]*timing[0],false,true);
            same(baseline,capture(window,.95f,false,true),"Flash survives discharge");
            same(baseline,capture(window,-.1f,false,true),"Flash before discharge");
            same(baseline,capture(window,.07f*timing[0],true,true),"Flash lights empty terrain/void");
            require(brightness(peak,80)>brightness(baseline,80)+.25,"World flash too dim");
            require(brightness(peak,80)>brightness(dim,80)+.2,"Flash does not fade between strokes");
            require(brightness(restrike,80)>brightness(dim,80)+.15,"Return stroke missing illumination");
            require(brightness(peak,240)>brightness(peak,80)+.15,"World detail washed out");
            for(int i=0;i<peak.length;i+=4) {
                for(int c=0;c<4;c++)require(Float.isFinite(peak[i+c])&&peak[i+c]>=0&&peak[i+c]<=1.001,"Invalid flash pixel");
                require(Math.abs(peak[i+3]-1)<.0001,"Flash changed scene alpha");
            }
            LightningTest.save(baseline,"flash-before");LightningTest.save(peak,"flash-peak");LightningTest.save(restrike,"flash-return");
            System.out.printf("PASS: Vulkan world flash %.3f -> %.3f, synchronized return %.3f; fade, expiry, empty space, detail and alpha%n",
                brightness(baseline,80),brightness(peak,80),brightness(restrike,80));
        } catch(Throwable e){e.printStackTrace();exit=1;}finally{window.dispose();tk.dispose();}
        System.exit(exit);
    }
}
