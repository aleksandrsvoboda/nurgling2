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

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        return runSteps(gui, scenario.getSteps());
    }

    /** Sequentially runs an ordered list of {@link BotStep}s: resolve id -> BotDescriptor ->
     *  Action, run it, abort on the first non-success result. Shared by scenario execution above
     *  and by any other caller that just needs to run a plain step list (e.g. a Forager waypoint's
     *  attached steps) without wrapping it in a full {@link nurgling.scenarios.Scenario}. */
    public static Results runSteps(NGameUI gui, java.util.List<BotStep> steps) throws InterruptedException {
        for (BotStep step : steps) {
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
