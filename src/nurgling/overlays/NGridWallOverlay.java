package nurgling.overlays;

import haven.*;
import haven.render.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders short colored walls along the boundaries of all currently loaded
 * 100x100 map grids. The mesh combines every visible grid into a single
 * Model with one draw call, deduplicating shared edges between adjacent grids,
 * so cost stays roughly constant in the number of distinct boundaries rather
 * than scaling with the number of grids.
 *
 * Walls fade vertically from the selected color (saturated at the base) to
 * fully transparent at the top via per-vertex alpha.
 */
public class NGridWallOverlay implements RenderTree.Node, Rendered {
    // Position (3 floats = 12 bytes) + RGBA color (4 floats = 16 bytes) = 28 bytes per vertex
    private static final int STRIDE = 28;
    private static final int FLOATS_PER_VERT = 7;
    private static final int VERTS_PER_SEGMENT = 6; // two triangles per quad
    private static final VertexArray.Layout LAYOUT = new VertexArray.Layout(
            new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, STRIDE),
            new VertexArray.Layout.Input(VertexColor.color, new VectorFormat(4, NumberFormat.FLOAT32), 0, 12, STRIDE));

    private static final float Z_OFFSET = 0.3f;
    private static final float WALL_HEIGHT = 6f;
    private static final int SAMPLES_PER_EDGE = 30;

    private final Pipe.Op state;
    public final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
    private volatile Model model;

    public NGridWallOverlay() {
        this.state = Pipe.Op.compose(
                Rendered.postpfx,
                VertexColor.instance,
                new States.Facecull(States.Facecull.Mode.NONE),
                States.maskdepth,
                Clickable.No
        );
    }

    /**
     * Rebuilds the combined wall mesh for the supplied set of loaded grid coords
     * with the supplied wall color. Returns false if terrain data isn't loaded
     * yet at any of the grid corners — caller should retry on a later tick.
     */
    /**
     * Returns true if the four corners of the given grid have loaded terrain
     * heights — i.e. this grid is ready to contribute to the wall mesh.
     * Callers should filter their grid set with this before passing to
     * {@link #rebuild}.
     */
    public static boolean cornersReady(MCache map, Coord gc) {
        float gridSizeX = (float) (MCache.cmaps.x * MCache.tilesz.x);
        float gridSizeY = (float) (MCache.cmaps.y * MCache.tilesz.y);
        float x0 = gc.x * gridSizeX;
        float y0 = gc.y * gridSizeY;
        float x1 = x0 + gridSizeX;
        float y1 = y0 + gridSizeY;
        try {
            map.getcz(x0, y0);
            map.getcz(x1, y0);
            map.getcz(x1, y1);
            map.getcz(x0, y1);
            return true;
        } catch (Loading l) {
            return false;
        }
    }

    /**
     * Builds the combined wall mesh for the supplied grid coords with the
     * supplied wall color. Caller is expected to have filtered gridCoords to
     * grids whose corners are loaded (via {@link #cornersReady}); interior
     * samples that fail Loading fall back to a default height and do not
     * abort the build.
     */
    public void rebuild(MCache map, Set<Coord> gridCoords, Color color) {
        if (gridCoords.isEmpty()) {
            this.model = null;
            return;
        }

        float r = color.getRed()   / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue()  / 255f;
        float baseA = color.getAlpha() / 255f;
        float[] bottomCol = {r, g, b, baseA};
        float[] topCol    = {r, g, b, 0f};

        // Collect unique edges. Horizontal edges run along x at a fixed grid-y;
        // vertical edges run along y at a fixed grid-x. Each edge spans a single
        // grid in its perpendicular axis.
        Set<Long> seenH = new HashSet<>();
        Set<Long> seenV = new HashSet<>();
        List<int[]> hEdges = new ArrayList<>(gridCoords.size() * 2);
        List<int[]> vEdges = new ArrayList<>(gridCoords.size() * 2);
        for (Coord gc : gridCoords) {
            if (seenH.add(packEdge(gc.x, gc.y)))         hEdges.add(new int[]{gc.x, gc.y});
            if (seenH.add(packEdge(gc.x, gc.y + 1)))     hEdges.add(new int[]{gc.x, gc.y + 1});
            if (seenV.add(packEdge(gc.x, gc.y)))         vEdges.add(new int[]{gc.x, gc.y});
            if (seenV.add(packEdge(gc.x + 1, gc.y)))     vEdges.add(new int[]{gc.x + 1, gc.y});
        }

        int totalEdges = hEdges.size() + vEdges.size();
        int floatsPerEdge = SAMPLES_PER_EDGE * VERTS_PER_SEGMENT * FLOATS_PER_VERT;
        float[] data = new float[totalEdges * floatsPerEdge];

        float gridSizeX = (float) (MCache.cmaps.x * MCache.tilesz.x);
        float gridSizeY = (float) (MCache.cmaps.y * MCache.tilesz.y);

        int[] idx = {0};
        for (int[] e : hEdges) {
            float ax = e[0] * gridSizeX;
            float ay = e[1] * gridSizeY;
            float bx = ax + gridSizeX;
            float by = ay;
            addEdgeWall(data, idx, map, ax, ay, bx, by, bottomCol, topCol);
        }
        for (int[] e : vEdges) {
            float ax = e[0] * gridSizeX;
            float ay = e[1] * gridSizeY;
            float bx = ax;
            float by = ay + gridSizeY;
            addEdgeWall(data, idx, map, ax, ay, bx, by, bottomCol, topCol);
        }

        VertexArray.Buffer vbo = new VertexArray.Buffer(data.length * 4,
                DataBuffer.Usage.STATIC, DataBuffer.Filler.of(data));
        VertexArray va = new VertexArray(LAYOUT, vbo);
        this.model = new Model(Model.Mode.TRIANGLES, va, null);
    }

    private static long packEdge(int x, int y) {
        return (((long) x) << 32) | (((long) y) & 0xFFFFFFFFL);
    }

    private void addEdgeWall(float[] data, int[] idx, MCache map,
                             float ax, float ay, float bx, float by,
                             float[] bottomCol, float[] topCol) {
        float prevX = ax;
        float prevY = ay;
        float prevZ = terrainZ(map, ax, ay);
        for (int i = 1; i <= SAMPLES_PER_EDGE; i++) {
            float t = (float) i / SAMPLES_PER_EDGE;
            float px = ax + (bx - ax) * t;
            float py = ay + (by - ay) * t;
            float pz = terrainZ(map, px, py);
            float prevTop = prevZ + WALL_HEIGHT;
            float curTop  = pz + WALL_HEIGHT;
            // Quad as two triangles: (prevBot, curBot, curTop), (prevBot, curTop, prevTop)
            writeVert(data, idx, prevX, prevY, prevZ, bottomCol);
            writeVert(data, idx, px,    py,    pz,    bottomCol);
            writeVert(data, idx, px,    py,    curTop, topCol);
            writeVert(data, idx, prevX, prevY, prevZ, bottomCol);
            writeVert(data, idx, px,    py,    curTop, topCol);
            writeVert(data, idx, prevX, prevY, prevTop, topCol);
            prevX = px;
            prevY = py;
            prevZ = pz;
        }
    }

    private static void writeVert(float[] data, int[] idx, float x, float y, float z, float[] color) {
        int i = idx[0];
        data[i++] = x;
        data[i++] = -y;
        data[i++] = z;
        data[i++] = color[0];
        data[i++] = color[1];
        data[i++] = color[2];
        data[i++] = color[3];
        idx[0] = i;
    }

    private float terrainZ(MCache map, float x, float y) {
        try {
            return (float) map.getcz(x, y) + Z_OFFSET;
        } catch (Loading l) {
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
        Model m = this.model;
        if (m != null) {
            out.draw(context, m);
        }
    }
}
