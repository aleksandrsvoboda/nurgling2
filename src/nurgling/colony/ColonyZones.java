package nurgling.colony;

import haven.Area;
import haven.Coord;
import nurgling.NGameUI;
import nurgling.areas.NArea;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the colony may take building materials from: only Construction Materials zones (specialisation
 * {@code buildMaterials}) with the material's subtype. Nothing else is touched, so good boards or blocks kept
 * elsewhere are never spent on a beacon.
 *
 * <p>A snapshot holds each zone's tiles per map grid, so storage-index rows (grid id plus the container's spot in the
 * grid) can be tested against them without the zone being loaded.
 */
public final class ColonyZones {
    /** The zone subtype per building material, as in {@link nurgling.conf.ConstructionMaterialsRegistry}. */
    public static final Map<ColonyItems.Material, String> SUBTYPE;

    static {
        Map<ColonyItems.Material, String> s = new EnumMap<>(ColonyItems.Material.class);
        s.put(ColonyItems.Material.CLAY, "Clay");
        s.put(ColonyItems.Material.STONE, "Stone");
        s.put(ColonyItems.Material.BOARD, "Board");
        s.put(ColonyItems.Material.BLOCK, "Block");
        SUBTYPE = Collections.unmodifiableMap(s);
    }

    /** Posres units per tile in a container's stored spot ("(x, y)" inside its grid). */
    private static final int POS_PER_TILE = 1024;

    private final Map<ColonyItems.Material, Map<Long, List<Area>>> zones;
    private final String key;

    private ColonyZones(Map<ColonyItems.Material, Map<Long, List<Area>>> zones, String key) {
        this.zones = zones;
        this.key = key;
    }

    public static final ColonyZones NONE = new ColonyZones(Collections.emptyMap(), "");

    /** The zones as {@code gui}'s areas have them. Call on that session's UI thread. */
    public static ColonyZones of(NGameUI gui) {
        if (gui == null || gui.map == null)
            return NONE;
        Map<ColonyItems.Material, Map<Long, List<Area>>> z = new EnumMap<>(ColonyItems.Material.class);
        StringBuilder key = new StringBuilder();
        for (NArea a : new ArrayList<>(gui.map.glob.map.areas.values())) {
            if (a == null || a.isDisabled() || a.space == null)
                continue;
            for (NArea.Specialisation s : a.spec) {
                if (!Specialisation.SpecName.buildMaterials.toString().equals(s.name) || s.subtype == null)
                    continue;
                for (Map.Entry<ColonyItems.Material, String> e : SUBTYPE.entrySet()) {
                    if (!e.getValue().equalsIgnoreCase(s.subtype))
                        continue;
                    Map<Long, List<Area>> byGrid = z.computeIfAbsent(e.getKey(), k -> new HashMap<>());
                    for (Map.Entry<Long, NArea.VArea> g : a.space.space.entrySet()) {
                        byGrid.computeIfAbsent(g.getKey(), k -> new ArrayList<>()).add(g.getValue().area);
                        key.append(e.getKey()).append(g.getKey()).append(g.getValue().area).append(';');
                    }
                }
            }
        }
        return new ColonyZones(z, key.toString());
    }

    public static ColonyZones of(Map<ColonyItems.Material, Map<Long, List<Area>>> zones) {
        return new ColonyZones(zones, String.valueOf(zones));
    }

    /** Every grid a zone lies on. */
    public Set<Long> grids() {
        Set<Long> ids = new HashSet<>();
        for (Map<Long, List<Area>> g : zones.values())
            ids.addAll(g.keySet());
        return ids;
    }

    /** Whether there is any zone for {@code m}. */
    public boolean has(ColonyItems.Material m) {
        Map<Long, List<Area>> g = zones.get(m);
        return g != null && !g.isEmpty();
    }

    /** Whether a container at {@code coord} ("(x, y)" in posres units) of grid {@code gridId} is in a zone for m. */
    public boolean contains(ColonyItems.Material m, long gridId, String coord) {
        Map<Long, List<Area>> g = zones.get(m);
        List<Area> in = (g == null) ? null : g.get(gridId);
        if (in == null)
            return false;
        Coord tc = tile(coord);
        if (tc == null)
            return false;
        for (Area a : in)
            if (a.contains(tc))
                return true;
        return false;
    }

    static Coord tile(String coord) {
        if (coord == null)
            return null;
        String[] p = coord.replace("(", "").replace(")", "").replace(" ", "").split(",");
        if (p.length != 2)
            return null;
        try {
            return Coord.of((int) Math.floor(Double.parseDouble(p[0]) / POS_PER_TILE),
                            (int) Math.floor(Double.parseDouble(p[1]) / POS_PER_TILE));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Each material's zone grids and tiles, for the log. */
    @Override
    public String toString() {
        return zones.toString();
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof ColonyZones) && ((ColonyZones) o).key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }
}
