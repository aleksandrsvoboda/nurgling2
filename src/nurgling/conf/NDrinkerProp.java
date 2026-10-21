package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.NUtils;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** The Drinker bot's last sip counts and cup choices, kept per character. */
public class NDrinkerProp implements JConf
{
    final private String username;
    final private String chrid;
    public final LinkedHashMap<String, Integer> sips = new LinkedHashMap<>();
    public final LinkedHashMap<String, String> cups = new LinkedHashMap<>();

    public NDrinkerProp(String username, String chrid) {
        this.username = username;
        this.chrid = chrid;
    }

    @SuppressWarnings("unchecked")
    public NDrinkerProp(HashMap<String, Object> values)
    {
        chrid = (String) values.get("chrid");
        username = (String) values.get("username");
        if (values.get("sips") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) values.get("sips")).entrySet()) {
                if (e.getValue() instanceof Number)
                    sips.put(e.getKey(), ((Number) e.getValue()).intValue());
            }
        }
        if (values.get("cups") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) values.get("cups")).entrySet()) {
                if (e.getValue() instanceof String)
                    cups.put(e.getKey(), (String) e.getValue());
            }
        }
    }

    public static void set(NDrinkerProp prop)
    {
        ArrayList<NDrinkerProp> drinkerProps = ((ArrayList<NDrinkerProp>) NConfig.get(NConfig.Key.drinkerprop));
        if (drinkerProps != null)
        {
            for (Iterator<NDrinkerProp> i = drinkerProps.iterator(); i.hasNext(); )
            {
                NDrinkerProp oldprop = i.next();
                if(oldprop.username.equals(prop.username) && oldprop.chrid.equals(prop.chrid))
                {
                    i.remove();
                    break;
                }
            }
        }
        else
        {
            drinkerProps = new ArrayList<>();
        }
        drinkerProps.add(prop);
        NConfig.set(NConfig.Key.drinkerprop, drinkerProps);
    }

    @Override
    public String toString()
    {
        return "NDrinkerProp[" + username + "|" + chrid + "]";
    }

    @Override
    public JSONObject toJson()
    {
        JSONObject jdrinker = new JSONObject();
        jdrinker.put("type", "NDrinkerProp");
        jdrinker.put("username", username);
        jdrinker.put("chrid", chrid);
        jdrinker.put("sips", new JSONObject(sips));
        jdrinker.put("cups", new JSONObject(cups));
        return jdrinker;
    }

    public static NDrinkerProp get(NUI.NSessInfo sessInfo)
    {
        if (sessInfo == null || NUtils.getGameUI() == null || NUtils.getGameUI().getCharInfo() == null)
            return null;
        String chrid = NUtils.getGameUI().getCharInfo().chrid;
        ArrayList<NDrinkerProp> drinkerProps = ((ArrayList<NDrinkerProp>) NConfig.get(NConfig.Key.drinkerprop));
        if (drinkerProps == null)
            drinkerProps = new ArrayList<>();
        for (NDrinkerProp prop : drinkerProps)
        {
            if (prop.username.equals(sessInfo.username) && prop.chrid.equals(chrid))
            {
                return prop;
            }
        }
        return new NDrinkerProp(sessInfo.username, chrid);
    }
}
