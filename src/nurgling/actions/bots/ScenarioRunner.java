package nurgling.actions.bots;

import nurgling.NGameUI;
import nurgling.actions.*;
import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.scenarios.*;
import nurgling.actions.bots.registry.BotRegistry;

public class ScenarioRunner implements Action {
    private final Scenario scenario;

    public ScenarioRunner(Scenario scenario) {
        this.scenario = scenario;
    }

    /**
     * The stacking mode the whole scenario needs: DISABLED if any step needs stacking off,
     * otherwise ENABLED if any step needs it on, otherwise leave the player's setting alone.
     */
    public BotDescriptor.StackMode stackMode() {
        BotDescriptor.StackMode mode = BotDescriptor.StackMode.UNCHANGED;
        for (BotStep step : scenario.getSteps()) {
            BotDescriptor desc = BotRegistry.byId(step.getId());
            if (desc == null)
                continue;
            if (desc.stackMode == BotDescriptor.StackMode.DISABLED)
                return BotDescriptor.StackMode.DISABLED;
            if (desc.stackMode == BotDescriptor.StackMode.ENABLED)
                mode = BotDescriptor.StackMode.ENABLED;
        }
        return mode;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        for (BotStep step : scenario.getSteps()) {
            BotDescriptor desc = BotRegistry.byId(step.getId());
            Action bot = (desc != null) ? desc.instantiate(step.getSettings()) : null;
            if (bot == null) {
                gui.msg("ScenarioRunner: Unknown bot key: " + step.getId());
                return Results.FAIL();
            }
            Results result = bot.run(gui);
            if (!result.IsSuccess()) {
                gui.msg("ScenarioRunner: Bot failed: " + step.getId());
                return result;
            }
        }
        return Results.SUCCESS();
    }
}
