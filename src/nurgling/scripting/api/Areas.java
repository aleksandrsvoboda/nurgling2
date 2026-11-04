package nurgling.scripting.api;

import nurgling.NMapView;
import nurgling.areas.NArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Area lookups against the run's own session (never the active tab). */
public final class Areas {
    public static Map<Integer, NArea> all(OpContext c) {
        return c.gui.map.glob.map.areas;
    }

    public static NArea byId(OpContext c, int id) {
        return all(c).get(id);
    }

    public static NArea byName(OpContext c, String name) {
        return ((NMapView) c.gui.map).findArea(name);
    }

    /** Enabled areas carrying a specialisation (and subtype, if given). */
    public static List<NArea> bySpec(OpContext c, String spec, String sub) {
        List<NArea> res = new ArrayList<>();
        for (NArea a : all(c).values()) {
            if (a.isDisabled()) continue;
            for (NArea.Specialisation s : a.spec) {
                if (s.name.equals(spec) && (sub == null || sub.equals(s.subtype))) {
                    res.add(a);
                    break;
                }
            }
        }
        return res;
    }

    private Areas() {}
}
