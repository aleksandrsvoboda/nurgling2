package nurgling.widgets;

/**
 * Shaded relief for one map grid: tile colours lit by the ground's slope, thin lines where the
 * tile type changes, dark cliffs, and optional contour lines.
 *
 * Pure: the input is a {@link Cells} block (one grid plus a one-tile border of its neighbours)
 * and the output is RGBA bytes, so it runs off the UI thread and in tests. {@link ReliefGrid}
 * fills the cells from the map file.
 *
 * Light comes from the north-west, as on printed maps; flat ground keeps its colour exactly.
 */
public final class ReliefRenderer {
    /** World units per tile. */
    static final double TILE = 11;
    /** Slope exaggeration at strength 1; matches the web map's shading for gentle slopes. */
    static final double EXAGGERATION = 0.8;
    static final double MINSHADE = 0.55, MAXSHADE = 1.35;
    /** Water shows its bed's shape at half strength, so it reads as depth, not noise. */
    static final double WATERSHADE = 0.5;
    static final double EDGE = 0.72, SOFTEDGE = 0.8, NEARCLIFF = 0.8;
    static final int CLIFF = 0x1c1612;
    /** Contour lines; every fifth one (an index contour) darker. */
    static final double CONTOUR = 0.84, INDEXCONTOUR = 0.70;
    static final int INDEXEVERY = 5;
    /** Leveling plans: tiles to dig, to fill, already level, and the plan's outline. */
    static final int CUT = 0xe0463c, FILL = 0x3c8cf0, LEVEL = 0x96d296, PLANLINE = 0xe0a040;
    /** Ground within this many height units of the target counts as level. */
    static final double LEVELTOL = 0.5;
    /** Height difference at which the dig or fill tint is at its strongest. */
    static final double FULLTINT = 10;
    static final double MINTINT = 0.25, MAXTINT = 0.8, LEVELTINT = 0.45, WASH = 0.35, PLANEDGE1 = 0.6;
    /* Light from the north-west, about 35 degrees up: x east, y south, z up. */
    private static final double LX, LY, LZ;
    static {
	double lx = -1, ly = -1, lz = 1.4, l = Math.sqrt((lx * lx) + (ly * ly) + (lz * lz));
	LX = lx / l; LY = ly / l; LZ = lz / l;
    }

    private ReliefRenderer() {}

    /** One grid of {@code n x n} tiles and a border ring; x and y run from -1 to n. */
    public static final class Cells {
	public final int n, w;
	/** Tile kind; tiles of one kind draw no line between them. -1 = no data (unexplored). */
	public final int[] tile;
	/** Draw order: at 1 pixel per tile the lower-ranked side of a boundary is darkened. */
	public final int[] rank;
	public final float[] z;
	/** Colour, 0xRRGGBB; read only inside the grid. */
	public final int[] rgb;
	public final boolean[] cliff, water;
	/** The leveling plan a tile belongs to, as a small id; -1 = none. Read on the border ring too. */
	public final int[] plan;
	/** The height a plan levels this tile to; NaN = none. */
	public final float[] target;
	/** Thin lines where the tile type changes; off when relief shading is off and only a plan draws. */
	public boolean edges = true;

	public Cells(int n) {
	    this.n = n;
	    this.w = n + 2;
	    int sz = w * w;
	    tile = new int[sz];
	    java.util.Arrays.fill(tile, -1);
	    rank = new int[sz];
	    z = new float[sz];
	    rgb = new int[sz];
	    cliff = new boolean[sz];
	    water = new boolean[sz];
	    plan = new int[sz];
	    java.util.Arrays.fill(plan, -1);
	    target = new float[sz];
	    java.util.Arrays.fill(target, Float.NaN);
	}

	public int at(int x, int y) {
	    return(((y + 1) * w) + x + 1);
	}

	boolean has(int i) {
	    return(tile[i] >= 0);
	}
    }

    /**
     * Brightness factor of a tile from its slope. A neighbour with no data is skipped (a one-sided
     * difference), so the edge of the explored map does not read as a cliff.
     *
     * @param span world units between two tile centres at this zoom level
     */
    static double shade(Cells c, int x, int y, double span, double ex) {
	int i = c.at(x, y);
	double z0 = c.z[i];
	double dx = slope(c, c.at(x - 1, y), c.at(x + 1, y), z0);
	double dy = slope(c, c.at(x, y - 1), c.at(x, y + 1), z0);
	double gx = dx / span * ex, gy = dy / span * ex;
	double d = ((-gx * LX) - (gy * LY) + LZ) / Math.sqrt((gx * gx) + (gy * gy) + 1);
	return(Math.max(MINSHADE, Math.min(MAXSHADE, d / LZ)));
    }

    /** Height change per tile across a cell, from whichever of its two neighbours have data. */
    private static double slope(Cells c, int lo, int hi, double z0) {
	boolean hl = c.has(lo), hh = c.has(hi);
	if(hl && hh)
	    return((c.z[hi] - c.z[lo]) / 2);
	if(hh)
	    return(c.z[hi] - z0);
	if(hl)
	    return(z0 - c.z[lo]);
	return(0);
    }

    private static boolean nearcliff(Cells c, int x, int y) {
	return(c.cliff[c.at(x - 1, y)] || c.cliff[c.at(x + 1, y)] || c.cliff[c.at(x, y - 1)] || c.cliff[c.at(x, y + 1)]);
    }

    /**
     * The contour interval at a zoom level. Zoomed out the ground is squeezed together, so the
     * interval widens with it and the lines stay apart.
     */
    static double contourinterval(double base, int lvl) {
	return(base * (1 << Math.max(0, lvl - 1)));
    }

    private static int band(Cells c, int i, double iv) {
	return((int)Math.floor(c.z[i] / iv));
    }

    /** Whether a contour runs between two cells, ignoring water, cliffs and unexplored ground. */
    private static boolean contourable(Cells c, int i) {
	return(c.has(i) && !c.water[i] && !c.cliff[i]);
    }

    /** Darkening for a contour between two cells, or 1 if none: the line is the level between their bands. */
    private static double contour(Cells c, int i, int j, double iv) {
	if(!contourable(c, i) || !contourable(c, j))
	    return(1);
	int a = band(c, i, iv), b = band(c, j, iv);
	if(a == b)
	    return(1);
	return((Math.floorMod(Math.max(a, b), INDEXEVERY) == 0) ? INDEXCONTOUR : CONTOUR);
    }

    /** At 1 pixel per tile a contour darkens the tiles on its high side. */
    private static double contourhigh(Cells c, int x, int y, double iv) {
	int i = c.at(x, y);
	double f = 1;
	for(int j : new int[] {c.at(x - 1, y), c.at(x + 1, y), c.at(x, y - 1), c.at(x, y + 1)}) {
	    if(contourable(c, j) && (band(c, j, iv) < band(c, i, iv)))
		f = Math.min(f, contour(c, i, j, iv));
	}
	return(f);
    }

    private static int mix(int a, int b, double t) {
	int r = (int)Math.round((((a >> 16) & 0xff) * (1 - t)) + (((b >> 16) & 0xff) * t));
	int g = (int)Math.round((((a >> 8) & 0xff) * (1 - t)) + (((b >> 8) & 0xff) * t));
	int l = (int)Math.round(((a & 0xff) * (1 - t)) + ((b & 0xff) * t));
	return((r << 16) | (g << 8) | l);
    }

    /**
     * A tile's colour under its leveling plan: red where the ground is above the target, blue where
     * it is below, pale green where it is already level, stronger the further off it is. The tile's
     * height is the mean of its four corners. Zoomed out the heights are block minimums, which
     * would read as fill everywhere, so there the plan is a flat wash instead.
     */
    static int planrgb(Cells c, int x, int y, int lvl, int col) {
	int i = c.at(x, y);
	if((c.plan[i] < 0) || c.water[i] || c.cliff[i])
	    return(col);
	if(lvl > 0)
	    return(mix(col, PLANLINE, WASH));
	float t = c.target[i];
	if(Float.isNaN(t))
	    return(col);
	double zs = 0;
	int zn = 0;
	for(int j : new int[] {i, c.at(x + 1, y), c.at(x, y + 1), c.at(x + 1, y + 1)}) {
	    if(c.has(j)) {
		zs += c.z[j];
		zn++;
	    }
	}
	double d = (zs / zn) - t;
	if(Math.abs(d) < LEVELTOL)
	    return(mix(col, LEVEL, LEVELTINT));
	double a = MINTINT + ((MAXTINT - MINTINT) * Math.min(1, Math.abs(d) / FULLTINT));
	return(mix(col, (d > 0) ? CUT : FILL, a));
    }

    /** Whether a plan's outline runs between two cells: one is in a plan and the other is not, or in another. */
    private static boolean planedge(Cells c, int i, int j) {
	return((c.plan[i] != c.plan[j]) && ((c.plan[i] >= 0) || (c.plan[j] >= 0)));
    }

    private static boolean belowneighbour(Cells c, int x, int y) {
	int i = c.at(x, y);
	for(int j : new int[] {c.at(x - 1, y), c.at(x + 1, y), c.at(x, y - 1), c.at(x, y + 1)}) {
	    if(c.has(j) && (c.tile[j] != c.tile[i]) && (c.rank[j] > c.rank[i]))
		return(true);
	}
	return(false);
    }

    /**
     * Draws the grid at {@code k} pixels per tile into {@code out}, RGBA bytes of an
     * {@code (n*k) x (n*k)} image.
     *
     * @param lvl the map file's zoom level: one cell spans 2^lvl tiles
     * @param strength 1 = normal relief, 0 = none
     * @param contours height between contour lines in world units at full detail; 0 = no contours
     */
    public static void render(Cells c, int k, int lvl, double strength, double contours, byte[] out) {
	int n = c.n, ow = n * k;
	if(out.length < ow * ow * 4)
	    throw(new IllegalArgumentException("output too small"));
	double span = TILE * (1 << lvl);
	/* Hills shrink when zoomed out; exaggerate a little so they stay readable. */
	double ex = strength * EXAGGERATION * Math.sqrt(1 << lvl);
	double iv = (contours > 0) ? contourinterval(contours, lvl) : 0;
	for(int y = 0; y < n; y++) {
	    for(int x = 0; x < n; x++) {
		int i = c.at(x, y);
		int col;
		double f;
		if(c.cliff[i]) {
		    col = CLIFF;
		    f = 1;
		} else {
		    col = planrgb(c, x, y, lvl, c.rgb[i]);
		    f = shade(c, x, y, span, c.water[i] ? ex * WATERSHADE : ex);
		    if(nearcliff(c, x, y))
			f *= NEARCLIFF;
		}
		boolean lline = false, tline = false, lplan = false, tplan = false;
		double lcont = 1, tcont = 1;
		if(k == 1) {
		    if(c.edges && belowneighbour(c, x, y))
			f *= SOFTEDGE;
		    if(iv > 0)
			f *= contourhigh(c, x, y, iv);
		    if(c.plan[i] >= 0) {
			for(int j : new int[] {c.at(x - 1, y), c.at(x + 1, y), c.at(x, y - 1), c.at(x, y + 1)}) {
			    if(planedge(c, i, j)) {
				col = mix(col, PLANLINE, PLANEDGE1);
				break;
			    }
			}
		    }
		} else {
		    int l = c.at(x - 1, y), t = c.at(x, y - 1);
		    lline = c.edges && c.has(l) && (c.tile[l] != c.tile[i]);
		    tline = c.edges && c.has(t) && (c.tile[t] != c.tile[i]);
		    lplan = planedge(c, i, l);
		    tplan = planedge(c, i, t);
		    if(iv > 0) {
			lcont = contour(c, i, l, iv);
			tcont = contour(c, i, t, iv);
		    }
		}
		int r = (col >> 16) & 0xff, g = (col >> 8) & 0xff, b = col & 0xff;
		for(int py = 0; py < k; py++) {
		    int o = ((((y * k) + py) * ow) + (x * k)) * 4;
		    for(int px = 0; px < k; px++, o += 4) {
			double pf = f;
			if((lline && (px == 0)) || (tline && (py == 0)))
			    pf *= EDGE;
			if(px == 0)
			    pf *= lcont;
			if(py == 0)
			    pf *= tcont;
			if((lplan && (px == 0)) || (tplan && (py == 0))) {
			    out[o]     = (byte)((PLANLINE >> 16) & 0xff);
			    out[o + 1] = (byte)((PLANLINE >> 8) & 0xff);
			    out[o + 2] = (byte)(PLANLINE & 0xff);
			    out[o + 3] = (byte)255;
			    continue;
			}
			out[o]     = (byte)clip(r * pf);
			out[o + 1] = (byte)clip(g * pf);
			out[o + 2] = (byte)clip(b * pf);
			out[o + 3] = (byte)255;
		    }
		}
	    }
	}
    }

    private static int clip(double v) {
	return((v <= 0) ? 0 : (v >= 255) ? 255 : (int)Math.round(v));
    }
}
