package nurgling.actions.bots;

import haven.WItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.actions.SelectFlowerAction;
import nurgling.actions.TakeItems2;
import nurgling.areas.NContext;
import nurgling.tools.NBuffChecker;

import java.util.ArrayList;

/** Takes one Tansy from its configured Take area and rubs it on skin, unless the character
 *  already has the Scent of Tansy buff (which keeps midges - and the swamp fever risk they carry -
 *  away). Shared by the standalone bot and the Forager waypoint-step wrapper. */
public class ApplyTansyIfMissing implements Action {

    private static final String ITEM_NAME = "Tansy";
    private static final String FLOWER_ACTION = "Rub on skin";

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if (NBuffChecker.hasScentOfTansy()) {
            return Results.SUCCESS();
        }

        NContext context = new NContext(gui);
        // getInStorages (called inside TakeItems2) only ever looks at areas already registered
        // into NContext's own inAreas map - it does no discovery itself. addInItem is what
        // actually finds the existing area tagged with this item (or prompts to create one, if a
        // fallback image were given) and registers it, matching the pattern every other bot using
        // NContext+TakeItems2 already follows (e.g. BakerAction's addInItem(doughName, null)).
        // Without this, TakeItems2 silently finds zero storages and never even attempts to path
        // anywhere - the exact symptom reported live.
        context.addInItem(ITEM_NAME, null);

        Results takeResult = new TakeItems2(context, ITEM_NAME, 1).run(gui);
        if (!takeResult.IsSuccess()) {
            // No Tansy configured/available - not an error the user needs a popup for, just
            // nothing to do this time.
            return Results.FAIL();
        }

        ArrayList<WItem> items = NUtils.getGameUI().getInventory().getItems(ITEM_NAME);
        if (items.isEmpty()) {
            return Results.FAIL();
        }
        new SelectFlowerAction(FLOWER_ACTION, items.get(0)).run(gui);

        return Results.SUCCESS();
    }
}
