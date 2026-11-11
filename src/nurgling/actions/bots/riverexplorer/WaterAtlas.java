package nurgling.actions.bots.riverexplorer;

import haven.Coord;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import nurgling.actions.bots.worldexplorer.SeaGrid;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Every known grid's water (GridWater), read from the map file's recorded tiles - so the bot can plan by water through grids it
 *  isn't near. Grids are read nearest first and time-boxed, the way Map Explorer's DeepWater.nearestRecorded does it; what was
 *  read stays cached for the client session (water doesn't move), per water rules and hull size. */
public final class WaterAtlas {
    // "<rules>|<grid id>" -> the grid's compact water. Shared across runs: a restart doesn't read the map again.
    private static final Map<String, GridWater> cache = new ConcurrentHashMap<>();

    private final Predicate<String> fresh, sea;
    private final double fit;
    private final String rules;

    /** sea null = the sea is a wall. */
    public WaterAtlas(Predicate<String> fresh, Predicate<String> sea, double fit, String rulesKey) {
        this.fresh = fresh;
        this.sea = sea;
        this.fit = fit;
        this.rules = rulesKey + "|" + Math.round(fit * 10);
    }

    public Predicate<String> fresh() {
        return fresh;
    }

    public Predicate<String> sea() {
        return sea;
    }

    public double fit() {
        return fit;
    }

    /** Reads not-yet-cached known grids, nearest to the player's first, until maxMs pass. True when every known grid is read. */
    public boolean load(MapFile file, KnownGrids snap, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        Set<Coord> seen = new HashSet<>();
        ArrayDeque<Coord> queue = new ArrayDeque<>();
        seen.add(snap.playerGrid);
        queue.add(snap.playerGrid);
        boolean complete = true;
        while (!queue.isEmpty()) {
            Coord gc = queue.poll();
            Long id = snap.ids.get(gc);
            if (id != null && !cache.containsKey(key(id))) {
                if (System.currentTimeMillis() > deadline) {
                    complete = false;
                } else {
                    MapFile.Grid grid = loadGrid(file, id);
                    // An unreadable grid counts as dry, so loading always gets further.
                    cache.put(key(id), (grid == null) ? dry() : GridWater.build(recorded(grid), fresh, sea, fit).compact());
                }
            }
            for (Coord d : RiverFrontier.NEIGHBOURS) {
                Coord nb = gc.add(d);
                if (seen.contains(nb) || !snap.ids.containsKey(nb)) continue;
                seen.add(nb);
                queue.add(nb);
            }
        }
        return complete;
    }

    /** The known grids read so far, by segment grid coord. */
    public Map<Coord, GridWater> world(KnownGrids snap) {
        Map<Coord, GridWater> res = new HashMap<>();
        for (Map.Entry<Coord, Long> e : snap.ids.entrySet()) {
            GridWater g = cache.get(key(e.getValue()));
            if (g != null) res.put(e.getKey(), g);
        }
        return res;
    }

    /** A grid from the live map, with its cells (for a search from the boat); null while any tile is still loading. walls:
     *  grid-local tiles that are closed whatever the tile (beaver dams), or null. */
    public GridWater live(MCache map, Coord sessGrid, java.util.Collection<Coord> walls) {
        Coord ul = sessGrid.mul(MCache.cmaps);
        String[] names = new String[GridWater.TILES * GridWater.TILES];
        for (int y = 0; y < GridWater.TILES; y++) {
            for (int x = 0; x < GridWater.TILES; x++) {
                try {
                    names[y * GridWater.TILES + x] = map.tilesetname(map.gettile(ul.add(x, y)));
                } catch (Loading l) {
                    return null;
                }
            }
        }
        return GridWater.build(tc -> names[tc.y * GridWater.TILES + tc.x], fresh, sea, fit, walls);
    }

    /** A compact copy of a live grid replaces the recorded one: the live tiles are the current ones. */
    public void put(long id, GridWater g) {
        cache.put(key(id), g);
    }

    private String key(long id) {
        return rules + "|" + id;
    }

    private GridWater dry() {
        return GridWater.build(tc -> null, fresh, sea, fit).compact();
    }

    /** A recorded grid's tile names, grid-local (index y * TILES + x; null where unknown), or null when it can't be read. */
    public static String[] recordedNames(MapFile file, long id) {
        MapFile.Grid grid = loadGrid(file, id);
        if (grid == null) return null;
        SeaGrid.Tiles t = recorded(grid);
        String[] names = new String[GridWater.TILES * GridWater.TILES];
        for (int y = 0; y < GridWater.TILES; y++)
            for (int x = 0; x < GridWater.TILES; x++)
                names[y * GridWater.TILES + x] = t.name(Coord.of(x, y));
        return names;
    }

    private static SeaGrid.Tiles recorded(MapFile.Grid g) {
        return t -> {
            int id = g.gettile(t);
            return (id >= 0 && id < g.tilesets.length && g.tilesets[id] != null && g.tilesets[id].res != null) ? g.tilesets[id].res.name : null;
        };
    }

    private static MapFile.Grid loadGrid(MapFile file, long id) {
        file.lock.readLock().lock();
        try {
            return MapFile.Grid.load(file, id);
        } finally {
            file.lock.readLock().unlock();
        }
    }
}
