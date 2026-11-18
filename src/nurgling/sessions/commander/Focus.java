package nurgling.sessions.commander;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import haven.MiniMap;
import nurgling.sessions.squad.WorldRef;

/** A point on a map segment: where the commander camera looks. Immutable, so sessions can share it. */
public final class Focus {
    public final long seg;
    /** World position inside the segment (segment tile * tilesz). */
    public final Coord2d pos;

    public Focus(long seg, Coord2d pos) {
        this.seg = seg;
        this.pos = pos;
    }

    /** A local point of the session placed at {@code sl}. */
    public static Focus of(MiniMap.Location sl, Coord2d local) {
        return (sl == null || local == null) ? null : new Focus(sl.seg.id, WorldRef.toSegment(sl.tc, local));
    }

    /** This point in the frame of the session placed at {@code sl}, or null when that session is on another segment. */
    public Coord2d localIn(MiniMap.Location sl) {
        return (sl == null || sl.seg.id != seg) ? null : WorldRef.fromSegment(sl.tc, pos);
    }

    public Focus add(Coord2d d) {
        return new Focus(seg, pos.add(d));
    }

    /** The grid this is in, in the segment's grid coordinates. */
    public Coord grid() {
        return gridOf(pos);
    }

    public static Coord gridOf(Coord2d segPos) {
        double g = MCache.cmaps.x * MCache.tilesz.x;
        return new Coord((int) Math.floor(segPos.x / g), (int) Math.floor(segPos.y / g));
    }

    public static Focus of(WorldRef r) {
        return (r == null || r.segPos == null) ? null : new Focus(r.segId, r.segPos);
    }

    @Override
    public String toString() {
        return String.format("Focus[%x %s]", seg, pos);
    }
}
