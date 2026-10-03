package nurgling.render;

import java.nio.file.*;
import javax.imageio.ImageIO;
import nurgling.i18n.L10n;

public class FrameHistoryTest {
    private static void near(double actual, double expected) {
        if(Math.abs(actual - expected) > .00001)
            throw(new AssertionError(actual + " != " + expected));
    }

    public static void main(String[] args) throws Exception {
        FrameHistory h = new FrameHistory();
        double now = 0;
        h.record(now);
        for(int i = 0; i < 99; i++) h.record(now += .01);
        h.record(now += .2);
        FrameHistory.Snapshot s = h.snapshot(now);
        near(s.average, 100 / 1.19);
        near(s.minimum, 5);
        near(s.low, 5);
        near(s.p99Ms, 10);
        near(s.maxMs, 200);
        if(s.slow != 1 || s.seconds.length != 100) throw(new AssertionError("Lost spike"));
        h.record(Double.NaN); h.record(now); h.record(now - 1);
        if(h.snapshot(now).seconds.length != 100) throw(new AssertionError("Invalid clock sample"));
        if(h.snapshot(now + 11).seconds.length != 0) throw(new AssertionError("Expired history"));
        h.reset(20); h.record(20.01);
        near(h.snapshot(20.01).fps, 100);
        // Exercise ring wrap and a full ten-second rolling window.
        h.reset(0);
        for(int i = 1; i <= 20000; i++) h.record(i * .0001);
        if(h.snapshot(2).seconds.length != 16384) throw(new AssertionError("Ring wrap"));
        h.reset(0);
        for(int i = 1; i <= 1200; i++) h.record(i / 60.0);
        s = h.snapshot(20);
        near(s.average, 60);
        if(s.times[0] < 10 || s.seconds.length > 601) throw(new AssertionError("Time window"));
        System.out.println("Frame history: PASS (spike, 1% low, percentiles, invalid clocks, expiry, reset, ring wrap)");
        if(args.length > 0) {
            h.reset(0); now = 0;
            for(int i = 0; i < 500; i++) h.record(now += i % 113 == 0 ? .12 : 1.0 / 60);
            Path dir = Paths.get(args[0]); Files.createDirectories(dir);
            for(String lang : new String[]{"en", "ru"}) {
                L10n.setLanguage(lang);
                ImageIO.write(FpsGraph.render(h.snapshot(now), "Vulkan", FpsGraph.W, FpsGraph.H), "png", dir.resolve("fps-" + lang + ".png").toFile());
                ImageIO.write(FpsGraph.render(h.snapshot(now), "Vulkan", FpsGraph.W * 2, FpsGraph.H * 2), "png", dir.resolve("fps-" + lang + "-2x.png").toFile());
            }
        }
        System.exit(0);
    }
}
