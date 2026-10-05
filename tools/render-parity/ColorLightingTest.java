package nurgling.render;

import haven.*;
import haven.render.*;
import haven.iosys.tk.Toolkit;
import haven.iosys.tk.Windeye;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Daily palette continuity and an actual GPU readback of the standalone grading pass. */
public class ColorLightingTest {
    private static void require(boolean condition, String message) {
        if(!condition) throw new AssertionError(message);
    }

    private static void palette() {
        NGfx.Settings base = NGfx.classic;
        require(!base.worldlight && !base.colorpass(), "Classic changes lighting");
        require(!base.with("tod", true).colorpass(), "Removed time-of-day setting is still active");
        require(base.with("autoexp", true).colorpass(), "Auto-exposure alone has no color pass");
        NGfx.Settings custom = base.with("enabled", true).with("worldlight", true).with("worldlightstrength", .7f);
        require(custom.with("autoexp", true).worldlightstrength == .7f, "Strength lost on another setting change");
        require(!NGfx.effective(custom, false).worldlight && !NGfx.Preset.CLASSIC.settings(custom).worldlight,
                "Classic/OpenGL opts into world lighting");
        DirLight original = new DirLight(new FColor(.3f,.3f,.3f), new FColor(.6f,.6f,.6f),
                new FColor(.2f,.2f,.2f), new Coord3f(.4f,.3f,1));
        require(WorldLighting.apply(original, .5, 0) == original, "Zero strength is not exact baseline");
        DirLight dark = new DirLight(FColor.BLACK, FColor.BLACK, FColor.BLACK, Coord3f.zu);
        require(WorldLighting.apply(dark, .5, 1) == dark, "Underground light invented");
        DirLight night = WorldLighting.apply(original, 0, 1), day = WorldLighting.apply(original, .5, 1);
        DirLight dawn = WorldLighting.apply(original, 6.25 / 24, 1);
        require(night.dif[2] > night.dif[0] * 1.8 && dawn.dif[0] > dawn.dif[2] * 1.8,
                "Day/night palette is not distinct");
        require(day.amb[0] > night.amb[0] && night.amb[2] > night.amb[0] * 1.5,
                "Ambient light does not distinguish warm day and cool night");
        SceneDebug preview = new SceneDebug();
        preview.time(0);
        DirLight faintMoon = preview.light(), readableMoon = WorldLighting.apply(faintMoon, 0, 1);
        require(readableMoon.dif[2] > faintMoon.dif[2] * 2 && readableMoon.amb[1] > faintMoon.amb[1] * 2,
                "Faint source light still makes the moonlit scene unreadable");
        require(WorldLighting.apply(faintMoon,0,0) == faintMoon, "Zero strength changes night lighting");
        DirLight previous = night;
        for(int minute = 1; minute <= 1440; minute++) {
            DirLight light = WorldLighting.apply(original, minute / 1440.0, 1);
            for(int c = 0; c < 3; c++) {
                require(Float.isFinite(light.dif[c]), "Invalid daily color");
                require(Math.abs(light.dif[c] - previous.dif[c]) < .06, "Abrupt daily transition");
                require(Math.abs(light.dir[c] - original.dir[c]) < .00001, "Shadow direction changed");
            }
            previous = light;
        }
        require(original.dif[0] == .6f, "Original light mutated");
        require(day.dif[0] > original.dif[0] * 1.25f, "Midday diffuse maximum is too dim");
        for(int c = 0; c < 3; c++) {
            require(day.dif[c] <= original.dif[c] * 1.5f && day.spc[c] <= original.spc[c], "Excessive midday gain or amplified specular light");
            require(night.dif[c] <= .60001f, "Midday gain leaked into night lighting");
        }
        DirLight matte = new DirLight(FColor.WHITE, FColor.WHITE, FColor.BLACK, Coord3f.zu);
        require(WorldLighting.apply(matte, .5, 1).spc[0] == 0, "Matte light gained a specular highlight");
    }

    private static int captureCel(Windeye window, float level, boolean smooth) throws Exception {
        Coord size = Coord.of(32,32);
        VectorFormat rgba = new VectorFormat(4, NumberFormat.UNORM8);
        Texture2D output = new Texture2D(size, DataBuffer.Usage.STATIC, rgba, null);
        try {
            Pipe pipe = new BufPipe().prep(new FragColor<>(output.image(0)))
                .prep(new States.Viewport(Area.sized(size))).prep(Projection.ortho(-1,1,-1,1,.1f,10))
                .prep(new Light.PhongLight(true,FColor.BLACK,FColor.WHITE,FColor.BLACK,FColor.BLACK,0))
                .prep(new Lighting.SimpleLights(new Object[][]{{new float[]{0,0,0,1},
                    new float[]{level,level,level,1},new float[]{0,0,0,1},new float[]{0,0,1,0},0f,0f,0f,0f}}))
                .prep(Light.celshade);
            if(smooth) pipe.prep(WorldLighting.smooth);
            VertexArray.Layout layout = new VertexArray.Layout(
                new VertexArray.Layout.Input(Homo3D.vertex,new VectorFormat(3,NumberFormat.FLOAT32),0,0,24),
                new VertexArray.Layout.Input(Homo3D.normal,new VectorFormat(3,NumberFormat.FLOAT32),0,12,24));
            Render out = window.env().render();
            out.draw(pipe,Model.Mode.TRIANGLES,null,layout,6,new float[]{
                -1,-1,-3,0,0,1, 1,-1,-3,0,0,1, 1,1,-3,0,0,1,
                -1,-1,-3,0,0,1, 1,1,-3,0,0,1, -1,1,-3,0,0,1});
            CompletableFuture<Integer> result = new CompletableFuture<>();
            out.pget(output.image(0),rgba,bytes -> result.complete(bytes.get() & 255));
            window.swapbuffers(out,false); window.env().submit(out);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while(!result.isDone() && System.nanoTime() < deadline) {
                Render pump = window.env().render(); window.swapbuffers(pump,false); window.env().submit(pump);
                Thread.sleep(10);
            }
            return result.get(1,TimeUnit.SECONDS);
        } finally { output.dispose(); }
    }

    private static int[] capture(Windeye window, float[] tint, boolean tonemap) throws Exception {
        Coord size = Coord.of(32, 32);
        VectorFormat rgba = new VectorFormat(4, NumberFormat.UNORM8);
        Texture2D input = new Texture2D(1, 1, DataBuffer.Usage.STATIC, rgba, (img, env) -> {
            FillBuffer fill = env.fillbuf(img);
            fill.push().put(new byte[]{(byte)230, (byte)230, (byte)230, (byte)255});
            return fill;
        });
        Texture2D output = new Texture2D(size, DataBuffer.Usage.STATIC, rgba, null);
        NPostFX.Grade effect = new NPostFX.Grade();
        try {
            effect.tod = tint;
            effect.tonemap = tonemap;
            Pipe pipe = new BufPipe().prep(new FragColor<>(output.image(0)))
                .prep(new States.Viewport(Area.sized(size))).prep(new Ortho2D(Area.sized(size)));
            Render out = window.env().render();
            effect.run(new GOut(out, pipe, size), input.sampler());
            CompletableFuture<int[]> result = new CompletableFuture<>();
            out.pget(output.image(0), rgba, bytes -> result.complete(new int[]{
                    bytes.get() & 255, bytes.get() & 255, bytes.get() & 255, bytes.get() & 255}));
            window.swapbuffers(out, false);
            window.env().submit(out);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while(!result.isDone() && System.nanoTime() < deadline) {
                Render pump = window.env().render();
                window.swapbuffers(pump, false);
                window.env().submit(pump);
                Thread.sleep(10);
            }
            return result.get(1, TimeUnit.SECONDS);
        } finally {
            effect.dispose(); input.dispose(); output.dispose();
        }
    }

    public static void main(String[] args) throws Exception {
        palette();
        Toolkit toolkit = Toolkit.toolkits().get("vulkan").open();
        Windeye window = toolkit.window();
        int exit = 0;
        try {
            window.title("Color lighting regression");
            window.sizing(new Windeye.Sizing().fixsize(Coord.of(64,64))).show(true);
            int classicLow = captureCel(window,.49f,false), classicHigh = captureCel(window,.51f,false);
            int smoothLow = captureCel(window,.49f,true), smoothHigh = captureCel(window,.51f,true);
            require(classicHigh - classicLow > 100, "Test does not reproduce classic cel threshold");
            require(smoothHigh - smoothLow >= 4 && smoothHigh - smoothLow <= 7, "Improved light still jumps at cel threshold");
            require(captureCel(window,.49f,false) == classicLow, "Smooth lighting leaked into Classic");
            int[] neutral = capture(window, new float[]{1,1,1,1}, false);
            int[] night = capture(window, new float[]{.92f,.98f,1.10f,.82f}, false);
            int[] mapped = capture(window, new float[]{1,1,1,1}, true);
            require(Math.abs(neutral[0] - 230) <= 1 && neutral[3] == 255, "Neutral pass changes the image");
            require(night[2] > night[0] + 25, "Standalone time-of-day grading not visible on GPU");
            require(mapped[0] < neutral[0] - 3, "Tone-mapping switch is ineffective");
            System.out.println("Color lighting: PASS (baseline, independent toggles, palette, continuity, Vulkan readback)");
            System.out.printf("GPU RGB neutral %d/%d/%d, night %d/%d/%d%n", neutral[0],neutral[1],neutral[2],night[0],night[1],night[2]);
            System.out.printf("GPU light transition: classic %d -> %d; improved %d -> %d%n",classicLow,classicHigh,smoothLow,smoothHigh);
        } catch(Throwable failure) {
            failure.printStackTrace(); exit = 1;
        } finally {
            window.dispose(); toolkit.dispose();
        }
        System.exit(exit);
    }

}
