package nurgling.timers;

import haven.Coord;
import haven.Gob;
import haven.MCache;
import haven.MapFile;
import haven.MiniMap;
import nurgling.NGameUI;
import nurgling.widgets.NMapWnd;

/**
 * Where a timer sits: turning a map click or the player's position into a grid id and offset, and a
 * timer's grid id back into a place on this client's map.
 */
public final class TimerPlacement {
    /** A grid id and the tile offset inside that grid. */
    public static final class Spot {
        public final long gridId;
        public final Coord offset;

        Spot(long gridId, Coord offset) {
            this.gridId = gridId;
            this.offset = offset;
        }
    }

    private TimerPlacement() {
    }

    /** The spot under a map location, or null when that part of the map has no grid id. */
    public static Spot fromMap(MapFile file, MiniMap.Location loc) {
        if(loc == null)
            return null;
        Long gid;
        file.lock.readLock().lock();
        try {
            gid = loc.seg.map.get(loc.tc.div(MCache.cmaps));
        } finally {
            file.lock.readLock().unlock();
        }
        if(gid == null)
            return null;
        return new Spot(gid, Timer.gridOffset(loc.tc));
    }

    /** The spot of a map marker, which stores its segment id and tile. */
    public static Spot fromSegment(MapFile file, long segId, Coord tc) {
        MapFile.Segment seg;
        file.lock.readLock().lock();
        try {
            seg = file.segments.get(segId);
        } finally {
            file.lock.readLock().unlock();
        }
        return (seg == null) ? null : fromMap(file, new MiniMap.Location(seg, tc));
    }

    /** Where the player stands, or null while the grid there is still loading. */
    public static Spot fromPlayer(NGameUI gui) {
        if(gui.map == null)
            return null;
        Gob player = gui.map.player();
        if(player == null)
            return null;
        Coord tc = player.rc.floor(MCache.tilesz);
        MCache.Grid grid;
        try {
            grid = gui.map.glob.map.getgrid(tc.div(MCache.cmaps));
        } catch(MCache.LoadingMap e) {
            return null;
        }
        return new Spot(grid.id, tc.sub(grid.ul));
    }

    /**
     * The timer's place in this client's map file, looked up now. For the occasional click (Show on map),
     * not for render passes - those go through {@link nurgling.tools.GridLocator}, which never blocks.
     */
    public static MiniMap.Location locate(MapFile file, Timer t) {
        if(file == null || !t.hasLocation())
            return null;
        file.lock.readLock().lock();
        try {
            MapFile.GridInfo info = file.gridinfo.get(t.gridId);
            if(info == null)
                return null;
            MapFile.Segment seg = file.segments.get(info.seg);
            if(seg == null)
                return null;
            return new MiniMap.Location(seg, info.sc.mul(MCache.cmaps).add(t.ox, t.oy));
        } finally {
            file.lock.readLock().unlock();
        }
    }

    /** Open the map window centred on the timer. Returns false when this client's map has no such place. */
    public static boolean showOnMap(NGameUI gui, Timer t) {
        NMapWnd wnd = gui.mapfile;
        if(wnd == null || gui.mmap == null)
            return false;
        MiniMap.Location loc = locate(gui.mmap.file, t);
        if(loc == null)
            return false;
        if(!wnd.visible())
            gui.togglewnd(wnd);
        wnd.view.center(loc);
        wnd.view.follow(null);
        return true;
    }
}
