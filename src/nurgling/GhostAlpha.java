package nurgling;

import haven.*;
import haven.render.*;

public class GhostAlpha extends GAttrib implements Gob.SetupMod {
    private static final Pipe.Op ghostState = Pipe.Op.compose(
        new BaseColor(new java.awt.Color(150, 200, 255, 110)),  // Light blue, ~43% opacity for a clear ghosty feel
        new States.Facecull(States.Facecull.Mode.NONE)
    );
    
    public GhostAlpha(Gob gob) {
        super(gob);
    }
    
    @Override
    public Pipe.Op gobstate() {
        return ghostState;
    }
}
