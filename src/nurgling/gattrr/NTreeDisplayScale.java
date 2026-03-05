package nurgling.gattrr;

import haven.GAttrib;
import haven.Gob;
import haven.render.Pipe;
import haven.res.lib.tree.Tree;

public class NTreeDisplayScale extends GAttrib implements Gob.SetupMod {
    public final float scale;

    public NTreeDisplayScale(Gob gob, float scale) {
        super(gob);
        this.scale = scale;
    }

    @Override
    public Pipe.Op gobstate() {
        return Tree.mkscale(scale);
    }
}
