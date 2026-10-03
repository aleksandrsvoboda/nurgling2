package haven.render.vk;

import haven.*;
import haven.iosys.tk.Toolkit;
import haven.iosys.tk.Windeye;
import haven.render.*;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Exercises the UI loop's CPU fences around presentation, without a game session. */
public class VulkanFrameTest {
    private static long units(VkEnvironment env) throws Exception {
        synchronized(env.exec) {
            Field field = VkExec.class.getDeclaredField("nunits");
            field.setAccessible(true);
            return(field.getLong(env.exec));
        }
    }

    private static CompletableFuture<Void> fence(Render out) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        out.fence(() -> done.complete(null));
        return(done);
    }

    public static void main(String[] args) throws Exception {
        int exit = 0;
        Toolkit toolkit = Toolkit.toolkits().get("vulkan").open();
        Windeye window = toolkit.window();
        try {
            window.title("Vulkan frame scheduling test");
            window.sizing(new Windeye.Sizing().fixsize(Coord.of(128,128))).show(true);
            VkEnvironment env = (VkEnvironment)window.env();
            Pipe p = new BufPipe().prep(window.fbstate())
                .prep(new States.Viewport(Area.sized(Coord.of(128,128))))
                .prep(new Ortho2D(0,0,128,128));
            VertexArray.Layout layout = new VertexArray.Layout(
                new VertexArray.Layout.Input(Ortho2D.pos, new VectorFormat(2, NumberFormat.FLOAT32), 0, 0, 8));
            double[] times = new double[90];
            long before = 0;
            for(int i = -10; i < times.length; i++) {
                if(i == 0) before = units(env);
                long start = System.nanoTime();
                Render out = env.render();
                fence(out); // FRAME sync point, before drawing
                out.clear(p, FragColor.fragcol, new FColor(.15f,.2f,.3f,1));
                Render sub = env.render();
                sub.draw(p, Model.Mode.TRIANGLES, null, layout, 3,
                         new float[]{10,10, 100,10, 50,100});
                out.submit(sub);
                window.swapbuffers(out, true);
                CompletableFuture<Void> done = fence(out); // frame-lag callback, after swap
                env.submit(out);
                done.get(10, TimeUnit.SECONDS);
                units(env); // join execution, including the end-of-process submit
                if(i >= 0) times[i] = (System.nanoTime() - start) / 1e6;
            }
            long submissions = units(env) - before;
            Arrays.sort(times);
            System.out.printf("%d frames: %d GPU submissions; median %.2f ms, p95 %.2f ms, max %.2f ms%n",
                              times.length, submissions, times[45], times[85], times[89]);
            Render cpu = env.render();
            Render sub = env.render();
            fence(sub);
            cpu.submit(sub);
            CompletableFuture<Void> done = fence(cpu);
            before = units(env);
            env.submit(cpu);
            done.get(10, TimeUnit.SECONDS);
            long cpuSubmissions = units(env) - before;
            System.out.println("Callback-only render GPU submissions: " + cpuSubmissions);
            if(submissions != times.length || cpuSubmissions != 0)
                throw(new AssertionError("CPU callbacks must not create GPU submissions"));
        } catch(Throwable failure) {
            failure.printStackTrace(); exit = 1;
        } finally {
            window.dispose(); toolkit.dispose();
        }
        System.exit(exit);
    }
}
