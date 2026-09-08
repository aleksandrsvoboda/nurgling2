package nurgling.guarding;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** One configured row in a {@link GuardingProfile}: guard type, enabled, inputs, outcome. */
public final class GuardEntry {
    public String guardId;
    public boolean enabled = true;
    public Map<String, Double> settings = new LinkedHashMap<>();
    public String outcomeId = "break";

    public GuardEntry() {}

    public GuardEntry(String guardId, boolean enabled, String outcomeId) {
        this.guardId = guardId;
        this.enabled = enabled;
        this.outcomeId = outcomeId;
        fillDefaultSettings();
    }

    @SuppressWarnings("unchecked")
    public GuardEntry(HashMap<String, Object> map) {
        this.guardId = (String) map.get("guardId");
        this.enabled = !map.containsKey("enabled") || (Boolean) map.get("enabled");
        this.outcomeId = map.containsKey("outcomeId") ? (String) map.get("outcomeId") : "break";
        if (map.containsKey("settings")) {
            HashMap<String, Object> settingsMap = (HashMap<String, Object>) map.get("settings");
            for (Map.Entry<String, Object> e : settingsMap.entrySet()) {
                settings.put(e.getKey(), ((Number) e.getValue()).doubleValue());
            }
        }
        fillDefaultSettings();
    }

    /** Fills in any input the owning GuardSpec declares that isn't already present. */
    private void fillDefaultSettings() {
        GuardSpec spec = GuardRegistry.get(guardId);
        if (spec != null) {
            for (GuardInput input : spec.inputs) {
                settings.putIfAbsent(input.key, input.defaultValue);
            }
        }
    }

    /** Builds a live {@link Guard} from this entry, or null if disabled or its guard type is unknown. */
    public Guard toGuard() {
        if (!enabled) {
            return null;
        }
        GuardSpec spec = GuardRegistry.get(guardId);
        if (spec == null) {
            // Distinct from "disabled" - a saved profile pointing at a guard type that no longer
            // exists (e.g. after a rename) would otherwise silently do nothing with zero
            // indication the user's configured protection isn't actually active.
            try {
                nurgling.NUtils.getGameUI().error("Guarding: unknown guard type \"" + guardId + "\" - this entry is disabled until removed/reconfigured.");
            } catch (Exception e) {
                // Best-effort only - never let a missing/unready game UI break guard resolution itself.
            }
            return null;
        }
        GuardTrigger trigger = spec.factory.build(settings);
        GuardOutcome outcome = GuardOutcome.fromId(outcomeId);
        return new Guard(spec.label, trigger, outcome);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("guardId", guardId);
        json.put("enabled", enabled);
        json.put("outcomeId", outcomeId);
        JSONObject settingsJson = new JSONObject();
        for (Map.Entry<String, Double> e : settings.entrySet()) {
            settingsJson.put(e.getKey(), e.getValue());
        }
        json.put("settings", settingsJson);
        return json;
    }
}
