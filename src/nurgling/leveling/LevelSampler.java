package nurgling.leveling;

import haven.Coord;
import haven.Locked;
import haven.MapFile;
import haven.Tileset;
import nurgling.widgets.ReliefGrid;

import java.util.HashMap;
import java.util.Map;

import static haven.MCache.cmaps;

/**
 * Reads a plan's ground out of the map file: the height of every tile corner to level.
 *
 * <p>Water and cliff tiles are left out of the plan's ground (nobody levels a lake), and a corner
 * counts when any tile it touches is still in. Grids the plan names that are not in the given
 * segment are counted, not guessed at. Reads the map file under its read lock and may load grids
 * from disk, so it runs on a loader thread, never the UI thread.
 */
public final class LevelSampler {
    /** What the map file knows about a plan's ground. */
    public static final class Sample {
        public final LevelPlan plan;
        public final float[] heights;
        /** Tiles levelled; water and cliffs not included. */
        public final int tiles;
        /** Water and cliff tiles left out. */
        public final int skipped;
        /** Grids of the plan that are not in this segment of the map. */
        public final int unresolved;
        /** When the oldest grid of the plan was last seen, ms since the epoch; 0 if none. */
        public final long oldest;
        public final LevelAnalysis analysis;

        Sample(LevelPlan plan, float[] heights, int tiles, int skipped, int unresolved, long oldest) {
            this.plan = plan;
            this.heights = heights;
            this.tiles = tiles;
            this.skipped = skipped;
            this.unresolved = unresolved;
            this.oldest = oldest;
            this.analysis = new LevelAnalysis(heights);
        }
    }

    private static final Coord[] CORNERS = {Coord.of(0, 0), Coord.of(1, 0), Coord.of(0, 1), Coord.of(1, 1)};

    private LevelSampler() {}

    /**
     * The plan's ground, read in the map segment that holds most of its grids. A plan drawn on one
     * map stays in one segment unless the map file was split since; grids elsewhere are counted
     * as unresolved.
     */
    public static Sample sample(MapFile file, LevelPlan plan) {
        try(Locked lk = new Locked(file.lock.readLock())) {
            Map<Long, MapFile.GridInfo> infos = new HashMap<>();
            Map<Long, Integer> votes = new HashMap<>();
            for(Long gid : plan.masks.keySet()) {
                MapFile.GridInfo info = file.gridinfo.get(gid);
                if(info != null) {
                    infos.put(gid, info);
                    votes.merge(info.seg, 1, Integer::sum);
                }
            }
            long best = 0;
            int bestn = 0;
            for(Map.Entry<Long, Integer> e : votes.entrySet()) {
                if(e.getValue() > bestn) {
                    best = e.getKey();
                    bestn = e.getValue();
                }
            }
            MapFile.Segment seg = (bestn == 0) ? null : file.segments.get(best);
            /* The plan's grids that are in this segment, and where. */
            Map<Coord, long[]> masks = new HashMap<>();
            int unresolved = 0;
            for(Map.Entry<Long, long[]> e : plan.masks.entrySet()) {
                MapFile.GridInfo info = infos.get(e.getKey());
                if((seg == null) || (info == null) || (info.seg != seg.id))
                    unresolved++;
                else
                    masks.put(info.sc, e.getValue());
            }
            if(masks.isEmpty())
                return(new Sample(plan, new float[0], 0, 0, unresolved, 0));
            /* The grids holding a corner of a plan tile: each plan grid and the three after it. */
            MapFile.View view = new MapFile.View(seg);
            long oldest = 0;
            for(Coord gc : masks.keySet()) {
                for(Coord d : CORNERS)
                    view.addgrid(gc.add(d));
                MapFile.Grid g = seg.grid(gc).get();
                if((g != null) && ((oldest == 0) || (g.mtime < oldest)))
                    oldest = g.mtime;
            }
            view.fin();

            /* Plan tiles that are ground to level. */
            Map<Coord, long[]> ground = new HashMap<>();
            Map<Integer, Boolean> excluded = new HashMap<>();
            int tiles = 0, skipped = 0;
            for(Map.Entry<Coord, long[]> e : masks.entrySet()) {
                Coord ul = e.getKey().mul(cmaps);
                long[] m = e.getValue(), gm = TileMask.create();
                for(int y = 0; y < TileMask.SIZE; y++) {
                    for(int x = 0; x < TileMask.SIZE; x++) {
                        if(!TileMask.get(m, x, y))
                            continue;
                        Coord tc = ul.add(x, y);
                        int t = view.gettile(tc);
                        if((t < 0) || excluded(view, tc, t, excluded)) {
                            skipped++;
                            continue;
                        }
                        TileMask.put(gm, x, y, true);
                        tiles++;
                    }
                }
                ground.put(e.getKey(), gm);
            }

            /* Every corner touching a ground tile, each once: a corner belongs to the grid holding it. */
            Map<Coord, Boolean> seen = new HashMap<>();
            float[] buf = new float[Math.max(16, tiles * 2)];
            int n = 0;
            for(Coord pgc : ground.keySet()) {
                for(Coord d : CORNERS) {
                    Coord gc = pgc.add(d);
                    if(seen.put(gc, Boolean.TRUE) != null)
                        continue;
                    Coord ul = gc.mul(cmaps);
                    for(int y = 0; y < TileMask.SIZE; y++) {
                        for(int x = 0; x < TileMask.SIZE; x++) {
                            Coord vc = ul.add(x, y);
                            if(!cornerof(ground, vc) || (view.gettile(vc) < 0))
                                continue;
                            if(n == buf.length)
                                buf = java.util.Arrays.copyOf(buf, n * 2);
                            buf[n++] = (float)view.getfz(vc);
                        }
                    }
                }
            }
            return(new Sample(plan, java.util.Arrays.copyOf(buf, n), tiles, skipped, unresolved, oldest));
        }
    }

    /** Whether a corner touches a ground tile: the tiles to its upper left, up, left and itself. */
    private static boolean cornerof(Map<Coord, long[]> ground, Coord vc) {
        for(Coord d : CORNERS) {
            Coord tc = vc.sub(d);
            Coord gc = tc.div(cmaps);
            long[] m = ground.get(gc);
            if((m != null) && TileMask.get(m, tc.x - (gc.x * cmaps.x), tc.y - (gc.y * cmaps.y)))
                return(true);
        }
        return(false);
    }

    private static boolean excluded(MapFile.View view, Coord tc, int t, Map<Integer, Boolean> water) {
        Boolean w = water.get(t);
        if(w == null) {
            Tileset set = view.tileset(t);
            w = (set != null) && ReliefGrid.waterp(set.getres().name);
            water.put(t, w);
        }
        return(w || ReliefGrid.cliffp(view, tc, t));
    }
}
