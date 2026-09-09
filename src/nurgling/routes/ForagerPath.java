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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ForagerPath {

    private static final double SECTION_LENGTH = 50.0;

    public String name;
    public List<ForagerWaypoint> waypoints;
    public List<ForagerSection> sections;

    // Individual tiles painted with the map editor's "Exclusion" brush, keyed by segment id.
    public Map<Long, Set<Coord>> exclusionTiles;

    public void paintExclusion(long seg, Coord tc) {
        exclusionTiles.computeIfAbsent(seg, k -> new HashSet<>()).add(tc);
    }

    public boolean isExcluded(long seg, Coord tc) {
        Set<Coord> tiles = exclusionTiles.get(seg);
        return tiles != null && tiles.contains(tc);
    }

    // Caps on branching off the route to detour-forage, in tiles; -1 = no cap. See DetourBranchBudget.
    public int maxBranches = -1;
    public int maxDistance = -1;
    public int maxBranchDistance = -1;

    // When set, Forager also avoids known cliffs when picking a detour target.
    public boolean avoidCliffs = false;

    // Extra tile margin to keep beyond a detected cliff/ridge tile when avoidCliffs is on.
    public int cliffBufferTiles = 1;

    public ForagerPath(String name) {
        this.name = name;
        this.waypoints = new ArrayList<>();
        this.sections = new ArrayList<>();
        this.exclusionTiles = new HashMap<>();
    }

    public void addWaypoint(ForagerWaypoint wp) {
        waypoints.add(wp);
    }

    public void removeLastWaypoint() {
        if (!waypoints.isEmpty()) {
            waypoints.remove(waypoints.size() - 1);
        }
    }

    /** Removes the waypoint at index, if in range. */
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
            ForagerWaypoint fromWp = waypoints.get(i - 1);
            ForagerWaypoint toWp = waypoints.get(i);
            int waypointIndex = i - 1;

            Coord2d nextPoint = toWp.toWorldCoord(sessloc);
            if(nextPoint == null) continue;
            double distance = currentStart.dist(nextPoint);

            // A milestone splice teleports instantly - world distance between the two sides is
            // meaningless (they can be on entirely different segments) and must never be split
            // into multiple sections, or the main loop would try to replay the same teleport
            // more than once.
            boolean isMilestoneSplice = fromWp.milestoneHash != null && fromWp.milestoneHash.equals(toWp.milestoneHash);

            if (isMilestoneSplice || distance <= SECTION_LENGTH) {
                // Points are close (or a milestone splice), create one section
                sections.add(new ForagerSection(currentStart, nextPoint, sectionIndex++, waypointIndex, true));
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
                    boolean isLastInGap = (j == numSections - 1);
                    sections.add(new ForagerSection(sectionStart, sectionEnd, sectionIndex++, waypointIndex, isLastInGap));
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
        this.exclusionTiles = new HashMap<>();

        // Load waypoints (grid-based)
        if (json.has("waypoints")) {
            JSONArray waypointsArray = json.getJSONArray("waypoints");
            for (int i = 0; i < waypointsArray.length(); i++) {
                JSONObject wpJson = waypointsArray.getJSONObject(i);
                waypoints.add(new ForagerWaypoint(wpJson));
            }
        }

        if (json.has("exclusionTiles")) {
            JSONArray segArray = json.getJSONArray("exclusionTiles");
            for (int i = 0; i < segArray.length(); i++) {
                JSONObject segJson = segArray.getJSONObject(i);
                long seg = segJson.getLong("seg");
                Set<Coord> tiles = new HashSet<>();
                JSONArray tilesArray = segJson.getJSONArray("tiles");
                for (int j = 0; j < tilesArray.length(); j++) {
                    JSONObject tJson = tilesArray.getJSONObject(j);
                    tiles.add(new Coord(tJson.getInt("x"), tJson.getInt("y")));
                }
                exclusionTiles.put(seg, tiles);
            }
        }

        if (json.has("maxBranches")) {
            this.maxBranches = json.getInt("maxBranches");
        }
        if (json.has("maxDistance")) {
            this.maxDistance = json.getInt("maxDistance");
        }
        if (json.has("maxBranchDistance")) {
            this.maxBranchDistance = json.getInt("maxBranchDistance");
        }
        this.avoidCliffs = json.optBoolean("avoidCliffs", false);
        this.cliffBufferTiles = json.optInt("cliffBufferTiles", 1);

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

        JSONArray segArray = new JSONArray();
        for (Map.Entry<Long, Set<Coord>> entry : exclusionTiles.entrySet()) {
            JSONObject segJson = new JSONObject();
            segJson.put("seg", entry.getKey());
            JSONArray tilesArray = new JSONArray();
            for (Coord tc : entry.getValue()) {
                JSONObject tJson = new JSONObject();
                tJson.put("x", tc.x);
                tJson.put("y", tc.y);
                tilesArray.put(tJson);
            }
            segJson.put("tiles", tilesArray);
            segArray.put(segJson);
        }
        json.put("exclusionTiles", segArray);

        if (maxBranches >= 0) {
            json.put("maxBranches", maxBranches);
        }
        if (maxDistance >= 0) {
            json.put("maxDistance", maxDistance);
        }
        if (maxBranchDistance >= 0) {
            json.put("maxBranchDistance", maxBranchDistance);
        }
        json.put("avoidCliffs", avoidCliffs);
        json.put("cliffBufferTiles", cliffBufferTiles);

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
