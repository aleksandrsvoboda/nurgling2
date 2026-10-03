package nurgling.render;

import haven.*;
import haven.render.*;
import haven.iosys.tk.Toolkit;
import haven.iosys.tk.Windeye;
import java.util.concurrent.*;

/** GPU checks for motion weighting, jitter-only motion and newly visible surfaces. */
public class TemporalAATest {
    private static final Coord SIZE=Coord.of(64,64);
    private static final VectorFormat RGBA=new VectorFormat(4,NumberFormat.UNORM8);
    private static final VectorFormat DEPTH=new VectorFormat(1,NumberFormat.FLOAT32);
    private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }

    private static Texture2D colors(boolean checker) {
        return new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,(image,env)->{
            if(image.level!=0) return null;
            FillBuffer fill=env.fillbuf(image);
            java.nio.ByteBuffer data=fill.push();
            for(int y=0;y<SIZE.y;y++) for(int x=0;x<SIZE.x;x++) {
                byte c=(byte)(checker ? ((x&1)==0 ? 153 : 102) : 128);
                data.put(c).put(c).put(c).put((byte)255);
            }
            return fill;
        });
    }
    private static Texture2D depths(float value) {
        return new Texture2D(SIZE,DataBuffer.Usage.STATIC,DEPTH,(image,env)->{
            if(image.level!=0) return null;
            FillBuffer fill=env.fillbuf(image);
            java.nio.ByteBuffer data=fill.push();
            for(int i=0;i<SIZE.x*SIZE.y;i++) data.putFloat(value);
            return fill;
        });
    }

    private static int capture(Windeye window,float motion,float jitter,float oldDepth,boolean reset) throws Exception {
        Texture2D current=colors(true), history=colors(false), depth=depths(.5f), old=depths(oldDepth);
        Texture2D savedDepth=new Texture2D(SIZE,DataBuffer.Usage.STATIC,DEPTH,null);
        Texture2D output=new Texture2D(SIZE,DataBuffer.Usage.STATIC,RGBA,null);
        try {
            Matrix4f rep=Transform.makexlate(new Matrix4f(),new Coord3f(motion*2/SIZE.x,0,0));
            if(reset) rep.m[15]=-1;
            Render out=window.env().render();
            Pipe pipe=new BufPipe().prep(new FragColor<>(output.image(0)))
                .prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            GOut g=new GOut(out,pipe,SIZE);
            NPostFX.blit(NPostFX.target(g,savedDepth.sampler()),current.sampler(),
                new NPostFX.Pass(Temporal.ta_depth_sh,old.sampler()));
            NPostFX.blit(g,current.sampler(),new NPostFX.Pass(Temporal.ta_sh,current.sampler(),history.sampler(),
                depth.sampler(),rep,savedDepth.sampler(),new float[]{jitter/SIZE.x,0},new float[]{-.02f,-1.002f,1,0}));
            CompletableFuture<Integer> result=new CompletableFuture<>();
            out.pget(output.image(0),RGBA,bytes->result.complete(bytes.get((32*SIZE.x+16)*4)&255));
            window.swapbuffers(out,false);window.env().submit(out);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!result.isDone() && System.nanoTime()<deadline) {
                Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);
            }
            return result.get(1,TimeUnit.SECONDS);
        } finally {
            current.dispose();history.dispose();depth.dispose();old.dispose();savedDepth.dispose();output.dispose();
        }
    }

    public static void main(String[] args) throws Exception {
        Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();
        Windeye window=toolkit.window();
        int exit=0;
        try {
            window.title("Temporal AA motion regression");
            window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            int still=capture(window,0,0,.5f,false), moving=capture(window,8,0,.5f,false);
            int jitter=capture(window,1,1,.5f,false), exposed=capture(window,0,0,.2f,false);
            int reset=capture(window,0,0,.5f,true), outside=capture(window,64,0,.5f,false);
            require(still>128 && still<140,"Stationary TAA no longer accumulates history");
            require(moving>=145 && moving>still+10,"Motion still dominated by stale history");
            require(Math.abs(jitter-still)<=1,"Subpixel jitter mistaken for camera movement");
            require(exposed==153 && reset==153 && outside==153,"Invalid history contaminates current frame");
            System.out.printf("TAA: PASS (static=%d, motion=%d, jitter=%d, newly exposed=%d, reset=%d, out of view=%d; current=153)%n",
                still,moving,jitter,exposed,reset,outside);
        } catch(Throwable failure) { failure.printStackTrace();exit=1; }
        finally { window.dispose();toolkit.dispose(); }
        System.exit(exit);
    }
}
