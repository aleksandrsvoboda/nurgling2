package haven.resutil;

import haven.*;
import haven.render.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

/** Decode and convert the reflection cubemap away from the render thread. */
public final class WaterSky {
    private static final int[][] ORDER = {{3,1},{1,1},{2,0},{2,2},{2,1},{0,1}};
    private volatile TextureCube.SamplerCube ready;
    private final TextureCube.SamplerCube fallback;

    static final WaterSky shared = new WaterSky(() ->
        Resource.local().load("gfx/tiles/skycube").get().layer(Resource.imgc).img);

    WaterSky(Supplier<BufferedImage> source) {
        byte[][] tiny = new byte[6][];
        for(int i = 0; i < tiny.length; i++) tiny[i] = new byte[]{90,116,(byte)140,(byte)255};
        fallback = new TextureCube.SamplerCube(texture(1, 1, tiny));
        Thread prepare = new Thread(() -> {
            try {
                BufferedImage image = Loading.waitfor(source::get);
                int w = image.getWidth() / 4, h = image.getHeight() / 3;
                ready = new TextureCube.SamplerCube(texture(w, h, faces(image)));
            } catch(RuntimeException e) {
                new Warning(e, "could not prepare water reflection sky").issue();
            }
        }, "water-sky-prepare");
        prepare.setDaemon(true);
        prepare.start();
    }

    TextureCube.SamplerCube get() {
        TextureCube.SamplerCube sky = ready;
        return sky == null ? fallback : sky;
    }

    static byte[][] faces(BufferedImage image) {
        int w = image.getWidth() / 4, h = image.getHeight() / 3;
        if(w == 0 || h == 0 || image.getWidth() != w * 4 || image.getHeight() != h * 3)
            throw new IllegalArgumentException("water sky must be a 4 by 3 cubemap cross");
        byte[][] faces = new byte[6][];
        for(int i = 0; i < faces.length; i++) {
            // Crop before Java2D color conversion, avoiding conversion of the full
            // cross six times on pipelines that convert the entire source surface.
            BufferedImage face = image.getSubimage(ORDER[i][0] * w, ORDER[i][1] * h, w, h);
            faces[i] = TexI.convert(face, new Coord(w, h));
        }
        return faces;
    }

    static TextureCube texture(int w, int h, byte[][] faces) {
        return new TextureCube(w, h, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8),
            (image, env) -> {
                if(image.level != 0) return null;
                FillBuffer buffer = env.fillbuf(image);
                buffer.pull(ByteBuffer.wrap(faces[((TextureCube.CubeImage)image).face.ordinal()]));
                return buffer;
            });
        // Immutable pixels stay available for GL/Vulkan context recreation; filling
        // a GPU texture never waits for decoding or invokes Java2D.
    }
}
