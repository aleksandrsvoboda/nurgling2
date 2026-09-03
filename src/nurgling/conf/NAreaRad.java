package nurgling.conf;

import nurgling.NConfig;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

public class NAreaRad implements JConf
{
    // Resource name of plain Rat, which shares this "draw an awareness ring" list with
    // genuinely aggressive critters (e.g. Cave Rat) despite not itself being dangerous -
    // DangerousAnimalTrigger used to hardcode a single exclusion for exactly this string,
    // which broke the moment a user tracked any OTHER non-dangerous critter for visibility.
    // Used only as this class's own migration default below, not read anywhere else -
    // DangerousAnimalTrigger now just reads the dangerous field like any other entry.
    private static final String RAT_RESOURCE = "gfx/kritter/rat/rat";

    public String name;
    public boolean vis;
    public int radius;
    // Whether DangerousAnimalTrigger should treat this ring as a threat. Independent of vis
    // (which only controls whether the ring is drawn) - a critter can be worth seeing without
    // being worth an emergency stop, or vice versa.
    public boolean dangerous;

    public NAreaRad(String name, int radius) {
        this.name = name;
        this.vis = true;
        this.radius = radius;
        this.dangerous = true;
    }

    public NAreaRad(HashMap<String, Object> values)
    {
        name = (String) values.get("name");
        if (values.get("vis") != null)
            vis = (Boolean) values.get("vis");
        if (values.get("radius") != null)
            radius = (Integer) values.get("radius");
        // Pre-existing saved entries have no "dangerous" key at all - default them to match
        // the exact behavior the old hardcoded single-name exclusion gave (everything except
        // plain Rat was treated as dangerous), so migrating doesn't change anyone's existing
        // Ring Settings behavior. A freshly-added entry (the other constructor) has no such
        // history to preserve and just defaults to true.
        dangerous = (values.get("dangerous") != null) ? (Boolean) values.get("dangerous") : !RAT_RESOURCE.equals(name);
    }

    @Override
    public JSONObject toJson()
    {
        JSONObject jobj = new JSONObject();
        jobj.put("type", "NAreaRad");
        jobj.put("name", name);
        jobj.put("vis", vis);
        jobj.put("radius", radius);
        jobj.put("dangerous", dangerous);
        return jobj;
    }

    public static NAreaRad get(String val)
    {
        ArrayList<NAreaRad> radProps = ((ArrayList<NAreaRad>) NConfig.get(NConfig.Key.animalrad));
        if (radProps == null)
            radProps = new ArrayList<>();
        for (NAreaRad prop : radProps)
        {
            if (prop.name.equals(val))
            {
                return prop;
            }
        }
        return null;
    }

    public static void set(String val, NAreaRad prop)
    {
        ArrayList<NAreaRad> radProps = ((ArrayList<NAreaRad>) NConfig.get(NConfig.Key.animalrad));
        if (radProps != null)
        {
            for (Iterator<NAreaRad> i = radProps.iterator(); i.hasNext(); )
            {
                NAreaRad oldprop = i.next();
                if (oldprop.name.equals(prop.name))
                {
                    i.remove();
                    break;
                }
            }

        }
        else
        {
            radProps = new ArrayList<>();
        }
        radProps.add(prop);
        NConfig.set(NConfig.Key.animalrad, radProps);
    }
}
