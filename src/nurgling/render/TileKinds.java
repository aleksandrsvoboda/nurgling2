package nurgling.render;

import haven.*;
import java.util.*;

/*
 * What kind of ground a map tile is, for the world effects: water to
 * wade in, snow and dry dirt to kick up, and grassland, with how
 * dense and how dry its grass is.
 */
public class TileKinds {
    public static final int OTHER = 0, WATER = 1, SNOW = 2, DUST = 3;

    private static final Set<String> water = new HashSet<>(Arrays.asList(
	"water", "deep", "owater", "odeep", "odeeper", "bogwater", "fenwater", "marshwater", "honeyriver", "warmdepth"));
    private static final Set<String> snow = new HashSet<>(Arrays.asList(
	"snow", "snow-forest", "snow-pave", "mountainsnow"));
    private static final Set<String> dust = new HashSet<>(Arrays.asList(
	"dirt", "field", "plowed", "sand", "beach", "dryflat", "badlands", "sandcliff", "ashland", "hardsteppe", "redplain", "anthill", "acreclaypit"));

    /* Grass: density (0..1) and dryness (0 lush green, 1 straw). */
    private static final Map<String, float[]> grass = new HashMap<>();
    static {
	float[][] v = {
	    {1.0f, 0.0f}, {1.0f, 0.1f}, {0.9f, 0.0f}, {1.0f, 0.05f}, {0.9f, 0.1f}, {0.8f, 0.2f},
	    {0.8f, 0.05f}, {0.7f, 0.25f}, {0.6f, 0.3f}, {0.7f, 0.35f}, {0.7f, 0.15f}, {0.6f, 0.1f},
	    {0.5f, 0.6f}, {0.45f, 0.7f}, {0.4f, 0.75f},
	    {0.35f, 0.1f}, {0.3f, 0.1f}, {0.35f, 0.15f}, {0.3f, 0.2f}, {0.35f, 0.1f}, {0.3f, 0.15f},
	    {0.25f, 0.25f}, {0.3f, 0.1f}, {0.25f, 0.2f}, {0.2f, 0.3f},
	};
	String[] n = {
	    "greensward", "wildturf", "lushfield", "flowermeadow", "oxpasture", "bullgrass",
	    "greenbrake", "highground", "scrubveld", "moor", "wildmoor", "fen",
	    "hardsteppe", "redplain", "dryflat",
	    "grove", "beechgrove", "oakwilds", "timberland", "shadycopse", "mossbrush",
	    "lichenwold", "thicket", "rootbosk", "pinebarren",
	};
	for(int i = 0; i < n.length; i++)
	    grass.put(n[i], v[i]);
    }

    private static String base(String resname) {
	if(resname == null)
	    return(null);
	int i = resname.lastIndexOf('/');
	return((i < 0) ? resname : resname.substring(i + 1));
    }

    public static int kind(String resname) {
	String b = base(resname);
	if(b == null) return(OTHER);
	if(water.contains(b)) return(WATER);
	if(snow.contains(b)) return(SNOW);
	if(dust.contains(b)) return(DUST);
	return(OTHER);
    }

    /* {density, dryness}, or null for no grass. */
    public static float[] grass(String resname) {
	return(grass.get(base(resname)));
    }

    /* The tile's resource name at a map position, or null. */
    public static String at(MCache map, Coord2d mc) {
	try {
	    int t = map.gettile(mc.floor(MCache.tilesz));
	    return(map.tilesetname(t));
	} catch(Loading l) {
	    return(null);
	}
    }
}
