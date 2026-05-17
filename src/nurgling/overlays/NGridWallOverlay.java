package nurgling.overlays;

import haven.*;
import haven.render.*;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Renders short colored walls along the boundary of a single 100x100 map grid.
 * Each wall is a single vertical plane that follows the terrain along its length.
 * Wall color fades vertically from a saturated bottom to fully transparent at the
 * top via per-vertex alpha.
 *
 * Geometry is built once via {@link #tryBuild(MCache)} once terrain heights are
 * available for the grid corners; the caller should retry on subsequent ticks if
 * the first attempt returns false.
 */
public class NGridWallOverlay implements RenderTree.Node, Rendered {
    // Position (3 floats = 12 bytes) + RGBA color (4 floats = 16 bytes) = 28 bytes per vertex
    private static final int STRIDE = 28;
    private static final VertexArray.Layout LAYOUT = new VertexArray.Layout(
            new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, STRIDE),
            new VertexArray.Layout.Input(VertexColor.color, new VectorFormat(4, NumberFormat.FLOAT32), 0, 12, STRIDE));

    private static final float Z_OFFSET = 0.3f;
    private static final float WALL_HEIGHT = 6f;
    private static final int SAMPLES_PER_EDGE = 50;

    // Color at base of wall (mostly opaque orange) and at top (fully transparent)
    private static final float[] BOTTOM_COLOR = {1.0f, 0.55f, 0.0f, 0.85f};
    private static final float[] TOP_COLOR    = {1.0f, 0.55f, 0.0f, 0.0f};

    private final Coord gridCoord;
    private final Pipe.Op state;
    public final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
    private Model model;
    private boolean built = false;

    public NGridWallOverlay(Coord gridCoord) {
        this.gridCoord = gridCoord;
        this.state = Pipe.Op.compose(
                Rendered.postpfx,
                VertexColor.instance,
                new States.Facecull(States.Facecull.Mode.NONE),
                Clickable.No
        );
    }

    /**
     * Attempts to build wall geometry against the supplied map. Returns false if
     * terrain data for the grid's corners isn't loaded yet so the caller can retry.
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

            // Probe corners; if any throw Loading we'll bail and retry later.
            map.getcz(x0, y0);
            map.getcz(x1, y0);
            map.getcz(x1, y1);
            map.getcz(x0, y1);

            ArrayList<Float> data = new ArrayList<>();
            addEdgeWall(data, map, x0, y0, x1, y0);
            addEdgeWall(data, map, x1, y0, x1, y1);
            addEdgeWall(data, map, x1, y1, x0, y1);
            addEdgeWall(data, map, x0, y1, x0, y0);

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

    private void addEdgeWall(ArrayList<Float> data, MCache map, float ax, float ay, float bx, float by) {
        float prevX = ax;
        float prevY = ay;
        float prevZ = getTerrainZ(map, ax, ay);
        for (int i = 1; i <= SAMPLES_PER_EDGE; i++) {
            float t = (float) i / SAMPLES_PER_EDGE;
            float px = ax + (bx - ax) * t;
            float py = ay + (by - ay) * t;
            float pz = getTerrainZ(map, px, py);
            float prevTop = prevZ + WALL_HEIGHT;
            float curTop  = pz + WALL_HEIGHT;
            emitQuad(data,
                    prevX, prevY, prevZ,    BOTTOM_COLOR,
                    px,    py,    pz,       BOTTOM_COLOR,
                    px,    py,    curTop,   TOP_COLOR,
                    prevX, prevY, prevTop,  TOP_COLOR);
            prevX = px;
            prevY = py;
            prevZ = pz;
        }
    }

    private static void emitQuad(ArrayList<Float> data,
                                 float ax, float ay, float az, float[] aCol,
                                 float bx, float by, float bz, float[] bCol,
                                 float cx, float cy, float cz, float[] cCol,
                                 float dx, float dy, float dz, float[] dCol) {
        emitVertex(data, ax, ay, az, aCol);
        emitVertex(data, bx, by, bz, bCol);
        emitVertex(data, cx, cy, cz, cCol);
        emitVertex(data, ax, ay, az, aCol);
        emitVertex(data, cx, cy, cz, cCol);
        emitVertex(data, dx, dy, dz, dCol);
    }

    private static void emitVertex(ArrayList<Float> data, float x, float y, float z, float[] color) {
        // Render uses y-flipped world coordinates
        data.add(x);
        data.add(-y);
        data.add(z);
        data.add(color[0]);
        data.add(color[1]);
        data.add(color[2]);
        data.add(color[3]);
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
