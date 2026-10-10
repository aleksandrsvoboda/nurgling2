package nurgling.actions.bots.swillpiles;

import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Swill Piles to Troughs: empties every stockpile in every "Swill stockpiles" area into the
 * troughs of the "Trough for swill" area. Needs no input, so it also runs as a scenario step.
 * Piles of anything that is not swill are left alone. With a cistern in the "Swill" area full
 * troughs are emptied into it; without one the run stops once every trough is full.
 */
public class SwillPilesToTroughsBot implements Action {
    private static final String SPEC = Specialisation.SpecName.swillPiles.toString();

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        List<NArea> marked = markedAreas(gui);
        if (marked.isEmpty())
            return fail(gui, "no areas with the Swill stockpiles specialisation");

        NContext context = new NContext(gui);
        NArea troughArea = context.findArea(Specialisation.SpecName.trough);
        if (troughArea == null)
            return fail(gui, "no area with the Trough for swill specialisation");
        NArea swillArea = context.findArea(Specialisation.SpecName.swill);

        // findAllSpec leaves out areas chunk navigation cannot route to; name them in the report.
        Set<Integer> routable = new HashSet<>();
        for (NArea area : NContext.findAllSpec(SPEC))
            routable.add(area.id);
        List<String> skippedAreas = new ArrayList<>();
        Set<Integer> remaining = new HashSet<>();
        for (NArea area : marked) {
            if (routable.contains(area.id))
                remaining.add(area.id);
            else
                skippedAreas.add(area.name);
        }

        PileSweep.Shared shared = new PileSweep.Shared();
        TroughDelivery delivery = new TroughDelivery(troughArea, swillArea);

        // Check the troughs before hauling anything, and feed whatever swill is already carried.
        Results r = delivery.run(gui);
        if (!r.IsSuccess())
            return r;
        if (!delivery.hasRoom)
            return finish(gui, shared, delivery, skippedAreas, "every trough is full");

        Map<Integer, PileSweep> sweeps = new HashMap<>();
        while (!remaining.isEmpty()) {
            NArea area = nearest(remaining);
            if (area == null) {
                // Lost its route since the start; nothing left that can be reached.
                for (NArea m : marked) {
                    if (remaining.contains(m.id))
                        skippedAreas.add(m.name);
                }
                break;
            }
            PileSweep sweep = sweeps.computeIfAbsent(area.id, id -> new PileSweep(area, shared));
            if (!sweep.run(gui).IsSuccess()) {
                skippedAreas.add(area.name);
                remaining.remove(area.id);
                continue;
            }
            if (sweep.exhausted) {
                remaining.remove(area.id);
                // A part load travels on to the next area; deliver only when full or done.
                continue;
            }
            r = delivery.run(gui);
            if (!r.IsSuccess())
                return r;
            if (!delivery.hasRoom)
                return finish(gui, shared, delivery, skippedAreas, "every trough is full");
        }

        if (SwillScan.countSwill(gui) > 0) {
            r = delivery.run(gui);
            if (!r.IsSuccess())
                return r;
        }
        return finish(gui, shared, delivery, skippedAreas, null);
    }

    /** Every enabled area carrying the specialisation, routable or not. */
    private static List<NArea> markedAreas(NGameUI gui) {
        List<NArea> result = new ArrayList<>();
        for (NArea area : gui.map.glob.map.areas.values()) {
            if (area == null || area.isDisabled())
                continue;
            for (NArea.Specialisation s : area.spec) {
                if (SPEC.equals(s.name)) {
                    result.add(area);
                    break;
                }
            }
        }
        return result;
    }

    /** Nearest remaining area from where the player stands now; findAllSpec sorts by route distance. */
    private static NArea nearest(Set<Integer> remaining) {
        for (NArea area : NContext.findAllSpec(SPEC)) {
            if (remaining.contains(area.id))
                return area;
        }
        return null;
    }

    private static Results finish(NGameUI gui, PileSweep.Shared shared, TroughDelivery delivery,
                                  List<String> skippedAreas, String stopReason) throws InterruptedException {
        int left = SwillScan.countSwill(gui);
        StringBuilder sb = new StringBuilder("Swill Piles: ");
        sb.append(delivery.delivered).append(" items fed, ")
                .append(shared.pilesEmptied).append(" piles emptied");
        if (shared.pilesSkipped > 0)
            sb.append(", ").append(shared.pilesSkipped).append(" piles skipped");
        if (!shared.rejectedNames.isEmpty())
            sb.append(" (not swill: ").append(String.join(", ", shared.rejectedNames)).append(")");
        if (!skippedAreas.isEmpty())
            sb.append(", unreachable areas: ").append(String.join(", ", skippedAreas));
        if (left > 0)
            sb.append(", ").append(left).append(" swill items still carried");
        if (stopReason != null)
            sb.append(". Stopped: ").append(stopReason);
        gui.msg(sb.toString());
        System.out.println("[SwillPiles] " + sb);
        return (stopReason == null) ? Results.SUCCESS() : Results.FAIL();
    }

    private static Results fail(NGameUI gui, String reason) {
        gui.error("Swill Piles: " + reason);
        return Results.ERROR(reason);
    }
}
