package nurgling.tools;

import haven.Coord;
import nurgling.NConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared read/write logic for passively-recorded milestone (signpost) travel links, keyed by the milestone gob's durable hash. */
public class MilestoneRegistry {

    private MilestoneRegistry() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static final String MILESTONES_KEY = "milestones";
    private static final String GOB_NAME_KEY = "gobName";
    private static final String SEG_KEY = "seg";
    private static final String TC_X_KEY = "tcX";
    private static final String TC_Y_KEY = "tcY";
    private static final String DESTINATIONS_KEY = "destinations";
    private static final String LABEL_KEY = "label";
    private static final String LAST_TRAVERSED_KEY = "lastTraversed";

    // How close two recorded destination tile coords must be to merge as "the same path".
    private static final int DEST_MERGE_RADIUS_TILES = 5;

    public static class Location {
        public final long seg;
        public final Coord tc;

        public Location(long seg, Coord tc) {
            this.seg = seg;
            this.tc = tc;
        }
    }

    /** Load every recorded milestone. @return map of gobHash -> milestone entry. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> allMilestones() {
        Map<String, Object> raw = NConfig.getAsMap(NConfig.Key.milestones);

        Object milestonesObj = raw.get(MILESTONES_KEY);
        if (milestonesObj instanceof Map) {
            return new HashMap<>((Map<String, Object>) milestonesObj);
        }
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMilestone(String hash) {
        if (hash == null) {
            return null;
        }
        Object entry = allMilestones().get(hash);
        return (entry instanceof Map) ? (Map<String, Object>) entry : null;
    }

    public static Location getMilestoneLocation(Map<String, Object> entry) {
        return locationOf(entry);
    }

    public static String getMilestoneGobName(Map<String, Object> entry) {
        Object v = entry.get(GOB_NAME_KEY);
        return (v instanceof String) ? (String) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getDestinations(Map<String, Object> entry) {
        Object v = entry.get(DESTINATIONS_KEY);
        return (v instanceof List) ? (List<Map<String, Object>>) v : new ArrayList<>();
    }

    public static Location getDestinationLocation(Map<String, Object> dest) {
        return locationOf(dest);
    }

    public static String getDestinationLabel(Map<String, Object> dest) {
        Object v = dest.get(LABEL_KEY);
        return (v instanceof String) ? (String) v : "Destination";
    }

    private static Location locationOf(Map<String, Object> obj) {
        Object segObj = obj.get(SEG_KEY);
        Object xObj = obj.get(TC_X_KEY);
        Object yObj = obj.get(TC_Y_KEY);
        if (!(segObj instanceof Number) || !(xObj instanceof Number) || !(yObj instanceof Number)) {
            return null;
        }
        return new Location(((Number) segObj).longValue(),
                new Coord(((Number) xObj).intValue(), ((Number) yObj).intValue()));
    }

    /** Records (or refreshes, by location proximity match) a traversal from src to dest for milestone hash. */
    @SuppressWarnings("unchecked")
    public static void recordDestination(String hash, String gobName, Location src, Location dest) {
        if (hash == null || src == null || dest == null) {
            return;
        }

        Map<String, Object> milestones = allMilestones();
        Object entryObj = milestones.get(hash);
        Map<String, Object> entry = (entryObj instanceof Map) ? new HashMap<>((Map<String, Object>) entryObj) : new HashMap<>();

        entry.put(GOB_NAME_KEY, gobName);
        entry.put(SEG_KEY, src.seg);
        entry.put(TC_X_KEY, src.tc.x);
        entry.put(TC_Y_KEY, src.tc.y);

        List<Map<String, Object>> destinations = new ArrayList<>(getDestinations(entry));

        Map<String, Object> match = null;
        for (Map<String, Object> d : destinations) {
            Location loc = locationOf(d);
            if (loc == null || loc.seg != dest.seg) {
                continue;
            }
            if (Math.abs(loc.tc.x - dest.tc.x) <= DEST_MERGE_RADIUS_TILES
                    && Math.abs(loc.tc.y - dest.tc.y) <= DEST_MERGE_RADIUS_TILES) {
                match = d;
                break;
            }
        }

        if (match == null) {
            match = new HashMap<>();
            match.put(LABEL_KEY, "Destination " + (destinations.size() + 1));
            destinations.add(match);
        }
        match.put(SEG_KEY, dest.seg);
        match.put(TC_X_KEY, dest.tc.x);
        match.put(TC_Y_KEY, dest.tc.y);
        match.put(LAST_TRAVERSED_KEY, System.currentTimeMillis());

        entry.put(DESTINATIONS_KEY, destinations);
        milestones.put(hash, entry);
        save(milestones);
    }

    /** Rename one destination (identified by its index within its milestone's destination list). */
    @SuppressWarnings("unchecked")
    public static void renameDestination(String hash, int destIndex, String label) {
        Map<String, Object> milestones = allMilestones();
        Object entryObj = milestones.get(hash);
        if (!(entryObj instanceof Map)) {
            return;
        }
        Map<String, Object> entry = new HashMap<>((Map<String, Object>) entryObj);
        List<Map<String, Object>> destinations = new ArrayList<>(getDestinations(entry));
        if (destIndex < 0 || destIndex >= destinations.size()) {
            return;
        }
        Map<String, Object> dest = new HashMap<>(destinations.get(destIndex));
        dest.put(LABEL_KEY, label);
        destinations.set(destIndex, dest);
        entry.put(DESTINATIONS_KEY, destinations);
        milestones.put(hash, entry);
        save(milestones);
    }

    public static void save(Map<String, Object> milestones) {
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put(MILESTONES_KEY, milestones);
        NConfig.set(NConfig.Key.milestones, wrapper);
    }
}
