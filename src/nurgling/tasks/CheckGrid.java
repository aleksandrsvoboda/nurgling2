package nurgling.tasks;


import haven.Coord;
import nurgling.NUtils;

public class CheckGrid extends NTask {

    Coord current;
    boolean aborted = false;

    public CheckGrid(Coord current) {
        if(current.x == 0 && current.y == 0) {
            int a = 0;
        }
        this.current = current;
    }

    @Override
    public boolean check() {
        boolean res = NUtils.getUI().sess.glob.map.checkGrid(current);
        if(res)
            return true;
        else
        {
            res = NUtils.getUI().sess.glob.map.inReq(current);
            if(res) {
                aborted = true;
                return true;
            }
        }
        return false;
    }

    public boolean status()
    {
        return aborted;
    }
}
