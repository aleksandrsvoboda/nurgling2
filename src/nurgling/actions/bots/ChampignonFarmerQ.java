package nurgling.actions.bots;

import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.conf.CropRegistry;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.List;

/**
 * Quality champignon farming in X*Y patches on woodchips. Any of the three mushrooms can be
 * replanted, so the highest-quality ones are planted whatever their kind. Champignons can't go
 * to a trough, so the low-quality overflow of the quality container goes to the containers of
 * the optional seed(Champignon) store instead, reached by global navigation.
 */
public class ChampignonFarmerQ implements Action {
    private static final String CROP = "Champignon";
    private static final NAlias PLANT = new NAlias("plants/champignon");

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        boolean oldStackingValue = ((NInventory) NUtils.getGameUI().maininv).bundle.a;

        NArea.Specialisation cropQ = new NArea.Specialisation(Specialisation.SpecName.cropQ.toString(), CROP);
        NArea.Specialisation seedQ = new NArea.Specialisation(Specialisation.SpecName.seedQ.toString(), CROP);
        NArea.Specialisation storeSpec = new NArea.Specialisation(Specialisation.SpecName.seed.toString(), CROP);

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(cropQ);
        req.add(seedQ);
        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        opt.add(storeSpec);

        if (!new Validator(req, opt).run(gui).IsSuccess()) {
            return Results.FAIL();
        }

        List<NAlias> kinds = CropRegistry.getPlantingMaterials(PLANT, CropRegistry.StorageBehavior.CONTAINER);
        ArrayList<String> names = new ArrayList<>();
        for (NAlias kind : kinds)
            names.addAll(kind.keys);
        NAlias anyMushroom = new NAlias(names);

        NArea field = NContext.findSpec(cropQ);
        NArea qContainer = NContext.findSpec(seedQ);

        NUtils.stackSwitch(true);

        new HarvestCrop(field, qContainer, null, PLANT, true, cropQ).run(gui);

        new SeedCrop(field, qContainer, PLANT, anyMushroom, true).run(gui);

        NArea storeArea = new NContext(gui).findArea(Specialisation.SpecName.seed, CROP);
        if ((Boolean) NConfig.get(NConfig.Key.cleanupQContainers) && storeArea != null) {
            new CleanupSeedQContainer(qContainer, anyMushroom, new AreaContainerStore(storeArea, field), kinds).run(gui);
        }

        NUtils.stackSwitch(oldStackingValue);
        return Results.SUCCESS();
    }
}
