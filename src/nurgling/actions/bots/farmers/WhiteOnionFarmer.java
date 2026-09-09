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

public class WhiteOnionFarmer implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext nContext = new NContext(gui);
        boolean oldStackingValue = ((NInventory) NUtils.getGameUI().maininv).bundle.a;

        NArea.Specialisation field = new NArea.Specialisation(Specialisation.SpecName.crop.toString(), "White Onion");
        // White onions have no seed item - the onion itself is the planting material, so the
        // "seed" area is where the harvested onions are piled and replanted from.
        NArea.Specialisation whiteOnionAsSeed = new NArea.Specialisation(Specialisation.SpecName.seed.toString(), "White Onion");
        NArea.Specialisation trough = new NArea.Specialisation(Specialisation.SpecName.trough.toString());
        NArea.Specialisation swill = new NArea.Specialisation(Specialisation.SpecName.swill.toString());

        nContext.goToArea(Specialisation.SpecName.crop, "White Onion");

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(field);
        req.add(whiteOnionAsSeed);
        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        opt.add(swill);

        if (new Validator(req, opt).run(gui).IsSuccess()) {
            NUtils.stackSwitch(true);

            if ((Boolean) NConfig.get(NConfig.Key.validateAllCropsBeforeHarvest)) {
                if (!new ValidateAllCropsReady(NContext.findSpec(field), new NAlias("plants/whiteonion")).run(gui).isSuccess) {
                    NUtils.stackSwitch(oldStackingValue);
                    gui.msg("Not all white onion crops are ready for harvest, skipping harvest.");
                    return Results.SUCCESS();
                }
            }

            new HarvestCrop(
                    NContext.findSpec(field),
                    NContext.findSpec(whiteOnionAsSeed),
                    NContext.findSpec(trough),
                    NContext.findSpec(swill),
                    new NAlias("plants/whiteonion")
            ).run(gui);

            // Auto-equip traveller's sacks if setting is enabled
            if ((Boolean) NConfig.get(NConfig.Key.autoEquipTravellersSacks)) {
                new EquipTravellersSacksFromBelt().run(gui);
            }

            if (NContext.findSpec(whiteOnionAsSeed) != null)
                new CollectItemsToPile(NContext.findSpec(field).getRCArea(), NContext.findSpec(whiteOnionAsSeed).getRCArea(), new NAlias("items/whiteonion", "White Onion")).run(gui);

            new SeedCrop(NContext.findSpec(field), NContext.findSpec(whiteOnionAsSeed), new NAlias("plants/whiteonion")).run(gui);

            NUtils.stackSwitch(oldStackingValue);

            return Results.SUCCESS();
        }

        NUtils.stackSwitch(oldStackingValue);

        return Results.FAIL();
    }
}
