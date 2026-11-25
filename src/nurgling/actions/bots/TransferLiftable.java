package nurgling.actions.bots;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.actions.cart.CartHauler;
import nurgling.actions.cart.SelectCart;
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
 * - requireGlobalZones=false (default): prompts user for both input and output zones every run
 * - requireGlobalZones=true: requires global CarrierOut zone (errors if not found), uses global sorting zone for input with prompt fallback
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
            NUtils.getUI().core.addTask(new WaitCheckable(NUtils.getGameUI().add((w = new nurgling.widgets.bots.Carrier(true)), UI.scale(200, 200))));
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

        NArea carrierOutArea;
        NArea inarea;

        if (requireGlobalZones) {
            carrierOutArea = context.findArea(Specialisation.SpecName.carrierout);
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
            String outsaId = context.createArea("Please, select output area", Resource.loadsimg("baubles/outputArea"));
            carrierOutArea = context.goToAreaById(outsaId);
        }


        final NArea source = inarea;
        final NAlias alias = new NAlias(prop.object);
        CarryLiftablesToArea.Source from = new CarryLiftablesToArea.Source() {
            @Override
            public ArrayList<Gob> candidates() throws InterruptedException {
                return Finder.findGobs(source, alias);
            }

            @Override
            public void goBack() throws InterruptedException {
                NUtils.navigateToArea(source);
            }
        };
        String unreachable = "Can't reach any " + prop.object + " in current area, skipping...";

        Gob cart = SelectCart.ask(gui);
        CartHauler hauler = (cart != null) ? new CartHauler(gui, cart) : null;
        try {
            return new CarryLiftablesToArea(from, carrierOutArea, prop.spread, unreachable, hauler, true).run(gui);
        } finally {
            if (hauler != null)
                hauler.detach();
        }
    }
}
