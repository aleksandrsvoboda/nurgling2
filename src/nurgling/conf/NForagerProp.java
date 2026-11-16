package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.guarding.GuardEntry;
import nurgling.guarding.GuardingProfile;
import nurgling.routes.ForagerAction;
import nurgling.routes.ForagerPath;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NForagerProp implements JConf {
    
    private final String username;
    private final String chrid;
    public String currentPreset = "Default";
    public HashMap<String, PresetData> presets = new HashMap<>();

    // Named, independently-saved pickup-action lists, reusable across different presets.
    public String currentActionsProfile = "Default";
    public HashMap<String, ArrayList<ForagerAction>> actionsProfiles = new HashMap<>();

    // Named, independently-saved safety-watchdog configurations (see nurgling.guarding.GuardingProfile).
    public String currentGuardingProfile = "Default";
    public HashMap<String, GuardingProfile> guardingProfiles = new HashMap<>();

    public static class PresetData {
        public String pathFile = "";
        public transient ForagerPath foragerPath = null;
        public ArrayList<ForagerAction> actions = new ArrayList<>();

        // Legacy - superseded by guardingProfileName/GuardingProfile; kept only for the migration in the deserializing constructor.
        public String onPlayerAction = "nothing";
        public String onAnimalAction = "logout";
        public boolean ignoreBats = true;
        public boolean waterMode = false;

        public String afterFinishAction = "nothing";
        public String onFullInventoryAction = "nothing";

        // Which Actions/Guarding Profile this preset runs with; null = not yet assigned.
        public String actionsProfileName = null;
        public String guardingProfileName = null;

        // Skips every action's Maintain quantity check for this preset (see ForagerAction.maintainQuantity).
        public boolean ignoreMaintainLimits = false;

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
                if (entry.getValue().get("ignoreMaintainLimits") != null)
                    pd.ignoreMaintainLimits = (Boolean) entry.getValue().get("ignoreMaintainLimits");

                presets.put(entry.getKey(), pd);
            }
        }
        
        if (presets.isEmpty()) {
            presets.put("Default", new PresetData());
        }

        if (values.get("currentActionsProfile") != null)
            currentActionsProfile = (String) values.get("currentActionsProfile");

        // Whether the key was present at all, not whether the resulting map ends up empty - a
        // user who deliberately deletes their last Actions Profile and saves has a genuinely
        // empty (but present) "actionsProfiles":{} in their file, and that must NOT be treated as
        // a pre-migration config and have old, possibly-deleted-on-purpose data resurrected from
        // presets' vestigial legacy `actions` field below.
        boolean hadActionsProfilesKey = values.get("actionsProfiles") != null;
        actionsProfiles = new HashMap<>();
        if (hadActionsProfilesKey) {
            HashMap<String, ArrayList<HashMap<String, Object>>> profilesMap =
                (HashMap<String, ArrayList<HashMap<String, Object>>>) values.get("actionsProfiles");
            for (Map.Entry<String, ArrayList<HashMap<String, Object>>> entry : profilesMap.entrySet()) {
                ArrayList<ForagerAction> profileActions = new ArrayList<>();
                for (HashMap<String, Object> actionMap : entry.getValue()) {
                    profileActions.add(new ForagerAction(actionMap));
                }
                actionsProfiles.put(entry.getKey(), profileActions);
            }
        } else {
            // Genuinely pre-migration config: carry over each preset's old `actions` field as its own profile.
            for (Map.Entry<String, PresetData> entry : presets.entrySet()) {
                if (!entry.getValue().actions.isEmpty()) {
                    actionsProfiles.put(entry.getKey(), new ArrayList<>(entry.getValue().actions));
                }
            }
        }
        // Regardless of which path above ran, currentActionsProfile must always point at a real
        // entry - seeding an empty "Default" here is just keeping the app usable, not resurrecting anything.
        if (!actionsProfiles.containsKey(currentActionsProfile)) {
            if (actionsProfiles.isEmpty()) {
                actionsProfiles.put("Default", new ArrayList<>());
                currentActionsProfile = "Default";
            } else {
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
            // Legacy config: migrate each preset's onAnimalAction/ignoreBats/waterMode into its own GuardingProfile.
            for (Map.Entry<String, PresetData> entry : presets.entrySet()) {
                PresetData pd = entry.getValue();
                GuardingProfile migrated = GuardingProfile.withDefaults();
                migrated.waterMode = pd.waterMode;
                migrated.ignoreBats = pd.ignoreBats;
                for (GuardEntryPatch patch : new GuardEntryPatch[]{
                        new GuardEntryPatch("dangerous_animal", pd.onAnimalAction),
                        new GuardEntryPatch("unknown_player", pd.onPlayerAction)}) {
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

        // Default any preset missing an Actions/Guarding Profile to the current one.
        for (PresetData pd : presets.values()) {
            if (pd.actionsProfileName == null || !actionsProfiles.containsKey(pd.actionsProfileName)) {
                pd.actionsProfileName = currentActionsProfile;
            }
            if (pd.guardingProfileName == null || !guardingProfiles.containsKey(pd.guardingProfileName)) {
                pd.guardingProfileName = currentGuardingProfile;
            }
        }
    }

    /** Migration helper: applies an old preset's action string onto the named guard entry. */
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

    // Synchronized: find-remove-add is a read-modify-write sequence, and both the UI thread and a bot thread can now call this.
    public static void set(NForagerProp prop) {
        synchronized (NForagerProp.class) {
            @SuppressWarnings("unchecked")
            ArrayList<NForagerProp> foragerProps = ((ArrayList<NForagerProp>) NConfig.get(NConfig.Key.foragerprop));
            if (foragerProps != null) {
                // Drops every casing variant too, so a character saved under two spellings collapses back to one entry.
                foragerProps.removeIf(oldprop -> oldprop.isFor(prop.username, prop.chrid));
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
            presetsJson.put(entry.getKey(), presetJson(entry.getValue()));
        }
        jforager.put("presets", presetsJson);

        jforager.put("currentActionsProfile", currentActionsProfile);
        JSONObject actionsProfilesJson = new JSONObject();
        for (Map.Entry<String, ArrayList<ForagerAction>> entry : actionsProfiles.entrySet()) {
            actionsProfilesJson.put(entry.getKey(), actionsJson(entry.getValue()));
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

    private static JSONObject presetJson(PresetData pd) {
        JSONObject presetJson = new JSONObject();
        presetJson.put("pathFile", pd.pathFile);
        presetJson.put("actions", actionsJson(pd.actions));
        presetJson.put("onPlayerAction", pd.onPlayerAction);
        presetJson.put("onAnimalAction", pd.onAnimalAction);
        presetJson.put("afterFinishAction", pd.afterFinishAction);
        presetJson.put("onFullInventoryAction", pd.onFullInventoryAction);
        presetJson.put("ignoreBats", pd.ignoreBats);
        presetJson.put("waterMode", pd.waterMode);
        if (pd.actionsProfileName != null)
            presetJson.put("actionsProfileName", pd.actionsProfileName);
        if (pd.guardingProfileName != null)
            presetJson.put("guardingProfileName", pd.guardingProfileName);
        presetJson.put("ignoreMaintainLimits", pd.ignoreMaintainLimits);
        return presetJson;
    }

    /** Kept for existing callers: the settings are shared by every character now, so the session doesn't pick them. */
    public static NForagerProp get(NUI.NSessInfo sessInfo) {
        return get();
    }

    /**
     * The one set of Forager settings shared by every character on this PC. The first call after the
     * switch from per-character settings merges every character's entry into it (see {@link #merge}).
     * The per-character entries stay in the list untouched, so an older client still finds its own.
     */
    public static NForagerProp get() {
        synchronized (NForagerProp.class) {
            @SuppressWarnings("unchecked")
            ArrayList<NForagerProp> foragerProps = ((ArrayList<NForagerProp>) NConfig.get(NConfig.Key.foragerprop));
            if (foragerProps == null)
                foragerProps = new ArrayList<>();
            for (NForagerProp prop : foragerProps) {
                if (prop.isShared()) {
                    return prop;
                }
            }
            NForagerProp shared = merge(foragerProps);
            set(shared);
            return shared;
        }
    }

    /** Marks the shared entry in the saved list. No account or character is named "*", so an older client never takes it for its own. */
    static final String SHARED = "*";

    private boolean isShared() {
        return SHARED.equals(username) && SHARED.equals(chrid);
    }

    /**
     * Builds the shared entry from the per-character ones. The most recently saved character (last in
     * the list, since set() appends) goes first, so its names and selections stay as they are. A later
     * character's profile or preset with the same name and the same contents is kept once; with the same
     * name and different contents it is added as "name (character)", so nothing is lost.
     */
    static NForagerProp merge(List<NForagerProp> perCharacter) {
        NForagerProp shared = new NForagerProp(SHARED, SHARED);
        if (perCharacter.isEmpty()) {
            return shared;
        }
        shared.presets.clear();
        shared.actionsProfiles.clear();
        shared.guardingProfiles.clear();
        boolean first = true;
        for (int i = perCharacter.size() - 1; i >= 0; i--) {
            if (perCharacter.get(i).isShared()) continue;
            // A copy: the shared entry must not hold the per-character entries' objects, or editing it would rewrite those too.
            NForagerProp from = copy(perCharacter.get(i));
            String owner = from.chrid != null && !from.chrid.isEmpty() ? from.chrid : from.username;

            Map<String, String> actionsNames = new HashMap<>();
            for (Map.Entry<String, ArrayList<ForagerAction>> e : from.actionsProfiles.entrySet()) {
                actionsNames.put(e.getKey(), place(shared.actionsProfiles, e.getKey(), e.getValue(), owner,
                        (a, b) -> actionsJson(a).similar(actionsJson(b))));
            }
            Map<String, String> guardingNames = new HashMap<>();
            for (Map.Entry<String, GuardingProfile> e : from.guardingProfiles.entrySet()) {
                guardingNames.put(e.getKey(), place(shared.guardingProfiles, e.getKey(), e.getValue(), owner,
                        (a, b) -> a.toJson().similar(b.toJson())));
            }
            Map<String, String> presetNames = new HashMap<>();
            for (Map.Entry<String, PresetData> e : from.presets.entrySet()) {
                PresetData pd = e.getValue();
                pd.actionsProfileName = actionsNames.getOrDefault(pd.actionsProfileName, pd.actionsProfileName);
                pd.guardingProfileName = guardingNames.getOrDefault(pd.guardingProfileName, pd.guardingProfileName);
                presetNames.put(e.getKey(), place(shared.presets, e.getKey(), pd, owner,
                        (a, b) -> presetJson(a).similar(presetJson(b))));
            }

            if (first) {
                first = false;
                shared.currentPreset = presetNames.getOrDefault(from.currentPreset, from.currentPreset);
                shared.currentActionsProfile = actionsNames.getOrDefault(from.currentActionsProfile, from.currentActionsProfile);
                shared.currentGuardingProfile = guardingNames.getOrDefault(from.currentGuardingProfile, from.currentGuardingProfile);
            }
        }
        if (shared.presets.isEmpty()) {
            shared.presets.put("Default", new PresetData());
        }
        if (shared.actionsProfiles.isEmpty()) {
            shared.actionsProfiles.put("Default", new ArrayList<>());
        }
        if (shared.guardingProfiles.isEmpty()) {
            shared.guardingProfiles.put("Default", GuardingProfile.withDefaults());
        }
        if (!shared.presets.containsKey(shared.currentPreset)) {
            shared.currentPreset = shared.presets.keySet().iterator().next();
        }
        if (!shared.actionsProfiles.containsKey(shared.currentActionsProfile)) {
            shared.currentActionsProfile = shared.actionsProfiles.keySet().iterator().next();
        }
        if (!shared.guardingProfiles.containsKey(shared.currentGuardingProfile)) {
            shared.currentGuardingProfile = shared.guardingProfiles.keySet().iterator().next();
        }
        return shared;
    }

    @SuppressWarnings("unchecked")
    private static NForagerProp copy(NForagerProp prop) {
        return new NForagerProp((HashMap<String, Object>) prop.toJson().toMap());
    }

    /** Puts value into into under name, or keeps the existing one if it's the same; returns the name it ended up under. */
    private static <T> String place(Map<String, T> into, String name, T value, String owner, java.util.function.BiPredicate<T, T> same) {
        T existing = into.get(name);
        if (existing == null) {
            into.put(name, value);
            return name;
        }
        if (same.test(existing, value)) {
            return name;
        }
        String base = name + " (" + owner + ")";
        String renamed = base;
        for (int n = 2; into.containsKey(renamed); n++) {
            if (same.test(into.get(renamed), value)) {
                return renamed;
            }
            renamed = base + " " + n;
        }
        into.put(renamed, value);
        return renamed;
    }

    private static JSONArray actionsJson(List<ForagerAction> actions) {
        JSONArray arr = new JSONArray();
        for (ForagerAction action : actions) {
            arr.put(action.toJson());
        }
        return arr;
    }

    /** The server echoes the character name as the client sent it (a launcher's typed "bob" vs the charlist's "Bob"), so match ignoring case. */
    private boolean isFor(String username, String chrid) {
        return this.username != null && this.username.equalsIgnoreCase(username)
                && this.chrid != null && this.chrid.equalsIgnoreCase(chrid);
    }

    /** Writes one Actions Profile's own JSON file - the format {@link #importActionsProfile} reads back. */
    public static void exportActionsProfile(String name, ArrayList<ForagerAction> actions, java.io.File file) throws java.io.IOException {
        JSONObject root = new JSONObject();
        root.put("name", name);
        JSONArray arr = new JSONArray();
        for (ForagerAction action : actions) {
            arr.put(action.toJson());
        }
        root.put("actions", arr);
        java.nio.file.Files.write(file.toPath(), root.toString(2).getBytes());
    }

    /** One profile loaded from an exportActionsProfile() file, with a name already de-duplicated against existingNames. */
    public static final class ImportedActionsProfile {
        public final String name;
        public final ArrayList<ForagerAction> actions;

        public ImportedActionsProfile(String name, ArrayList<ForagerAction> actions) {
            this.name = name;
            this.actions = actions;
        }
    }

    /** Reads a profile saved by {@link #exportActionsProfile}, picking a "name (2)", "name (3)", ... suffix if `name` is already taken. */
    public static ImportedActionsProfile importActionsProfile(java.io.File file, java.util.Set<String> existingNames) throws java.io.IOException {
        String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
        JSONObject root = new JSONObject(content);
        JSONArray arr = root.getJSONArray("actions");
        ArrayList<ForagerAction> actions = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            actions.add(new ForagerAction(arr.getJSONObject(i)));
        }

        String baseName = root.has("name") ? root.getString("name") : file.getName().replaceFirst("\\.json$", "");
        String name = baseName;
        int suffix = 2;
        while (existingNames.contains(name)) {
            name = baseName + " (" + suffix + ")";
            suffix++;
        }
        return new ImportedActionsProfile(name, actions);
    }
}
