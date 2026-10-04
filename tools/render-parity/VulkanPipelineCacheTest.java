package haven.render.vk;

import haven.*;
import haven.render.*;
import haven.iosys.tk.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercise the five-minute housekeeping path with extraction blocked and real native snapshots. */
public class VulkanPipelineCacheTest {
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void frame(VkEnvironment env,Windeye window,Pipe pipe)throws Exception {
        Render out=env.render();out.clear(pipe,FragColor.fragcol,new FColor(.1f,.2f,.3f,1));
        window.swapbuffers(out,false);
        CompletableFuture<Void> done=new CompletableFuture<>();out.fence(() -> done.complete(null));env.submit(out);
        done.get(5,TimeUnit.SECONDS);
    }
    public static void main(String[] args)throws Exception {
        Toolkit toolkit=Toolkit.toolkits().get("vulkan").open();Windeye window=toolkit.window();int exit=0;
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            window.sizing(new Windeye.Sizing().fixsize(Coord.of(128,128))).show(true);
            VkEnvironment env=(VkEnvironment)window.env();
            Method snapshot=VkEnvironment.class.getDeclaredMethod("pipecachesnapshot");snapshot.setAccessible(true);
            Path path=Files.createTempDirectory(Paths.get("build"),"native-cache-test-").resolve("pipelines.bin");
            AtomicInteger calls=new AtomicInteger();AtomicReference<String> thread=new AtomicReference<>();
            PipelineCacheWriter writer=new PipelineCacheWriter(path,() -> {
                thread.set(Thread.currentThread().getName());
                if(calls.incrementAndGet()==1) {
                    entered.countDown();PipelineCacheWriterTest.waitFor(release);
                }
                try {
                    PipelineCacheWriter.Snapshot data=(PipelineCacheWriter.Snapshot)snapshot.invoke(env);
                    require(data!=null&&data.data.isDirect(),"Native cache snapshot missing or copied to heap");
                    return data;
                }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
            });
            env.pipecachewriter.close();
            Field writerField=VkEnvironment.class.getDeclaredField("pipecachewriter");writerField.setAccessible(true);writerField.set(env,writer);
            Pipe pipe=new BufPipe().prep(window.fbstate()).prep(new States.Viewport(Area.sized(Coord.of(128,128))));
            writer.changed();
            Field lastSave=VkEnvironment.class.getDeclaredField("lastpsave");lastSave.setAccessible(true);lastSave.setDouble(env,Utils.rtime()-301);
            frame(env,window,pipe);PipelineCacheWriterTest.waitFor(entered);
            long start=System.nanoTime();
            for(int i=0;i<30;i++)frame(env,window,pipe);
            require(calls.get()==1,"Housekeeping queued duplicate native snapshots");
            require(thread.get().equals("Vulkan pipeline cache writer"),"Extraction executed on rendering thread");
            release.countDown();
            for(int i=0;i<30;i++)frame(env,window,pipe);
            writer.close(); // Joins real driver extraction and atomic disk write.
            require(Files.size(path)>32,"Invalid native cache file");
            // The saved binary must be accepted by the driver for a fresh cache.
            try(org.lwjgl.system.MemoryStack stack=org.lwjgl.system.MemoryStack.stackPush()) {
                byte[] bytes=Files.readAllBytes(path);
                java.nio.ByteBuffer data=org.lwjgl.system.MemoryUtil.memAlloc(bytes.length);
                try {
                    data.put(bytes).flip();
                    org.lwjgl.vulkan.VkPipelineCacheCreateInfo info=org.lwjgl.vulkan.VkPipelineCacheCreateInfo.calloc(stack).sType$Default().pInitialData(data);
                    java.nio.LongBuffer cache=stack.mallocLong(1);
                    VkEnvironment.check(org.lwjgl.vulkan.VK10.vkCreatePipelineCache(env.dev,info,null,cache),"reload saved cache");
                    org.lwjgl.vulkan.VK10.vkDestroyPipelineCache(env.dev,cache.get(0),null);
                }finally{org.lwjgl.system.MemoryUtil.memFree(data);}
            }
            System.out.printf("PASS: 60 frames continued around blocked/background save (%.1f ms total); %,d-byte native cache reloaded; one extraction%n",
                (System.nanoTime()-start)/1e6,Files.size(path));
        }catch(Throwable e){e.printStackTrace();exit=1;}
        finally{release.countDown();window.dispose();toolkit.dispose();}
        System.exit(exit);
    }
}
