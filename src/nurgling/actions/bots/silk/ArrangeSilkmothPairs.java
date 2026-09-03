package nurgling.actions.bots.silk;

import haven.Coord;
import haven.Gob;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups the breeding moths so the best males share a cupboard with the best females.
 *
 * <p>Egg quality is the average of the two parents, so matching cannot raise the average egg
 * quality - the total is the sum of every moth either way. It raises the SPREAD, which is worth
 * having only because the rest of the bot selects: it plants the best eggs and breeds from the best
 * cocoons. Widening the top tail and culling the bottom is what ratchets the farm upward.
 *
 * <p>Which male mates with which female inside a cupboard is the game's business, so the lever is
 * which moths share a cupboard:
 *
 * <ol>
 *   <li>Open every container once and read the quality of every moth in it.</li>
 *   <li>Sort the females and the males, then walk the containers giving each the next 8 of each -
 *       or fewer where cocoons have taken the slots.</li>
 *   <li>Carry moths until reality matches that, a few at a time, because the inventory holds
 *       nowhere near all of them.</li>
 * </ol>
 *
 * <p><b>The plan is built once and never recomputed.</b> An earlier version re-derived it each
 * pass, ranking containers by the moths they happened to hold - so every move re-ranked the
 * containers and changed the goal. It chased its own tail, ran to the pass limit every time and
 * stopped halfway through a shuffle, leaving cupboards with 9 females and 7 males. A fixed plan
 * cannot do that: every round strictly reduces the difference from it, so it settles.
 *
 * <p>The plan also covers surplus moths of whichever sex is in excess, by assigning them to the
 * container already holding them. They never move, and they can never end up stranded in the
 * inventory with nowhere that wants them.
 */
public class ArrangeSilkmothPairs implements Action {

    /** Quality is re-read from rebuilt widgets between rounds, so it is matched with slack. */
    private static final float QEPS = 0.0001f;
    private static final int SLOTS = 16;
    private static final int MAX_PAIRS = 8;
    /** Backstop only: a fixed plan settles in one or two rounds. */
    private static final int MAX_ROUNDS = 8;

    private static final NAlias FEMALE = new NAlias("Female Silkmoth");
    /** "Female Silkmoth" contains "male", so it has to be excluded explicitly. */
    private static final NAlias MALE = new NAlias(
            new ArrayList<>(List.of("Male Silkmoth")), new ArrayList<>(List.of("female")));
    private static final NAlias COCOON = new NAlias("Silkworm Cocoon");

    /** What one container should be holding when this is done. Fixed for the whole run. */
    private static class Target {
        final Container container;
        final ArrayList<Float> females = new ArrayList<>();
        final ArrayList<Float> males = new ArrayList<>();
        int mothSlots; // slots the cocoons have not taken

        Target(Container container) {
            this.container = container;
        }
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);

        NArea breedingArea = context.goToArea(Specialisation.SpecName.silkmothBreeding);
        if (breedingArea == null)
            return Results.ERROR("SilkmothBreeding area not found");

        ArrayList<Container> containers = getContainersInArea(breedingArea);
        if (containers.isEmpty())
            return Results.ERROR("No containers found in breeding area");

        ArrayList<Target> plan = buildPlan(gui, containers);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (!executeRound(gui, plan))
                break;
        }
        return Results.SUCCESS();
    }

    private ArrayList<Container> getContainersInArea(NArea area) throws InterruptedException {
        ArrayList<Gob> gobs = Finder.findGobs(area, new NAlias(new ArrayList<>(NContext.contcaps.keySet())));
        ArrayList<Container> containers = new ArrayList<>();
        for (Gob gob : gobs) {
            Container c = new Container(gob, NContext.contcaps.get(gob.ngob.name), area);
            c.initattr(Container.Space.class);
            containers.add(c);
        }
        return containers;
    }

    /**
     * One pass over every container to see what is there, then the whole assignment at once:
     * best 8 females with the best 8 males in the first container, the next 8 of each in the
     * second, and so on down.
     */
    private ArrayList<Target> buildPlan(NGameUI gui, ArrayList<Container> containers) throws InterruptedException {
        ArrayList<Target> plan = new ArrayList<>();
        ArrayList<Float> allFemales = new ArrayList<>();
        ArrayList<Float> allMales = new ArrayList<>();
        Map<Long, ArrayList<Float>> heldFemales = new HashMap<>();
        Map<Long, ArrayList<Float>> heldMales = new HashMap<>();

        for (Container container : containers) {
            new PathFinder(Finder.findGob(container.gobid)).run(gui);
            new OpenTargetContainer(container).run(gui);

            NInventory inv = gui.getInventory(container.cap);
            ArrayList<Float> females = qualitiesOf(inv.getItems(FEMALE));
            ArrayList<Float> males = qualitiesOf(inv.getItems(MALE));
            int cocoons = inv.getItems(COCOON).size();

            new CloseTargetContainer(container).run(gui);

            Target target = new Target(container);
            target.mothSlots = Math.max(0, SLOTS - cocoons);
            plan.add(target);

            heldFemales.put(container.gobid, females);
            heldMales.put(container.gobid, males);
            allFemales.addAll(females);
            allMales.addAll(males);
        }

        Collections.sort(allFemales, Collections.reverseOrder());
        Collections.sort(allMales, Collections.reverseOrder());

        // Pairs first, best with best. A container only ever gets as many as it has slots for.
        int fi = 0, mi = 0;
        for (Target target : plan) {
            int pairs = Math.min(MAX_PAIRS, target.mothSlots / 2);
            pairs = Math.min(pairs, Math.min(allFemales.size() - fi, allMales.size() - mi));
            for (int k = 0; k < pairs; k++) {
                target.females.add(allFemales.get(fi++));
                target.males.add(allMales.get(mi++));
            }
        }

        /* Whichever sex is in surplus has moths left over that no pair wants. Assign each to the
         * container already holding it: it will die unpaired wherever it sits, so carrying it
         * about is pure waste - and leaving it out of the plan entirely would make it a removal
         * with no destination, stranded in the inventory. */
        keepSurplusWhereItIs(plan, heldFemales, allFemales, fi, true);
        keepSurplusWhereItIs(plan, heldMales, allMales, mi, false);

        return plan;
    }

    private void keepSurplusWhereItIs(ArrayList<Target> plan, Map<Long, ArrayList<Float>> held,
                                      ArrayList<Float> all, int from, boolean female) {
        if (from >= all.size())
            return;
        ArrayList<Float> leftover = new ArrayList<>(all.subList(from, all.size()));

        // Preferred: the container already holding it, so it never has to be carried anywhere.
        for (Target target : plan) {
            ArrayList<Float> mine = held.get(target.container.gobid);
            if (mine == null)
                continue;
            for (Float quality : mine) {
                if (leftover.isEmpty())
                    return;
                if (planned(target) >= target.mothSlots)
                    break;
                int idx = indexOfNear(leftover, quality);
                if (idx < 0)
                    continue;
                leftover.remove(idx);
                (female ? target.females : target.males).add(quality);
            }
        }

        /* Its own container filled up with paired moths from elsewhere. Somewhere still has room -
         * every moth physically fits today, so the slots exist - and leaving one unplanned would
         * make it a removal with no destination, stranded in the inventory for FreeInventory2 to
         * deal with. */
        for (Target target : plan) {
            while (!leftover.isEmpty() && planned(target) < target.mothSlots)
                (female ? target.females : target.males).add(leftover.remove(0));
        }
    }

    private static int planned(Target target) {
        return target.females.size() + target.males.size();
    }

    /** One visit per container: take out what does not belong, put in what does. */
    private boolean executeRound(NGameUI gui, ArrayList<Target> plan) throws InterruptedException {
        boolean moved = false;
        for (Target target : plan) {
            new PathFinder(Finder.findGob(target.container.gobid)).run(gui);
            new OpenTargetContainer(target.container).run(gui);

            moved |= reconcile(gui, target, FEMALE, target.females);
            moved |= reconcile(gui, target, MALE, target.males);

            new CloseTargetContainer(target.container).run(gui);
        }
        return moved;
    }

    /**
     * Bring one sex of one container in line with its plan.
     *
     * <p>Removals happen before additions so a full cupboard can still be swapped: the moths that
     * do not belong leave first, which is what makes room for the ones that do. When the inventory
     * is too full to carry anything out, the additions still run, which frees it for the next
     * round - so a round can always make progress somewhere.
     */
    private boolean reconcile(NGameUI gui, Target target, NAlias alias, ArrayList<Float> wanted)
            throws InterruptedException {
        NInventory cinv = gui.getInventory(target.container.cap);
        if (cinv == null)
            return false;

        ArrayList<WItem> toRemove = new ArrayList<>();
        ArrayList<Float> missing = new ArrayList<>();
        diff(cinv.getItems(alias), wanted, toRemove, missing);

        boolean moved = false;

        if (!toRemove.isEmpty()) {
            int room = gui.getInventory().getNumberFreeCoord(new Coord(1, 1));
            int take = Math.min(room, toRemove.size());
            if (take > 0) {
                new TakeWItemsFromContainer(target.container,
                        new ArrayList<>(toRemove.subList(0, take))).run(gui);
                moved = true;
            }
        }

        if (!missing.isEmpty()) {
            ArrayList<WItem> inHand = matching(gui.getInventory().getItems(alias), missing);
            if (!inHand.isEmpty()) {
                // Re-resolve: taking items above rebuilt the container's widgets.
                NInventory dest = gui.getInventory(target.container.cap);
                if (dest != null) {
                    new SimpleTransferToContainer(dest, inHand, inHand.size()).run(gui);
                    moved = true;
                }
            }
        }
        return moved;
    }

    /**
     * Multiset difference by quality over two descending lists: what the container holds and
     * should not, and what it should hold and does not. Equal qualities cancel, so a moth that is
     * already in the right place is never picked up and put back down.
     */
    private static void diff(ArrayList<WItem> actual, ArrayList<Float> wanted,
                             ArrayList<WItem> toRemove, ArrayList<Float> missing) {
        ArrayList<WItem> have = new ArrayList<>(actual);
        have.sort((a, b) -> Double.compare(qualityOf(b), qualityOf(a)));
        ArrayList<Float> want = new ArrayList<>(wanted);
        Collections.sort(want, Collections.reverseOrder());

        int i = 0, j = 0;
        while (i < have.size() && j < want.size()) {
            double a = qualityOf(have.get(i));
            float w = want.get(j);
            if (Math.abs(a - w) <= QEPS) {
                i++;
                j++;
            } else if (a > w) {
                toRemove.add(have.get(i++));
            } else {
                missing.add(want.get(j++));
            }
        }
        while (i < have.size())
            toRemove.add(have.get(i++));
        while (j < want.size())
            missing.add(want.get(j++));
    }

    /** The carried moths that satisfy the wanted qualities, each wanted entry claimed at most once. */
    private static ArrayList<WItem> matching(ArrayList<WItem> carried, ArrayList<Float> wanted) {
        ArrayList<Float> open = new ArrayList<>(wanted);
        ArrayList<WItem> picked = new ArrayList<>();
        for (WItem witem : carried) {
            if (open.isEmpty())
                break;
            Float quality = ((NGItem) witem.item).quality;
            if (quality == null)
                continue;
            int idx = indexOfNear(open, quality);
            if (idx < 0)
                continue;
            open.remove(idx);
            picked.add(witem);
        }
        return picked;
    }

    private static int indexOfNear(ArrayList<Float> values, Float quality) {
        if (quality == null)
            return -1;
        for (int i = 0; i < values.size(); i++) {
            if (Math.abs(values.get(i) - quality) <= QEPS)
                return i;
        }
        return -1;
    }

    private static ArrayList<Float> qualitiesOf(ArrayList<WItem> moths) {
        ArrayList<Float> qualities = new ArrayList<>();
        for (WItem witem : moths) {
            Float q = ((NGItem) witem.item).quality;
            if (q != null)
                qualities.add(q);
        }
        return qualities;
    }

    /** Unreadable quality sorts last, so such a moth is only ever moved as a genuine surplus. */
    private static double qualityOf(WItem witem) {
        Float q = ((NGItem) witem.item).quality;
        return (q == null) ? Double.NEGATIVE_INFINITY : q;
    }
}
