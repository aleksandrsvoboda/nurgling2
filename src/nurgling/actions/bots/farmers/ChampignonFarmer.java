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

/**
 * Harvests and replants a champignon bed. Champignons have no seeds - the mushrooms are
 * replanted - grow on woodchips, and can be kept in containers only. The harvest goes to the
 * containers of the seed(Champignon) area, wherever it is, and replanting takes from there
 * (Button first, then Cremini, then Portobello). The field's harvest stage is honoured.
 */
public class ChampignonFarmer implements Action {
    private static final String CROP = "Champignon";
    private static final NAlias PLANT = new NAlias("plants/champignon");

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext nContext = new NContext(gui);
        boolean oldStackingValue = ((NInventory) NUtils.getGameUI().maininv).bundle.a;

        NArea.Specialisation field = new NArea.Specialisation(Specialisation.SpecName.crop.toString(), CROP);
        NArea.Specialisation storeSpec = new NArea.Specialisation(Specialisation.SpecName.seed.toString(), CROP);

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(field);
        req.add(storeSpec);

        if (!new Validator(req, new ArrayList<>()).run(gui).IsSuccess()) {
            return Results.FAIL();
        }

        NArea storeArea = nContext.findArea(Specialisation.SpecName.seed, CROP);
        NArea fieldArea = nContext.goToArea(Specialisation.SpecName.crop, CROP);
        if (fieldArea == null || storeArea == null) {
            return Results.ERROR("Areas crop(Champignon) and seed(Champignon) are required");
        }
        AreaContainerStore store = new AreaContainerStore(storeArea, fieldArea);

        NUtils.stackSwitch(true);

        if (!new ValidateAllCropsReady(fieldArea, field, PLANT,
                (Boolean) NConfig.get(NConfig.Key.validateAllCropsBeforeHarvest)).run(gui).isSuccess) {
            NUtils.stackSwitch(oldStackingValue);
            return Results.SUCCESS();
        }

        new HarvestCrop(fieldArea, store, PLANT, field).run(gui);

        if ((Boolean) NConfig.get(NConfig.Key.autoEquipTravellersSacks)) {
            new EquipTravellersSacksFromBelt().run(gui);
        }

        new SeedCrop(fieldArea, store, PLANT).run(gui);

        NUtils.stackSwitch(oldStackingValue);
        return Results.SUCCESS();
    }
}
