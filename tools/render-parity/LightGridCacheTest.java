package haven.render;

import haven.*;
import haven.iosys.tk.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.management.ManagementFactory;

/** Grid reuse must preserve moving lights, color updates and deferred texture data. */
public class LightGridCacheTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static byte[] read(Windeye window,Texture2D texture,VectorFormat format)throws Exception {
        CompletableFuture<byte[]> result=new CompletableFuture<>();Render out=window.env().render();
        out.pget(texture.image(0),format,b->{byte[] copy=new byte[b.remaining()];b.get(copy);result.complete(copy);});
        window.swapbuffers(out,false);window.env().submit(out);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!result.isDone()&&System.nanoTime()<end){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(2);}
        return result.get(1,TimeUnit.SECONDS);
    }
    static Object[] light(float x,float w){return new Object[]{new float[]{.1f,.1f,.1f,1},new float[]{.6f,.4f,.2f,1},new float[]{.2f,.2f,.2f,1},new float[]{x,0,-20,w},1f,0f,.05f,.05f};}
    public static void main(String[] args)throws Exception {
        int status=0;Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();
        Lighting.LightGrid grid=new Lighting.LightGrid(64,64,64);Lighting.LightGrid.GridLights state=null;
        try {
            window.title("Light grid cache regression");window.sizing(new Windeye.Sizing().fixsize(Coord.of(64,64))).show(true);
            Object[][] lights={light(0,0),light(-12,1)};Projection projection=Projection.ortho(-40,40,-40,40,1,80);
            for(int step=0;step<8;step++) {
                if(step==1)((float[])lights[1][1])[0]=.9f; // Color must refresh without rebuilding topology.
                if(step==2)((float[])lights[1][3])[0]=15;
                if(step==3)lights[1][6]=.2f;
                if(step==4)projection=Projection.ortho(-20,20,-20,20,1,80);
                if(step==5)grid.maxlights=1;
                if(step==6)lights=new Object[][]{lights[1],lights[0]};
                if(step==7)lights=new Object[][]{lights[0]};
                state=(Lighting.LightGrid.GridLights)grid.compile(lights,projection);
                Lighting.LightGrid fresh=new Lighting.LightGrid(64,64,64);fresh.maxlights=grid.maxlights;
                Lighting.LightGrid.GridLights reference=(Lighting.LightGrid.GridLights)fresh.compile(lights,projection);
                try {
                    VectorFormat ints=new VectorFormat(1,NumberFormat.UINT16),floats=new VectorFormat(4,NumberFormat.FLOAT32);
                    byte[] actual=read(window,(Texture2D)state.lstex.tex,ints),expected=read(window,(Texture2D)reference.lstex.tex,ints);
                    check(Arrays.equals(Arrays.copyOf(actual,64*64*64*2),Arrays.copyOf(expected,64*64*64*2)),"Stale grid after update "+step);
                    actual=read(window,(Texture2D)state.ldtex.tex,floats);expected=read(window,(Texture2D)reference.ldtex.tex,floats);
                    check(Arrays.equals(Arrays.copyOf(actual,lights.length*80),Arrays.copyOf(expected,lights.length*80)),"Stale light color/position "+step);
                }finally{reference.dispose();}
            }
            com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
            for(int i=0;i<10;i++)state=(Lighting.LightGrid.GridLights)grid.compile(lights,projection);
            long id=Thread.currentThread().getId(),before=bean.getThreadAllocatedBytes(id);
            for(int i=0;i<30;i++)state=(Lighting.LightGrid.GridLights)grid.compile(lights,projection);
            long bytes=(bean.getThreadAllocatedBytes(id)-before)/30;
            check(bytes<128*1024,"Stationary scene allocates another 512 KiB light grid: "+bytes);
            System.out.println("Light grid PASS: GPU data matches uncached compilation after color, position, range, projection, limit, ordering and removal changes; cached bytes/compile="+bytes);
        }catch(Throwable e){e.printStackTrace();status=1;}finally{if(state!=null)state.dispose();window.dispose();toolkit.dispose();}
        System.exit(status);
    }
}
