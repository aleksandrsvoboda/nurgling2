package nurgling.render;

import haven.*;
import haven.render.*;
import haven.iosys.tk.*;
import java.util.*;
import java.util.concurrent.*;

public class FpsGraphTextureTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void submit(Windeye window,Render out)throws Exception {
        CompletableFuture<Void> done=new CompletableFuture<>();
        window.swapbuffers(out,false);out.fence(()->done.complete(null));window.env().submit(out);
        done.get(10,TimeUnit.SECONDS);
    }
    static byte[] read(Windeye window,Render out,Texture2D texture)throws Exception {
        CompletableFuture<byte[]> result=new CompletableFuture<>();
        out.pget(texture.image(0),new VectorFormat(4,NumberFormat.UNORM8),buf->{byte[] data=new byte[buf.remaining()];buf.get(data);result.complete(data);});
        submit(window,out);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!result.isDone()&&System.nanoTime()<end){submit(window,window.env().render());Thread.sleep(1);}
        return result.get(1,TimeUnit.SECONDS);
    }
    public static void main(String[] args)throws Exception {
        int exit=0;
        try {
            FrameHistory history=new FrameHistory();history.reset(0);for(int i=1;i<=600;i++)history.record(i/60.0);
            Coord size=Coord.of(FpsGraph.W,FpsGraph.H);
            byte[] rgba=FpsGraphTexture.raster(history.snapshot(10),"Vulkan",size);
            byte[] reference=TexI.convert(FpsGraph.render(history.snapshot(10),"Vulkan",size.x,size.y),size);
            for(int i=0;i<rgba.length;i++)check(Math.abs((rgba[i]&255)-(reference[i]&255))<=1,"Graph raster changed at "+i);
            FpsGraphTexture raster=new FpsGraphTexture();
            byte[] firstRaster=raster.rasterFrame(history.snapshot(10),"Vulkan",size),saved=firstRaster.clone();
            history.record(10.12);
            byte[] nextRaster=raster.rasterFrame(history.snapshot(10.12),"Vulkan",size);
            check(Arrays.equals(firstRaster,saved),"Worker overwrites a pending GPU upload");
            check(Arrays.equals(nextRaster,FpsGraphTexture.raster(history.snapshot(10.12),"Vulkan",size)),"Reused graph retains old lines or accumulates alpha");
            Coord resizedRaster=Coord.of(200,170);
            check(Arrays.equals(raster.rasterFrame(history.snapshot(10.12),"OpenGL",resizedRaster),
                    FpsGraphTexture.raster(history.snapshot(10.12),"OpenGL",resizedRaster)),"CPU raster resize corrupts graph");
            raster.dispose();
            history.reset(0);for(int i=1;i<=600;i++)history.record(i/60.0);
            for(String backend:new String[]{"vulkan","jogl"}) {
                Toolkit toolkit=Toolkit.toolkits().get(backend).open();Windeye window=toolkit.window();
                FpsGraphTexture graph=new FpsGraphTexture();CountDownLatch gate=new CountDownLatch(1);
                try {
                    window.title("FPS graph streaming: "+backend);window.sizing(new Windeye.Sizing().fixsize(Coord.of(64,64))).show(true);
                    FpsGraphTexture.worker.submit(()->{try{gate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                    Render first=window.env().render();
                    long start=System.nanoTime();
                    for(int i=0;i<100;i++)graph.update(first,history,10,backend,size);
                    check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(200),"UI waited for raster worker");
                    check(FpsGraphTexture.worker.getQueue().size()==1,"More than one pending raster for panel");
                    check(graph.texture==null,"Raster executed inline");submit(window,first);gate.countDown();
                    FpsGraphTexture.worker.submit(()->{}).get(10,TimeUnit.SECONDS);
                    Render ready=window.env().render();graph.update(ready,history,10,backend,size);
                    check(Arrays.equals(FpsGraphTexture.raster(history.snapshot(10),backend,size),read(window,ready,graph.texture)),"Graph upload pixels differ");
                    Texture2D texture=graph.texture;Object nativeTexture=texture.ro;
                    for(int i=0;i<6;i++) {
                        byte[] changed=rgba.clone();changed[0]=(byte)(17+i);
                        Render out=window.env().render();graph.upload(out,size,changed);
                        check(graph.texture==texture&&texture.ro==nativeTexture,"Texture recreated during refresh");
                        check(Arrays.equals(changed,read(window,out,texture)),"Streaming refresh was stale/corrupt");
                    }
                    Render resized=window.env().render();Coord small=Coord.of(32,24);byte[] pixels=new byte[32*24*4];Arrays.fill(pixels,(byte)93);
                    graph.upload(resized,small,pixels);check(graph.texture!=texture,"Resize kept wrong allocation");
                    check(Arrays.equals(pixels,read(window,resized,graph.texture)),"Resize upload failed");
                    graph.dispose();check(graph.texture==null,"Disposed panel retains texture");
                    System.out.println(backend+" PASS: blocked raster never blocks UI; bounded pending work; exact GPU pixels, native texture reuse, resize/disposal");
                } finally {gate.countDown();graph.dispose();window.dispose();toolkit.dispose();}
            }
        } catch(Throwable e){e.printStackTrace();exit=1;}
        System.exit(exit);
    }
}
