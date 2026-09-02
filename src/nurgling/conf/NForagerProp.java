package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.guarding.GuardEntry;
import nurgling.guarding.GuardingProfile;
import nurgling.routes.ForagerAction;
import nurgling.routes.ForagerPath;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class NForagerProp implements JConf {
    
    private final String username;
    private final String chrid;
    public String currentPreset = "Default";
    public HashMap<String, PresetData> presets = new HashMap<>();

    // Actions profiles: named, independently-saved pickup-action lists (see
    // ForagerPickupContainer), decoupled from a single bundled PresetData so the same actions
    // set can be reused across different routes/guarding profiles. Additive alongside `presets`
    // for now - existing presets keep working unchanged until the bot-start flow is migrated to
    // pick an actions profile, a route, and a guarding profile independently.
    public String currentActionsProfile = "Default";
    public HashMap<String, ArrayList<ForagerAction>> actionsProfiles = new HashMap<>();

    // Guarding profiles: named, independently-saved safety-watchdog configurations (Forager
    // Settings > Guarding), each a list of composable Guard entries (see
    // nurgling.guarding.GuardingProfile) - decoupled from PresetData the same way
    // actionsProfiles already is above.
    public String currentGuardingProfile = "Default";
    public HashMap<String, GuardingProfile> guardingProfiles = new HashMap<>();

    public static class PresetData {
        public String pathFile = "";
        public transient ForagerPath foragerPath = null;
        public ArrayList<ForagerAction> actions = new ArrayList<>();

        // Legacy per-preset safety fields - superseded by guardingProfileName/GuardingProfile
        // below (see nurgling.guarding). Kept only because the guardingProfiles migration in
        // the deserializing constructor still reads them off an already-populated PresetData
        // to seed a profile for an old config that predates guardingProfiles entirely; not
        // read by any live bot logic any more.
        public String onPlayerAction = "nothing";
        public String onAnimalAction = "logout";
        public boolean ignoreBats = true;
        public boolean waterMode = false;

        public String afterFinishAction = "nothing";
        public String onFullInventoryAction = "nothing";

        // Which Actions/Guarding Profile this preset runs with (null = not yet assigned; the
        // deserializing constructor defaults every preset missing one to whatever
        // currentActionsProfile/currentGuardingProfile already resolved to, so an old preset
        // keeps behaving exactly as it did before presets could each pick their own). A preset
        // is now the full "which route + which actions + which guarding, plus start
        // area/finish reactions" bundle - editing lives in Forager Settings' Presets section,
        // the bot-launch window only selects a preset by name.
        public String actionsProfileName = null;
        public String guardingProfileName = null;

        public PresetData() {}

        public PresetData(String pathFile) {
            this.pathFile = pathFile;
        }
    }
    
    public NForagerProp(String username, String chrid) {
        this.username = username;
        this.chrid = chrid;
        PresetData defaultPreset = new PresetData();
        defaultPreset.actionsProfileName = "Default";
        defaultPreset.guardingProfileName = "Default";
        presets.put("Default", defaultPreset);
        actionsProfiles.put("Default", new ArrayList<>());
        guardingProfiles.put("Default", GuardingProfile.withDefaults());
    }
    
    @SuppressWarnings("unchecked")
    public NForagerProp(HashMap<String, Object> values) {
        chrid = (String) values.get("chrid");
        username = (String) values.get("username");
        if (values.get("currentPreset") != null)
            currentPreset = (String) values.get("currentPreset");
        
        presets = new HashMap<>();
        if (values.get("presets") != null) {
            HashMap<String, HashMap<String, Object>> presetsMap = 
                (HashMap<String, HashMap<String, Object>>) values.get("presets");
            for (Map.Entry<String, HashMap<String, Object>> entry : presetsMap.entrySet()) {
                PresetData pd = new PresetData();
                if (entry.getValue().get("pathFile") != null)
                    pd.pathFile = (String) entry.getValue().get("pathFile");
                
                if (entry.getValue().get("actions") != null) {
                    ArrayList<HashMap<String, Object>> actionsData = 
                        (ArrayList<HashMap<String, Object>>) entry.getValue().get("actions");
                    for (HashMap<String, Object> actionMap : actionsData) {
                        pd.actions.add(new ForagerAction(actionMap));
                    }
                }
                
                if (entry.getValue().get("onPlayerAction") != null)
                    pd.onPlayerAction = (String) entry.getValue().get("onPlayerAction");
                if (entry.getValue().get("onAnimalAction") != null)
                    pd.onAnimalAction = (String) entry.getValue().get("onAnimalAction");
                if (entry.getValue().get("afterFinishAction") != null)
                    pd.afterFinishAction = (String) entry.getValue().get("afterFinishAction");
                if (entry.getValue().get("onFullInventoryAction") != null)
                    pd.onFullInventoryAction = (String) entry.getValue().get("onFullInventoryAction");
                if (entry.getValue().get("ignoreBats") != null)
                    pd.ignoreBats = (Boolean) entry.getValue().get("ignoreBats");
                if (entry.getValue().get("waterMode") != null)
                    pd.waterMode = (Boolean) entry.getValue().get("waterMode");
                if (entry.getValue().get("actionsProfileName") != null)
                    pd.actionsProfileName = (String) entry.getValue().get("actionsProfileName");
                if (entry.getValue().get("guardingProfileName") != null)
                    pd.guardingProfileName = (String) entry.getValue().get("guardingProfileName");

                presets.put(entry.getKey(), pd);
            }
        }
        
        if (presets.isEmpty()) {
            presets.put("Default", new PresetData());
        }

        if (values.get("currentActionsProfile") != null)
            currentActionsProfile = (String) values.get("currentActionsProfile");

        actionsProfiles = new HashMap<>();
        if (values.get("actionsProfiles") != null) {
            HashMap<String, ArrayList<HashMap<String, Object>>> profilesMap =
                (HashMap<String, ArrayList<HashMap<String, Object>>>) values.get("actionsProfiles");
            for (Map.Entry<String, ArrayList<HashMap<String, Object>>> entry : profilesMap.entrySet()) {
                ArrayList<ForagerAction> profileActions = new ArrayList<>();
                for (HashMap<String, Object> actionMap : entry.getValue()) {
                    profileActions.add(new ForagerAction(actionMap));
                }
                actionsProfiles.put(entry.getKey(), profileActions);
            }
        }
        if (actionsProfiles.isEmpty()) {
            // Legacy config (predates actionsProfiles, or a not-yet-migrated in-development
            // save from earlier in this same refactor): carry over whatever was already picked
            // up per-preset via the old `actions` field, one profile per preset name, rather
            // than silently presenting an empty pickup list. Falls back to one bare "Default"
            // only if there was nothing to carry over.
            for (Map.Entry<String, PresetData> entry : presets.entrySet()) {
                if (!entry.getValue().actions.isEmpty()) {
                    actionsProfiles.put(entry.getKey(), new ArrayList<>(entry.getValue().actions));
                }
            }
            if (actionsProfiles.isEmpty()) {
                actionsProfiles.put("Default", new ArrayList<>());
            }
            if (!actionsProfiles.containsKey(currentActionsProfile)) {
                currentActionsProfile = actionsProfiles.containsKey(currentPreset)
                        ? currentPreset : actionsProfiles.keySet().iterator().next();
            }
        }

        if (values.get("currentGuardingProfile") != null)
            currentGuardingProfile = (String) values.get("currentGuardingProfile");

        guardingProfiles = new HashMap<>();
        if (values.get("guardingProfiles") != null) {
            HashMap<String, HashMap<String, Object>> profilesMap =
                (HashMap<String, HashMap<String, Object>>) values.get("guardingProfiles");
            for (Map.Entry<String, HashMap<String, Object>> entry : profilesMap.entrySet()) {
                guardingProfiles.put(entry.getKey(), new GuardingProfile(entry.getValue()));
            }
        }
        if (guardingProfiles.isEmpty()) {
            // Legacy config predating guardingProfiles: migrate each existing preset's own
            // onAnimalAction/ignoreBats/waterMode into its own named GuardingProfile, one per
            // preset name (same shape actionsProfiles' own migration above already uses),
            // rather than silently discarding those settings or presenting bare defaults. A
            // preset's onAnimalAction=="nothing" used to mean the whole animal scan was
            // skipped entirely (see the old detectThreat()'s `if
            // (!preset.onAnimalAction.equals("nothing"))` guard) - the new model's equivalent
            // of "skip this check" is disabling its guard outright, not picking an outcome that
            // does nothing (that option no longer exists), so that maps to enabled=false here
            // rather than outcomeId="break".
            for (Map.Entry<String, PresetData> entry : presets.entrySet()) {
                PresetData pd = entry.getValue();
                GuardingProfile migrated = GuardingProfile.withDefaults();
                migrated.waterMode = pd.waterMode;
                migrated.ignoreBats = pd.ignoreBats;
                for (GuardEntryPatch patch : new GuardEntryPatch[]{new GuardEntryPatch("dangerous_animal", pd.onAnimalAction)}) {
                    patch.applyTo(migrated.inflightGuards);
                }
                guardingProfiles.put(entry.getKey(), migrated);
            }
            if (guardingProfiles.isEmpty()) {
                guardingProfiles.put("Default", GuardingProfile.withDefaults());
            }
            if (!guardingProfiles.containsKey(currentGuardingProfile)) {
                currentGuardingProfile = guardingProfiles.containsKey(currentPreset)
                        ? currentPreset : guardingProfiles.keySet().iterator().next();
            }
        }

        // Every preset now carries its own Actions/Guarding Profile selection (Presets phase)
        // instead of the bot-launch window picking one prop-wide - default any preset that
        // doesn't have one yet (every existing preset, on first load after this shipped) to
        // whatever currentActionsProfile/currentGuardingProfile already resolved to above, so
        // an old preset keeps running with exactly the profile it always did until the user
        // deliberately changes it in Forager Settings > Presets.
        for (PresetData pd : presets.values()) {
            if (pd.actionsProfileName == null || !actionsProfiles.containsKey(pd.actionsProfileName)) {
                pd.actionsProfileName = currentActionsProfile;
            }
            if (pd.guardingProfileName == null || !guardingProfiles.containsKey(pd.guardingProfileName)) {
                pd.guardingProfileName = currentGuardingProfile;
            }
        }
    }

    /** One-shot helper for the migration above: finds the named guard entry in a freshly
     *  seeded GuardingProfile's list and applies an old preset's action string to it (mapping
     *  "nothing" to disabling the guard, any other value to that outcome, enabled). */
    private static final class GuardEntryPatch {
        final String guardId;
        final String oldAction;

        GuardEntryPatch(String guardId, String oldAction) {
            this.guardId = guardId;
            this.oldAction = oldAction;
        }

        void applyTo(List<GuardEntry> list) {
            for (GuardEntry e : list) {
                if (guardId.equals(e.guardId)) {
                    if ("nothing".equals(oldAction)) {
                        e.enabled = false;
                    } else {
                        e.enabled = true;
                        e.outcomeId = oldAction;
                    }
                    return;
                }
            }
        }
    }

    // Find-remove-add is a read-modify-write sequence, not one atomic operation - NConfig.get()/
    // set() each synchronize their own single map access, but not the sequence as a whole. Used
    // to only ever be called from the UI thread (Settings save, the bot-launch window), where
    // that was harmless; the bot's own thread now also calls this (see Forager.confirmActionName,
    // persisting a confirmed flower-menu guess mid-run), so two real threads can now race here for
    // the same character - synchronize the whole sequence to close that.
    public static void set(NForagerProp prop) {
        synchronized (NForagerProp.class) {
            @SuppressWarnings("unchecked")
            ArrayList<NForagerProp> foragerProps = ((ArrayList<NForagerProp>) NConfig.get(NConfig.Key.foragerprop));
            if (foragerProps != null) {
                for (Iterator<NForagerProp> i = foragerProps.iterator(); i.hasNext(); ) {
                    NForagerProp oldprop = i.next();
                    if (oldprop.username.equals(prop.username) && oldprop.chrid.equals(prop.chrid)) {
                        i.remove();
                        break;
                    }
                }
            } else {
                foragerProps = new ArrayList<>();
            }
            foragerProps.add(prop);
            NConfig.set(NConfig.Key.foragerprop, foragerProps);
        }
    }
    
    @Override
    public String toString() {
        return "NForagerProp[" + username + "|" + chrid + "]";
    }
    
    @Override
    public JSONObject toJson() {
        JSONObject jforager = new JSONObject();
        jforager.put("type", "NForagerProp");
        jforager.put("username", username);
        jforager.put("chrid", chrid);
        jforager.put("currentPreset", currentPreset);
        
        JSONObject presetsJson = new JSONObject();
        for (Map.Entry<String, PresetData> entry : presets.entrySet()) {
            JSONObject presetJson = new JSONObject();
            presetJson.put("pathFile", entry.getValue().pathFile);
            
            JSONArray actionsJson = new JSONArray();
            for (ForagerAction action : entry.getValue().actions) {
                actionsJson.put(action.toJson());
            }
            presetJson.put("actions", actionsJson);

            presetJson.put("onPlayerAction", entry.getValue().onPlayerAction);
            presetJson.put("onAnimalAction", entry.getValue().onAnimalAction);
            presetJson.put("afterFinishAction", entry.getValue().afterFinishAction);
            presetJson.put("onFullInventoryAction", entry.getValue().onFullInventoryAction);
            presetJson.put("ignoreBats", entry.getValue().ignoreBats);
            presetJson.put("waterMode", entry.getValue().waterMode);
            if (entry.getValue().actionsProfileName != null)
                presetJson.put("actionsProfileName", entry.getValue().actionsProfileName);
            if (entry.getValue().guardingProfileName != null)
                presetJson.put("guardingProfileName", entry.getValue().guardingProfileName);

            presetsJson.put(entry.getKey(), presetJson);
        }
        jforager.put("presets", presetsJson);

        jforager.put("currentActionsProfile", currentActionsProfile);
        JSONObject actionsProfilesJson = new JSONObject();
        for (Map.Entry<String, ArrayList<ForagerAction>> entry : actionsProfiles.entrySet()) {
            JSONArray actionsJson = new JSONArray();
            for (ForagerAction action : entry.getValue()) {
                actionsJson.put(action.toJson());
            }
            actionsProfilesJson.put(entry.getKey(), actionsJson);
        }
        jforager.put("actionsProfiles", actionsProfilesJson);

        jforager.put("currentGuardingProfile", currentGuardingProfile);
        JSONObject guardingProfilesJson = new JSONObject();
        for (Map.Entry<String, GuardingProfile> entry : guardingProfiles.entrySet()) {
            guardingProfilesJson.put(entry.getKey(), entry.getValue().toJson());
        }
        jforager.put("guardingProfiles", guardingProfilesJson);

        return jforager;
    }

    public static NForagerProp get(NUI.NSessInfo sessInfo) {
        if (sessInfo == null || NUtils.getGameUI() == null || NUtils.getGameUI().getCharInfo() == null)
            return null;
        String chrid = NUtils.getGameUI().getCharInfo().chrid;
        @SuppressWarnings("unchecked")
        ArrayList<NForagerProp> foragerProps = ((ArrayList<NForagerProp>) NConfig.get(NConfig.Key.foragerprop));
        if (foragerProps == null)
            foragerProps = new ArrayList<>();
        for (NForagerProp prop : foragerProps) {
            if (prop.username.equals(sessInfo.username) && prop.chrid.equals(chrid)) {
                return prop;
            }
        }
        return new NForagerProp(sessInfo.username, chrid);
    }
}
