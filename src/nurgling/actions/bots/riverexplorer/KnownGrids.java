package nurgling.actions.bots.riverexplorer;

import haven.Coord;
import haven.Gob;
import haven.MCache;
import haven.MapFile;
import haven.MiniMap;
import nurgling.NGameUI;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A snapshot of the current segment's recorded grids (segment grid coord <-> grid id) and the player's grid in it.
 *  Copied from nurgling-plugins mapexplorer/KnownGrids - keep the two in step. */
public final class KnownGrids {
    public final MiniMap.Location loc;
    public final Map<Coord, Long> ids;
    public final Map<Long, Coord> coords = new HashMap<>();
    public final Coord playerGrid;

    private KnownGrids(MiniMap.Location loc, Map<Coord, Long> ids, Coord playerGrid) {
        this.loc = loc;
        this.ids = ids;
        this.playerGrid = playerGrid;
        for (Map.Entry<Coord, Long> e : ids.entrySet())
            coords.put(e.getValue(), e.getKey());
    }

    /** Null while the player or the minimap's location isn't there yet. */
    public static KnownGrids read(NGameUI gui) {
        MiniMap.Location loc = gui.mmap != null ? gui.mmap.sessloc : null;
        Gob player = gui.map != null ? gui.map.player() : null;
        if (loc == null || player == null) return null;
        MapFile file = gui.mmap.file;
        Map<Coord, Long> ids;
        file.lock.readLock().lock();
        try {
            ids = new HashMap<>(loc.seg.map);
        } finally {
            file.lock.readLock().unlock();
        }
        return new KnownGrids(loc, ids, player.rc.floor(MCache.tilesz).add(loc.tc).div(MCache.cmaps));
    }

    /** Segment grid coords of the given grid ids that are in this segment. */
    public Set<Coord> coordsOf(Set<Long> gridIds) {
        Set<Coord> res = new HashSet<>();
        for (Long id : gridIds) {
            Coord c = coords.get(id);
            if (c != null) res.add(c);
        }
        return res;
    }

    /** Offset from session grid coords to segment grid coords. */
    public Coord gridOffset() {
        return loc.tc.div(MCache.cmaps);
    }
}
