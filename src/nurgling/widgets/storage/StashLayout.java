package nurgling.widgets.storage;

import nurgling.NConfig;
import nurgling.tools.NFileUtils;
import nurgling.tools.StorageClassifier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The Storage window's tabs, kept per user in {@code storage_tabs.nurgling.json}. A pinned tab is
 * a list of titled sections of slots (item names, optionally with a target count) and may also
 * auto-fill with every other stored item of one category. An auto tab only auto-fills.
 *
 * <p>Pinned presets ship in {@code storage-tabs-default.json}. Each carries a preset id and a
 * version, so presets added or reworked later can be offered to users whose file predates them
 * without touching the tabs they made themselves.
 */
public final class StashLayout {
    private static final String PRESETS = "/nurgling/widgets/storage/storage-tabs-default.json";
    private static final String EXPORT_PREFIX = "nstash1:";

    public static final class Slot {
        public final String name;
        public int target; // 0 = no target

        public Slot(String name, int target) {
            this.name = name;
            this.target = target;
        }

        JSONObject json() {
            JSONObject o = new JSONObject().put("name", name);
            if (target > 0)
                o.put("target", target);
            return o;
        }

        static Slot of(JSONObject o) {
            return new Slot(o.getString("name"), Math.max(0, o.optInt("target", 0)));
        }
    }

    public static final class Section {
        public String title;
        public final List<Slot> slots = new ArrayList<>();

        public Section(String title) {
            this.title = title;
        }

        JSONObject json() {
            JSONArray a = new JSONArray();
            for (Slot s : slots)
                a.put(s.json());
            return new JSONObject().put("title", title).put("slots", a);
        }

        static Section of(JSONObject o) {
            Section s = new Section(o.optString("title", ""));
            JSONArray a = o.optJSONArray("slots");
            if (a != null)
                for (int i = 0; i < a.length(); i++)
                    s.slots.add(Slot.of(a.getJSONObject(i)));
            return s;
        }
    }

    public static final class Tab {
        public String id;
        public String title;
        public String icon;      // item name whose icon the tab shows
        public String autoFill;  // StorageClassifier.Category id, or null
        public boolean pinned;   // false = auto tab: no sections, only autoFill
        public String preset;    // "id@version" for shipped presets, else null
        public final List<Section> sections = new ArrayList<>();

        JSONObject json() {
            JSONObject o = new JSONObject().put("id", id).put("title", title).put("pinned", pinned);
            if (icon != null)
                o.put("icon", icon);
            if (autoFill != null)
                o.put("autoFill", autoFill);
            if (preset != null)
                o.put("preset", preset);
            JSONArray a = new JSONArray();
            for (Section s : sections)
                a.put(s.json());
            return o.put("sections", a);
        }

        static Tab of(JSONObject o) {
            Tab t = new Tab();
            t.id = o.getString("id");
            t.title = o.optString("title", null); // null = the category's localized name
            t.icon = o.optString("icon", null);
            t.autoFill = o.optString("autoFill", null);
            t.pinned = o.optBoolean("pinned", true);
            t.preset = o.optString("preset", null);
            JSONArray a = o.optJSONArray("sections");
            if (a != null)
                for (int i = 0; i < a.length(); i++)
                    t.sections.add(Section.of(a.getJSONObject(i)));
            return t;
        }

        public StorageClassifier.Category category() {
            return autoFill == null ? null : StorageClassifier.Category.byId(autoFill);
        }

        /** Every pinned slot, in order. */
        public List<Slot> slots() {
            List<Slot> out = new ArrayList<>();
            for (Section s : sections)
                out.addAll(s.slots);
            return out;
        }

        public Slot find(String name) {
            String key = StorageClassifier.normalize(name);
            for (Section s : sections)
                for (Slot slot : s.slots)
                    if (StorageClassifier.normalize(slot.name).equals(key))
                        return slot;
            return null;
        }

        public boolean unpin(String name) {
            String key = StorageClassifier.normalize(name);
            boolean removed = false;
            for (Section s : sections)
                removed |= s.slots.removeIf(slot -> StorageClassifier.normalize(slot.name).equals(key));
            return removed;
        }

        public void pin(String name) {
            if (find(name) != null)
                return;
            if (sections.isEmpty())
                sections.add(new Section(""));
            sections.get(sections.size() - 1).slots.add(new Slot(name, 0));
        }
    }

    public final List<Tab> tabs = new ArrayList<>();
    /** Preset ids the user deleted; never offered again. */
    private final Set<String> dismissedPresets = new HashSet<>();
    private final String path;

    private StashLayout(String path) {
        this.path = path;
    }

    /** The user's layout, or the shipped presets plus one auto tab per remaining category. */
    public static StashLayout load() {
        NConfig cfg = NConfig.getGlobalInstance();
        StashLayout layout = new StashLayout(cfg == null ? null : cfg.getStorageTabsPath());
        String content = layout.path == null ? null : NFileUtils.readWithBackupFallback(layout.path);
        if (content != null && !content.isEmpty()) {
            try {
                JSONObject main = new JSONObject(content);
                JSONArray a = main.getJSONArray("tabs");
                for (int i = 0; i < a.length(); i++)
                    layout.tabs.add(Tab.of(a.getJSONObject(i)));
                JSONArray d = main.optJSONArray("dismissedPresets");
                if (d != null)
                    for (int i = 0; i < d.length(); i++)
                        layout.dismissedPresets.add(d.getString(i));
                return layout;
            } catch (JSONException e) {
                System.err.println("[StashLayout] failed to parse " + layout.path + ": " + e.getMessage());
                layout.tabs.clear();
            }
        }
        layout.tabs.addAll(presets());
        layout.addMissingAutoTabs();
        return layout;
    }

    public void save() {
        if (path == null)
            return;
        JSONArray a = new JSONArray();
        for (Tab t : tabs)
            a.put(t.json());
        JSONObject main = new JSONObject().put("version", 1).put("tabs", a)
                .put("dismissedPresets", new JSONArray(dismissedPresets));
        try {
            NFileUtils.writeAtomically(path, main.toString(2));
        } catch (IOException e) {
            System.err.println("[StashLayout] save failed: " + e.getMessage());
        }
    }

    /** One auto tab for every category no tab auto-fills yet, so every stored item has a home. */
    public void addMissingAutoTabs() {
        Set<String> covered = new HashSet<>();
        for (Tab t : tabs)
            if (t.autoFill != null)
                covered.add(t.autoFill);
        for (StorageClassifier.Category c : StorageClassifier.Category.values()) {
            if (covered.contains(c.id))
                continue;
            Tab t = new Tab();
            t.id = "auto-" + c.id;
            t.title = null; // localized category name
            t.icon = c.icon;
            t.autoFill = c.id;
            t.pinned = false;
            tabs.add(t);
        }
    }

    /** Shipped presets the user has neither got nor deleted, or has an older version of. */
    public List<Tab> newPresets() {
        Map<String, Integer> have = new HashMap<>();
        for (Tab t : tabs) {
            if (t.preset == null)
                continue;
            String[] p = t.preset.split("@");
            have.put(p[0], p.length > 1 ? parseInt(p[1]) : 0);
        }
        List<Tab> out = new ArrayList<>();
        for (Tab t : presets()) {
            String[] p = t.preset.split("@");
            if (dismissedPresets.contains(p[0]))
                continue;
            Integer v = have.get(p[0]);
            if (v == null || v < parseInt(p[1]))
                out.add(t);
        }
        return out;
    }

    /** Adds missing presets and replaces outdated ones in place. */
    public void addPresets(List<Tab> presets) {
        for (Tab p : presets) {
            String id = p.preset.split("@")[0];
            int at = -1;
            for (int i = 0; i < tabs.size(); i++)
                if (tabs.get(i).preset != null && tabs.get(i).preset.split("@")[0].equals(id))
                    at = i;
            if (at >= 0) {
                tabs.set(at, p);
            } else {
                // A preset that auto-fills a category takes over from that category's auto tab.
                tabs.removeIf(t -> !t.pinned && p.autoFill != null && p.autoFill.equals(t.autoFill));
                tabs.add(firstAutoIndex(), p);
            }
        }
    }

    /** Puts a preset tab back the way it shipped; returns the replacement, or null. */
    public Tab resetPreset(Tab tab) {
        int at = tabs.indexOf(tab);
        if (tab.preset == null || at < 0)
            return null;
        String id = tab.preset.split("@")[0];
        for (Tab p : presets()) {
            if (p.preset.split("@")[0].equals(id)) {
                tabs.set(at, p);
                return p;
            }
        }
        return null;
    }

    public void remove(Tab tab) {
        tabs.remove(tab);
        if (tab.preset != null)
            dismissedPresets.add(tab.preset.split("@")[0]);
        addMissingAutoTabs();
    }

    public Tab newTab(String title) {
        Tab t = new Tab();
        t.id = "user-" + Long.toHexString(System.currentTimeMillis()) + "-" + tabs.size();
        t.title = title;
        t.pinned = true;
        t.sections.add(new Section(""));
        tabs.add(firstAutoIndex(), t);
        return t;
    }

    /** Pinned tabs come first, auto tabs after. */
    public int firstAutoIndex() {
        for (int i = 0; i < tabs.size(); i++)
            if (!tabs.get(i).pinned)
                return i;
        return tabs.size();
    }

    public List<Tab> pinnedTabs() {
        List<Tab> out = new ArrayList<>();
        for (Tab t : tabs)
            if (t.pinned)
                out.add(t);
        return out;
    }

    /** A tab as one line of text that can be pasted to someone else. */
    public static String export(Tab tab) {
        JSONObject o = tab.json();
        o.remove("preset");
        return EXPORT_PREFIX + Base64.getEncoder().encodeToString(o.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The tab in an exported line, as a new pinned tab, or null if the text is not one. */
    public Tab importTab(String text) {
        if (text == null)
            return null;
        text = text.trim();
        if (!text.startsWith(EXPORT_PREFIX))
            return null;
        try {
            String json = new String(Base64.getDecoder().decode(text.substring(EXPORT_PREFIX.length())), StandardCharsets.UTF_8);
            Tab t = Tab.of(new JSONObject(json));
            t.id = "user-" + Long.toHexString(System.currentTimeMillis()) + "-" + tabs.size();
            t.pinned = true;
            t.preset = null;
            if (t.sections.isEmpty())
                t.sections.add(new Section(""));
            tabs.add(firstAutoIndex(), t);
            return t;
        } catch (IllegalArgumentException | JSONException e) {
            return null;
        }
    }

    private static List<Tab> presets() {
        List<Tab> out = new ArrayList<>();
        try (InputStream in = StashLayout.class.getResourceAsStream(PRESETS)) {
            if (in == null)
                return out;
            JSONArray a = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("tabs");
            for (int i = 0; i < a.length(); i++)
                out.add(Tab.of(a.getJSONObject(i)));
        } catch (IOException | JSONException e) {
            System.err.println("[StashLayout] presets: " + e.getMessage());
        }
        return out;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
