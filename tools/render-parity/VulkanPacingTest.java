package haven.render.vk;

import haven.*;
import haven.render.*;
import haven.iosys.tk.Toolkit;
import haven.iosys.tk.Windeye;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Reproduces FRAME-mode pipelining: UI work overlaps the previous GPU frame. */
public class VulkanPacingTest {
    private static double[] frames(Windeye window, boolean vsync, int count) throws Exception {
        return frames(window, vsync, count, false);
    }

    private static double[] frames(Windeye window, boolean vsync, int count, boolean spikes) throws Exception {
        VkEnvironment env = (VkEnvironment)window.env();
        Pipe pipe = new BufPipe().prep(window.fbstate());
        CompletableFuture<Void> previous = CompletableFuture.completedFuture(null), done = previous;
        long[] times = new long[count];
        for(int i = -20; i < count; i++) {
            Render out = env.render();
            CompletableFuture<Void> sync = new CompletableFuture<>();
            out.fence(() -> sync.complete(null));
            // Simulated tick, before waiting for the previous frame's CPU fence.
            Thread.sleep(i % 13 == 0 ? 6 : 3);
            previous.get(10, TimeUnit.SECONDS);
            if(spikes) {
                // Model occasional command preparation spikes on the render
                // thread, with ample average headroom for a 60 Hz display.
                final int work = i % 13 == 0 ? 20 : 4;
                out.fence(() -> {
                    try {Thread.sleep(work);}
                    catch(InterruptedException e) {Thread.currentThread().interrupt();}
                });
            }
            out.clear(pipe, FragColor.fragcol, new FColor(.2f, .3f, .4f, 1));
            window.swapbuffers(out, vsync);
            CompletableFuture<Void> end = new CompletableFuture<>();
            out.fence(() -> end.complete(null));
            done = end;
            env.submit(out);
            previous = sync;
            if(i >= 0) times[i] = System.nanoTime();
        }
        done.get(10, TimeUnit.SECONDS);
        double[] intervals = new double[count - 1];
        for(int i = 1; i < count; i++) intervals[i - 1] = (times[i] - times[i - 1]) / 1e6;
        Arrays.sort(intervals);
        return(intervals);
    }

    public static void main(String[] args) throws Exception {
        int exit = 0;
        Toolkit toolkit = Toolkit.toolkits().get("vulkan").open();
        Windeye window = toolkit.window();
        try {
            window.title("Vulkan frame pacing regression");
            window.sizing(new Windeye.Sizing().fixsize(Coord.of(128,128))).show(true);
            VkEnvironment env = (VkEnvironment)window.env();
            System.out.println("Presentation pacing supported/enabled: " + env.presentwait);
            double[] dt = frames(window, true, 180);
            double median = dt[dt.length / 2], p95 = dt[(int)(dt.length * .95)];
            System.out.printf("Pipelined UI: median %.2f ms, p95 %.2f ms, max %.2f ms, mean %.2f ms%n",
                              median, p95, dt[dt.length - 1], Arrays.stream(dt).average().getAsDouble());
            // The synthetic 3/6 ms CPU work needs headroom; on high-refresh
            // displays it can itself become the limiting stage.
            if(env.presentwait && median >= 10 && p95 > median * 1.75)
                throw(new AssertionError("Bursty FRAME pacing: p95 exceeds 1.75x median"));
            double[] stressed = frames(window, true, 180, true);
            double stressedMedian = stressed[stressed.length / 2], stressed95 = stressed[(int)(stressed.length * .95)];
            System.out.printf("Render preparation spikes: median %.2f ms, p95 %.2f ms, max %.2f ms, mean %.2f ms%n",
                              stressedMedian, stressed95, stressed[stressed.length - 1], Arrays.stream(stressed).average().getAsDouble());
            if(env.presentwait && stressedMedian >= 14 && stressed95 > stressedMedian * 1.75)
                throw(new AssertionError("Presentation wait amplified isolated render preparation spikes"));
            frames(window, false, 12);
            window.sizing(new Windeye.Sizing().fixsize(Coord.of(160,144)));
            frames(window, true, 12); // new swapchain and fresh present IDs
            window.show(false);
            frames(window, true, 2); // bounded waits even while the window is hidden
            window.show(true);
            frames(window, true, 12);
            System.out.println("VSync toggle, resize, hide/show: PASS");
        } catch(Throwable failure) {
            failure.printStackTrace(); exit = 1;
        } finally {
            window.dispose(); toolkit.dispose();
        }
        System.exit(exit);
    }
}
