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

public class ValidateAllCropsReady implements Action {

    private final NArea field;
    private final NAlias crop;

    public ValidateAllCropsReady(NArea field, NAlias crop) {
        this.field = field;
        this.crop = crop;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
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
            if (countedStages.add(stage.stage))
                readyCropCount += Finder.findGobs(field, crop, stage.stage).size();
        }

        if (readyCropCount < totalCropCount) {
            return Results.FAIL();
        }

        return Results.SUCCESS();
    }
}