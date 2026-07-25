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

public class PumpkinFarmerQ implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        boolean oldStackingValue = ((NInventory) NUtils.getGameUI().maininv).bundle.a;

        NArea.Specialisation cropQ = new NArea.Specialisation(Specialisation.SpecName.cropQ.toString(), "Pumpkin");
        NArea.Specialisation seedQ = new NArea.Specialisation(Specialisation.SpecName.seedQ.toString(), "Pumpkin");
        NArea.Specialisation trough = new NArea.Specialisation(Specialisation.SpecName.trough.toString());

        NArea pumpkinFleshArea = NContext.findOut(new NAlias("Pumpkin Flesh"), 1);

        if (pumpkinFleshArea == null) {
            return Results.ERROR("PUT Area for Pumpkin Flesh required, but not found!");
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
                    new NAlias("plants/pumpkin"),
                    true
            ).run(gui);

            if (!new QualityFarmerCycleContract(
                    new LettuceAndPumpkinCollector(NContext.findSpec(cropQ), NContext.findSpec(seedQ), pumpkinFleshArea, new NAlias(Arrays.asList("items/pumpkin", "Pumpkin"), Arrays.asList("plants", "seed", "flesh")), null, true),
                    SeedCrop.forQualityGrid(NContext.findSpec(cropQ), NContext.findSpec(seedQ), new NAlias("plants/pumpkin"))
            ).run(gui).IsSuccess()) {
                NUtils.stackSwitch(oldStackingValue);
                return Results.FAIL();
            }

            if (cleanupQContainers && NContext.findSpec(trough) != null) {
                new CleanupSeedQContainer(NContext.findSpec(seedQ), new NAlias("Pumpkin"), NContext.findSpec(trough)).run(gui);
            }

            NUtils.stackSwitch(oldStackingValue);
            return Results.SUCCESS();
        }

        NUtils.stackSwitch(oldStackingValue);
        return Results.FAIL();
    }
}
