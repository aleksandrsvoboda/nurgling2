package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.routes.ForagerAction;
import nurgling.routes.ForagerPath;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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

    public static class PresetData {
        public String pathFile = "";
        public transient ForagerPath foragerPath = null;
        public ArrayList<ForagerAction> actions = new ArrayList<>();

        // NArea.id of an area to ChunkNav-travel to before starting the recorded path, or -1
        // for none. The path's own waypoints are stored as persistent map-segment + tile
        // coordinates (see ForagerWaypoint) which only resolve to a world position while
        // already standing in that same segment - if the bot is started from elsewhere (e.g.
        // indoors), that resolution fails outright. Setting a start area here lets the bot
        // reach the right segment first via chunk navigation, then fall back to the existing
        // local walk to the path's actual first waypoint.
        public int startAreaId = -1;

        public String onPlayerAction = "nothing";
        public String onAnimalAction = "logout";
        public String afterFinishAction = "nothing";
        public String onFullInventoryAction = "nothing";
        public boolean ignoreBats = true;
        public boolean waterMode = false;

        public PresetData() {}
        
        public PresetData(String pathFile) {
            this.pathFile = pathFile;
        }
    }
    
    public NForagerProp(String username, String chrid) {
        this.username = username;
        this.chrid = chrid;
        presets.put("Default", new PresetData());
        actionsProfiles.put("Default", new ArrayList<>());
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
                
                if (entry.getValue().get("startAreaId") != null)
                    pd.startAreaId = ((Number) entry.getValue().get("startAreaId")).intValue();
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
    }

    public static void set(NForagerProp prop) {
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

            presetJson.put("startAreaId", entry.getValue().startAreaId);
            presetJson.put("onPlayerAction", entry.getValue().onPlayerAction);
            presetJson.put("onAnimalAction", entry.getValue().onAnimalAction);
            presetJson.put("afterFinishAction", entry.getValue().afterFinishAction);
            presetJson.put("onFullInventoryAction", entry.getValue().onFullInventoryAction);
            presetJson.put("ignoreBats", entry.getValue().ignoreBats);
            presetJson.put("waterMode", entry.getValue().waterMode);

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
