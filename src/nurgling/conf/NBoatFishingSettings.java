package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.NUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

/** Boat Fisher settings, one entry per character. Copied from NFishingSettings without the zone and pile options. */
public class NBoatFishingSettings implements JConf {
    final private String username;
    final private String chrid;
    public String tool = null;
    public String fishline = null;
    public String bait = null;
    public String hook = null;
    public ArrayList<String> targets = new ArrayList<>();
    /** When the bite window lists none of the targets: catch the best fish instead of recasting. */
    public boolean goForBest = false;
    /** Any item of the kind will do; the chosen one is then ignored. */
    public boolean anyLine = false;
    public boolean anyHook = false;
    /** Any bait (pole) or any lure (casting rod). */
    public boolean anyBait = false;

    public NBoatFishingSettings(String username, String chrid) {
        this.username = username;
        this.chrid = chrid;
    }

    @SuppressWarnings("unchecked")
    public NBoatFishingSettings(HashMap<String, Object> values) {
        chrid = (String) values.get("chrid");
        username = (String) values.get("username");
        if (values.get("tool") != null)
            tool = (String) values.get("tool");
        if (values.get("fishline") != null)
            fishline = (String) values.get("fishline");
        if (values.get("bait") != null)
            bait = (String) values.get("bait");
        if (values.get("hook") != null)
            hook = (String) values.get("hook");
        if (values.get("goForBest") != null)
            goForBest = (Boolean) values.get("goForBest");
        if (values.get("anyLine") != null)
            anyLine = (Boolean) values.get("anyLine");
        if (values.get("anyHook") != null)
            anyHook = (Boolean) values.get("anyHook");
        if (values.get("anyBait") != null)
            anyBait = (Boolean) values.get("anyBait");
        if (values.get("targets") != null)
            targets.addAll((Collection<? extends String>) values.get("targets"));
    }

    @SuppressWarnings("unchecked")
    public static void set(NBoatFishingSettings prop) {
        ArrayList<NBoatFishingSettings> all = (ArrayList<NBoatFishingSettings>) NConfig.get(NConfig.Key.boatfishingsettings);
        if (all != null) {
            for (Iterator<NBoatFishingSettings> i = all.iterator(); i.hasNext(); ) {
                NBoatFishingSettings old = i.next();
                if (old.username.equals(prop.username) && old.chrid.equals(prop.chrid)) {
                    i.remove();
                    break;
                }
            }
        } else {
            all = new ArrayList<>();
        }
        all.add(prop);
        NConfig.set(NConfig.Key.boatfishingsettings, all);
    }

    @SuppressWarnings("unchecked")
    public static NBoatFishingSettings get(NUI.NSessInfo sessInfo) {
        if (sessInfo == null || NUtils.getGameUI() == null || NUtils.getGameUI().getCharInfo() == null)
            return null;
        String chrid = NUtils.getGameUI().getCharInfo().chrid;
        ArrayList<NBoatFishingSettings> all = (ArrayList<NBoatFishingSettings>) NConfig.get(NConfig.Key.boatfishingsettings);
        if (all != null) {
            for (NBoatFishingSettings prop : all) {
                if (prop.username.equals(sessInfo.username) && prop.chrid.equals(chrid))
                    return prop;
            }
        }
        return new NBoatFishingSettings(sessInfo.username, chrid);
    }

    public boolean isPole() {
        return "Bushcraft Fishingpole".equals(tool);
    }

    @Override
    public String toString() {
        return "NBoatFishingSettings[" + username + "|" + chrid + "]";
    }

    @Override
    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        j.put("type", "NBoatFishingSettings");
        j.put("username", username);
        j.put("chrid", chrid);
        j.put("tool", tool);
        j.put("fishline", fishline);
        j.put("bait", bait);
        j.put("hook", hook);
        j.put("goForBest", goForBest);
        j.put("anyLine", anyLine);
        j.put("anyHook", anyHook);
        j.put("anyBait", anyBait);
        JSONArray fish = new JSONArray();
        for (String key : targets)
            fish.put(key);
        j.put("targets", fish);
        return j;
    }
}
