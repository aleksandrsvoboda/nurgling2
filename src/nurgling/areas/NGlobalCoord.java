package nurgling.areas;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import nurgling.NUtils;

import static haven.MCache.cmaps;
import static haven.OCache.posres;

public class NGlobalCoord {
    private Coord oldCoord = null;

    public long getGridId() {
        return grid_id;
    }

    private long grid_id;


    public NGlobalCoord(long gridId, Coord localCoord) {
        oldCoord = localCoord;
        grid_id = gridId;
    }

    public NGlobalCoord(Coord2d coord2d)
    {
        Coord pltc = (new Coord2d(coord2d.x / MCache.tilesz.x, coord2d.y / MCache.tilesz.y)).floor();
        synchronized (NUtils.getGameUI().ui.sess.glob.map.grids) {
            if (NUtils.getGameUI().ui.sess.glob.map.grids.containsKey(pltc.div(cmaps))) {
                MCache.Grid g = NUtils.getGameUI().ui.sess.glob.map.getgridt(pltc);
                oldCoord = (coord2d.sub(g.ul.mul(Coord2d.of(11, 11)))).floor(posres);
                grid_id = g.id;
            }
        }
    }

    public Coord2d getCurrentCoord()
    {
        if(oldCoord!=null) {
            synchronized (NUtils.getGameUI().ui.sess.glob.map.grids) {
                for (MCache.Grid g : NUtils.getGameUI().ui.sess.glob.map.grids.values()) {
                    if (g.id == grid_id) {
                        return oldCoord.mul(posres).add(g.ul.mul(Coord2d.of(11, 11)));
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns the grid-local tile coordinate of this bookmark, or null if the
     * grid is not currently loaded. Safe to call across sessions/chunk-nav hops —
     * combines stored grid id with the stored posres offset.
     */
    public Coord getLocalTile()
    {
        if(oldCoord == null) return null;
        Coord2d absolute = getCurrentCoord();
        if(absolute == null) return null;
        synchronized (NUtils.getGameUI().ui.sess.glob.map.grids) {
            for (MCache.Grid g : NUtils.getGameUI().ui.sess.glob.map.grids.values()) {
                if (g.id == grid_id) {
                    Coord2d worldTile = new Coord2d(absolute.x / MCache.tilesz.x, absolute.y / MCache.tilesz.y);
                    return worldTile.floor().sub(g.ul);
                }
            }
        }
        return null;
    }
}
