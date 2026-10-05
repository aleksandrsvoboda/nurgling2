package nurgling.render;

import haven.*;
import haven.render.*;
import haven.iosys.tk.*;
import java.nio.ByteOrder;
import java.util.concurrent.*;

/** GPU metering regression: lit interiors surrounded by void, shadows and night. */
public class AutoExposureTest {
    static final Coord SIZE = Coord.of(256, 256);
    static final VectorFormat RGBA = new VectorFormat(4, NumberFormat.FLOAT32);
    static void require(boolean ok, String message) {if(!ok) throw new AssertionError(message);}

    static float[] grade(Windeye window, float gain, boolean protect, boolean tonemap) throws Exception {
        return grade(window,gain,protect,tonemap,true);
    }

    static float[] grade(Windeye window, float gain, boolean protect, boolean tonemap, boolean enabled) throws Exception {
        Coord size=Coord.of(256,1);
        Texture2D source=new Texture2D(size,DataBuffer.Usage.STATIC,RGBA,(img,env)->{
            if(img.level!=0)return null;
            FillBuffer fill=env.fillbuf(img);java.nio.FloatBuffer data=fill.push().order(ByteOrder.nativeOrder()).asFloatBuffer();
            for(int x=0;x<256;x++){float v=x/255f*1.3f;data.put(v).put(v).put(v).put(1);}
            return fill;
        });
        Texture2D exp=new Texture2D(1,1,DataBuffer.Usage.STATIC,RGBA,(img,env)->{
            if(img.level!=0)return null;
            FillBuffer fill=env.fillbuf(img);fill.push().order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{gain,0,0,1});return fill;
        });
        Texture2D target=new Texture2D(size,DataBuffer.Usage.STATIC,RGBA,null);
        NPostFX.Grade effect=new NPostFX.Grade();effect.expo=enabled?exp.sampler():null;effect.nightvision=protect;effect.tonemap=tonemap;
        try {
            Render out=window.env().render();
            Pipe dst=new BufPipe().prep(new FragColor<>(target.image(0))).prep(new States.Viewport(Area.sized(size))).prep(new Ortho2D(Area.sized(size)));
            effect.run(new GOut(out,dst,size),source.sampler());
            CompletableFuture<float[]> result=new CompletableFuture<>();
            out.pget(target.image(0),RGBA,bytes->{float[] pixels=new float[256*4];bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().get(pixels);result.complete(pixels);});
            window.swapbuffers(out,false);window.env().submit(out);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!result.isDone()&&System.nanoTime()<end){Render pump=window.env().render();window.swapbuffers(pump,false);window.env().submit(pump);Thread.sleep(5);}
            return result.get(1,TimeUnit.SECONDS);
        } finally {effect.dispose();source.dispose();exp.dispose();target.dispose();}
    }

    static float capture(Windeye window, float level, int width, boolean shadow, boolean missingDepth) throws Exception {
        return capture(window, level, width, shadow, missingDepth, false, false);
    }

    static float capture(Windeye window, float level, int width, boolean shadow, boolean missingDepth,
                         boolean outdoors, boolean enterInterior) throws Exception {
        PView view = new PView(SIZE) {protected void basic() {}};
        Texture2D input = new Texture2D(SIZE, DataBuffer.Usage.STATIC, RGBA, null);
        Texture2D output = new Texture2D(SIZE, DataBuffer.Usage.STATIC, RGBA, null);
        Texture2D depth = new Texture2D(SIZE, DataBuffer.Usage.STATIC,
                new VectorFormat(1, NumberFormat.FLOAT32), (img, env) -> {
            if(img.level != 0) return null;
            FillBuffer fill = env.fillbuf(img);
            java.nio.FloatBuffer data = fill.push().order(ByteOrder.nativeOrder()).asFloatBuffer();
            for(int y = 0; y < SIZE.y; y++) for(int x = 0; x < SIZE.x; x++)
                data.put(x < width || shadow ? .5f : 1f);
            return fill;
        });
        view.depth = missingDepth ? null : depth;
        Temporal.AutoExposure effect = new Temporal.AutoExposure(view);
        effect.outdoors = outdoors;
        try {
            Pipe src = new BufPipe().prep(new FragColor<>(input.image(0)))
                    .prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            Pipe dst = new BufPipe().prep(new FragColor<>(output.image(0)))
                    .prep(new States.Viewport(Area.sized(SIZE))).prep(new Ortho2D(Area.sized(SIZE)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while(true) {
                Render out = window.env().render();
                out.clear(src, FragColor.fragcol, FColor.BLACK);
                if(width > 0) {
                    GOut pattern = new GOut(out, src, SIZE);
                    pattern.chcolor(new java.awt.Color(level, level, level));
                    pattern.frect(Coord.z, Coord.of(width, SIZE.y));
                }
                effect.run(new GOut(out, dst, SIZE), input.sampler());
                if(missingDepth) {
                    require(effect.exposure == null, "Missing depth reuses stale exposure");
                    out.dispose();
                    return 1;
                }
                CompletableFuture<Float> result = new CompletableFuture<>();
                out.pget(effect.exposure.tex.image(0), RGBA,
                        bytes -> result.complete(bytes.order(ByteOrder.nativeOrder()).getFloat()));
                window.swapbuffers(out, false); window.env().submit(out);
                while(!result.isDone() && System.nanoTime() < deadline) {
                    Render pump = window.env().render();
                    window.swapbuffers(pump, false); window.env().submit(pump); Thread.sleep(10);
                }
                float value = result.get(1, TimeUnit.SECONDS);
                require(System.nanoTime() < deadline, "Exposure pipeline warmup timed out");
                if(((haven.render.vk.VkRender)out).pendingDraws() == 0) {
                    require(Float.isFinite(value), "Nonfinite exposure");
                    if(enterInterior && effect.outdoors) {
                        require(value < 1, "Transition test needs outdoor dimming history");
                        effect.outdoors = false;
                        continue;
                    }
                    return value;
                }
                // Warmup frames may skip intermediate passes. Start adaptation afresh.
                effect.dispose(); effect = new Temporal.AutoExposure(view);
                effect.outdoors = outdoors;
            }
        } finally {
            effect.dispose(); view.depth = null; view.dispose(); input.dispose(); output.dispose(); depth.dispose();
        }
    }

    public static void main(String[] args) throws Exception {
        Toolkit toolkit = Toolkit.toolkits().get("vulkan").open();
        Windeye window = toolkit.window(); int exit = 0;
        try {
            window.sizing(new Windeye.Sizing().fixsize(SIZE)).show(true);
            for(float level : new float[]{.08f, .3f, .7f}) {
                float full = capture(window, level, 256, false, false);
                require(full >= .999f, "Interior night vision darkens the baseline: " + full);
                for(int width : new int[]{128, 64, 16}) {
                    float interior = capture(window, level, width, false, false);
                    require(Math.abs(full - interior) < .04, "Void changes exposure: " + full + " -> " + interior);
                }
                if(level < .1) require(full > 2, "Night no longer brightens");
                if(level > .6) require(full <= 1, "Lit interior is amplified");
            }
            float mixed = capture(window, .9f, 128, true, false);
            require(mixed <= 1.05f, "Lit objects wash out in deep shadows: " + mixed);
            require(capture(window, .08f, 128, true, false) > 2, "Dark geometry was discarded");
            require(Math.abs(capture(window, 0, 0, false, false) - 1) < .001, "Empty scene is not neutral");
            capture(window, .7f, 256, false, true);
            require(capture(window, .7f, 256, false, false, true, false) < .8f, "Outdoor glare reduction lost");
            require(capture(window, .7f, 256, false, false, true, true) >= .999f, "Outdoor dimming carried into interior");
            float[] lifted=grade(window,2.2f,true,true), unprotected=grade(window,2.2f,false,true), neutral=grade(window,1,false,false,false);
            float[] brighter=grade(window,1,false,true);
            require(Math.abs(brighter[60*4]/neutral[60*4]-1.15f)<.002f,"Night vision brightness is not +15 percent");
            for(int x=1;x<256;x++) {
                require(lifted[x*4]>=lifted[(x-1)*4]-.0001f,"Highlight protection reverses brightness ordering");
                require(lifted[x*4]<.9999f,"Interior highlights hard-clipped");
                require(Math.abs(neutral[x*4]-Math.min(1,x/255f*1.3f))<.002,"Disabled exposure changed baseline");
            }
            require(lifted[16*4]>.15f,"Shadow lift lost");
            require(lifted[180*4]<.96f&&unprotected[180*4]>.99f,"Bright areas still receive full exposure multiplier");
            require(lifted[195*4]-lifted[170*4]>.02f,"Highlight detail collapsed");
            System.out.println("PASS: interior exposure gain >=1, protected highlight ramp and shadow lift; outdoor transition, void exclusion, dark geometry and empty/missing depth");
        } catch(Throwable failure) {failure.printStackTrace(); exit = 1;}
        finally {window.dispose(); toolkit.dispose();}
        System.exit(exit);
    }
}
