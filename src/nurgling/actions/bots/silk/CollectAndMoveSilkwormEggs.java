package nurgling.actions.bots.silk;

import nurgling.NGameUI;
import nurgling.actions.*;
import nurgling.areas.NContext;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.HashSet;

/**
 * Collects silkworm eggs from breeding cabinets and stores them in egg storage
 * Uses FreeInventory2 to automatically deposit eggs in the correct storage area
 */
public class CollectAndMoveSilkwormEggs implements Action {

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);

        Specialisation.SpecName specName = Specialisation.SpecName.silkmothBreeding;
        String item = "Silkworm Egg";
        /* Breeding cupboards already emptied of eggs. Without this every pass re-walks and
         * re-opens all of them, including one whole fruitless tour to discover it is done.
         * FreeInventory2 stores eggs in the egg PUT area, never back into these cupboards, so
         * nothing refills them mid-run. A moth that lays into an already-emptied cupboard while
         * we work is simply picked up on the next run - far cheaper than re-touring every pass. */
        HashSet<String> depletedCupboards = new HashSet<>();

        while (true) {
            int invSpace = gui.getInventory().getFreeSpace();
            int before = gui.getInventory().getItems(new NAlias(item)).size();

            TakeItems2 take = new TakeItems2(context, item, invSpace, specName);
            take.depleted = depletedCupboards;
            take.run(gui);

            int after = gui.getInventory().getItems(new NAlias(item)).size();

            boolean hasEggsInInventory = after > 0;

            if (hasEggsInInventory) {
                new FreeInventory2(context).run(gui);
            }

            if (after <= before) {
                return Results.SUCCESS();
            }
        }
    }
}
