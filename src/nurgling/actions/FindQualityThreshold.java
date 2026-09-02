package nurgling.actions;

import haven.Gob;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.areas.NContext;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Works out the quality cut-off that picks the {@code count} best copies of an item across ALL
 * of its source containers, by opening each of them once.
 * <p>
 * Taking "the highest quality" one container at a time is greedy and wrong: whichever container
 * happens to be scanned first wins, so a chest of poor copies beats a better chest further away.
 * The only way to know the global best is to look everywhere first, which is what this costs -
 * one pass over the source containers, once.
 * <p>
 * The cut-off is the count-th highest quality found, so "quality >= cut-off" selects exactly the
 * best {@code count} copies (plus any ties at the cut-off itself, which are by definition just as
 * good). That makes the order of the take pass irrelevant: every copy that clears the bar belongs
 * in the result, so callers can keep fetching greedily and still end up with the global best.
 * <p>
 * Stockpiles and barter stands are ignored - they offer no per-copy choice to rank.
 */
public class FindQualityThreshold implements Action {

    /**
     * Quality is re-read from freshly built widgets on the take pass, so the comparison is made
     * with a hair of slack rather than exact float equality. Erring this way admits at worst one
     * copy a hair below the cut-off; erring the other way would drop the very copy that defined it.
     */
    public static final float QEPS = 0.0001f;

    private final NContext context;
    private final String item;
    private final int count;

    private Float threshold = null;
    private final HashSet<String> withoutEligible = new HashSet<>();

    public FindQualityThreshold(NContext context, String item, int count) {
        this.context = context;
        this.item = item;
        this.count = count;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if (count <= 0)
            return Results.SUCCESS();

        ArrayList<NContext.ObjectStorage> inputs = context.getInStorages(item);
        if (inputs == null || inputs.isEmpty())
            return Results.FAIL();

        NAlias alias = new NAlias(item);
        ArrayList<Float> all = new ArrayList<>();
        /* Only containers we read in full land here. Anything we could not trust is left out
         * entirely, so the take pass still visits it - wrongly ruling a container out would
         * strand its contents, while wrongly keeping one only costs an extra open. */
        Map<String, ArrayList<Float>> readable = new HashMap<>();

        for (NContext.ObjectStorage input : inputs) {
            if (!(input instanceof Container))
                continue;
            Container cont = (Container) input;
            if (cont.cap == null || cont.gobHash == null)
                continue;
            Gob gob = Finder.findGob(cont.gobHash);
            if (gob == null)
                continue;
            // The same visual-empty skip the take pass applies, so both agree on what is worth opening.
            if (!"Frame".equals(cont.cap) && gob.ngob.isContainerEmpty())
                continue;

            new PathFinder(gob).run(gui);
            new OpenTargetContainer(cont).run(gui);

            /* getInventory resolves by window caption and can hand back another container of the
             * same kind, so only a window provably bound to this gob is worth recording. */
            NInventory inv = gui.getInventory(cont.cap);
            if (inv != null && inv.parentGob != null && inv.parentGob.id == cont.gobid) {
                ArrayList<WItem> found = inv.getItems(alias);
                ArrayList<Float> qualities = new ArrayList<>();
                for (WItem witem : found) {
                    Float q = ((NGItem) witem.item).quality;
                    if (q != null)
                        qualities.add(q);
                }
                // A copy whose quality would not read means this container cannot be ruled out later.
                if (qualities.size() == found.size())
                    readable.put(cont.gobHash, qualities);
                all.addAll(qualities);
            }

            new CloseTargetContainer(cont).run(gui);
        }

        if (all.isEmpty())
            return Results.SUCCESS(); // nothing to rank - leave the cut-off unset

        /* Room for every copy there is: the cut-off would exclude nothing, so leave it unset and
         * let the take pass run at full speed - a set bound also forces stacks to be broken up
         * one copy at a time, which is only worth paying for when it actually excludes something. */
        if (count >= all.size())
            return Results.SUCCESS();

        Collections.sort(all, Collections.reverseOrder());
        threshold = all.get(count - 1);

        for (Map.Entry<String, ArrayList<Float>> entry : readable.entrySet()) {
            boolean eligible = false;
            for (Float q : entry.getValue()) {
                if (q >= threshold - QEPS) {
                    eligible = true;
                    break;
                }
            }
            if (!eligible)
                withoutEligible.add(entry.getKey());
        }

        System.out.println("FindQualityThreshold: " + item + " - " + all.size() + " available, want "
                + count + ", cut-off q" + threshold + ", " + withoutEligible.size()
                + " container(s) hold nothing that good");
        return Results.SUCCESS();
    }

    /**
     * The cut-off, or null when there is nothing to gate on - no source, no copies, or no
     * readable quality. Callers must treat null as "no filtering".
     */
    public Float getThreshold() {
        return threshold;
    }

    /**
     * Hashes of containers proven to hold nothing at or above the cut-off, ready to seed
     * {@link TakeItems2#depleted} so the take pass never walks to them.
     */
    public Set<String> getWithoutEligible() {
        return withoutEligible;
    }
}
