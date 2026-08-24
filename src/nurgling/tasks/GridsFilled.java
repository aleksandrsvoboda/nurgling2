package nurgling.tasks;

import haven.Coord;
import haven.MCache;
import nurgling.NUtils;

public class GridsFilled extends NTask {
    public GridsFilled(Coord coord) {
    this.coord = coord;
    }
    Coord coord;

    @Override
    public boolean check() {
        MCache map = NUtils.getGameUI().map.glob.map;
        synchronized (map.grids) {
            if (map.grids.size() != 9)
                return false;
            if (map.grids.get(coord) == null)
                return true;
            for (Coord gc : map.grids.keySet())
            {
                Coord pos = gc.sub(coord.sub(1,1));
                if(pos.x<0||pos.x>=3||pos.y<0||pos.y>=3)
                {
                    return false;
                }
            }
            return true;
        }
    }
}
