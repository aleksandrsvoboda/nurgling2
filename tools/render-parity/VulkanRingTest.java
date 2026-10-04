package haven.render.vk;

import haven.*;
import haven.render.*;
import haven.iosys.tk.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import static org.lwjgl.system.MemoryUtil.*;

/** Native allocation reuse plus GPU upload/readback across the actual fenced slots. */
public class VulkanRingTest {
    static void require(boolean ok, String message) { if(!ok) throw new AssertionError(message); }
    static long bytes(VkExec.Ring ring) { return ring.chunks.stream().mapToLong(c -> c.size).sum(); }
    static void allocationCases(VkExec exec) {
        synchronized(exec) {
            VkExec.Ring ring = exec.new Ring();
            try {
                ring.alloc(256, 256); long small = ring.rbuf;
                ring.alloc(6 << 20, 16); long large = ring.rbuf;
                ring.reset();
                long allocations = exec.ringAllocations, frees = exec.ringDestructions;
                for(int i = 0; i < 20; i++) {
                    // Different order: a large request must find the later cached block.
                    ring.alloc(6 << 20, 256);
                    require(ring.rbuf == large, "Large allocation missed reusable block");
                    long first = ring.raddr; memPutInt(first, 0x12345678);
                    ring.alloc(3 << 20, 16);
                    require(ring.rbuf == small, "Small allocation missed reusable block");
                    long second = ring.raddr; memPutInt(second, 0x76543210);
                    ring.alloc(17, 256);
                    require(ring.roff % 256 == 0, "Allocation alignment");
                    memPutInt(ring.raddr, 99);
                    require(memGetInt(first) == 0x12345678 && memGetInt(second) == 0x76543210,
                            "Live ring allocations overlap");
                    ring.reset();
                }
                require(exec.ringAllocations == allocations && exec.ringDestructions == frees,
                        "Repeated workload creates/destroys buffers after warmup");
                ring.alloc(20 << 20, 16); // New demand remains cached, then expires when unused.
                ring.reset();
                for(int i = 0; i < VkExec.RING_IDLE_RESETS; i++) ring.reset();
                require(bytes(ring) <= 4L * VkExec.CHUNK, "Idle upload burst retained too much memory");
                ring.alloc((int)VkExec.RING_CACHE_BYTES + VkExec.CHUNK, 16);
                ring.reset();
                require(bytes(ring) <= VkExec.RING_CACHE_BYTES, "Per-slot cache budget exceeded");
            } finally { ring.destroy(); }
            require(ring.chunks.isEmpty(), "Ring teardown retained buffers");
        }
    }
    static long allocations(VkEnvironment env) { synchronized(env.exec) { return env.exec.ringAllocations; } }
    static CompletableFuture<ByteBuffer> upload(Render out, Texture2D tex, int value) {
        byte[] data = new byte[tex.w * tex.h * 4]; Arrays.fill(data, (byte)value);
        out.update(tex.image(0), (image, env) -> {
            FillBuffer fill = env.fillbuf(image); fill.pull(ByteBuffer.wrap(data)); return fill;
        });
        CompletableFuture<ByteBuffer> read = new CompletableFuture<>();
        out.pget(tex.image(0), tex.efmt, read::complete);
        return read;
    }
    static void check(ByteBuffer data, int value) {
        for(int i = 0; i < data.limit(); i += 4093)
            require((data.get(i) & 255) == value, "GPU data corruption at byte " + i);
        require((data.get(data.limit() - 1) & 255) == value, "GPU tail corruption");
    }
    static void transfers(Windeye window) throws Exception {
        VkEnvironment env = (VkEnvironment)window.env();
        VectorFormat rgba = new VectorFormat(4, NumberFormat.UNORM8);
        Texture2D a = new Texture2D(2048,1024,DataBuffer.Usage.STREAM,rgba,null);
        Texture2D b = new Texture2D(1536,1024,DataBuffer.Usage.STREAM,rgba,null);
        Pipe pipe = new BufPipe().prep(window.fbstate());
        long warm = -1;
        try {
            for(int i = 0; i < 18; i++) {
                Render out = env.render();
                CompletableFuture<ByteBuffer> ar = upload(out,a,i+10), br = upload(out,b,i+80);
                out.clear(pipe,FragColor.fragcol,new FColor(.1f,.2f,.3f,1));
                window.swapbuffers(out,false); env.submit(out);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while(!(ar.isDone() && br.isDone()) && System.nanoTime() < deadline) {
                    Render pump = env.render();
                    pump.clear(pipe,FragColor.fragcol,new FColor(.1f,.2f,.3f,1));
                    window.swapbuffers(pump,false); env.submit(pump);
                    Thread.sleep(10);
                }
                check(ar.get(1,TimeUnit.SECONDS),i+10); check(br.get(1,TimeUnit.SECONDS),i+80);
                if(i == 11) warm = allocations(env);
            }
            require(allocations(env) == warm, "GPU upload ring still allocates after warmup");
            System.out.println("GPU upload/readback PASS: 18 pairs of 8/6 MiB transfers; no ring allocations in final 6 iterations");
        } finally { a.dispose(); b.dispose(); }
    }
    public static void main(String[] args) throws Exception {
        int exit = 0;
        Toolkit toolkit = Toolkit.toolkits().get("vulkan").open(); Windeye window = toolkit.window();
        try {
            window.title("Vulkan upload ring reuse regression");
            window.sizing(new Windeye.Sizing().fixsize(Coord.of(128,128))).show(true);
            allocationCases(((VkEnvironment)window.env()).exec);
            System.out.println("Ring PASS: reuse, mixed order, alignment, non-overlap, idle expiry, budget and teardown");
            transfers(window);
        } catch(Throwable failure) { failure.printStackTrace(); exit = 1; }
        finally { window.dispose(); toolkit.dispose(); }
        System.exit(exit);
    }
}
