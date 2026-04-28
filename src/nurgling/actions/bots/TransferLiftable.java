package nurgling.actions.bots;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.conf.NCarrierProp;
import nurgling.tasks.WaitCheckable;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

/**
 * Transfers liftable objects between zones.
 * Two modes:
 * - requireGlobalZones=false (default): prompts user to select input zone, uses global CarrierOut or prompts for output
 * - requireGlobalZones=true: requires global CarrierOut zone (errors if not found), tries global sorting zone for input with prompt fallback
 */
public class TransferLiftable implements Action
{
    protected final boolean requireGlobalZones;

    public TransferLiftable() { this.requireGlobalZones = false; }
    public TransferLiftable(boolean requireGlobalZones) { this.requireGlobalZones = requireGlobalZones; }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        nurgling.widgets.bots.Carrier w = null;
        NCarrierProp prop = null;
        try
        {
            NUtils.getUI().core.addTask(new WaitCheckable(NUtils.getGameUI().add((w = new nurgling.widgets.bots.Carrier()), UI.scale(200, 200))));
            prop = w.prop;
        } catch (InterruptedException e)
        {
            throw e;
        } finally
        {
            if (w != null)
                w.destroy();
        }
        if (prop == null)
        {
            return Results.ERROR("No config");
        }

        NContext context = new NContext(gui);

        NArea carrierOutArea = context.findArea(Specialisation.SpecName.carrierout);

        NArea inarea;

        if (requireGlobalZones) {
            if (carrierOutArea == null) {
                return Results.ERROR("No CarrierOut zone found! Please create a global zone with 'carrierout' specialization.");
            }
            inarea = context.findArea(Specialisation.SpecName.sorting);
            if (inarea == null) {
                String insaId = context.createArea("Please, select input area", Resource.loadsimg("baubles/inputArea"));
                inarea = context.goToAreaById(insaId);
            }
        } else {
            String insaId = context.createArea("Please, select input area", Resource.loadsimg("baubles/inputArea"));
            inarea = context.goToAreaById(insaId);
            if (carrierOutArea == null) {
                String outsaId = context.createArea("Please, select output area", Resource.loadsimg("baubles/outputArea"));
                carrierOutArea = context.goToAreaById(outsaId);
            }
        }


        ArrayList<Gob> items;
        while (!(items = Finder.findGobs(inarea, new NAlias(prop.object))).isEmpty())
        {
            ArrayList<Gob> availableItems = new ArrayList<>();
            for (Gob currGob : items)
            {
                if (PathFinder.isAvailable(currGob))
                    availableItems.add(currGob);
            }
            if (availableItems.isEmpty())
            {
                NUtils.getGameUI().msg("Can't reach any " + prop.object + " in current area, skipping...");
                break;
            }

            availableItems.sort(NUtils.d_comp);
            Gob item = availableItems.get(0);

            // Lift the item
            new LiftObject(item).run(gui);

            NUtils.navigateToArea(carrierOutArea);
            // Move to output area and place the item
            new FindPlaceAndAction(null, carrierOutArea.getRCArea()).run(gui);

            // Move away from the placed item
            Coord2d shift = item.rc.sub(NUtils.player().rc).norm().mul(2);
            new GoTo(NUtils.player().rc.sub(shift)).run(gui);
            NUtils.navigateToArea(inarea);
        }

        return Results.SUCCESS();
    }
}
