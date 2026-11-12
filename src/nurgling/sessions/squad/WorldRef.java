package nurgling.sessions.squad;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import haven.MiniMap;
import nurgling.areas.NGlobalCoord;

import static haven.OCache.posres;

/**
 * A world position that means the same thing in every session.
 *
 * A raw {@code Coord2d} only means something in the session that produced it: each session anchors its origin where
 * that character logged in. Two anchors survive the trip between sessions. The map grid's global id plus the offset
 * inside that grid is exact, but only resolves where the other session has that grid loaded. The position inside the
 * shared map segment (the minimap's {@code sessloc}) resolves anywhere on the same segment, which is what lets a far
 * away character be placed at all. Either half may be missing; a ref with neither is never built.
 */
public final class WorldRef {
    public static final double GRID = MCache.cmaps.x * MCache.tilesz.x;

    /** Global grid id, or -1 when the grid was not loaded where this ref was made. */
    public final long gridId;
    /** Offset inside that grid, 0..GRID. */
    public final Coord2d off;
    /** Map segment id; meaningful only when {@link #segPos} is not null. */
    public final long segId;
    /** World position inside the segment (segment tile * tilesz), or null. */
    public final Coord2d segPos;

    public WorldRef(long gridId, Coord2d off, long segId, Coord2d segPos) {
        this.gridId = gridId;
        this.off = off;
        this.segId = segId;
        this.segPos = segPos;
    }

    /**
     * Encode a local position of the session owning {@code map} and {@code sessloc}. Returns null when neither the grid
     * nor the segment position is known.
     */
    public static WorldRef of(MCache map, MiniMap.Location sessloc, Coord2d local) {
        if (local == null)
            return null;
        long gid = -1;
        Coord2d off = null;
        if (map != null) {
            try {
                MCache.Grid g = map.getgrid(gridOf(local));
                gid = g.id;
                off = local.sub(gridOrigin(g.ul));
            } catch (MCache.LoadingMap e) {
                /* Not loaded here; the segment half may still carry it. */
            }
        }
        Coord2d seg = (sessloc == null) ? null : toSegment(sessloc.tc, local);
        long segId = (sessloc == null) ? 0 : sessloc.seg.id;
        if (gid < 0 && seg == null)
            return null;
        return new WorldRef(gid, off, segId, seg);
    }

    /**
     * The local position of this ref in the session owning {@code map} and {@code sessloc}, or null when it cannot be
     * placed there (grid not loaded and a different or unknown segment).
     */
    public Coord2d localIn(MCache map, MiniMap.Location sessloc) {
        if (gridId >= 0 && map != null) {
            MCache.Grid g = map.findGrid(gridId);
            if (g != null)
                return fromGrid(g.ul, off);
        }
        if (segPos != null && sessloc != null && sessloc.seg.id == segId)
            return fromSegment(sessloc.tc, segPos);
        return null;
    }

    /** As a bookmark chunk navigation can route to, or null without a grid id. */
    public NGlobalCoord global() {
        if (gridId < 0)
            return null;
        return new NGlobalCoord(gridId, off.floor(posres));
    }

    /* Pure arithmetic, kept separate so it can be tested without a running client. */

    static Coord gridOf(Coord2d local) {
        return new Coord((int) Math.floor(local.x / GRID), (int) Math.floor(local.y / GRID));
    }

    static Coord2d gridOrigin(Coord gridUlTile) {
        return new Coord2d(gridUlTile.x * MCache.tilesz.x, gridUlTile.y * MCache.tilesz.y);
    }

    static Coord2d fromGrid(Coord gridUlTile, Coord2d off) {
        return gridOrigin(gridUlTile).add(off);
    }

    /** Local world position to segment world position, given the session's segment tile at its local origin. */
    static Coord2d toSegment(Coord sessTc, Coord2d local) {
        return local.add(sessTc.x * MCache.tilesz.x, sessTc.y * MCache.tilesz.y);
    }

    static Coord2d fromSegment(Coord sessTc, Coord2d segPos) {
        return segPos.sub(sessTc.x * MCache.tilesz.x, sessTc.y * MCache.tilesz.y);
    }

    @Override
    public String toString() {
        return "WorldRef[grid=" + gridId + " off=" + off + " seg=" + segId + " " + segPos + "]";
    }
}
