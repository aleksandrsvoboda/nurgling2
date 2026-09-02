package nurgling.actions.bots.silk;

import haven.Gob;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Optimizes silkmoth pairs across containers using inventory as buffer, and groups the moths by
 * quality while doing it.
 *
 * <p>Egg quality is the average of the two parents, so how the moths are matched cannot change the
 * average egg quality at all - the total is the sum of every moth either way. What it changes is
 * the SPREAD: pairing like with like roughly doubles the variance of pair quality against pairing
 * at random. That is worth having only because the rest of the bot selects - it plants the best
 * eggs it can find and breeds from the best cocoons - so a wider top tail with the bottom culled
 * ratchets the whole farm upward each generation, which random pairing never does.
 *
 * <p>Which male mates with which female inside a cupboard is the game's business, not ours. The
 * only lever is which moths share a cupboard, so this assigns each container a quality BAND: the
 * containers are ranked, the moths are ranked, and container n gets the n-th slice of both the
 * male and the female ordering. A moth already inside its band is left alone, which keeps the
 * shuffling down to the moths that are genuinely in the wrong place.
 */
public class ArrangeSilkmothPairs implements Action {

    /** Quality is re-read from rebuilt widgets between passes, so bands are matched with slack. */
    private static final float QEPS = 0.0001f;
    private static final int MAX_PASSES = 12;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);

        // Get silkmothBreeding area
        NArea breedingArea = context.goToArea(Specialisation.SpecName.silkmothBreeding);
        if (breedingArea == null) {
            return Results.ERROR("SilkmothBreeding area not found");
        }

        // Get all containers in the breeding area
        ArrayList<Container> containers = getContainersInArea(breedingArea);
        if (containers.isEmpty()) {
            return Results.ERROR("No containers found in breeding area");
        }

        /* Bounded because the balance test below is over quality bands as well as counts, and
         * equal-quality moths satisfy more than one band - a pass that cannot settle would
         * otherwise trade the same moths back and forth forever. */
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            // Step 1: Analyze container contents
            ArrayList<ContainerState> containerStates = analyzeContainers(gui, containers);

            // Step 2: Calculate differences (excess and shortage)
            calculateDifferences(containerStates);

            // Check if we're done (all containers are balanced)
            if (allContainersBalanced(containerStates)) {
                break;
            }

            // Step 3: Check inventory space and limit processing if needed
            int inventorySpace = gui.getInventory().getFreeSpace();
            ArrayList<ContainerState> processingBatch = selectProcessingBatch(containerStates, inventorySpace);

            if (processingBatch.isEmpty()) {
                break; // No containers can be processed due to inventory space
            }

            // Step 4: Collect excess moths into inventory
            collectExcessMoths(gui, processingBatch);

            // Step 5: Redistribute moths from inventory
            redistributeMoths(gui, processingBatch);
        }

        return Results.SUCCESS();
    }

    private ArrayList<Container> getContainersInArea(NArea area) throws InterruptedException {
        ArrayList<Gob> gobs = Finder.findGobs(area, new NAlias(new ArrayList<>(NContext.contcaps.keySet())));
        ArrayList<Container> containers = new ArrayList<>();
        for (Gob gob : gobs) {
            Container c = new Container(gob, NContext.contcaps.get(gob.ngob.name),area);
            c.initattr(Container.Space.class);
            containers.add(c);
        }
        return containers;
    }

    private ArrayList<ContainerState> analyzeContainers(NGameUI gui, ArrayList<Container> containers) throws InterruptedException {
        ArrayList<ContainerState> states = new ArrayList<>();
        NAlias femaleMothAlias = new NAlias("Female Silkmoth");
        NAlias maleMothAlias = new NAlias(new ArrayList<>(List.of("Male Silkmoth")), new ArrayList<>(List.of("female")));
        NAlias cocoonAlias = new NAlias("Silkworm Cocoon");

        // First pass: collect all counts
        int totalFemales = 0;
        int totalMales = 0;
        
        for (Container container : containers) {
            new PathFinder(Finder.findGob(container.gobid)).run(gui);
            new OpenTargetContainer(container).run(gui);

            // Count items
            ArrayList<WItem> females = gui.getInventory(container.cap).getItems(femaleMothAlias);
            ArrayList<WItem> males = gui.getInventory(container.cap).getItems(maleMothAlias);
            int femaleCount = females.size();
            int maleCount = males.size();
            int cocoonCount = gui.getInventory(container.cap).getItems(cocoonAlias).size();

            totalFemales += femaleCount;
            totalMales += maleCount;

            // Calculate max possible pairs for this container based on space
            int freeSlots = 16 - cocoonCount;
            int maxPossiblePairs = Math.min(8, freeSlots / 2);

            ContainerState state = new ContainerState(container, femaleCount, maleCount, cocoonCount, 0, 0); // targets will be set later
            state.maxPossiblePairs = maxPossiblePairs;
            state.femaleQualities = qualitiesOf(females);
            state.maleQualities = qualitiesOf(males);
            states.add(state);

            new CloseTargetContainer(container).run(gui);
        }
        
        // Calculate global constraints and distribute pairs realistically
        int totalPossiblePairs = Math.min(totalFemales, totalMales);
        distributePairsAcrossContainers(states, totalPossiblePairs, totalFemales, totalMales);
        assignQualityBands(states);

        return states;
    }

    /** Qualities of the given moths, best first. Copies that will not report one are skipped. */
    private static ArrayList<Float> qualitiesOf(ArrayList<WItem> moths) {
        ArrayList<Float> qualities = new ArrayList<>();
        for (WItem witem : moths) {
            Float q = ((NGItem) witem.item).quality;
            if (q != null)
                qualities.add(q);
        }
        Collections.sort(qualities, Collections.reverseOrder());
        return qualities;
    }

    /**
     * Give every container the quality slice it should be holding.
     *
     * <p>Rank all the males and all the females across the whole area, rank the containers, then
     * hand container n the n-th slice of each ordering, sized to the pair target it was already
     * given. The result is expressed as a quality range rather than a set of specific moths, which
     * is what lets the moves below survive the inventory shuffle: a moth is in the right place if
     * its quality falls in its container's band, no matter which container it came out of.
     *
     * <p>Containers are ranked by the moths they already hold, so the best band lands where the
     * best moths already are and the least possible carrying is needed to reach it.
     */
    private void assignQualityBands(ArrayList<ContainerState> states) {
        ArrayList<Float> allFemales = new ArrayList<>();
        ArrayList<Float> allMales = new ArrayList<>();
        for (ContainerState state : states) {
            allFemales.addAll(state.femaleQualities);
            allMales.addAll(state.maleQualities);
        }
        if (allFemales.isEmpty() && allMales.isEmpty())
            return;
        Collections.sort(allFemales, Collections.reverseOrder());
        Collections.sort(allMales, Collections.reverseOrder());

        states.sort((a, b) -> Double.compare(b.bestHeldQuality(), a.bestHeldQuality()));

        int femaleFrom = 0, maleFrom = 0;
        for (ContainerState state : states) {
            state.femaleBand = sliceBand(allFemales, femaleFrom, state.targetFemale);
            state.maleBand = sliceBand(allMales, maleFrom, state.targetMale);
            femaleFrom += state.targetFemale;
            maleFrom += state.targetMale;
        }
    }

    /**
     * The quality range covering ranks [from, from+size) of an ordered list, or null when the
     * container is owed nothing - a null band means "anything fits", so a container we have no
     * ranking opinion about is never churned.
     */
    private static float[] sliceBand(ArrayList<Float> sorted, int from, int size) {
        if (size <= 0 || from >= sorted.size())
            return null;
        int to = Math.min(sorted.size(), from + size);
        return new float[]{sorted.get(to - 1), sorted.get(from)}; // {min, max}
    }
    
    private void distributePairsAcrossContainers(ArrayList<ContainerState> states, int totalPossiblePairs, int totalFemales, int totalMales) {
        // Sort containers by their capacity (prefer containers that can hold more pairs)
        states.sort((a, b) -> Integer.compare(b.maxPossiblePairs, a.maxPossiblePairs));
        
        int pairsDistributed = 0;
        
        // Distribute pairs to containers - but respect each container's maxPossiblePairs
        for (ContainerState state : states) {
            // Use the already calculated maxPossiblePairs which accounts for cocoons
            int pairsForThisContainer = Math.min(state.maxPossiblePairs, totalPossiblePairs - pairsDistributed);
            state.targetFemale = pairsForThisContainer;
            state.targetMale = pairsForThisContainer;
            pairsDistributed += pairsForThisContainer;

            if (pairsDistributed >= totalPossiblePairs) {
                break;
            }
        }
        
        // Handle remaining unpaired moths - distribute to containers with available space
        int remainingFemales = totalFemales - pairsDistributed;
        int remainingMales = totalMales - pairsDistributed;
        
        for (ContainerState state : states) {
            // Calculate available space AFTER pairs and cocoons are placed
            int usedSlots = state.cocoonCount + state.targetFemale + state.targetMale;
            int availableSlots = 16 - usedSlots;

            if (availableSlots > 0 && (remainingFemales > 0 || remainingMales > 0)) {
                // Only add moths if there's actual space remaining
                int totalRemainingMoths = remainingFemales + remainingMales;
                int mothsToAdd = Math.min(totalRemainingMoths, availableSlots);
                
                // Distribute proportionally between females and males
                int femalesToAdd = 0;
                int malesToAdd = 0;
                
                if (mothsToAdd > 0) {
                    if (remainingFemales > 0 && remainingMales > 0) {
                        // Both genders available - distribute proportionally
                        femalesToAdd = Math.min(remainingFemales, (mothsToAdd * remainingFemales) / totalRemainingMoths);
                        malesToAdd = Math.min(mothsToAdd - femalesToAdd, remainingMales);
                    } else if (remainingFemales > 0) {
                        // Only females available
                        femalesToAdd = Math.min(remainingFemales, mothsToAdd);
                    } else {
                        // Only males available
                        malesToAdd = Math.min(remainingMales, mothsToAdd);
                    }
                    
                    state.targetFemale += femalesToAdd;
                    state.targetMale += malesToAdd;
                    
                    remainingFemales -= femalesToAdd;
                    remainingMales -= malesToAdd;
                }
            }
            
            if (remainingFemales == 0 && remainingMales == 0) {
                break;
            }
        }
    }

    /** How far outside its band a quality sits; 0 when it belongs there. */
    private static double bandMiss(float[] band, Float quality) {
        if (band == null)
            return 0; // no opinion about this container - everything belongs
        if (quality == null)
            return 0; // unreadable quality is never a reason to carry a moth about
        if (quality < band[0] - QEPS)
            return band[0] - quality;
        if (quality > band[1] + QEPS)
            return quality - band[1];
        return 0;
    }

    /**
     * The given moths ordered by how well they suit a band. Used from both ends: worst-first to
     * decide which moths leave a container, best-first to decide which of the ones in hand arrive.
     */
    private static ArrayList<WItem> byBandFit(ArrayList<WItem> moths, float[] band, boolean worstFirst) {
        ArrayList<WItem> sorted = new ArrayList<>(moths);
        Comparator<WItem> byMiss = (a, b) -> Double.compare(
                bandMiss(band, ((NGItem) a.item).quality),
                bandMiss(band, ((NGItem) b.item).quality));
        Collections.sort(sorted, worstFirst ? byMiss.reversed() : byMiss);
        return sorted;
    }

    private static int countMisfits(ArrayList<Float> qualities, float[] band) {
        int misfits = 0;
        for (Float q : qualities) {
            if (bandMiss(band, q) > 0)
                misfits++;
        }
        return misfits;
    }

    private void calculateDifferences(ArrayList<ContainerState> containerStates) {
        for (ContainerState state : containerStates) {
            /* A container is wrong either because it holds the wrong NUMBER of moths or because it
             * holds the wrong ONES. Both are settled by carrying moths out, so the excess is
             * whichever demands more movement; the shortage is then whatever the target still
             * needs once those have left. */
            state.excessFemale = Math.min(state.femaleCount, Math.max(
                    Math.max(0, state.femaleCount - state.targetFemale),
                    countMisfits(state.femaleQualities, state.femaleBand)));
            state.excessMale = Math.min(state.maleCount, Math.max(
                    Math.max(0, state.maleCount - state.targetMale),
                    countMisfits(state.maleQualities, state.maleBand)));
            state.shortageFemale = Math.max(0, state.targetFemale - (state.femaleCount - state.excessFemale));
            state.shortageMale = Math.max(0, state.targetMale - (state.maleCount - state.excessMale));
        }
    }

    private boolean allContainersBalanced(ArrayList<ContainerState> containerStates) {
        for (ContainerState state : containerStates) {
            if (state.femaleCount != state.targetFemale || state.maleCount != state.targetMale) {
                return false;
            }
            if (countMisfits(state.femaleQualities, state.femaleBand) > 0
                    || countMisfits(state.maleQualities, state.maleBand) > 0) {
                return false;
            }
        }
        return true;
    }

    private ArrayList<ContainerState> selectProcessingBatch(ArrayList<ContainerState> containerStates, int inventorySpace) {
        ArrayList<ContainerState> batch = new ArrayList<>();
        int requiredSpace = 0;

        // Sort by containers closest to target (minimize transfers)
        containerStates.sort((a, b) -> {
            int aDistance = Math.abs(a.femaleCount - a.targetFemale) + Math.abs(a.maleCount - a.targetMale);
            int bDistance = Math.abs(b.femaleCount - b.targetFemale) + Math.abs(b.maleCount - b.targetMale);
            return Integer.compare(aDistance, bDistance);
        });

        for (ContainerState state : containerStates) {
            int stateRequiredSpace = state.excessFemale + state.excessMale;
            if (requiredSpace + stateRequiredSpace <= inventorySpace) {
                batch.add(state);
                requiredSpace += stateRequiredSpace;
            } else {
                break; // Cannot fit more containers in this batch
            }
        }

        return batch;
    }

    /** Top of a container's bands, so the batch can be served best-entitled first. */
    private static double bandTop(ContainerState state) {
        double top = Double.NEGATIVE_INFINITY;
        if (state.femaleBand != null)
            top = Math.max(top, state.femaleBand[1]);
        if (state.maleBand != null)
            top = Math.max(top, state.maleBand[1]);
        return top;
    }

    private void collectExcessMoths(NGameUI gui, ArrayList<ContainerState> processingBatch) throws InterruptedException {
        NAlias femaleMothAlias = new NAlias("Female Silkmoth");
        NAlias maleMothAlias = new NAlias(new ArrayList<>(List.of("Male Silkmoth")), new ArrayList<>(List.of("female")));

        for (ContainerState state : processingBatch) {
            if (state.excessFemale > 0 || state.excessMale > 0) {
                new PathFinder(Finder.findGob(state.container.gobid)).run(gui);
                new OpenTargetContainer(state.container).run(gui);

                // Take excess female moths
                if (state.excessFemale > 0) {
                    // Worst fit first, so the moths that leave are the ones in the wrong band.
                    ArrayList<WItem> femaleMoths = byBandFit(
                            gui.getInventory(state.container.cap).getItems(femaleMothAlias), state.femaleBand, true);
                    ArrayList<WItem> excessFemales = new ArrayList<>();
                    for (int i = 0; i < Math.min(state.excessFemale, femaleMoths.size()); i++) {
                        excessFemales.add(femaleMoths.get(i));
                    }
                    if (!excessFemales.isEmpty()) {
                        new TakeWItemsFromContainer(state.container, excessFemales).run(gui);
                        // Update state to reflect actual count after taking
                        new OpenTargetContainer(state.container).run(gui);
                        state.femaleCount = gui.getInventory(state.container.cap).getItems(femaleMothAlias).size();
                    }
                }

                // Take excess male moths
                if (state.excessMale > 0) {
                    ArrayList<WItem> maleMoths = byBandFit(
                            gui.getInventory(state.container.cap).getItems(maleMothAlias), state.maleBand, true);
                    ArrayList<WItem> excessMales = new ArrayList<>();
                    for (int i = 0; i < Math.min(state.excessMale, maleMoths.size()); i++) {
                        excessMales.add(maleMoths.get(i));
                    }
                    if (!excessMales.isEmpty()) {
                        new TakeWItemsFromContainer(state.container, excessMales).run(gui);
                        // Update state to reflect actual count after taking
                        new OpenTargetContainer(state.container).run(gui);
                        state.maleCount = gui.getInventory(state.container.cap).getItems(maleMothAlias).size();
                    }
                }

                new CloseTargetContainer(state.container).run(gui);
            }
        }
    }

    private void redistributeMoths(NGameUI gui, ArrayList<ContainerState> processingBatch) throws InterruptedException {
        NAlias femaleMothAlias = new NAlias("Female Silkmoth");
        NAlias maleMothAlias = new NAlias(new ArrayList<>(List.of("Male Silkmoth")), new ArrayList<>(List.of("female")));

        /* Serve the highest band first: whatever is in hand is finite, so the best moths must be
         * offered to the container entitled to them before a lower one can take them. */
        processingBatch.sort((a, b) -> Double.compare(bandTop(b), bandTop(a)));

        for (ContainerState state : processingBatch) {
            if (state.shortageFemale > 0 || state.shortageMale > 0) {
                new PathFinder(Finder.findGob(state.container.gobid)).run(gui);
                new OpenTargetContainer(state.container).run(gui);

                // Add needed female moths
                if (state.shortageFemale > 0) {
                    ArrayList<WItem> femalesInInventory = byBandFit(
                            gui.getInventory().getItems(femaleMothAlias), state.femaleBand, false);
                    if (!femalesInInventory.isEmpty()) {
                        int toTransfer = Math.min(state.shortageFemale, femalesInInventory.size());
                        new SimpleTransferToContainer(gui.getInventory(state.container.cap), femalesInInventory, toTransfer).run(gui);
                        // Update state to reflect actual count after adding
                        state.femaleCount = gui.getInventory(state.container.cap).getItems(femaleMothAlias).size();
                    }
                }

                // Add needed male moths
                if (state.shortageMale > 0) {
                    ArrayList<WItem> malesInInventory = byBandFit(
                            gui.getInventory().getItems(maleMothAlias), state.maleBand, false);
                    if (!malesInInventory.isEmpty()) {
                        int toTransfer = Math.min(state.shortageMale, malesInInventory.size());
                        new SimpleTransferToContainer(gui.getInventory(state.container.cap), malesInInventory, toTransfer).run(gui);
                        // Update state to reflect actual count after adding
                        state.maleCount = gui.getInventory(state.container.cap).getItems(maleMothAlias).size();
                    }
                }

                new CloseTargetContainer(state.container).run(gui);
            }
        }
    }

    // Container state class as specified in silk_task.md
    private static class ContainerState {
        Container container;
        int femaleCount;
        int maleCount;
        int cocoonCount;
        int targetFemale;
        int targetMale;
        int maxPossiblePairs; // Maximum pairs this container can hold based on space

        /** Qualities currently held, best first. Read once per pass, in analyzeContainers. */
        ArrayList<Float> femaleQualities = new ArrayList<>();
        ArrayList<Float> maleQualities = new ArrayList<>();
        /** {min, max} quality this container should be holding, or null for "anything fits". */
        float[] femaleBand;
        float[] maleBand;

        /** Best moth of either sex in here, used to rank containers before handing out bands. */
        double bestHeldQuality() {
            double best = Double.NEGATIVE_INFINITY;
            if (!femaleQualities.isEmpty())
                best = Math.max(best, femaleQualities.get(0));
            if (!maleQualities.isEmpty())
                best = Math.max(best, maleQualities.get(0));
            return best;
        }

        // Calculated differences
        int excessFemale;
        int excessMale;
        int shortageFemale;
        int shortageMale;

        ContainerState(Container container, int femaleCount, int maleCount, int cocoonCount, int targetFemale, int targetMale) {
            this.container = container;
            this.femaleCount = femaleCount;
            this.maleCount = maleCount;
            this.cocoonCount = cocoonCount;
            this.targetFemale = targetFemale;
            this.targetMale = targetMale;
        }
    }
}