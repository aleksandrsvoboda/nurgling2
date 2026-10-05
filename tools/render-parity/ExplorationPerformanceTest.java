package nurgling.overlays.map;

import haven.*;
import nurgling.NConfig;
import nurgling.tools.ExploredArea;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/** No game connection: immutable masks, background I/O, bounded cache and upload budget. */
public class ExplorationPerformanceTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static Object field(Class<?> type,Object target,String name)throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    static Tex ready(MinimapExploredAreaRenderer cache,Coord grid,boolean[] mask)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            cache.beginFrame();Tex result=cache.getOverlay(grid,17,mask,Color.YELLOW,false);
            if(result!=null)return result;
            Thread.sleep(1);
        }
        throw new AssertionError("Overlay did not complete");
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory(Paths.get("build"),"exploration-perf-");
        // Keep constructor defaults and resource caches inside this test directory.
        Field localdir=Config.class.getDeclaredField("localdir");localdir.setAccessible(true);localdir.set(null,dir);
        Field haslocaldir=Config.class.getDeclaredField("haslocaldir");haslocaldir.setAccessible(true);haslocaldir.setBoolean(null,true);
        NConfig previous=NConfig.current;
        NConfig.current=new NConfig(){
            public String getExploredPath(){return dir.resolve("main.json").toString();}
            public String getSessionExploredPath(){return dir.resolve("session.json").toString();}
        };
        MinimapExploredAreaRenderer cache=new MinimapExploredAreaRenderer();
        ExecutorService io=(ExecutorService)field(ExploredArea.class,null,"io");
        ThreadPoolExecutor raster=(ThreadPoolExecutor)field(MinimapExploredAreaRenderer.class,null,"rasterizer");
        CountDownLatch ioGate=new CountDownLatch(1),rasterGate=new CountDownLatch(1);
        try {
            ExploredArea area=new ExploredArea(null);
            area.updateExploredTiles(new Coord(0,0),new Coord(101,1),17);
            boolean[] a=area.getExploredMaskForGrid(Coord.z,17,0);
            boolean[] b=area.getExploredMaskForGrid(new Coord(1,0),17,0);
            area.updateExploredTiles(new Coord(100,0),new Coord(102,1),17);
            check(a==area.getExploredMaskForGrid(Coord.z,17,0),"Another grid invalidates unchanged mask");
            check(!b[1]&&area.getExploredMaskForGrid(new Coord(1,0),17,0)[1],"Published mask mutated");
            boolean[] updated=area.getExploredMaskForGrid(new Coord(1,0),17,0);
            area.updateExploredTiles(new Coord(100,0),new Coord(102,1),17);
            check(updated==area.getExploredMaskForGrid(new Coord(1,0),17,0),"Unchanged exploration allocates masks");
            area.updateExploredTiles(new Coord(-1,-1),new Coord(0,0),17);
            check(area.getExploredMaskForGrid(new Coord(-1,-1),17,0)[9999],"Negative grid boundary broken");

            raster.submit(()->{try{rasterGate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            cache.beginFrame();
            long start=System.nanoTime();
            check(cache.getOverlay(Coord.z,17,a,Color.YELLOW,false)==null,"Raster ran inline");
            check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(100),"Render waited for raster worker");
            rasterGate.countDown();
            Tex first=ready(cache,Coord.z,a);
            check(((TexI)first).back.getRGB(0,0)==Color.YELLOW.getRGB(),"Overlay color changed");
            check(((TexI)first).back.getRGB(0,1)==0,"Unexplored tile became opaque");
            for(int i=0;i<10000;i++)check(cache.getOverlay(Coord.z,17,a,Color.YELLOW,false)==first,"Idle cache regenerates textures");
            cache.beginFrame();
            for(int i=1;i<=3;i++)cache.getOverlay(new Coord(i,0),17,a,Color.YELLOW,false);
            raster.submit(()->{}).get(5,TimeUnit.SECONDS);
            cache.beginFrame();int published=0;
            for(int i=1;i<=3;i++)if(cache.getOverlay(new Coord(i,0),17,a,Color.YELLOW,false)!=null)published++;
            check(published==2,"Texture upload budget not enforced: "+published);
            cache.beginFrame();check(cache.getOverlay(new Coord(3,0),17,a,Color.YELLOW,false)!=null,"Budgeted upload never resumes");
            MinimapExploredAreaRenderer another=new MinimapExploredAreaRenderer();
            try {check(ready(another,Coord.z,a)!=first,"Overlay cache leaks across maps/profiles");}finally{another.dispose();}
            for(int i=0;i<700;i++)cache.getOverlay(new Coord(i,5),17,a,Color.YELLOW,false);
            check(((Map<?,?>)field(MinimapExploredAreaRenderer.class,cache,"overlayCache")).size()<=512,"Unbounded texture cache");
            check(raster.getQueue().size()<=16,"Unbounded raster task queue");

            io.submit(()->{try{ioGate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            Path path=dir.resolve("main.json");
            start=System.nanoTime();
            CompletableFuture<Void> save=area.saveAsync(path.toString());
            check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(100)&&!save.isDone(),"UI waited for disk worker");
            area.updateExploredTiles(new Coord(2,2),new Coord(3,3),17);
            check(!a[202],"Saving/updates mutate raster snapshot");
            ioGate.countDown();save.get(5,TimeUnit.SECONDS);
            ExploredArea second=new ExploredArea(null);
            second.updateExploredTiles(new Coord(3,3),new Coord(4,4),17);
            second.saveAsync(path.toString()).get(5,TimeUnit.SECONDS);
            check(second.getExploredMaskForGrid(Coord.z,17,0)[202],"Disk exploration lost during merge");
            area.saveAsync(path.toString()).get(5,TimeUnit.SECONDS);
            check(area.getExploredMaskForGrid(Coord.z,17,0)[303],"Other client exploration not merged");
            byte[] before=Files.readAllBytes(path);
            try(FileChannel lockChannel=FileChannel.open(Paths.get(path+".lock"),StandardOpenOption.WRITE);
                    FileLock lock=lockChannel.lock()) {
                try {area.saveAsync(path.toString()).get(5,TimeUnit.SECONDS);throw new AssertionError("Locked file overwritten");}
                catch(ExecutionException expected) {check(Arrays.equals(before,Files.readAllBytes(path)),"Lock contention damages file");}
            }
            area.startSession();
            area.updateExploredTiles(new Coord(4,4),new Coord(5,5),17);
            area.tick(1);
            io.submit(()->{}).get(5,TimeUnit.SECONDS);
            check(Files.readString(dir.resolve("session.json")).contains("true"),"Session not saved asynchronously");
            area.endSession();io.submit(()->{}).get(5,TimeUnit.SECONDS);
            check(!Files.exists(dir.resolve("session.json")),"Old queued save resurrects ended session");
            System.out.println("PASS: per-grid immutable snapshots, idle texture reuse, background raster/I/O, two uploads/frame, bounded caches, profile isolation, merge and file-lock safety, ordered session deletion");
        } finally {ioGate.countDown();rasterGate.countDown();cache.dispose();NConfig.current=previous;}
        System.exit(0);
    }
}
