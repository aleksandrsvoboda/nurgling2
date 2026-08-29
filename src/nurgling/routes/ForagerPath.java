package nurgling.routes;

import haven.*;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ForagerPath {

    private static final double SECTION_LENGTH = 50.0;

    public String name;
    public List<ForagerWaypoint> waypoints;
    public List<ForagerSection> sections;

    /** A rectangular, segment-tied region the bot should never chain-forage into. Purely
     *  captured data for now - not yet read by any bot logic (see the Forager refactor plan). */
    public static class NoForageZone {
        public long seg;
        public Coord ul;
        public Coord br;

        public NoForageZone(long seg, Coord a, Coord b) {
            this.seg = seg;
            this.ul = new Coord(Math.min(a.x, b.x), Math.min(a.y, b.y));
            this.br = new Coord(Math.max(a.x, b.x), Math.max(a.y, b.y));
        }

        public NoForageZone(JSONObject json) {
            this.seg = json.getLong("seg");
            JSONObject ulJson = json.getJSONObject("ul");
            this.ul = new Coord(ulJson.getInt("x"), ulJson.getInt("y"));
            JSONObject brJson = json.getJSONObject("br");
            this.br = new Coord(brJson.getInt("x"), brJson.getInt("y"));
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            json.put("seg", seg);
            JSONObject ulJson = new JSONObject();
            ulJson.put("x", ul.x);
            ulJson.put("y", ul.y);
            json.put("ul", ulJson);
            JSONObject brJson = new JSONObject();
            brJson.put("x", br.x);
            brJson.put("y", br.y);
            json.put("br", brJson);
            return json;
        }
    }

    public List<NoForageZone> noForageZones;

    // Placeholder caps for a future Forager bot-behavior refactor (chain-foraging away from the
    // route) - not read by any bot logic yet. -1 = no cap, same sentinel convention as
    // NForagerProp.PresetData.startAreaId. All in tiles.
    public int maxChains = -1;
    public int maxDistance = -1;
    public int maxChainDistance = -1;

    public ForagerPath(String name) {
        this.name = name;
        this.waypoints = new ArrayList<>();
        this.sections = new ArrayList<>();
        this.noForageZones = new ArrayList<>();
    }

    public void addWaypoint(ForagerWaypoint wp) {
        waypoints.add(wp);
    }

    public void removeLastWaypoint() {
        if (!waypoints.isEmpty()) {
            waypoints.remove(waypoints.size() - 1);
        }
    }

    /** Removes the waypoint at index, if in range - used by the Routes map editor's
     *  right-click-to-delete (arbitrary index, unlike the append-only/last-only methods above). */
    public void removeWaypointAt(int index) {
        if (index >= 0 && index < waypoints.size()) {
            waypoints.remove(index);
        }
    }
    
    public void generateSections() {
        sections.clear();
        
        if (waypoints.size() < 2) {
            return;
        }
        
        // Get sessloc for coordinate conversion
        MiniMap.Location sessloc = nurgling.NUtils.getGameUI().mmap.sessloc;
        if(sessloc == null) return;
        
        int sectionIndex = 0;
        Coord2d currentStart = waypoints.get(0).toWorldCoord(sessloc);
        if(currentStart == null) return;
        
        for (int i = 1; i < waypoints.size(); i++) {
            Coord2d nextPoint = waypoints.get(i).toWorldCoord(sessloc);
            if(nextPoint == null) continue;
            double distance = currentStart.dist(nextPoint);
            
            if (distance <= SECTION_LENGTH) {
                // Points are close, create one section
                sections.add(new ForagerSection(currentStart, nextPoint, sectionIndex++));
                currentStart = nextPoint;
            } else {
                // Points are far, create intermediate sections
                int numSections = (int) Math.ceil(distance / SECTION_LENGTH);
                double stepX = (nextPoint.x - currentStart.x) / numSections;
                double stepY = (nextPoint.y - currentStart.y) / numSections;
                
                for (int j = 0; j < numSections; j++) {
                    Coord2d sectionStart = new Coord2d(
                        currentStart.x + stepX * j,
                        currentStart.y + stepY * j
                    );
                    Coord2d sectionEnd = new Coord2d(
                        currentStart.x + stepX * (j + 1),
                        currentStart.y + stepY * (j + 1)
                    );
                    sections.add(new ForagerSection(sectionStart, sectionEnd, sectionIndex++));
                }
                currentStart = nextPoint;
            }
        }
    }
    
    public ForagerSection getSection(int index) {
        if (index >= 0 && index < sections.size()) {
            return sections.get(index);
        }
        return null;
    }
    
    public int getSectionCount() {
        return sections.size();
    }
    
    public void save(String directory) throws IOException {
        Path dirPath = Paths.get(directory);
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }
        
        Path filePath = dirPath.resolve(name + ".json");
        JSONObject json = toJson();
        NFileUtils.writeAtomically(filePath.toString(), json.toString(2));
    }
    
    public static ForagerPath load(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        String content = new String(Files.readAllBytes(path));
        JSONObject json = new JSONObject(content);
        return new ForagerPath(json);
    }
    
    public ForagerPath(JSONObject json) {
        this.name = json.getString("name");
        this.waypoints = new ArrayList<>();
        this.sections = new ArrayList<>();
        this.noForageZones = new ArrayList<>();

        // Load waypoints (grid-based)
        if (json.has("waypoints")) {
            JSONArray waypointsArray = json.getJSONArray("waypoints");
            for (int i = 0; i < waypointsArray.length(); i++) {
                JSONObject wpJson = waypointsArray.getJSONObject(i);
                waypoints.add(new ForagerWaypoint(wpJson));
            }
        }

        if (json.has("noForageZones")) {
            JSONArray zonesArray = json.getJSONArray("noForageZones");
            for (int i = 0; i < zonesArray.length(); i++) {
                noForageZones.add(new NoForageZone(zonesArray.getJSONObject(i)));
            }
        }

        if (json.has("maxChains")) {
            this.maxChains = json.getInt("maxChains");
        }
        if (json.has("maxDistance")) {
            this.maxDistance = json.getInt("maxDistance");
        }
        if (json.has("maxChainDistance")) {
            this.maxChainDistance = json.getInt("maxChainDistance");
        }

        // Always generate sections from waypoints (don't load from JSON)
        // Sections use world coordinates which are session-specific
        generateSections();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("name", name);

        // Save only waypoints (grid-based, persistent between sessions)
        JSONArray waypointsArray = new JSONArray();
        for (ForagerWaypoint wp : waypoints) {
            waypointsArray.put(wp.toJson());
        }
        json.put("waypoints", waypointsArray);

        JSONArray zonesArray = new JSONArray();
        for (NoForageZone zone : noForageZones) {
            zonesArray.put(zone.toJson());
        }
        json.put("noForageZones", zonesArray);

        if (maxChains >= 0) {
            json.put("maxChains", maxChains);
        }
        if (maxDistance >= 0) {
            json.put("maxDistance", maxDistance);
        }
        if (maxChainDistance >= 0) {
            json.put("maxChainDistance", maxChainDistance);
        }

        // Don't save sections - they will be regenerated from waypoints
        // because they use world coordinates which are session-specific

        return json;
    }
    
    @Override
    public String toString() {
        return String.format("ForagerPath[%s: %d waypoints, %d sections]", 
            name, waypoints.size(), sections.size());
    }
}
