package nurgling.actions.bots;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

public class TarkilnAction implements Action {

    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        NArea.Specialisation tarkilnsa = new NArea.Specialisation(Specialisation.SpecName.tarkiln.toString());
        NArea area = NContext.findSpec(tarkilnsa);
        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(tarkilnsa);

        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        NContext context = new NContext(gui);

        NArea npile_area = NContext.findOut(new NAlias("Coal"),1);
        Pair<Coord2d,Coord2d> pile_area = npile_area!=null?npile_area.getRCArea():null;
        if(pile_area==null)
        {
            String onsaId = context.createArea("Please select area for output coal", Resource.loadsimg("baubles/coalPiles"));
            NArea onsaArea = context.goToAreaById(onsaId);
            pile_area = onsaArea.getRCArea();
        }

        String insaId = context.createArea("Please select area for fuel", Resource.loadsimg("baubles/fuel"));
        NArea insaArea = context.goToAreaById(insaId);

        if(new Validator(req, opt).run(gui).IsSuccess())
        {
            ArrayList<Gob> tarkilns = Finder.findGobs(area, new NAlias("gfx/terobjs/tarkiln"));
            tarkilns.sort(NUtils.d_comp);
            ArrayList<Gob> forRemove = new ArrayList<>();
            for(Gob tarkiln : tarkilns) {
                if((tarkiln.ngob.getModelAttribute()&4)!=0)
                    forRemove.add(tarkiln);
            }
            tarkilns.removeAll(forRemove);

            for(Gob tarkiln : tarkilns) {
                int attempts = 0;
                while (true) {
                    int freeBefore = NUtils.getGameUI().getInventory().getFreeSpace();
                    new CollectFromGob(tarkiln, "Collect coal", "gfx/borka/bushpickan", true, new Coord(1, 1), 8, new NAlias("Coal"), pile_area).run(gui);
                    if (NUtils.getGameUI().getInventory().getFreeSpace() >= 3) {
                        break;
                    }
                    attempts++;
                    new FreeInventory2(context).run(gui);
                    NUtils.navigateToArea(area);
                    int freeAfter = NUtils.getGameUI().getInventory().getFreeSpace();
                    if (freeAfter < 3 || (attempts >= 2 && freeAfter <= freeBefore)) {
                        break;
                    }
                }
            }
            new FreeInventory2(context).run(gui);
            NUtils.navigateToArea(area);

            if(!new FillFuelTarkilns(tarkilns,insaArea.getRCArea()).run(gui).IsSuccess())
                return Results.FAIL();
            ArrayList<String> flighted = new ArrayList<>();
            for (Gob cont : tarkilns) {
                flighted.add(cont.ngob.hash);
            }
            new LightGob(flighted,16).run(gui);
        }

        return Results.SUCCESS();
    }


}
