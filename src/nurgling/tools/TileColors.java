package nurgling.tools;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One colour per tile texture for the uniform biome colours map option: the average of the
 * texture's pixels (as ArdClient does), so no single pebble or shadow decides a biome's colour.
 * Transparent pixels are left out. Computed once per texture.
 */
public final class TileColors {
    private static final Map<BufferedImage, Integer> cache = new WeakHashMap<>();

    private TileColors() {}

    /** The texture's average colour, opaque, as 0xAARRGGBB. */
    public static int average(BufferedImage tex) {
	synchronized(cache) {
	    Integer hit = cache.get(tex);
	    if(hit != null)
		return(hit);
	}
	int rgb = compute(tex);
	synchronized(cache) {
	    cache.put(tex, rgb);
	}
	return(rgb);
    }

    static int compute(BufferedImage tex) {
	int w = tex.getWidth(), h = tex.getHeight();
	int[] px = tex.getRGB(0, 0, w, h, null, 0, w);
	long r = 0, g = 0, b = 0, n = 0;
	for(int p : px) {
	    if((p >>> 24) == 0)
		continue;
	    r += (p >> 16) & 0xff;
	    g += (p >> 8) & 0xff;
	    b += p & 0xff;
	    n++;
	}
	if(n == 0)
	    return(0xff000000);
	return(0xff000000 | (int)((r / n) << 16) | (int)((g / n) << 8) | (int)(b / n));
    }
}
