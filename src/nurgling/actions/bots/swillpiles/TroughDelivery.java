package nurgling.actions.bots.swillpiles;

import haven.Coord2d;
import haven.Gob;
import haven.Pair;
import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.actions.TransferToTrough;
import nurgling.areas.NArea;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Walks to the trough area and empties every swill item in the inventory into its troughs,
 * nearest first. With a cistern in the Swill area, a full trough is carried there and emptied
 * the way the farmers do it (TransferToTrough); without one a full trough is passed over.
 */
class TroughDelivery implements Action {
    private static final String TROUGH = "gfx/terobjs/trough";
    private static final NAlias CISTERN = new NAlias("gfx/terobjs/cistern");

    private final NArea troughArea;
    private final NArea swillArea;

    /** False once every trough is full and there is no cistern to empty them into. */
    boolean hasRoom = true;
    int delivered = 0;

    TroughDelivery(NArea troughArea, NArea swillArea) {
        this.troughArea = troughArea;
        this.swillArea = swillArea;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Pair<Coord2d, Coord2d> rc = SwillScan.reach(troughArea, true);
        if (rc == null)
            return Results.ERROR("could not reach the trough area " + troughArea.name);

        ArrayList<Gob> troughs = new ArrayList<>();
        for (Gob gob : Finder.findGobs(rc, new NAlias(TROUGH))) {
            if (TROUGH.equals(gob.ngob.name))
                troughs.add(gob);
        }
        if (troughs.isEmpty())
            return Results.ERROR("no troughs in the trough area " + troughArea.name);

        // The cistern is only usable when it is loaded from here: a full trough is carried to it.
        // Finder.findGob(NArea, ...) NPEs on an unloaded area, so resolve the bounds first.
        Pair<Coord2d, Coord2d> swillRc = (swillArea == null) ? null : swillArea.getRCArea();
        Gob cistern = (swillRc == null) ? null : Finder.findGob(swillRc, CISTERN);

        // Troughs that took nothing; never offered again this trip.
        Set<Long> refused = new HashSet<>();
        NAlias swill;
        while ((swill = SwillScan.carriedSwill(gui)) != null) {
            Gob trough = pick(troughs, refused, cistern != null);
            if (trough == null)
                break;
            int before = gui.getInventory().getItems(swill).size();
            new TransferToTrough(trough, swill, cistern).run(gui);
            int left = gui.getInventory().getItems(swill).size();
            delivered += Math.max(0, before - left);
            // No progress: offering the same trough again would loop forever.
            if (left >= before)
                refused.add(trough.id);
        }

        hasRoom = cistern != null || pick(troughs, refused, false) != null;
        return Results.SUCCESS();
    }

    /** Nearest trough with room; with a cistern, a full one too, since it can be emptied. */
    private static Gob pick(ArrayList<Gob> troughs, Set<Long> refused, boolean fullOk) {
        ArrayList<Gob> open = new ArrayList<>();
        ArrayList<Gob> full = new ArrayList<>();
        for (Gob cached : troughs) {
            Gob gob = Finder.findGob(cached.id);
            if (gob == null || refused.contains(gob.id))
                continue;
            (isFull(gob) ? full : open).add(gob);
        }
        if (!open.isEmpty())
            return SwillScan.nearest(open);
        return fullOk ? SwillScan.nearest(full) : null;
    }

    private static boolean isFull(Gob trough) {
        return trough.ngob.getModelAttribute() == 7;
    }
}
