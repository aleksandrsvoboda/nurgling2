package nurgling.guarding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** One named, independently-saved/selected Guarding configuration: water/ignore-bats flags plus pre-flight and in-flight guard lists. */
public final class GuardingProfile {
    public boolean waterMode = false;
    public boolean ignoreBats = true;
    public List<GuardEntry> preflightGuards = new ArrayList<>();
    public List<GuardEntry> inflightGuards = new ArrayList<>();

    public GuardingProfile() {}

    @SuppressWarnings("unchecked")
    public GuardingProfile(HashMap<String, Object> map) {
        this.waterMode = map.containsKey("waterMode") && (Boolean) map.get("waterMode");
        this.ignoreBats = !map.containsKey("ignoreBats") || (Boolean) map.get("ignoreBats");
        if (map.containsKey("preflightGuards")) {
            for (HashMap<String, Object> em : (ArrayList<HashMap<String, Object>>) map.get("preflightGuards")) {
                preflightGuards.add(new GuardEntry(em));
            }
        }
        if (map.containsKey("inflightGuards")) {
            for (HashMap<String, Object> em : (ArrayList<HashMap<String, Object>>) map.get("inflightGuards")) {
                inflightGuards.add(new GuardEntry(em));
            }
        }
        reconcileWithRegistry();
    }

    /** A brand-new profile's guards, seeded with every registered guard enabled, reacting via "travel hearth". */
    public static GuardingProfile withDefaults() {
        GuardingProfile p = new GuardingProfile();
        for (String id : GuardRegistry.preflightIds()) {
            p.preflightGuards.add(new GuardEntry(id, true, "travel hearth"));
        }
        for (String id : GuardRegistry.inflightIds()) {
            p.inflightGuards.add(new GuardEntry(id, true, "travel hearth"));
        }
        return p;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("waterMode", waterMode);
        json.put("ignoreBats", ignoreBats);
        JSONArray pre = new JSONArray();
        for (GuardEntry e : preflightGuards) {
            pre.put(e.toJson());
        }
        json.put("preflightGuards", pre);
        JSONArray in = new JSONArray();
        for (GuardEntry e : inflightGuards) {
            in.put(e.toJson());
        }
        json.put("inflightGuards", in);
        return json;
    }

    /** Keeps an old saved profile in sync with whatever guard types are currently registered - adds missing ones enabled, drops stale ones. */
    public void reconcileWithRegistry() {
        preflightGuards = reconcileList(preflightGuards, GuardRegistry.preflightIds());
        inflightGuards = reconcileList(inflightGuards, GuardRegistry.inflightIds());
    }

    /** Returns a fresh list rather than mutating the one passed in, so a bot mid-iteration over the old reference can't hit a ConcurrentModificationException. */
    private List<GuardEntry> reconcileList(List<GuardEntry> list, List<String> knownIds) {
        List<GuardEntry> result = new ArrayList<>();
        for (GuardEntry e : list) {
            if (e.guardId != null && knownIds.contains(e.guardId)) {
                result.add(e);
            }
        }
        for (String id : knownIds) {
            boolean present = false;
            for (GuardEntry e : result) {
                if (id.equals(e.guardId)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                result.add(new GuardEntry(id, true, "travel hearth"));
            }
        }
        return result;
    }
}
