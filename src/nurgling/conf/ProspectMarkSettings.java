package nurgling.conf;

import org.json.JSONObject;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Visibility settings for prospected sample marks on the map.
 * A master switch plus an independent enable flag and quality threshold per resource kind.
 */
public class ProspectMarkSettings implements JConf {
    public static final int MIN_THRESHOLD = 0;
    public static final int MAX_THRESHOLD = 500;

    public static class Entry {
        public boolean enabled = true;
        public int threshold = MIN_THRESHOLD;
    }

    public boolean master = true;
    private final Map<ProspectKind, Entry> kinds = new EnumMap<>(ProspectKind.class);

    public ProspectMarkSettings() {
        for(ProspectKind kind : ProspectKind.values())
            kinds.put(kind, new Entry());
    }

    @SuppressWarnings("unchecked")
    public ProspectMarkSettings(Map<String, Object> map) {
        this();
        if(map.get("master") instanceof Boolean)
            this.master = (Boolean) map.get("master");
        Object rawKinds = map.get("kinds");
        if(rawKinds instanceof Map) {
            Map<String, Object> kmap = (Map<String, Object>) rawKinds;
            for(Map.Entry<String, Object> e : kmap.entrySet()) {
                ProspectKind kind;
                try {
                    kind = ProspectKind.valueOf(e.getKey());
                } catch(IllegalArgumentException ignored) {
                    /* A kind that no longer exists; drop it. */
                    continue;
                }
                if(!(e.getValue() instanceof Map))
                    continue;
                Map<String, Object> emap = (Map<String, Object>) e.getValue();
                Entry entry = kinds.get(kind);
                if(emap.get("enabled") instanceof Boolean)
                    entry.enabled = (Boolean) emap.get("enabled");
                if(emap.get("threshold") instanceof Number)
                    entry.threshold = clamp(((Number) emap.get("threshold")).intValue());
            }
        }
    }

    public static int clamp(int threshold) {
        return Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, threshold));
    }

    public Entry entry(ProspectKind kind) {
        Entry entry = kinds.get(kind);
        if(entry == null) {
            entry = new Entry();
            kinds.put(kind, entry);
        }
        return entry;
    }

    public boolean enabled(ProspectKind kind) {
        return entry(kind).enabled;
    }

    public void setEnabled(ProspectKind kind, boolean enabled) {
        entry(kind).enabled = enabled;
    }

    public int threshold(ProspectKind kind) {
        return entry(kind).threshold;
    }

    public void setThreshold(ProspectKind kind, int threshold) {
        entry(kind).threshold = clamp(threshold);
    }

    public void setAllThresholds(int threshold) {
        for(ProspectKind kind : ProspectKind.values())
            setThreshold(kind, threshold);
    }

    /**
     * Whether a mark of this kind and quality should be drawn.
     * The comparison uses the rounded quality so that the number shown on the label
     * ("q40") is exactly the number the threshold is compared against.
     */
    public boolean shows(ProspectKind kind, double quality) {
        if(!master)
            return false;
        Entry entry = entry(kind);
        return entry.enabled && (Math.round(quality) >= entry.threshold);
    }

    @Override
    public JSONObject toJson() {
        Map<String, Object> ret = new HashMap<>();
        ret.put("type", "ProspectMarkSettings");
        ret.put("master", master);
        Map<String, Object> kmap = new HashMap<>();
        for(ProspectKind kind : ProspectKind.values()) {
            Entry entry = entry(kind);
            Map<String, Object> emap = new HashMap<>();
            emap.put("enabled", entry.enabled);
            emap.put("threshold", entry.threshold);
            kmap.put(kind.name(), emap);
        }
        ret.put("kinds", kmap);
        return new JSONObject(ret);
    }
}
