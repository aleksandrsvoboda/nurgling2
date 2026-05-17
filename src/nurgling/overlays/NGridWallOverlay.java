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
 *
 * Each unique edge is "owned" by the first grid in iteration order to claim
 * it. Terrain Z is sampled inside that owner grid (clamped by INSET) so the
 * frontier of the loaded map renders correctly without ever depending on
 * neighboring grid data.
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
    // Shift sample lookups by this many world units inside the owner grid to
    // avoid getcz reading tiles from the +x or +y neighbor grid at edges.
    private static final float INSET = 0.01f;

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
     * Returns true if the given grid's own tile data is loaded — checked via a
     * probe at an interior point so the answer doesn't depend on neighbor grids.
     */
    public static boolean isReady(MCache map, Coord gc) {
        float gridSizeX = (float) (MCache.cmaps.x * MCache.tilesz.x);
        float gridSizeY = (float) (MCache.cmaps.y * MCache.tilesz.y);
        try {
            map.getcz(gc.x * gridSizeX + INSET, gc.y * gridSizeY + INSET);
            return true;
        } catch (Loading l) {
            return false;
        }
    }

    /**
     * Builds the combined wall mesh for the supplied grid coords with the
     * supplied wall color. Caller should pre-filter to grids passing
     * {@link #isReady}.
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

        // Collect unique edges. Each edge stores [edgeX, edgeY, ownerX, ownerY]
        // where edgeX/edgeY are the edge's grid-axis coords and ownerX/ownerY
        // identify the loaded grid whose tiles we'll sample for Z.
        Set<Long> seenH = new HashSet<>();
        Set<Long> seenV = new HashSet<>();
        List<int[]> hEdges = new ArrayList<>(gridCoords.size() * 2);
        List<int[]> vEdges = new ArrayList<>(gridCoords.size() * 2);
        for (Coord gc : gridCoords) {
            if (seenH.add(packEdge(gc.x, gc.y)))         hEdges.add(new int[]{gc.x, gc.y,     gc.x, gc.y});
            if (seenH.add(packEdge(gc.x, gc.y + 1)))     hEdges.add(new int[]{gc.x, gc.y + 1, gc.x, gc.y});
            if (seenV.add(packEdge(gc.x, gc.y)))         vEdges.add(new int[]{gc.x,     gc.y, gc.x, gc.y});
            if (seenV.add(packEdge(gc.x + 1, gc.y)))     vEdges.add(new int[]{gc.x + 1, gc.y, gc.x, gc.y});
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
            addEdgeWall(data, idx, map, ax, ay, bx, by, e[2], e[3], gridSizeX, gridSizeY, bottomCol, topCol);
        }
        for (int[] e : vEdges) {
            float ax = e[0] * gridSizeX;
            float ay = e[1] * gridSizeY;
            float bx = ax;
            float by = ay + gridSizeY;
            addEdgeWall(data, idx, map, ax, ay, bx, by, e[2], e[3], gridSizeX, gridSizeY, bottomCol, topCol);
        }

        VertexArray.Buffer vbo = new VertexArray.Buffer(data.length * 4,
                DataBuffer.Usage.STATIC, DataBuffer.Filler.of(data));
        VertexArray va = new VertexArray(LAYOUT, vbo);
        this.model = new Model(Model.Mode.TRIANGLES, va, null);
        notifySlots();
    }

    private void notifySlots() {
        Collection<RenderTree.Slot> tslots;
        synchronized (slots) {
            tslots = new ArrayList<>(slots);
        }
        for (RenderTree.Slot s : tslots) {
            try {
                s.update();
            } catch (Exception ignored) {
            }
        }
    }

    private static long packEdge(int x, int y) {
        return (((long) x) << 32) | (((long) y) & 0xFFFFFFFFL);
    }

    private void addEdgeWall(float[] data, int[] idx, MCache map,
                             float ax, float ay, float bx, float by,
                             int ownerX, int ownerY,
                             float gridSizeX, float gridSizeY,
                             float[] bottomCol, float[] topCol) {
        // Clamp the Z sample point to stay strictly inside the owner grid, so
        // getcz never reads neighbor tiles. Vertex positions still sit on the
        // natural edge so adjacent walls meet at corners.
        float sxMin = ownerX * gridSizeX;
        float sxMax = (ownerX + 1) * gridSizeX - INSET;
        float syMin = ownerY * gridSizeY;
        float syMax = (ownerY + 1) * gridSizeY - INSET;

        float prevX = ax;
        float prevY = ay;
        float prevZ = terrainZ(map, clamp(ax, sxMin, sxMax), clamp(ay, syMin, syMax));
        for (int i = 1; i <= SAMPLES_PER_EDGE; i++) {
            float t = (float) i / SAMPLES_PER_EDGE;
            float px = ax + (bx - ax) * t;
            float py = ay + (by - ay) * t;
            float pz = terrainZ(map, clamp(px, sxMin, sxMax), clamp(py, syMin, syMax));
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

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
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
