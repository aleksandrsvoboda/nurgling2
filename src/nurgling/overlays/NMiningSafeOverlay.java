package nurgling.overlays;

import haven.*;
import haven.render.*;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Green dot on a tile the minesweeper solver has deduced is safe to mine.
 */
public class NMiningSafeOverlay extends Sprite implements RenderTree.Node {

    static final VertexArray.Layout pfmt = new VertexArray.Layout(
            new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 20),
            new VertexArray.Layout.Input(Tex2D.texc, new VectorFormat(2, NumberFormat.FLOAT32), 0, 12, 20)
    );

    private static TexI dotTex = null;

    final Model emod;
    ColorTex ct;

    private static TexI createDotTexture() {
        int size = UI.scale(64);
        BufferedImage img = TexI.mkbuf(new Coord(size, size));
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 220, 40, 255));
        int pad = UI.scale(8);
        g.fillOval(pad, pad, size - pad * 2, size - pad * 2);
        g.dispose();
        return new TexI(img);
    }

    public NMiningSafeOverlay(Owner owner) {
        super(owner, null);
        if (dotTex == null) {
            dotTex = createDotTexture();
        }
        ct = dotTex.st();
        float h = 0.22f * (float) MCache.tilesz.x;
        float[] data = {
                h, h, 1f, 1, 1,
                -h, h, 1f, 1, 0,
                -h, -h, 1f, 0, 0,
                h, -h, 1f, 0, 1,
        };
        VertexArray va = new VertexArray(pfmt,
                new VertexArray.Buffer(4 * pfmt.inputs[0].stride, DataBuffer.Usage.STATIC,
                        DataBuffer.Filler.of(data)));
        this.emod = new Model(Model.Mode.TRIANGLE_FAN, va, null);
    }

    public void added(RenderTree.Slot slot) {
        Pipe.Op rmat = Pipe.Op.compose(ct, Clickable.No, Rendered.postpfx, States.Depthtest.none);
        slot.add(emod, rmat);
    }

    @Override
    public boolean tick(double dt) {
        return false;
    }
}
