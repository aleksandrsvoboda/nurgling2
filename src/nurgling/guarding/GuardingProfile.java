package nurgling.guarding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * One named, independently-saved/selected Guarding configuration (Forager Settings &gt;
 * Guarding) - water mode/ignore bats mode flags, plus the pre-flight and in-flight guard lists
 * (each a {@link GuardEntry} per {@link GuardSpec} registered for that phase).
 */
public final class GuardingProfile {
    public boolean waterMode = false;
    public boolean ignoreBats = true;
    public List<GuardEntry> preflightGuards = new ArrayList<>();
    public List<GuardEntry> inflightGuards = new ArrayList<>();

    public GuardingProfile() {}

    public GuardingProfile(JSONObject json) {
        this.waterMode = json.optBoolean("waterMode", false);
        this.ignoreBats = json.optBoolean("ignoreBats", true);
        JSONArray pre = json.optJSONArray("preflightGuards");
        if (pre != null) {
            for (int i = 0; i < pre.length(); i++) {
                preflightGuards.add(new GuardEntry(pre.getJSONObject(i)));
            }
        }
        JSONArray in = json.optJSONArray("inflightGuards");
        if (in != null) {
            for (int i = 0; i < in.length(); i++) {
                inflightGuards.add(new GuardEntry(in.getJSONObject(i)));
            }
        }
        reconcileWithRegistry();
    }

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

    /** A brand-new profile's guards, seeded with every registered guard enabled and reacting
     *  the same way the fixed checks they replace always did (travel to hearth unconditionally)
     *  - so a fresh profile behaves like the old always-on watchdog until the user deliberately
     *  changes something. */
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

    /** Fills in any guard id newly registered since this profile was saved (e.g. a new guard
     *  type added in code) with a sensible enabled-by-default entry, and drops any
     *  no-longer-registered ones - keeps an old saved profile automatically in sync with
     *  whatever's currently registered, so a new GuardSpec shows up (with working defaults) in
     *  every existing profile too, not just brand-new ones. Called from both deserializing
     *  constructors above; safe to call again any time (e.g. right after GuardRegistry gains an
     *  entry mid-session, if that ever becomes possible). */
    public void reconcileWithRegistry() {
        preflightGuards = reconcileList(preflightGuards, GuardRegistry.preflightIds());
        inflightGuards = reconcileList(inflightGuards, GuardRegistry.inflightIds());
    }

    /** Returns a fresh list rather than mutating the one passed in - a running Forager bot
     *  (resolveGuardingProfile() in Forager.java) holds a live reference to this same
     *  GuardingProfile and reads its preflightGuards/inflightGuards fields directly; a
     *  ConcurrentModificationException is possible if Forager Settings reconciles this profile
     *  (e.g. just by opening the panel while it's selected) while a bot using it is mid-iteration
     *  over the *same* list object. Building a new list and reassigning the field means any
     *  reader that already captured the old reference keeps working off a stable snapshot
     *  instead of racing a structural edit to it. */
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
