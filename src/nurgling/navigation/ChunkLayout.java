package nurgling.navigation;

import java.util.*;

/**
 * Relative positions of recorded chunks, rebuilt from their persistent neighbour links, so the planner has a real
 * distance estimate even for chunks not seen this session (whose worldTileOrigin is not saved).
 *
 * A component is a set of chunks you can walk between: same layer, same known instance, linked as neighbours -
 * exactly the moves {@link UnifiedTilePathfinder} allows without a portal. Inside a component every chunk gets a
 * grid position; tile (x, y) of a chunk at (gx, gy) sits at (gx * 100 + x, gy * 100 + y). If the links contradict
 * each other (a chunk reached at two positions) the component is marked {@code geometric = false} and the planner
 * plans there without a distance estimate.
 */
final class ChunkLayout {
    final ChunkNavData[] chunks;
    final int[] comp;
    final int[] gx;
    final int[] gy;
    final boolean[] geometric;   // per component
    final int componentCount;
    private final Map<Long, Integer> index;

    ChunkLayout(ChunkNavGraph graph) {
        Collection<ChunkNavData> all = graph.getAllChunks();
        chunks = all.toArray(new ChunkNavData[0]);
        index = new HashMap<>(chunks.length * 2);
        for (int i = 0; i < chunks.length; i++)
            index.put(chunks[i].gridId, i);
        comp = new int[chunks.length];
        gx = new int[chunks.length];
        gy = new int[chunks.length];
        Arrays.fill(comp, -1);

        // Undirected walking links: a link in either direction groups two chunks.
        List<List<int[]>> links = new ArrayList<>(chunks.length);
        for (int i = 0; i < chunks.length; i++) links.add(new ArrayList<>(4));
        for (int i = 0; i < chunks.length; i++) {
            ChunkNavData c = chunks[i];
            link(links, i, c.neighborEast, 1, 0);
            link(links, i, c.neighborWest, -1, 0);
            link(links, i, c.neighborSouth, 0, 1);
            link(links, i, c.neighborNorth, 0, -1);
        }

        List<Boolean> geo = new ArrayList<>();
        int[] queue = new int[chunks.length];
        for (int s = 0; s < chunks.length; s++) {
            if (comp[s] != -1) continue;
            int id = geo.size();
            boolean ok = true;
            int head = 0, tail = 0;
            queue[tail++] = s;
            comp[s] = id;
            gx[s] = 0;
            gy[s] = 0;
            while (head < tail) {
                int cur = queue[head++];
                for (int[] l : links.get(cur)) {
                    int n = l[0];
                    int px = gx[cur] + l[1], py = gy[cur] + l[2];
                    if (comp[n] == -1) {
                        comp[n] = id;
                        gx[n] = px;
                        gy[n] = py;
                        queue[tail++] = n;
                    } else if (gx[n] != px || gy[n] != py) {
                        ok = false;
                    }
                }
            }
            geo.add(ok);
        }
        componentCount = geo.size();
        geometric = new boolean[componentCount];
        for (int i = 0; i < componentCount; i++) geometric[i] = geo.get(i);
    }

    private void link(List<List<int[]>> links, int from, long neighborId, int dx, int dy) {
        if (neighborId == -1) return;
        Integer to = index.get(neighborId);
        if (to == null || to == from) return;
        if (!walkable(chunks[from], chunks[to])) return;
        links.get(from).add(new int[] {to, dx, dy});
        links.get(to).add(new int[] {from, -dx, -dy});
    }

    /** The same rule as UnifiedTilePathfinder's edge crossing. */
    static boolean walkable(ChunkNavData a, ChunkNavData b) {
        if (!Objects.equals(a.layer, b.layer)) return false;
        if (a.instanceId == 0 || b.instanceId == 0) return false;
        return a.instanceId == b.instanceId;
    }

    int indexOf(long gridId) {
        Integer i = index.get(gridId);
        return i == null ? -1 : i;
    }

    int size() {
        return chunks.length;
    }
}
