package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.NUtils;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class NMasterMinerProp implements JConf {
    private final String username;
    private final String chrid;

    /** Lowest wall quality worth keeping; below this the stone is dropped. */
    public float minWallQ = 0f;

    /** Echo the calculation to chat. */
    public boolean showCalc = false;
    
    /** Quality below which a stone is dropped. */
    public float dropThreshold = Float.NaN;
    
    /** Separate drop threshold for Shell and Cat Gold. */
    public float shellCatGoldThreshold = Float.NaN;
    
    /** Quality above which a find is worth a map mark. */
    public float markerThreshold = Float.NaN;

    /** Stones always kept in the pack for placing supports; only the surplus is dropped. */
    public int keepStonesForSupport = 30;

    /** Last window position; both null means never placed. */
    public Integer wndX = null;
    public Integer wndY = null;

    public NMasterMinerProp(String username, String chrid) {
        this.username = username;
        this.chrid = chrid;
    }

    public NMasterMinerProp(HashMap<String, Object> values) {
        chrid = (String) values.get("chrid");
        username = (String) values.get("username");
        if (values.get("minWallQ") != null) {
            minWallQ = ((Number) values.get("minWallQ")).floatValue();
        }
        if (values.get("showCalc") != null) {
            showCalc = (Boolean) values.get("showCalc");
        }
        if (values.get("dropThreshold") != null) {
            Object dt = values.get("dropThreshold");
            if (dt instanceof Number) {
                float val = ((Number) dt).floatValue();
                dropThreshold = Float.isNaN(val) ? Float.NaN : val;
            }
        }
        if (values.get("shellCatGoldThreshold") != null) {
            Object dt = values.get("shellCatGoldThreshold");
            if (dt instanceof Number) {
                float val = ((Number) dt).floatValue();
                shellCatGoldThreshold = Float.isNaN(val) ? Float.NaN : val;
            }
        }
        if (values.get("markerThreshold") != null) {
            Object mt = values.get("markerThreshold");
            if (mt instanceof Number) {
                float val = ((Number) mt).floatValue();
                markerThreshold = Float.isNaN(val) ? Float.NaN : val;
            }
        }
        if (values.get("keepStonesForSupport") != null) {
            Object k = values.get("keepStonesForSupport");
            if (k instanceof Number) {
                keepStonesForSupport = Math.max(0, ((Number) k).intValue());
            }
        }
        if (values.get("wndX") instanceof Number) {
            wndX = ((Number) values.get("wndX")).intValue();
        }
        if (values.get("wndY") instanceof Number) {
            wndY = ((Number) values.get("wndY")).intValue();
        }
    }

    public boolean hasWindowPos() {
        return wndX != null && wndY != null;
    }

    public static void set(NMasterMinerProp prop) {
        NConfig.set(NConfig.Key.masterminerprop, replace(storedProps(), prop));
    }

    static ArrayList<NMasterMinerProp> replace(ArrayList<NMasterMinerProp> props, NMasterMinerProp prop) {
        ArrayList<NMasterMinerProp> next = new ArrayList<>(props);
        for (Iterator<NMasterMinerProp> i = next.iterator(); i.hasNext(); ) {
            NMasterMinerProp old = i.next();
            if (java.util.Objects.equals(old.username, prop.username) && java.util.Objects.equals(old.chrid, prop.chrid)) {
                i.remove();
                break;
            }
        }
        next.add(prop);
        return next;
    }

    @Override
    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        j.put("type", "NMasterMinerProp");
        j.put("username", username);
        j.put("chrid", chrid);
        j.put("minWallQ", minWallQ);
        j.put("showCalc", showCalc);
        if (!Float.isNaN(dropThreshold)) {
            j.put("dropThreshold", dropThreshold);
        }
        if (!Float.isNaN(shellCatGoldThreshold)) {
            j.put("shellCatGoldThreshold", shellCatGoldThreshold);
        }
        if (!Float.isNaN(markerThreshold)) {
            j.put("markerThreshold", markerThreshold);
        }
        j.put("keepStonesForSupport", keepStonesForSupport);
        if (hasWindowPos()) {
            j.put("wndX", wndX);
            j.put("wndY", wndY);
        }
        return j;
    }

    @Override
    public String toString() {
        return "NMasterMinerProp[" + username + "|" + chrid + "]";
    }

    public static NMasterMinerProp get(NUI.NSessInfo sessInfo) {
        if (sessInfo == null || NUtils.getGameUI() == null || NUtils.getGameUI().getCharInfo() == null) {
            return null;
        }
        return get(sessInfo.username, NUtils.getGameUI().getCharInfo().chrid);
    }

    public static NMasterMinerProp get(String username, String chrid) {
        return find(storedProps(), username, chrid);
    }

    static NMasterMinerProp find(ArrayList<NMasterMinerProp> props, String username, String chrid) {
        for (NMasterMinerProp prop : props) {
            if (java.util.Objects.equals(prop.username, username) && java.util.Objects.equals(prop.chrid, chrid)) {
                return prop;
            }
        }
        return new NMasterMinerProp(username, chrid);
    }

    static ArrayList<NMasterMinerProp> storedProps() {
        return listFromRaw(NConfig.getGlobal(NConfig.Key.masterminerprop));
    }

    @SuppressWarnings("unchecked")
    static ArrayList<NMasterMinerProp> listFromRaw(Object raw) {
        ArrayList<NMasterMinerProp> result = new ArrayList<>();
        if (!(raw instanceof List<?>)) {
            return result;
        }
        for (Object item : (List<?>) raw) {
            if (item instanceof NMasterMinerProp) {
                result.add((NMasterMinerProp) item);
            } else if (item instanceof HashMap<?, ?>) {
                result.add(new NMasterMinerProp((HashMap<String, Object>) item));
            }
        }
        return result;
    }
}
