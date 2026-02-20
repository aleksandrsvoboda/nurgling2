package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.actions.bots.ScenarioRunner;
import nurgling.scenarios.Scenario;
import nurgling.scenarios.ScenarioIcons;

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
            final NUI boundUI = NUtils.getUI();
            final NGameUI gui = (boundUI != null) ? boundUI.gui : null;
            if (gui == null) return;

            // Run scenario in background thread like other bots do
            Thread t = new Thread(() -> {
                NUtils.setThreadUI(boundUI);
                try {
                    ScenarioRunner runner = new ScenarioRunner(scenario);
                    runner.run(gui);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    gui.error("Scenario execution failed: " + e.getMessage());
                } finally {
                    NUtils.clearThreadUI();
                }
            }, "ScenarioRunner-" + scenario.getName());

            // Add to bot observer system like other actions
            gui.biw.addObserve(t);
            t.start();
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
