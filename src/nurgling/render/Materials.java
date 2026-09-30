package nurgling.render;

import java.awt.image.BufferedImage;

/*
 * What an object texture is made of, for its relief (see
 * GroundRelief). One recipe does not suit everything: stone and brick
 * read as domed blocks between grooves, wood as grooves running along
 * the grain, thatch as fine fibres, cloth and leaves hardly at all,
 * and plaster not at all. The kind comes from the texture's resource
 * name, or failing that from the picture itself.
 */
public class Materials {
    public enum Kind {
	STONE(1.0f), WOOD(0.75f), FIBER(0.6f), PLANT(0.3f), SOFT(0.22f), FLAT(0f);

	/* Depth, relative to stone's. */
	public final float depth;
	Kind(float depth) {this.depth = depth;}
    }

    private static final String[] flat = {"plaster", "whitewash", "glass", "paper", "parchment"};
    private static final String[] soft = {"cloth", "linen", "wool", "silk", "leather", "hide", "fur", "rope", "tent", "banner",
					  "rug", "carpet", "sail", "pillow", "yarn", "string", "cushion", "flag"};
    private static final String[] fiber = {"thatch", "straw", "hay", "reed", "wicker", "basket", "twig", "grass"};
    private static final String[] plant = {"leaf", "leaves", "flower", "herb", "bush", "crop", "plants/", "vine", "moss",
					   "hops", "grape", "fern", "mushroom", "shroom", "foliage"};
    private static final String[] stone = {"stone", "brick", "rock", "bumling", "cobble", "paving", "column", "boulder",
					   "granite", "marble", "slate", "gneiss", "cliff", "quern", "kiln", "oven", "smelter",
					   "tiles/", "cave"};
    private static final String[] wood = {"wood", "plank", "log", "board", "timber", "bark", "barrel", "chest", "crate",
					  "palisade", "cupboard", "table", "chair", "shed", "trellis", "fence", "door", "gate",
					  "bench", "trough", "rack", "frame", "ladder", "hut", "house", "cabin", "boat", "cart",
					  "wagon", "trees/", "stump", "beam", "pole", "post", "shelf", "bin", "coop", "hive"};

    private static boolean any(String nm, String[] words) {
	for(String w : words) {
	    if(nm.contains(w))
		return(true);
	}
	return(false);
    }

    /* The kind of a texture, by name first, then by the picture. */
    public static Kind classify(String name, BufferedImage img, float[] lum) {
	/* See-through cut-outs are foliage (leaves, hedges), whatever
	 * they belong to. */
	if(cutout(img) > 0.12f)
	    return(Kind.PLANT);
	String nm = (name == null) ? "" : name.toLowerCase();
	int i = nm.indexOf("texture in ");
	if(i >= 0)
	    nm = nm.substring(i + 11);
	if(any(nm, flat)) return(Kind.FLAT);
	if(any(nm, soft)) return(Kind.SOFT);
	if(any(nm, fiber)) return(Kind.FIBER);
	if(any(nm, plant)) return(Kind.PLANT);
	if(any(nm, stone)) return(Kind.STONE);
	if(any(nm, wood)) return(Kind.WOOD);
	/* Strongly directional texture reads as wood grain. */
	return((coherence(lum, img.getWidth(), img.getHeight()) > 0.45f) ? Kind.WOOD : Kind.STONE);
    }

    /* The share of (nearly) fully transparent pixels. */
    static float cutout(BufferedImage img) {
	if(!img.getColorModel().hasAlpha())
	    return(0);
	int w = img.getWidth(), h = img.getHeight(), n = 0, t = 0;
	for(int y = 0; y < h; y += 2) {
	    for(int x = 0; x < w; x += 2) {
		if(((img.getRGB(x, y) >>> 24) & 255) < 128)
		    t++;
		n++;
	    }
	}
	return((n == 0) ? 0 : ((float)t / n));
    }

    private static float at(float[] a, int w, int h, int x, int y) {
	return(a[Math.floorMod(y, h) * w + Math.floorMod(x, w)]);
    }

    /* The structure tensor (gxx, gyy, gxy), smoothed. */
    private static float[][] tensor(float[] lum, int w, int h) {
	float[] xx = new float[w * h], yy = new float[w * h], xy = new float[w * h];
	for(int y = 0; y < h; y++) {
	    for(int x = 0; x < w; x++) {
		float gx = (at(lum, w, h, x + 1, y) - at(lum, w, h, x - 1, y)) * 0.5f;
		float gy = (at(lum, w, h, x, y + 1) - at(lum, w, h, x, y - 1)) * 0.5f;
		int i = y * w + x;
		xx[i] = gx * gx; yy[i] = gy * gy; xy[i] = gx * gy;
	    }
	}
	int r = Math.max(2, Math.min(w, h) / 64);
	GroundRelief.blur(xx, w, h, r); GroundRelief.blur(xx, w, h, r);
	GroundRelief.blur(yy, w, h, r); GroundRelief.blur(yy, w, h, r);
	GroundRelief.blur(xy, w, h, r); GroundRelief.blur(xy, w, h, r);
	return(new float[][] {xx, yy, xy});
    }

    /* How strongly the texture runs in one direction, 0..1. */
    static float coherence(float[] lum, int w, int h) {
	float[][] t = tensor(lum, w, h);
	double sum = 0, wsum = 0;
	for(int i = 0; i < lum.length; i++) {
	    float a = t[0][i], b = t[1][i], c = t[2][i];
	    float tr = a + b;
	    if(tr < 1e-6f)
		continue;
	    sum += Math.sqrt((a - b) * (a - b) + 4 * c * c);
	    wsum += tr;
	}
	return((wsum <= 0) ? 0 : (float)(sum / wsum));
    }

    /* Wood: the grain's dark lines as grooves, smoothed along the
     * grain so they run as long furrows rather than dots. */
    static float[] wood(float[] lum, int w, int h) {
	int sz = Math.min(w, h);
	float[] mean = lum.clone();
	int rm = Math.max(2, sz / 48);
	GroundRelief.blur(mean, w, h, rm);
	GroundRelief.blur(mean, w, h, rm);
	float[] hp = new float[w * h];
	for(int i = 0; i < hp.length; i++)
	    hp[i] = lum[i] - mean[i];
	float[][] t = tensor(lum, w, h);
	float[] out = new float[w * h];
	for(int y = 0; y < h; y++) {
	    for(int x = 0; x < w; x++) {
		int i = y * w + x;
		/* The gradient's main direction runs across the grain. */
		double phi = 0.5 * Math.atan2(2 * t[2][i], t[0][i] - t[1][i]) + (Math.PI / 2);
		float dx = (float)Math.cos(phi), dy = (float)Math.sin(phi);
		float s = 0;
		for(int k = -4; k <= 4; k++)
		    s += at(hp, w, h, Math.round(x + dx * k), Math.round(y + dy * k));
		out[i] = GroundRelief.smoothstep(-0.05f, 0.05f, s / 9f);
	    }
	}
	GroundRelief.blur(out, w, h, 1);
	return(out);
    }

    /* Fine detail: fibres, weave, leaf veins. */
    static float[] fine(float[] lum, int w, int h) {
	float[] mean = lum.clone();
	int r = Math.max(1, Math.min(w, h) / 96);
	GroundRelief.blur(mean, w, h, r);
	float[] out = new float[w * h];
	for(int i = 0; i < out.length; i++)
	    out[i] = GroundRelief.smoothstep(-0.07f, 0.07f, lum[i] - mean[i]);
	GroundRelief.blur(out, w, h, 1);
	return(out);
    }
}
