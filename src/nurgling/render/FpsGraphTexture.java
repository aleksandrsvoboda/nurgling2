package nurgling.render;

import haven.*;
import haven.render.*;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/** One pending CPU raster per panel; a persistent texture updated on its owning UI thread. */
public final class FpsGraphTexture implements Disposable {
    static final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8), r -> {Thread t=new Thread(r,"fps-graph");t.setDaemon(true);return t;});
    private java.util.concurrent.Future<byte[]> pending;
    // Only accessed by this panel's single pending worker job. Pixel snapshots
    // remain independent because submitted GPU uploads may still consume them.
    private java.awt.image.BufferedImage rasterImage;
    private Coord pendingSize;
    private double nextUpdate;
    private boolean disposed;
    Texture2D texture;
    private Texture2D.Sampler2D sampler;
    private TexRaw image;

    public void update(Render out, FrameHistory history, double now, String backend, Coord size) {
        if(disposed) return;
        if(pending != null && pending.isDone()) {
            try {
                byte[] pixels=pending.get(); // Never waits for the worker.
                if(size.equals(pendingSize)) upload(out,size,pixels);
            } catch(InterruptedException e) {Thread.currentThread().interrupt();}
            catch(ExecutionException e) {Warning.warn("FPS graph raster failed: %s",e.getCause());}
            finally {pending=null;}
        }
        if(pending==null && now>=nextUpdate && worker.getQueue().remainingCapacity()>0) {
            FrameHistory.Snapshot snapshot=history.snapshot(now);
            Coord requested=new Coord(size);
            try {
                pending=worker.submit(() -> rasterFrame(snapshot,backend,requested));
                pendingSize=requested;
                nextUpdate=now+.1;
            } catch(RejectedExecutionException busy) { /* Retry without running CPU raster inline. */ }
        }
    }

    byte[] rasterFrame(FrameHistory.Snapshot snapshot,String backend,Coord size) {
        if(rasterImage==null||rasterImage.getWidth()!=size.x||rasterImage.getHeight()!=size.y)
            rasterImage=new java.awt.image.BufferedImage(size.x,size.y,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        FpsGraph.render(snapshot,backend,rasterImage);
        return pixels(rasterImage);
    }
    static byte[] raster(FrameHistory.Snapshot snapshot,String backend,Coord size) {
        return pixels(FpsGraph.render(snapshot,backend,size.x,size.y));
    }
    private static byte[] pixels(java.awt.image.BufferedImage image) {
        int[] argb=((DataBufferInt)image.getRaster().getDataBuffer()).getData();
        byte[] rgba=new byte[argb.length*4];
        for(int i=0,j=0;i<argb.length;i++) {
            int pixel=argb[i];
            rgba[j++]=(byte)(pixel>>16);rgba[j++]=(byte)(pixel>>8);
            rgba[j++]=(byte)pixel;rgba[j++]=(byte)(pixel>>24);
        }
        return rgba;
    }

    void upload(Render out,Coord size,byte[] pixels) {
        if(texture==null || !texture.sz().equals(size)) {
            releaseTexture();
            texture=new Texture2D(size,DataBuffer.Usage.STREAM,new VectorFormat(4,NumberFormat.UNORM8),null);
            texture.desc("FPS graph");
            sampler=new Texture2D.Sampler2D(texture);
            sampler.magfilter(Texture.Filter.NEAREST).minfilter(Texture.Filter.NEAREST);
            image=new TexRaw(sampler);
        }
        out.update(texture.image(0),(img,env)->{
            FillBuffer fill=env.fillbuf(img);
            fill.pull(ByteBuffer.wrap(pixels));
            return fill;
        });
    }

    public void draw(GOut g) {if(image!=null)g.image(image,Coord.z);}
    private void releaseTexture() {
        if(sampler!=null){sampler.dispose();sampler=null;}
        if(texture!=null){texture.dispose();texture=null;}
        image=null;
    }
    public void dispose() {
        disposed=true;
        if(pending!=null){pending.cancel(false);pending=null;}
        releaseTexture();
    }
}
