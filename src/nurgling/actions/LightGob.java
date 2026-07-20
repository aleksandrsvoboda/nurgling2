package nurgling.actions;

import haven.Gob;
import nurgling.NGameUI;
import nurgling.actions.bots.LightObject;
import nurgling.tools.Finder;

import java.util.ArrayList;

/**
 * Thin adapter that routes bot lighting through the unified {@link LightObject} lighter.
 *
 * <p>Historically this class implemented its own candelabrum-or-branches lighting. It now resolves
 * the gob hashes to live gobs and delegates the whole batch to {@link LightObject}, which owns the
 * full lighter priority (embers, torches, torchposts, candelabrum, branches) and the per-batch
 * acquire/apply/release optimization.
 *
 * <p>{@code flame_flag} is retained for source compatibility with the ~19 existing call sites but is
 * no longer used: the per-workstation flame bit now comes from {@link LightObject#getConfig}.
 */
public class LightGob implements Action
{
    ArrayList<String> gobs;

    @SuppressWarnings("unused")
    int flame_flag;

    public LightGob(ArrayList<String> gobs, int flame_flag) {
        this.gobs = gobs;
        this.flame_flag = flame_flag;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        ArrayList<Gob> targets = new ArrayList<>();
        for (String gobHash : gobs) {
            Gob gob = Finder.findGob(gobHash);
            if (gob != null)
                targets.add(gob);
        }
        if (targets.isEmpty())
            return Results.SUCCESS();

        return new LightObject(targets).run(gui);
    }
}
