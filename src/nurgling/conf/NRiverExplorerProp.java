package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.NUtils;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/** River Explorer's per-character settings (the NWorldExplorerProp pattern). */
public class NRiverExplorerProp implements JConf {
    final private String username;
    final private String chrid;
    public boolean useSea = true;
    /** Which way to head among targets of about equal worth: "any", "N", "E", "S" or "W" - so bots started at one place
     *  (yours and your friends') spread out. */
    public String head = "any";
    public boolean chopReeds = true;
    public String axe = null;
    public boolean avoidDanger = true;
    public int timeLimitMin = 0;
    public int leashGrids = 0;

    public NRiverExplorerProp(String username, String chrid) {
        this.username = username;
        this.chrid = chrid;
    }

    public NRiverExplorerProp(HashMap<String, Object> values) {
        chrid = (String) values.get("chrid");
        username = (String) values.get("username");
        if (values.get("useSea") != null)
            useSea = (Boolean) values.get("useSea");
        if (values.get("head") != null)
            head = (String) values.get("head");
        if (values.get("chopReeds") != null)
            chopReeds = (Boolean) values.get("chopReeds");
        if (values.get("axe") != null)
            axe = (String) values.get("axe");
        if (values.get("avoidDanger") != null)
            avoidDanger = (Boolean) values.get("avoidDanger");
        if (values.get("timeLimitMin") != null)
            timeLimitMin = ((Number) values.get("timeLimitMin")).intValue();
        if (values.get("leashGrids") != null)
            leashGrids = ((Number) values.get("leashGrids")).intValue();
    }

    @SuppressWarnings("unchecked")
    public static void set(NRiverExplorerProp prop) {
        ArrayList<NRiverExplorerProp> props = (ArrayList<NRiverExplorerProp>) NConfig.get(NConfig.Key.riverexplorerprop);
        if (props != null) {
            for (Iterator<NRiverExplorerProp> i = props.iterator(); i.hasNext(); ) {
                NRiverExplorerProp old = i.next();
                if (old.username.equals(prop.username) && old.chrid.equals(prop.chrid)) {
                    i.remove();
                    break;
                }
            }
        } else {
            props = new ArrayList<>();
        }
        props.add(prop);
        NConfig.set(NConfig.Key.riverexplorerprop, props);
    }

    @Override
    public String toString() {
        return "NRiverExplorer[" + username + "|" + chrid + "]";
    }

    @Override
    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        j.put("type", "NRiverExplorer");
        j.put("username", username);
        j.put("chrid", chrid);
        j.put("useSea", useSea);
        j.put("head", head);
        j.put("chopReeds", chopReeds);
        if (axe != null)
            j.put("axe", axe);
        j.put("avoidDanger", avoidDanger);
        j.put("timeLimitMin", timeLimitMin);
        j.put("leashGrids", leashGrids);
        return j;
    }

    @SuppressWarnings("unchecked")
    public static NRiverExplorerProp get(NUI.NSessInfo sessInfo) {
        if (sessInfo == null || NUtils.getGameUI() == null || NUtils.getGameUI().getCharInfo() == null)
            return null;
        String chrid = NUtils.getGameUI().getCharInfo().chrid;
        ArrayList<NRiverExplorerProp> props = (ArrayList<NRiverExplorerProp>) NConfig.get(NConfig.Key.riverexplorerprop);
        if (props != null) {
            for (NRiverExplorerProp prop : props)
                if (prop.username.equals(sessInfo.username) && prop.chrid.equals(chrid))
                    return prop;
        }
        return new NRiverExplorerProp(sessInfo.username, chrid);
    }
}
