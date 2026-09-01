package nurgling.guarding;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** One configured row in a {@link GuardingProfile} - which guard type, whether it's enabled,
 *  its resolved input values, and what to do when it fires. */
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

    public GuardEntry(JSONObject json) {
        this.guardId = json.optString("guardId", null);
        this.enabled = json.optBoolean("enabled", true);
        this.outcomeId = json.optString("outcomeId", "break");
        JSONObject settingsJson = json.optJSONObject("settings");
        if (settingsJson != null) {
            for (String key : settingsJson.keySet()) {
                // optDouble(key) alone returns NaN for a missing/non-numeric value - and since
                // the key would then still be present in the map, fillDefaultSettings()'s
                // putIfAbsent below could never replace that NaN with the guard's real default,
                // silently breaking every comparison against it (NaN comparisons are always
                // false in Java) for the rest of the run. Skip a NaN read entirely instead, so
                // it's treated the same as a genuinely-missing key.
                double v = settingsJson.optDouble(key);
                if (!Double.isNaN(v)) {
                    settings.put(key, v);
                }
            }
        }
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

    /** Fills in any input the owning GuardSpec declares that isn't already present - covers a
     *  brand-new entry, an old saved entry from before an input was added to its guard type,
     *  and a partially-populated JSON blob alike. */
    private void fillDefaultSettings() {
        GuardSpec spec = GuardRegistry.get(guardId);
        if (spec != null) {
            for (GuardInput input : spec.inputs) {
                settings.putIfAbsent(input.key, input.defaultValue);
            }
        }
    }

    /** Builds a live {@link Guard} from this entry, or null if disabled or its guard type is
     *  unknown (e.g. an old/removed id in a saved profile - degrades gracefully rather than
     *  erroring the whole run over one stale entry). */
    public Guard toGuard() {
        if (!enabled) {
            return null;
        }
        GuardSpec spec = GuardRegistry.get(guardId);
        if (spec == null) {
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
