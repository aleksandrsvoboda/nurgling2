package nurgling.actions.bots.largefields;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import haven.MapFile;
import nurgling.NGameUI;
import nurgling.areas.NArea;
import nurgling.areas.NGlobalCoord;

import java.util.HashMap;
import java.util.Map;

import static haven.OCache.posres;

/**
 * The whole of a crop area, worked out without the area being loaded.
 *
 * An NArea stores, per map grid id, the grid-local rectangle it covers. Where those grids sit
 * relative to each other comes from the map file (segment + grid position), or, when every grid
 * is loaded, from the live map. That gives the field in {@link FieldGeometry field tiles}, which
 * are turned into the session's tile coordinates whenever any of the field's grids is loaded.
 * NArea.getRCArea()/getArea() are never used: they return null or a partial box as soon as one
 * of the field's grids is out of range.
 */
final class FieldMap {
    final NArea area;
    /** Field bounds in field tiles, half-open. */
    final Area field;
    /** Grid id -> grid offset from the field's top-left grid. */
    private final Map<Long, Coord> rel;
    private final Map<Coord, Long> byRel = new HashMap<>();

    private FieldMap(NArea area, Area field, Map<Long, Coord> rel) {
        this.area = area;
        this.field = field;
        this.rel = rel;
        for (Map.Entry<Long, Coord> e : rel.entrySet())
            byRel.put(e.getValue(), e.getKey());
    }

    /** Null (with the reason in why[0]) when the field's grids can't be placed relative to each other. */
    static FieldMap of(NArea area, NGameUI gui, String[] why) {
        if (area.space == null || area.space.space.isEmpty()) {
            why[0] = "area has no tiles";
            return null;
        }
        Map<Long, Coord> pos = livePositions(area, gui.map.glob.map);
        if (pos == null)
            pos = mapFilePositions(area, gui, why);
        if (pos == null)
            return null;

        int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE;
        for (Coord c : pos.values()) {
            minx = Math.min(minx, c.x);
            miny = Math.min(miny, c.y);
        }
        Map<Long, Coord> rel = new HashMap<>();
        Map<Coord, Area> perGrid = new HashMap<>();
        for (Map.Entry<Long, Coord> e : pos.entrySet()) {
            Coord r = e.getValue().sub(minx, miny);
            rel.put(e.getKey(), r);
            perGrid.put(r, area.space.space.get(e.getKey()).area);
        }
        return new FieldMap(area, FieldGeometry.bounds(perGrid), rel);
    }

    /** Grid coordinates of every grid of the area, if all of them are loaded. */
    private static Map<Long, Coord> livePositions(NArea area, MCache map) {
        Map<Long, Coord> res = new HashMap<>();
        synchronized (map.grids) {
            for (MCache.Grid g : map.grids.values()) {
                if (area.space.space.containsKey(g.id))
                    res.put(g.id, g.gc);
            }
        }
        return (res.size() == area.space.space.size()) ? res : null;
    }

    /** Grid positions inside their map segment, from the map file. All grids must share a segment. */
    private static Map<Long, Coord> mapFilePositions(NArea area, NGameUI gui, String[] why) {
        if (gui.mmap == null || gui.mmap.file == null) {
            why[0] = "the map file is not available";
            return null;
        }
        MapFile file = gui.mmap.file;
        Map<Long, Coord> res = new HashMap<>();
        Long seg = null;
        file.lock.readLock().lock();
        try {
            for (Long id : area.space.space.keySet()) {
                MapFile.GridInfo info = file.gridinfo.get(id);
                if (info == null) {
                    why[0] = "part of the field is not on your map yet - walk over the whole field once";
                    return null;
                }
                if (seg != null && seg != info.seg) {
                    why[0] = "the field lies across two unconnected map pieces - walk between them so the map joins up";
                    return null;
                }
                seg = info.seg;
                res.put(id, info.sc);
            }
        } finally {
            file.lock.readLock().unlock();
        }
        return res;
    }

    /** Session tile of field tile (0,0), or null when none of the field's grids is loaded. */
    Coord origin(MCache map) {
        synchronized (map.grids) {
            for (MCache.Grid g : map.grids.values()) {
                Coord r = rel.get(g.id);
                if (r != null)
                    return g.ul.sub(r.mul(FieldGeometry.GRID));
            }
        }
        return null;
    }

    /** A half-open field-tile area as an inclusive session-tile area, or null when not loaded. */
    Area toSessionInclusive(Area fieldTiles, MCache map) {
        Coord o = origin(map);
        if (o == null)
            return null;
        return new Area(fieldTiles.ul.add(o), fieldTiles.br.sub(1, 1).add(o));
    }

    /** Field tile of a session tile, or null when the field isn't loaded. */
    Coord toField(Coord sessionTile, MCache map) {
        Coord o = origin(map);
        return (o == null) ? null : sessionTile.sub(o);
    }

    /** A bookmark at the centre of a field tile that survives the grid being unloaded. */
    NGlobalCoord bookmark(Coord fieldTile) {
        Long id = byRel.get(FieldGeometry.gridOf(fieldTile));
        if (id == null)
            return null;
        Coord local = FieldGeometry.localOf(fieldTile);
        return new NGlobalCoord(id, Coord2d.of(local).mul(MCache.tilesz).add(MCache.tilehsz).floor(posres));
    }

    /** True when every grid holding a corner of the half-open field-tile area is loaded. */
    boolean loaded(Area fieldTiles, MCache map) {
        Coord o = origin(map);
        if (o == null)
            return false;
        Coord[] corners = {fieldTiles.ul, new Coord(fieldTiles.br.x - 1, fieldTiles.ul.y),
                new Coord(fieldTiles.ul.x, fieldTiles.br.y - 1), fieldTiles.br.sub(1, 1)};
        synchronized (map.grids) {
            for (Coord c : corners) {
                if (!map.grids.containsKey(c.add(o).div(MCache.cmaps)))
                    return false;
            }
        }
        return true;
    }
}
