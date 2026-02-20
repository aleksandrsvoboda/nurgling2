package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.actions.bots.ScenarioRunner;
import nurgling.scenarios.Scenario;
import nurgling.scenarios.ScenarioIcons;
import nurgling.sessions.BotExecutor;

public class NScenarioButton extends IButton {
    private final Scenario scenario;

    public NScenarioButton(Scenario scenario) {
        super(
            ScenarioIcons.loadScenarioIconUp(scenario),
            ScenarioIcons.loadScenarioIconDown(scenario),
            ScenarioIcons.loadScenarioIconHover(scenario)
        );
        this.scenario = scenario;
        setupButton();
    }

    private void setupButton() {
        // Set up the click action for direct scenario execution
        this.action(() -> executeScenario());
    }

    private void executeScenario() {
        if (scenario != null) {
            BotExecutor.runAsync("ScenarioRunner-" + scenario.getName(), new ScenarioRunner(scenario));
        }
    }
    
    @Override
    public Object tooltip(Coord c, Widget prev) {
        if (scenario != null) {
            return scenario.getName();
        }
        return super.tooltip(c, prev);
    }
    
    public Scenario getScenario() {
        return scenario;
    }
    
    public String getDisplayName() {
        return scenario != null ? scenario.getName() : "Unknown Scenario";
    }
}
