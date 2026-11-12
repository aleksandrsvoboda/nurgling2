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

/**
 * Quality champignon farming in X*Y patches on woodchips. Any of the three mushrooms can be
 * replanted, so the highest-quality ones are planted whatever their kind.
 */
public class ChampignonFarmerQ implements Action {
    private static final String CROP = "Champignon";
    private static final NAlias PLANT = new NAlias("plants/champignon");

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        boolean oldStackingValue = ((NInventory) NUtils.getGameUI().maininv).bundle.a;

        NArea.Specialisation cropQ = new NArea.Specialisation(Specialisation.SpecName.cropQ.toString(), CROP);
        NArea.Specialisation seedQ = new NArea.Specialisation(Specialisation.SpecName.seedQ.toString(), CROP);
        NArea.Specialisation trough = new NArea.Specialisation(Specialisation.SpecName.trough.toString());

        Boolean cleanupQContainers = (Boolean) NConfig.get(NConfig.Key.cleanupQContainers);

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(cropQ);
        req.add(seedQ);
        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        opt.add(trough);

        if (new Validator(req, opt).run(gui).IsSuccess()) {
            ArrayList<String> names = new ArrayList<>();
            for (NAlias kind : CropRegistry.getPlantingMaterials(PLANT, CropRegistry.StorageBehavior.CONTAINER))
                names.addAll(kind.keys);
            NAlias anyMushroom = new NAlias(names);

            NUtils.stackSwitch(true);

            new HarvestCrop(NContext.findSpec(cropQ), NContext.findSpec(seedQ), null, PLANT, true, cropQ).run(gui);

            new SeedCrop(NContext.findSpec(cropQ), NContext.findSpec(seedQ), PLANT, anyMushroom, true).run(gui);

            if (cleanupQContainers && NContext.findSpec(trough) != null) {
                new CleanupSeedQContainer(NContext.findSpec(seedQ), anyMushroom, NContext.findSpec(trough)).run(gui);
            }

            NUtils.stackSwitch(oldStackingValue);
            return Results.SUCCESS();
        }

        NUtils.stackSwitch(oldStackingValue);
        return Results.FAIL();
    }
}
