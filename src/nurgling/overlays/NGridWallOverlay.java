package nurgling.overlays;

import haven.*;
import haven.render.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Renders short colored translucent walls along the boundary of a single 100x100 map grid.
 * Walls follow terrain heights along each edge. Geometry is built once via tryBuild()
 * and the overlay is intended to be added to MapView.basic.
 */
public class NGridWallOverlay implements RenderTree.Node, Rendered {
    private static final VertexArray.Layout LAYOUT = new VertexArray.Layout(
            new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 12));

    private static final float Z_OFFSET = 0.2f;
    private static final float WALL_HEIGHT = 6f;
    private static final int SAMPLES_PER_EDGE = 25;

    private final Coord gridCoord;
    private final Pipe.Op state;
    public final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
    private Model model;
    private boolean built = false;

    public NGridWallOverlay(Coord gridCoord, Color color) {
        this.gridCoord = gridCoord;
        this.state = Pipe.Op.compose(
                Rendered.postpfx,
                new BaseColor(color),
                new States.Facecull(States.Facecull.Mode.NONE),
                Clickable.No
        );
    }

    /**
     * Attempt to build wall geometry against the supplied map.
     * Returns false if terrain data is not yet available, in which case the caller
     * should retry on a later tick.
     */
    public boolean tryBuild(MCache map) {
        if (built) return true;
        try {
            float gridSizeX = (float) (MCache.cmaps.x * MCache.tilesz.x);
            float gridSizeY = (float) (MCache.cmaps.y * MCache.tilesz.y);
            float x0 = gridCoord.x * gridSizeX;
            float y0 = gridCoord.y * gridSizeY;
            float x1 = x0 + gridSizeX;
            float y1 = y0 + gridSizeY;

            // Probe corners and center; if any throws Loading we'll fall through to catch.
            map.getcz(x0, y0);
            map.getcz(x1, y0);
            map.getcz(x1, y1);
            map.getcz(x0, y1);

            ArrayList<Float> data = new ArrayList<>();
            addWallEdge(data, map, x0, y0, x1, y0, SAMPLES_PER_EDGE);
            addWallEdge(data, map, x1, y0, x1, y1, SAMPLES_PER_EDGE);
            addWallEdge(data, map, x1, y1, x0, y1, SAMPLES_PER_EDGE);
            addWallEdge(data, map, x0, y1, x0, y0, SAMPLES_PER_EDGE);

            float[] arr = new float[data.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = data.get(i);

            VertexArray.Buffer vbo = new VertexArray.Buffer(arr.length * 4,
                    DataBuffer.Usage.STATIC, DataBuffer.Filler.of(arr));
            VertexArray va = new VertexArray(LAYOUT, vbo);
            model = new Model(Model.Mode.TRIANGLES, va, null);
            built = true;
            return true;
        } catch (Loading e) {
            return false;
        }
    }

    private void addWallEdge(ArrayList<Float> data, MCache map, float ax, float ay, float bx, float by, int samples) {
        float prevX = ax;
        float prevY = ay;
        float prevZ = getTerrainZ(map, ax, ay);
        for (int i = 1; i <= samples; i++) {
            float t = (float) i / samples;
            float px = ax + (bx - ax) * t;
            float py = ay + (by - ay) * t;
            float pz = getTerrainZ(map, px, py);
            float prevTop = prevZ + WALL_HEIGHT;
            float curTop = pz + WALL_HEIGHT;
            // Two triangles forming a wall quad between prev and current sample
            data.add(prevX); data.add(-prevY); data.add(prevZ);
            data.add(px);    data.add(-py);   data.add(pz);
            data.add(px);    data.add(-py);   data.add(curTop);
            data.add(prevX); data.add(-prevY); data.add(prevZ);
            data.add(px);    data.add(-py);   data.add(curTop);
            data.add(prevX); data.add(-prevY); data.add(prevTop);
            prevX = px;
            prevY = py;
            prevZ = pz;
        }
    }

    private float getTerrainZ(MCache map, float x, float y) {
        try {
            return (float) map.getcz(x, y) + Z_OFFSET;
        } catch (Loading e) {
            return Z_OFFSET;
        }
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
        if (model != null) {
            out.draw(context, model);
        }
    }
}
