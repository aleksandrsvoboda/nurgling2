package nurgling.sessions.squad;

import haven.*;
import haven.render.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

/**
 * The ground ring under a squad character in the viewpoint. Same quad-on-the-ground technique as NKinRing, with the
 * texture drawn here so each look is one shared texture.
 */
public class SquadRing extends Sprite implements RenderTree.Node {
    public enum Kind { SELECTED, DIM, MEMBER, COMBAT }

    static final VertexArray.Layout pfmt = new VertexArray.Layout(
            new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 20),
            new VertexArray.Layout.Input(Tex2D.texc, new VectorFormat(2, NumberFormat.FLOAT32), 0, 12, 20));
    private static final float RADIUS = (float) (0.8 * MCache.tilesz.x);
    private static final Map<Kind, ColorTex> textures = new EnumMap<>(Kind.class);

    public final Kind kind;
    private final Model emod;

    public SquadRing(Owner owner, Kind kind) {
        super(owner, null);
        this.kind = kind;
        float r = RADIUS;
        float[] data = {
                r, r, 0.5f, 1, 1,
                -r, r, 0.5f, 0, 1,
                -r, -r, 0.5f, 0, 0,
                r, -r, 0.5f, 1, 0,
        };
        VertexArray va = new VertexArray(pfmt, new VertexArray.Buffer(4 * pfmt.inputs[0].stride, DataBuffer.Usage.STATIC,
                                                                      DataBuffer.Filler.of(data)));
        this.emod = new Model(Model.Mode.TRIANGLE_FAN, va, null);
    }

    private static synchronized ColorTex texture(Kind k) {
        ColorTex t = textures.get(k);
        if (t == null) {
            t = new TexI(draw(k)).st();
            textures.put(k, t);
        }
        return t;
    }

    private static BufferedImage draw(Kind k) {
        int sz = 128;
        BufferedImage img = TexI.mkbuf(new Coord(sz, sz));
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color c;
        float w = 10;
        switch (k) {
            case SELECTED: c = new Color(0xE9, 0x9C, 0x54, 255); break;
            case DIM: c = new Color(0xE9, 0x9C, 0x54, 110); w = 6; break;
            case COMBAT: c = new Color(0xFF, 0x64, 0x64, 255); break;
            default: c = new Color(0x5F, 0xD3, 0xC6, 200); w = 5; break;
        }
        if (k == Kind.MEMBER)
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{14, 10}, 0));
        else
            g.setStroke(new BasicStroke(w));
        g.setColor(new Color(0, 0, 0, c.getAlpha() / 2));
        g.drawOval((int) (w / 2) + 4, (int) (w / 2) + 4, sz - (int) w - 8, sz - (int) w - 8);
        g.setColor(c);
        g.drawOval((int) (w / 2) + 2, (int) (w / 2) + 2, sz - (int) w - 4, sz - (int) w - 4);
        g.dispose();
        return img;
    }

    @Override
    public void added(RenderTree.Slot slot) {
        Pipe.Op rmat = Pipe.Op.compose(new Rendered.Order.Default(-100), new States.Depthtest(States.Depthtest.Test.LE),
                States.maskdepth,
                FragColor.blend(new BlendMode(BlendMode.Function.ADD, BlendMode.Factor.SRC_ALPHA, BlendMode.Factor.INV_SRC_ALPHA,
                                              BlendMode.Function.ADD, BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA)),
                texture(kind), Clickable.No, Rendered.postpfx);
        slot.add(emod, rmat);
    }

    @Override
    public boolean tick(double dt) {
        return false;
    }
}
