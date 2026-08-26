package nurgling.tools;

import haven.*;
import nurgling.NGameUI;

/**
 * Turns a server grid id plus a tile offset inside that grid into somewhere this client can draw.
 *
 * <p>That pair is the only coordinate two clients agree on: {@code gob.rc} is local to a session and
 * the minimap's segment tiles are local to one client's map file, while the grid id is assigned by
 * the server and reads the same for everyone. Anything shared between players - a chat ping, a saved
 * fish spot, another player's position - therefore travels as (gid, local) and is resolved on arrival.
 *
 * <p>There are two independent answers, and a caller may want either:
 * <ul>
 *   <li>a <b>world position</b> ({@link Ref#wc()}), which only exists while the grid is loaded in
 *       {@link MCache}. Needed to draw in the 3D scene.</li>
 *   <li>a <b>map-file position</b> ({@link Ref#loc()}), which exists for any grid this client has
 *       ever walked or imported. Needed to draw on a minimap.</li>
 * </ul>
 *
 * <p>Both are allowed to fail and to succeed later - walking toward the spot loads its grid, and
 * importing a shared map can supply a segment that was missing a moment ago. So resolution is
 * retried on a throttle rather than treated as an error, and the map-file lookup, which can touch
 * disk, is deferred to the loader thread instead of blocking a frame.
 */
public class GridLocator {
    /** Seconds between retries of a resolution that has not succeeded yet. */
    private static final double RETRY = 0.5;
    /**
     * Seconds before a map-file lookup that found nothing is tried again.
     *
     * <p>Much slower than {@link #RETRY} because this path can touch disk and because the usual
     * answer is stable: a grid the map file has never heard of will still be unknown a moment later.
     * But it is not stable forever - importing a shared map can supply the very segment that was
     * missing - and a Ref can outlive that import by hours, so "never retry" is wrong. Matches the
     * re-resolve interval FishLocationService uses for the same situation.
     */
    private static final double FILE_RETRY = 60.0;

    /**
     * One (gid, local) pair together with whatever this client has managed to resolve it to.
     *
     * <p>Resolved positions are cached in the Ref itself rather than in a shared gid-keyed map. A
     * global cache would be tempting - grids never move - but a map import can merge two segments,
     * which moves a grid to a different {@link MiniMap.Segment} and would leave stale entries
     * pointing at the wrong place. Per-ref caching costs one lookup per new grid and cannot go stale.
     */
    public static class Ref {
        public final long gid;
        public final Coord local;

        private volatile Coord2d wc = null;
        private volatile MiniMap.Location loc = null;
        private double lastTry = Double.NEGATIVE_INFINITY;
        private volatile boolean filePending = false;
        private volatile double fileNextTry = Double.NEGATIVE_INFINITY;

        public Ref(long gid, Coord local) {
            this.gid = gid;
            this.local = local;
        }

        /** Session world position, or null while the grid is not loaded here. */
        public Coord2d wc() {return(wc);}

        /** Position in the local map file, or null while it is still being looked up. */
        public MiniMap.Location loc() {return(loc);}

        public boolean resolved() {return((wc != null) && (loc != null));}
    }

    /**
     * Fill in whichever of the two positions is still missing. Called from render passes, so it
     * must never block: the map-file path hands off to the loader and returns immediately.
     */
    public static void resolve(NGameUI gui, Ref ref) {
        if((gui == null) || ref.resolved())
            return;
        double now = Utils.rtime();
        if(now - ref.lastTry < RETRY)
            return;
        ref.lastTry = now;

        if(ref.wc == null)
            ref.wc = gui.ui.sess.glob.map.gridToScene(ref.local, ref.gid);

        if(ref.loc == null) {
            // Fast path: the grid is in the segment the player is standing in, which the map file
            // has already loaded in full, so this is a plain in-memory lookup.
            MiniMap.Location sessloc = (gui.mmap != null) ? gui.mmap.sessloc : null;
            if(sessloc != null) {
                Coord sgc = sessloc.seg.map.reverse().get(ref.gid);
                if(sgc != null)
                    ref.loc = new MiniMap.Location(sessloc.seg, sgc.mul(MCache.cmaps).add(ref.local));
            }
            if((ref.loc == null) && !ref.filePending && (now >= ref.fileNextTry))
                deferFileLookup(gui, ref);
        }
    }

    /**
     * Slow path for a grid in some other segment: ask the map file which segment it belongs to.
     * That reads from disk, so it goes to the loader rather than blocking the frame.
     *
     * <p>A lookup that finds nothing re-arms itself on the {@link #FILE_RETRY} timer rather than
     * giving up. Long-lived refs - a player standing still for an hour - would otherwise be stuck with
     * the answer from their first poll and would never appear even after the segment they are in
     * arrives in a map import.
     */
    private static void deferFileLookup(NGameUI gui, Ref ref) {
        MiniMap mmap = gui.mmap;
        if(mmap == null)
            return;
        MapFile file = mmap.file;
        if(file == null)
            return;
        ref.filePending = true;
        gui.ui.sess.glob.loader.defer(() -> {
            try {
                file.lock.readLock().lock();
                try {
                    MapFile.GridInfo info = file.gridinfo.get(ref.gid);
                    if(info == null)
                        return;
                    MapFile.Segment seg = file.segments.get(info.seg);
                    if(seg == null)
                        return;
                    ref.loc = new MiniMap.Location(seg, info.sc.mul(MCache.cmaps).add(ref.local));
                } finally {
                    file.lock.readLock().unlock();
                }
            } finally {
                /* Re-arm whatever the outcome. On success loc is set and resolve() stops asking; on
                 * failure the timer is what keeps this from becoming a per-frame disk hit. */
                ref.fileNextTry = Utils.rtime() + FILE_RETRY;
                ref.filePending = false;
            }
        }, null);
    }
}
