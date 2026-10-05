package nurgling.render;

import haven.*;
import haven.render.*;
import haven.iosys.tk.*;
import java.nio.ByteOrder;
import java.util.concurrent.*;

/** Readback of the actual heat shader: local footprint and foreground occlusion. */
public class HeatShimmerTest extends FireEffectsTest {
    static float[] captureHeat(Windeye window,boolean active,boolean blocked) throws Exception {
        return captureHeat(window,active,blocked,12,2.3f);
    }
    static float[] captureHeat(Windeye window,boolean active,boolean blocked,float radius,float time) throws Exception {
        VectorFormat rgba=new VectorFormat(4,NumberFormat.FLOAT32);
        Texture2D output=new Texture2D(SIZE,DataBuffer.Usage.STATIC,rgba,null);
        Texture2D source=new Texture2D(SIZE,DataBuffer.Usage.STATIC,rgba,(im,env)->{
            if(im.level!=0)return null;
            FillBuffer fill=env.fillbuf(im);java.nio.ByteBuffer b=fill.push();
            for(int y=0;y<SIZE.y;y++)for(int x=0;x<SIZE.x;x++)
                b.putFloat((x+.5f)/SIZE.x).putFloat((y+.5f)/SIZE.y).putFloat(0).putFloat(1);
            return fill;
        });
        Texture2D depth=new Texture2D(SIZE,DataBuffer.Usage.STATIC,new VectorFormat(1,NumberFormat.FLOAT32),(im,env)->{
            if(im.level!=0)return null;
            FillBuffer fill=env.fillbuf(im);java.nio.ByteBuffer b=fill.push();
            for(int i=0;i<SIZE.x*SIZE.y;i++)b.putFloat(blocked?.2f:1);
            return fill;
        });
        try {
            Texture2D.Sampler2D sampler=source.sampler();
            sampler.magfilter(Texture.Filter.LINEAR).minfilter(Texture.Filter.LINEAR);
            Object[] values=new Object[15];values[0]=sampler;values[1]=time;values[2]=depth.sampler();
            for(int i=3;i<values.length;i++)values[i]=new float[4];
            if(active){values[3]=new float[]{.5f,.3f,0,.35f};values[9]=new float[]{radius,.6f,.6f,0};}
            Render out=window.env().render();
            Pipe pipe=new BufPipe().prep(new FragColor<>(output.image(0))).prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            NPostFX.blit(new GOut(out,pipe,SIZE),sampler,new NPostFX.Pass(SceneFX.ht_sh,values));
            CompletableFuture<float[]> future=new CompletableFuture<>();
            out.pget(output.image(0),rgba,bytes->{float[] pixels=new float[SIZE.x*SIZE.y*4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(pixels);future.complete(pixels);});
            window.swapbuffers(out,false);window.env().submit(out);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!future.isDone() && System.nanoTime()<deadline){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(10);}
            return future.get(1,TimeUnit.SECONDS);
        } finally {output.dispose();source.dispose();depth.dispose();}
    }
    public static void main(String[] args) throws Exception {
        Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();int exit=0;
        try {
            window.title("Heat shimmer regression");window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            float[] baseline=captureHeat(window,false,false),heat=captureHeat(window,true,false),blocked=captureHeat(window,true,true);
            double maximum=0;int affected=0;
            for(int y=0;y<SIZE.y;y++)for(int x=0;x<SIZE.x;x++){
                int i=(y*SIZE.x+x)*4;
                double change=Math.hypot(heat[i]-baseline[i],heat[i+1]-baseline[i+1])*SIZE.x;
                maximum=Math.max(maximum,change);
                if(change>1e-5){affected++;require(x>115 && x<141 && y>75 && y<168,"Heat spills outside local flame plume");}
                require(Math.abs(blocked[i]-baseline[i])<1e-6 && Math.abs(blocked[i+1]-baseline[i+1])<1e-6,"Heat distorts foreground geometry");
            }
            require(affected>50 && affected<2300 && maximum>1 && maximum<2.5,"Absent or oversized shimmer");
            float[] candleHeat=captureHeat(window,true,false,2,2.3f);
            float[] nextHeat=captureHeat(window,true,false,12,2.5f);
            double candleMotion=0,animation=0;
            for(int i=0;i<heat.length;i+=4) {
                candleMotion=Math.max(candleMotion,Math.hypot(candleHeat[i]-baseline[i],candleHeat[i+1]-baseline[i+1])*SIZE.x);
                animation=Math.max(animation,Math.hypot(nextHeat[i]-heat[i],nextHeat[i+1]-heat[i+1])*SIZE.x);
            }
            require(candleMotion>.15 && candleMotion<.6,"Small flame shimmer invisible or oversized");
            require(animation>.5,"Heat ripple does not visibly animate");
            Matrix4f projection=Matrix4f.id.mul(Matrix4f.id);projection.m[5]=0;projection.m[9]=.04f;projection.m[0]=.04f;
            float[][] candle=SceneFX.Heat.plume(projection,new float[][]{{-.5f,-.5f,12},{1,1,2.5f}},SIZE);
            float[][] fire=SceneFX.Heat.plume(projection,new float[][]{{-5,-5,0},{10,10,13}},SIZE);
            require(candle!=null && fire!=null && fire[1][0]>candle[1][0]*8,"Candle heat as wide as campfire");
            require(candle[0][3]<.05f && candle[0][1]>.74f && candle[0][1]<.77f,"Candle plume detached from flame");
            System.out.printf("Heat shimmer: PASS (%d local pixels, %.3f px fire, %.3f px candle, %.3f px animation; occlusion and source scaling)%n",affected,maximum,candleMotion,animation);
        } catch(Throwable t){t.printStackTrace();exit=1;} finally {window.dispose();toolkit.dispose();}
        System.exit(exit);
    }
}
