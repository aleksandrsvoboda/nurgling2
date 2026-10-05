package nurgling.craft;

import java.util.*;
import org.json.*;

/** Named, independent calculator canvases saved together for one character. */
public final class CraftCanvases {
    public static final int LIMIT = 100;
    public static final class Entry {
        public final String id;
        private String name;
        public final CraftFlow flow;
        private Entry(String id, String name, CraftFlow flow) { this.id=id; this.name=name; this.flow=flow; }
        public String name() { return name; }
    }
    private final List<Entry> entries = new ArrayList<>();
    private Entry active;
    private long revision;

    public CraftCanvases() { this(new CraftFlow()); }
    public CraftCanvases(CraftFlow initial) {
        active = new Entry(UUID.randomUUID().toString(), "", initial);
        entries.add(active);
    }
    public List<Entry> entries() { return Collections.unmodifiableList(entries); }
    public Entry active() { return active; }
    public Entry create(String name) {
        if(entries.size() >= LIMIT) throw new IllegalArgumentException("Maximum 100 canvases");
        Entry entry = new Entry(UUID.randomUUID().toString(), checkedName(name), new CraftFlow());
        entries.add(entry); revision++; return entry;
    }
    public void select(Entry entry) {
        if(!entries.contains(entry)) throw new IllegalArgumentException("Unknown canvas");
        if(active != entry) { active = entry; revision++; }
    }
    public void rename(Entry entry, String name) {
        if(!entries.contains(entry)) throw new IllegalArgumentException("Unknown canvas");
        String value = checkedName(name);
        if(!value.equals(entry.name)) { entry.name=value; revision++; }
    }
    public void remove(Entry entry) {
        int index = entries.indexOf(entry);
        if(index < 0) throw new IllegalArgumentException("Unknown canvas");
        entries.remove(index);
        // Keep the save version increasing when the removed graph leaves the sum.
        revision += (long)entry.flow.version + 1;
        if(entries.isEmpty()) active = create("");
        else if(active == entry) active = entries.get(Math.min(index, entries.size() - 1));
    }
    private static String checkedName(String value) {
        String name = Objects.requireNonNull(value).trim();
        if(name.length() > 80 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid canvas name");
        return name;
    }
    public long version() {
        long version=revision;
        for(Entry entry : entries) version+=entry.flow.version;
        return version;
    }
    public JSONObject json() {
        JSONArray all = new JSONArray();
        for(Entry entry : entries) all.put(new JSONObject().put("id",entry.id).put("name",entry.name).put("flow",entry.flow.json()));
        return new JSONObject().put("version",2).put("active",active.id).put("canvases",all);
    }
    public void load(JSONObject data) {
        List<Entry> fresh = new ArrayList<>();
        String selected;
        if(data.getInt("version") == 1) {
            // Import the original single-canvas file without changing its graph or view.
            CraftFlow flow = new CraftFlow(); flow.load(data);
            selected=UUID.randomUUID().toString(); fresh.add(new Entry(selected,"",flow));
        } else if(data.getInt("version") == 2) {
            JSONArray all=data.getJSONArray("canvases");
            if(all.length()<1 || all.length()>LIMIT) throw new IllegalArgumentException("Invalid canvas count");
            Set<String> ids=new HashSet<>();
            for(int i=0; i<all.length(); i++) {
                JSONObject item=all.getJSONObject(i); String id=item.getString("id");
                if(id.isBlank() || id.length()>100 || !ids.add(id)) throw new IllegalArgumentException("Invalid canvas identity");
                CraftFlow flow=new CraftFlow(); flow.load(item.getJSONObject("flow"));
                fresh.add(new Entry(id,checkedName(item.getString("name")),flow));
            }
            selected=data.getString("active");
        } else throw new IllegalArgumentException("Unknown canvas collection version");
        Entry current=fresh.stream().filter(e -> e.id.equals(selected)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Missing active canvas"));
        // Commit only after every graph has passed DAG and input validation.
        entries.clear(); entries.addAll(fresh); active=current; revision++;
    }
}
