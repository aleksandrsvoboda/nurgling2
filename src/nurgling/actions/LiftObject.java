package nurgling.actions;

import haven.*;
import nurgling.*;

public class LiftObject implements Action {
    @Override
    public Results run ( NGameUI gui )
            throws InterruptedException {
        if ( gob != null ) {
            // Time-limited, with one more go from closer up: a lift that doesn't take (out of reach, the object
            // taken by someone else) used to wait forever. FAIL tells the caller to try another object.
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                if (NUtils.getGameUI().ui.sess.glob.oc.getgob(gob.id) == null)
                    break;
                new PathFinder ( gob ).run(gui);
                if (NUtils.tryLift(gob, LIFT_MS))
                    return Results.SUCCESS();
            }
            return Results.FAIL();
        }
        return Results.ERROR("No gob for Lift");
    }

    public LiftObject (
            Gob gob

    ) {
        this.gob = gob;
    }

    Gob gob = null;
    private static final int ATTEMPTS = 2;
    private static final long LIFT_MS = 5000;
}