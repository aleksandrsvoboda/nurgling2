package nurgling.scenarios;

import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.sessions.BotExecutor;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class ScenarioManager {
    private final Map<Integer, Scenario> scenarios = new HashMap<>();
    private boolean needsUpdate = false;

    public ScenarioManager() {
        loadScenarios();
    }

    public void loadScenarios() {
        scenarios.clear();
        String content = NFileUtils.readWithBackupFallback(NConfig.current.getScenariosPath());
        if (content != null && !content.isEmpty()) {
            try {
                JSONObject main = new JSONObject(content);
                JSONArray array = main.getJSONArray("scenarios");
                for (int i = 0; i < array.length(); i++) {
                    Scenario scenario = new Scenario(array.getJSONObject(i));
                    scenarios.put(scenario.getId(), scenario);
                }
                needsUpdate = false;
            } catch (org.json.JSONException e) {
                System.err.println("[ScenarioManager] Failed to parse scenarios file (corrupt JSON): " + e.getMessage());
            }
        }
    }

    public void writeScenarios(String customPath) {
        JSONObject main = new JSONObject();
        JSONArray jscenarios = new JSONArray();
        for (Scenario scenario : scenarios.values()) {
            jscenarios.put(scenario.toJson());
        }
        main.put("scenarios", jscenarios);

        try {
            NFileUtils.writeAtomically(customPath == null ? NConfig.current.getScenariosPath() : customPath, main.toString());
            needsUpdate = false;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void addOrUpdateScenario(Scenario scenario) {
        scenarios.put(scenario.getId(), scenario);
        needsUpdate = true;
    }

    public void deleteScenario(int scenarioId) {
        scenarios.remove(scenarioId);
        needsUpdate = true;
    }

    public Map<Integer, Scenario> getScenarios() {
        return scenarios;
    }

    public void executeScenarioByName(String scenarioName, NGameUI gui) {
        NUI boundUI = NUtils.getUI();
        if (gui == null) {
            gui = (boundUI != null) ? boundUI.gui : null;
        }
        if (gui == null) return;
        final NGameUI finalGui = gui;

        for(Scenario scenario : this.getScenarios().values()) {
            if(scenario.getName().equals(scenarioName)) {
                BotExecutor.runAsync("ScenarioRunner-" + scenarioName,
                    new nurgling.actions.bots.ScenarioRunner(scenario));
                return;
            }
        }
        finalGui.error("Scenario not found: " + scenarioName);
    }
}
