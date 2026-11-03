package nurgling.scripting.ui;

import haven.*;
import haven.render.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A script-drawn polyline on the ground (rectangle borders, paths), drawn like the zone measure
 * tool's border: on top of everything, following the terrain height at each tile step.
 * Heights are sampled once when it is built; added to and removed from {@code MapView.basic}.
 */
public class ScriptLines implements RenderTree.Node, Rendered {
    private static final VertexArray.Layout LAYOUT = new VertexArray.Layout(
            new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 12));
    private static final float Z_OFFSET = 0.5f;

    private final Pipe.Op state;
    private final Model model;
    private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);

    /** {@code pts} are world coordinates; {@code closed} joins the last point back to the first. */
    public ScriptLines(MCache map, List<Coord2d> pts, boolean closed, Color color, float width) {
        this.state = Pipe.Op.compose(
                new BaseColor(color),
                new States.LineWidth(width),
                Clickable.No,
                Pipe.Op.compose(Rendered.last, States.Depthtest.none, States.maskdepth));
        List<Coord2d> path = new ArrayList<>(pts);
        if (closed && path.size() > 2) path.add(path.get(0));
        List<float[]> verts = new ArrayList<>();
        double step = MCache.tilesz.x;
        for (int i = 0; i + 1 < path.size(); i++) {
            Coord2d a = path.get(i), b = path.get(i + 1);
            int n = Math.max(1, (int) Math.ceil(a.dist(b) / step));
            for (int k = 0; k < n; k++) {
                Coord2d p = a.add(b.sub(a).mul((double) k / n));
                Coord2d q = a.add(b.sub(a).mul((double) (k + 1) / n));
                verts.add(vert(map, p));
                verts.add(vert(map, q));
            }
        }
        float[] data = new float[verts.size() * 3];
        for (int i = 0; i < verts.size(); i++) System.arraycopy(verts.get(i), 0, data, i * 3, 3);
        if (data.length == 0) {
            model = null;
            return;
        }
        VertexArray.Buffer vbo = new VertexArray.Buffer(data.length * 4, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(data));
        model = new Model(Model.Mode.LINES, new VertexArray(LAYOUT, vbo), null);
    }

    private static float[] vert(MCache map, Coord2d p) {
        float z;
        try {
            z = (float) map.getcz(p.x, p.y) + Z_OFFSET;
        } catch (Loading e) {
            z = Z_OFFSET;
        }
        return new float[]{(float) p.x, (float) -p.y, z};
    }

    @Override
    public void added(RenderTree.Slot slot) {
        slot.ostate(state);
        synchronized (slots) {
            slots.add(slot);
        }
    }

    @Override
    public void removed(RenderTree.Slot slot) {
        synchronized (slots) {
            slots.remove(slot);
        }
    }

    @Override
    public void draw(Pipe context, Render out) {
        if (model != null) out.draw(context, model);
    }
}
