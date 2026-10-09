package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.NUtils;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/** Last Area Mover choices per character; an area id of -1 means "select on map". */
public class NAreaMoverProp implements JConf
{
    final private String username;
    final private String chrid;
    public int fromAreaId = -1;
    public int toAreaId = -1;
    public boolean createPiles = true;

    public NAreaMoverProp(String username, String chrid) {
        this.username = username;
        this.chrid = chrid;
    }

    public NAreaMoverProp(HashMap<String, Object> values)
    {
        chrid = (String) values.get("chrid");
        username = (String) values.get("username");
        if (values.get("fromAreaId") != null)
            fromAreaId = ((Number) values.get("fromAreaId")).intValue();
        if (values.get("toAreaId") != null)
            toAreaId = ((Number) values.get("toAreaId")).intValue();
        if (values.get("createPiles") != null)
            createPiles = (Boolean) values.get("createPiles");
    }

    public static void set(NAreaMoverProp prop)
    {
        ArrayList<NAreaMoverProp> props = ((ArrayList<NAreaMoverProp>) NConfig.get(NConfig.Key.areamoverprop));
        if (props != null)
        {
            for (Iterator<NAreaMoverProp> i = props.iterator(); i.hasNext(); )
            {
                NAreaMoverProp oldprop = i.next();
                if(oldprop.username.equals(prop.username) && oldprop.chrid.equals(prop.chrid))
                {
                    i.remove();
                    break;
                }
            }
        }
        else
        {
            props = new ArrayList<>();
        }
        props.add(prop);
        NConfig.set(NConfig.Key.areamoverprop, props);
    }

    @Override
    public String toString()
    {
        return "NAreaMoverProp[" + username + "|" + chrid + "]";
    }

    @Override
    public JSONObject toJson()
    {
        JSONObject json = new JSONObject();
        json.put("type", "NAreaMoverProp");
        json.put("username", username);
        json.put("chrid", chrid);
        json.put("fromAreaId", fromAreaId);
        json.put("toAreaId", toAreaId);
        json.put("createPiles", createPiles);
        return json;
    }

    public static NAreaMoverProp get(NUI.NSessInfo sessInfo)
    {
        if (sessInfo == null || NUtils.getGameUI() == null || NUtils.getGameUI().getCharInfo() == null)
            return null;
        String chrid = NUtils.getGameUI().getCharInfo().chrid;
        ArrayList<NAreaMoverProp> props = ((ArrayList<NAreaMoverProp>) NConfig.get(NConfig.Key.areamoverprop));
        if (props == null)
            props = new ArrayList<>();
        for (NAreaMoverProp prop : props)
        {
            if (prop.username.equals(sessInfo.username) && prop.chrid.equals(chrid))
            {
                return prop;
            }
        }
        return new NAreaMoverProp(sessInfo.username, chrid);
    }
}
