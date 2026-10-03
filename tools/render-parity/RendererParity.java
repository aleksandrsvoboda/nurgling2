package haven.render;

import haven.*;
import haven.iosys.tk.Toolkit;
import haven.iosys.tk.Windeye;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

/** Offline GPU comparison. No game server, account or mutable scene is used. */
public class RendererParity {
    private static final int SIZE = 128;
    private static final VectorFormat RGBA = new VectorFormat(4, NumberFormat.UNORM8);
    private static final String[] CASES = {
        "color", "alpha", "additive", "texture", "minification", "minification-linear", "magnification",
        "mipmaps", "srgb", "rgb-upload", "phong", "cel", "culling", "depth-sample", "normals-sample",
        "normals-readback", "scissor"
    };
    private static final VertexArray.Layout QUAD = new VertexArray.Layout(
        new VertexArray.Layout.Input(Ortho2D.pos, new VectorFormat(2, NumberFormat.FLOAT32), 0, 0, 16),
        new VertexArray.Layout.Input(ColorTex.texc, new VectorFormat(2, NumberFormat.FLOAT32), 0, 8, 16));
    private static final VertexArray.Layout SOLID = new VertexArray.Layout(
        new VertexArray.Layout.Input(Ortho2D.pos, new VectorFormat(2, NumberFormat.FLOAT32), 0, 0, 16));

    private static void quad(Render out, Pipe pipe, float start, float end, float uv) {
        out.draw(pipe, Model.Mode.TRIANGLES, null, pipe.get(ColorTex.slot) == null ? SOLID : QUAD, 6, new float[] {
            start,start,0,0, end,start,uv,0, end,end,uv,uv,
            start,start,0,0, end,end,uv,uv, start,end,0,uv
        });
    }

    private static Texture2D texture(boolean mips, boolean srgb, boolean rgb) {
        Texture2D tex = new Texture2D(256, 256, DataBuffer.Usage.STATIC,
            rgb ? new VectorFormat(3, NumberFormat.UNORM8) : RGBA, RGBA, (img, env) -> {
            if(!mips && img.level != 0)
                return(null);
            FillBuffer fill = env.fillbuf(img);
            ByteBuffer data = fill.push();
            for(int y = 0; y < img.h; y++) {
                for(int x = 0; x < img.w; x++) {
                    int c = (((x / 3) ^ (y / 3)) & 1) != 0 ? 220 : 40;
                    data.put((byte)c).put((byte)(x * 255 / img.w)).put((byte)(y * 255 / img.h)).put((byte)(rgb ? 37 : 255));
                }
            }
            return(fill);
        });
        tex.srgb = srgb;
        return(tex);
    }

    private static void sphere(Render out, Pipe base, boolean cel) {
        Pipe p = base.copy();
        p.put(States.vxf, null);
        p.prep(new States.Depthtest()).prep(Projection.ortho(-1.2f, 1.2f, -1.2f, 1.2f, 0.1f, 10));
        p.prep(new Light.PhongLight(true, new FColor(.3f,.2f,.1f), new FColor(.7f,.5f,.3f),
                                   new FColor(.2f,.2f,.2f), FColor.BLACK, 12));
        p.prep(new Lighting.SimpleLights(new Object[][] {
            {new float[]{.3f,.3f,.4f,1}, new float[]{.7f,.6f,.5f,1}, new float[]{1,1,1,1},
             new float[]{.4f,.5f,1,0}, 0f,0f,0f,0f},
            {new float[]{0,0,0,1}, new float[]{.1f,.2f,.4f,1}, new float[]{0,0,0,1},
             new float[]{-2,1,-1,1}, 1f,.1f,.05f,0f}
        }));
        if(cel)
            p.prep(Light.celshade);
        VertexArray.Layout layout = new VertexArray.Layout(
            new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 24),
            new VertexArray.Layout.Input(Homo3D.normal, new VectorFormat(3, NumberFormat.FLOAT32), 0, 12, 24));
        int rings = 24, segments = 48, at = 0;
        float[] vertices = new float[rings * segments * 6 * 6];
        int[][] corners = {{0,0},{1,0},{1,1},{0,0},{1,1},{0,1}};
        for(int y = 0; y < rings; y++) {
            for(int x = 0; x < segments; x++) {
                for(int[] c : corners) {
                    double a = (x + c[0]) * Math.PI * 2 / segments;
                    double b = (y + c[1]) * Math.PI / rings;
                    float nx = (float)(Math.sin(b) * Math.cos(a));
                    float ny = (float)(Math.sin(b) * Math.sin(a));
                    float nz = (float)Math.cos(b);
                    vertices[at++] = nx; vertices[at++] = ny; vertices[at++] = nz - 3;
                    vertices[at++] = nx; vertices[at++] = ny; vertices[at++] = nz;
                }
            }
        }
        out.draw(p, Model.Mode.TRIANGLES, null, layout, vertices.length / 6, vertices);
    }

    private static byte[] capture(Windeye window, String name) throws Exception {
        Environment env = window.env();
        Texture2D color = new Texture2D(SIZE, SIZE, DataBuffer.Usage.STATIC, RGBA, null);
        Texture2D depth = new Texture2D(SIZE, SIZE, DataBuffer.Usage.STATIC, Texture.DEPTH,
                                        new VectorFormat(1, NumberFormat.FLOAT32), null);
        Texture2D sampled = null;
        Texture2D normals = null;
        try {
            Pipe p = new BufPipe().prep(new FragColor<>(color.image(0))).prep(new DepthBuffer<>(depth.image(0)))
                .prep(new States.Viewport(Area.sized(Coord.of(SIZE,SIZE)))).prep(new Ortho2D(0,0,SIZE,SIZE));
            Render out = env.render();
            out.clear(p, FragColor.fragcol, new FColor(.12f,.16f,.2f,1));
            out.clear(p, 1);
            if(name.equals("phong") || name.equals("cel") || name.equals("culling") ||
               name.equals("depth-sample") || name.startsWith("normals-")) {
                Pipe scene = p.copy();
                if(name.equals("culling")) scene.prep(new States.Facecull());
                if(name.startsWith("normals-")) {
                    normals = new Texture2D(SIZE, SIZE, DataBuffer.Usage.STATIC,
                                            new VectorFormat(3, NumberFormat.SNORM8), null);
                    scene.prep(new RenderedNormals(normals.image(0)));
                    out.clear(new BufPipe().prep(new FragColor<>(normals.image(0))), FragColor.fragcol, new FColor(0,0,-1));
                }
                sphere(out, scene, name.equals("cel"));
                if(name.endsWith("-sample")) {
                    Pipe sample = p.copy();
                    sample.put(DepthBuffer.slot, null);
                    sample.prep(new ColorTex((normals == null ? depth : normals).sampler()));
                    quad(out, sample, 0, SIZE, 1);
                }
            } else if(name.equals("scissor")) {
                p.prep(new States.Scissor(Area.sized(Coord.of(19,37), Coord.of(45,63))));
                quad(out, p.prep(new BaseColor(.8f,.4f,.1f,1f)), 0, SIZE, 1);
            } else if(name.equals("color") || name.equals("alpha") || name.equals("additive")) {
                quad(out, p.copy().prep(new BaseColor(.6f,.2f,.3f,1f)), 8, 96, 1);
                Pipe q = p.copy().prep(new BaseColor(.1f,.9f,.6f,.4f));
                if(!name.equals("color"))
                    q.prep(FragColor.blend(name.equals("alpha") ? new BlendMode() :
                        new BlendMode(BlendMode.Factor.SRC_ALPHA, BlendMode.Factor.ONE)));
                quad(out, q, 32, 120, 1);
            } else {
                sampled = texture(name.equals("mipmaps"), name.equals("srgb"), name.equals("rgb-upload"));
                Texture2D.Sampler2D smp = sampled.sampler();
                smp.minfilter(name.equals("minification") ? Texture.Filter.NEAREST : Texture.Filter.LINEAR);
                smp.magfilter(name.equals("minification-linear") || name.equals("magnification") ?
                              Texture.Filter.NEAREST : Texture.Filter.LINEAR).wrapmode(Texture.Wrapping.REPEAT);
                if(name.equals("mipmaps"))
                    smp.mipfilter(Texture.Filter.LINEAR);
                quad(out, p.copy().prep(new ColorTex(smp)), 8, 120, name.equals("magnification") ? .17f : 1.37f);
            }
            CompletableFuture<byte[]> result = new CompletableFuture<>();
            out.pget((name.equals("normals-readback") ? normals : color).image(0), RGBA, bytes -> {
                byte[] copy = new byte[SIZE * SIZE * 4];
                bytes.get(copy);
                result.complete(copy);
            });
            window.swapbuffers(out, false);
            env.submit(out);
            long deadline = System.nanoTime() + 20_000_000_000L;
            while(!result.isDone() && System.nanoTime() < deadline) {
                Render pump = env.render();
                window.swapbuffers(pump, false);
                env.submit(pump);
                Thread.sleep(10);
            }
            if(!result.isDone())
                throw(new AssertionError("GPU readback timed out: " + name));
            byte[] data = result.get();
            int varied = 0;
            for(int i = 4; i < data.length; i += 4) {
                if(data[i] != data[0] || data[i+1] != data[1] || data[i+2] != data[2]) varied++;
            }
            if(varied < 100)
                throw(new AssertionError("Empty or uniform test image: " + name));
            return(data);
        } finally {
            color.dispose(); depth.dispose();
            if(sampled != null) sampled.dispose();
            if(normals != null) normals.dispose();
        }
    }

    private static BufferedImage image(byte[] data) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for(int y = 0, at = 0; y < SIZE; y++) {
            for(int x = 0; x < SIZE; x++, at += 4) {
                image.setRGB(x, SIZE - 1 - y, ((data[at+3]&255)<<24) | ((data[at]&255)<<16) |
                             ((data[at+1]&255)<<8) | (data[at+2]&255));
            }
        }
        return(image);
    }

    public static void main(String[] args) throws Exception {
        int exit = 0;
        try {
            Path dir = Paths.get(args.length > 0 ? args[0] : "build/render-parity");
            Files.createDirectories(dir);
            byte[][] reference = new byte[CASES.length][];
            for(String backend : new String[]{"jogl", "vulkan"}) {
                Toolkit toolkit = Toolkit.toolkits().get(backend).open();
                Windeye window = toolkit.window();
                try {
                    window.title("Offline renderer parity: " + backend);
                    window.sizing(new Windeye.Sizing().fixsize(Coord.of(SIZE,SIZE))).show(true);
                    System.out.println(backend + ": " + window.env().caps().device());
                    for(int i = 0; i < CASES.length; i++) {
                        byte[] actual = capture(window, CASES[i]);
                        ImageIO.write(image(actual), "png", dir.resolve(backend + "-" + CASES[i] + ".png").toFile());
                        if(backend.equals("jogl")) {
                            reference[i] = actual;
                        } else {
                            int max = 0, bad = 0; long sum = 0;
                            for(int j = 0; j < actual.length; j++) {
                                int delta = Math.abs((actual[j]&255) - (reference[i][j]&255));
                                max = Math.max(max, delta); sum += delta;
                                if(delta > 2) bad++;
                            }
                            System.out.printf("%s: max=%d mean=%.4f channels>2=%d/%d%n", CASES[i], max,
                                              (double)sum/actual.length, bad, actual.length);
                            if(bad > actual.length / 1000) exit = 1;
                        }
                    }
                } finally {
                    window.dispose(); toolkit.dispose();
                }
            }
        } catch(Throwable failure) {
            failure.printStackTrace(); exit = 2;
        }
        System.exit(exit);
    }
}
