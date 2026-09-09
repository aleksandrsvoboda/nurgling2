package nurgling.actions.bots;

import haven.WItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.actions.SelectFlowerAction;
import nurgling.actions.TakeItems2;
import nurgling.areas.NContext;
import nurgling.tasks.WaitTicks;
import nurgling.tools.NBuffChecker;

import java.util.ArrayList;
import java.util.Map;

/** Applies Tansy from its configured Take area, rubbing it on skin repeatedly until the "Scent of
 *  Tansy" buff (which keeps midges - and the swamp fever risk they carry - away) reaches a target
 *  stack count, rather than just a one-shot "apply if missing". The buff's count rises on each
 *  application and falls on each midge bite, so topping it up to a safe margin lasts longer than a
 *  single application. Shared by the standalone bot and the Scheduler step wrapper. */
public class ApplyTansyIfMissing implements Action {

    private static final String ITEM_NAME = "Tansy";
    private static final String FLOWER_ACTION = "Rub on skin";
    private static final int DEFAULT_TARGET_STACKS = 10;
    // Gives the buff's stack count a moment to register server-side before the next re-check.
    private static final int APPLY_SETTLE_TICKS = 20;

    private final int targetStacks;

    public ApplyTansyIfMissing() {
        this.targetStacks = DEFAULT_TARGET_STACKS;
    }

    public ApplyTansyIfMissing(Map<String, Object> settings) {
        int target = DEFAULT_TARGET_STACKS;
        if (settings != null && settings.containsKey("targetStacks")) {
            Object t = settings.get("targetStacks");
            if (t instanceof Number) {
                target = ((Number) t).intValue();
            }
        }
        this.targetStacks = target;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);
        // getInStorages (called inside TakeItems2) only ever looks at areas already registered
        // into NContext's own inAreas map - it does no discovery itself. addInItem is what
        // actually finds the existing area tagged with this item and registers it, matching the
        // pattern every other bot using NContext+TakeItems2 already follows (e.g. BakerAction's
        // addInItem(doughName, null)).
        context.addInItem(ITEM_NAME, null);

        int count = NBuffChecker.getScentOfTansyCount();
        boolean appliedAny = false;

        // Bounded by targetStacks itself - each successful, observable apply raises count by at
        // least one, so this can't loop more than targetStacks times even in the worst case.
        while (count < targetStacks) {
            Results takeResult = new TakeItems2(context, ITEM_NAME, 1).run(gui);
            if (!takeResult.IsSuccess()) {
                // No more Tansy available - keep whatever we already applied this run.
                break;
            }

            ArrayList<WItem> items = NUtils.getGameUI().getInventory().getItems(ITEM_NAME);
            if (items.isEmpty()) {
                break;
            }
            new SelectFlowerAction(FLOWER_ACTION, items.get(0)).run(gui);
            appliedAny = true;

            NUtils.getUI().core.addTask(new WaitTicks(APPLY_SETTLE_TICKS));
            int newCount = NBuffChecker.getScentOfTansyCount();
            if (newCount <= count) {
                // The stack count didn't move - buff isn't readable, or it's already capped.
                // Stop instead of spinning through the rest of the Take area for nothing.
                break;
            }
            count = newCount;
        }

        return (appliedAny || count >= targetStacks) ? Results.SUCCESS() : Results.FAIL();
    }
}
