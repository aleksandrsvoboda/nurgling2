package nurgling.actions.bots;

import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Arrays;

public class WatermelonFarmerQ implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        boolean oldStackingValue = ((NInventory) NUtils.getGameUI().maininv).bundle.a;

        NArea.Specialisation cropQ = new NArea.Specialisation(Specialisation.SpecName.cropQ.toString(), "Watermelon");
        NArea.Specialisation seedQ = new NArea.Specialisation(Specialisation.SpecName.seedQ.toString(), "Watermelon");
        NArea.Specialisation trough = new NArea.Specialisation(Specialisation.SpecName.trough.toString());

        NArea watermelonSliceArea = NContext.findOut(new NAlias("Watermelon Slice"), 1);

        if (watermelonSliceArea == null) {
            return Results.ERROR("PUT Area for Watermelon Slice required, but not found!");
        }

        Boolean cleanupQContainers = (Boolean) NConfig.get(NConfig.Key.cleanupQContainers);

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(cropQ);
        req.add(seedQ);
        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        opt.add(trough);

        if (new Validator(req, opt).run(gui).IsSuccess()) {
            NUtils.stackSwitch(true);

            new HarvestCrop(
                    NContext.findSpec(cropQ),
                    NContext.findSpec(seedQ),
                    null,
                    new NAlias("plants/watermelon"),
                    true
            ).run(gui);

            new LettucePumpkinAndWatermelonCollector(
                    NContext.findSpec(cropQ),
                    NContext.findSpec(seedQ),
                    watermelonSliceArea,
                    new NAlias(Arrays.asList("items/watermelon", "Watermelon"), Arrays.asList("plants", "seed", "slice")),
                    LettucePumpkinAndWatermelonCollector.Product.WATERMELON,
                    null,
                    true
            ).run(gui);

            new SeedCrop(NContext.findSpec(cropQ), NContext.findSpec(seedQ), new NAlias("plants/watermelon"), new NAlias("Watermelon"), true).run(gui);

            if (cleanupQContainers && NContext.findSpec(trough) != null) {
                new CleanupSeedQContainer(NContext.findSpec(seedQ), new NAlias("Watermelon"), NContext.findSpec(trough)).run(gui);
            }

            NUtils.stackSwitch(oldStackingValue);
            return Results.SUCCESS();
        }

        NUtils.stackSwitch(oldStackingValue);
        return Results.FAIL();
    }
}
