package nurgling.actions;

import nurgling.NGameUI;
import nurgling.areas.NArea;
import nurgling.conf.CropRegistry;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a farmer may harvest its field now. A field with a minimum harvest stage
 * (set on its crop specialisation) waits until every plant is at a harvest stage at least
 * that high; any other field waits for every plant to be at some harvest stage when
 * {@code requireAllReady} is set, and is always harvestable otherwise.
 *
 * Only registered harvest stages count as ready, because those are the only ones
 * HarvestCrop harvests - a turnip at stage 2 is past a minimum of 1 but would be left
 * standing in the middle of the field.
 */
public class ValidateAllCropsReady implements Action {

    private final NArea field;
    private final NArea.Specialisation spec;
    private final NAlias crop;
    private final boolean requireAllReady;

    public ValidateAllCropsReady(NArea field, NArea.Specialisation spec, NAlias crop, boolean requireAllReady) {
        this.field = field;
        this.spec = spec;
        this.crop = crop;
        this.requireAllReady = requireAllReady;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NArea.Specialisation fieldSpec = field.getSpecialisation(spec);
        Integer minStage = fieldSpec == null ? null : fieldSpec.minHarvestStage;

        if (minStage == null && !requireAllReady) {
            return Results.SUCCESS();
        }

        List<CropRegistry.CropStage> cropStages = CropRegistry.HARVESTABLE.getOrDefault(crop, Collections.emptyList());

        if (cropStages.isEmpty()) {
            return Results.FAIL();
        }

        int totalCropCount = Finder.findGobs(field, crop).size();
        if (totalCropCount == 0) {
            return Results.SUCCESS();
        }

        // A stage can carry several products (radish: seeds and radishes), so count each once.
        Set<Integer> countedStages = new HashSet<>();
        int readyCropCount = 0;
        for (CropRegistry.CropStage stage : cropStages) {
            if ((minStage == null || stage.stage >= minStage) && countedStages.add(stage.stage))
                readyCropCount += Finder.findGobs(field, crop, stage.stage).size();
        }

        if (readyCropCount < totalCropCount) {
            if (minStage == null) {
                gui.msg("Not all crops in " + field.name + " are ready for harvest, skipping harvest.");
            } else {
                String products = CropRegistry.describeStage(crop, minStage);
                gui.msg("Not all crops in " + field.name + " have reached stage " + minStage
                        + (products.isEmpty() ? "" : " (" + products + ")") + ", skipping harvest.");
            }
            return Results.FAIL();
        }

        return Results.SUCCESS();
    }
}
