package nurgling.actions.bots.farmers;

import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.actions.bots.EquipTravellersSacksFromBelt;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Arrays;

public class WatermelonFarmer implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext nContext = new NContext(gui);
        boolean oldStackingValue = ((NInventory) NUtils.getGameUI().maininv).bundle.a;

        NArea.Specialisation field = new NArea.Specialisation(Specialisation.SpecName.crop.toString(), "Watermelon");
        NArea.Specialisation seed = new NArea.Specialisation(Specialisation.SpecName.seed.toString(), "Watermelon");
        NArea.Specialisation trough = new NArea.Specialisation(Specialisation.SpecName.trough.toString());
        NArea.Specialisation swill = new NArea.Specialisation(Specialisation.SpecName.swill.toString());

        nContext.goToArea(Specialisation.SpecName.crop, "Watermelon");

        NArea watermelonSlice = NContext.findOut(new NAlias("Watermelon Slice"), 1);

        if(watermelonSlice == null) {
            return Results.ERROR("PUT Area for Watermelon Slice required, but not found!");
        }

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(field);
        req.add(seed);
        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        req.add(trough);
        opt.add(swill);

        if (new Validator(req, opt).run(gui).IsSuccess()) {
            NUtils.stackSwitch(true);

            if ((Boolean) NConfig.get(NConfig.Key.validateAllCropsBeforeHarvest)) {
                if (!new ValidateAllCropsReady(NContext.findSpec(field), new NAlias("plants/watermelon")).run(gui).isSuccess) {
                    NUtils.stackSwitch(oldStackingValue);
                    gui.msg("Not all watermelon crops are ready for harvest, skipping harvest.");
                    return Results.SUCCESS();
                }
            }

            new HarvestCrop(
                    NContext.findSpec(field),
                    NContext.findSpec(seed),
                    NContext.findSpec(trough),
                    NContext.findSpec(swill),
                    new NAlias("plants/watermelon")
            ).run(gui);

            // Auto-equip traveller's sacks if setting is enabled
            if ((Boolean) NConfig.get(NConfig.Key.autoEquipTravellersSacks)) {
                new EquipTravellersSacksFromBelt().run(gui);
            }

            // "Watermelon" also matches the growing crop, the seeds and the slices, so all
            // three are excluded - what is left is the whole melons lying in the field.
            new LettucePumpkinAndWatermelonCollector(
                    NContext.findSpec(field),
                    NContext.findSpec(seed),
                    watermelonSlice,
                    new NAlias(Arrays.asList("items/watermelon", "Watermelon"), Arrays.asList("plants", "seed", "slice")),
                    LettucePumpkinAndWatermelonCollector.Product.WATERMELON,
                    NContext.findSpec(trough)
            ).run(gui);
            new SeedCrop(NContext.findSpec(field), NContext.findSpec(seed), new NAlias("plants/watermelon")).run(gui);

            NUtils.stackSwitch(oldStackingValue);

            return Results.SUCCESS();
        }

        NUtils.stackSwitch(oldStackingValue);

        return Results.FAIL();
    }
}
