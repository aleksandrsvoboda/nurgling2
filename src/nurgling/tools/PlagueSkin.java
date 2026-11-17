package nurgling.tools;

import haven.*;
import haven.render.Pipe;
import haven.render.Texture;
import nurgling.NConfig;
import nurgling.widgets.NAvaview;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The plague skin: hearthling bodies redrawn as Nurgle's children - mottled rot-green skin covered
 * in ulcers and boil clusters. Haven has one skin, baked into one texture per gender together with
 * the eyes, beard and lips, so this generates a replacement texture from the stock one and swaps it
 * into the body's material. Purely client-side: nobody else sees it.
 * <p>
 * The character-selection screen shows it when its "Nurglingify" toggle is on ({@link NAvaview#charsel},
 * {@link #nurglingify}); in game it follows the {@code plagueSkin} setting. The two are independent. Selection-screen characters each get their own pattern, seeded by
 * name; in game every hearthling gets one of a few shared variants, picked by gob id, so a crowd
 * costs a handful of textures rather than one each.
 */
public class PlagueSkin {
    private static final String MALE = "gfx/borka/male", FEMALE = "gfx/borka/female";
    /** In-game variants per gender. */
    private static final int VARIANTS = 4;
    /** Generated textures kept; selection-screen characters each take one. */
    private static final int CACHED = 48;
    private static final Map<String, Defer.Future<Material>> cache = new LinkedHashMap<String, Defer.Future<Material>>(16, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Defer.Future<Material>> e) {
            return (size() > CACHED);
        }
    };

    /**
     * The material a composite's mod layer should use: {@code mat} itself, or the plague version
     * when {@code res} is a hearthling body and the plague skin applies to {@code comp}. Throws
     * {@link Loading} while the texture is being generated.
     */
    public static Material material(Composited comp, Resource res, Material mat) {
        if (!res.name.equals(MALE) && !res.name.equals(FEMALE))
            return (mat);
        OwnerContext own = comp.eqowner;
        Avaview view = (own == null) ? null : own.fcontext(Avaview.class, false);
        long seed;
        if ((view instanceof NAvaview) && ((NAvaview) view).charsel) {
            if (!nurglingify())
                return (mat);
            seed = ((NAvaview) view).plagueSeed;
        } else if (!enabled()) {
            return (mat);
        } else if (view != null) {
            /* An in-game portrait follows its gob, so it shows the gob's variant. */
            seed = variant(view.avagob);
        } else if (own instanceof Gob) {
            seed = variant(((Gob) own).id);
        } else {
            return (mat);
        }
        Defer.Future<Material> f;
        String key = res.name + "#" + seed;
        synchronized (cache) {
            f = cache.get(key);
            if (f == null)
                cache.put(key, f = Defer.later(() -> build(res, mat, seed)));
        }
        try {
            return (f.get());
        } catch (Defer.DeferredException e) {
            /* A body texture we cannot rework: keep the stock skin rather than lose the character. */
            System.out.println("[PlagueSkin] could not build " + key + ": " + e.getCause());
            return (mat);
        }
    }

    /** Whether the in-game plague skin is on. */
    public static boolean enabled() {
        Object v = NConfig.get(NConfig.Key.plagueSkin);
        return ((v instanceof Boolean) && (Boolean) v);
    }

    /** Whether the character-selection screen is nurglingified (plague skin; the miasma is always on). */
    public static boolean nurglingify() {
        Object v = NConfig.get(NConfig.Key.nurglingify);
        return ((v instanceof Boolean) && (Boolean) v);
    }

    /** Whether the character-selection screen shows its green smoke (on unless turned off). */
    public static boolean charselSmoke() {
        Object v = NConfig.get(NConfig.Key.charselSmoke);
        return (!(v instanceof Boolean) || (Boolean) v);
    }

    /** A selection-screen character's own pattern. */
    public static long seed(String name) {
        return ((name == null) ? 0 : name.hashCode() * 0x9E3779B97F4A7C15L);
    }

    private static long variant(long gobid) {
        return (1000 + Math.floorMod(gobid, VARIANTS));
    }

    /* ------------------------------------------------------------- material */

    private static Material build(Resource res, Material mat, long seed) {
        TexR texr = res.layer(TexR.class);
        if (texr == null)
            throw (new RuntimeException("no texture in " + res.name));
        TexL orig = texr.tex();
        TexL tex = new TexL.Fixed(paint(orig.fill(), seed));
        tex.mipmap(Mipmapper.avg);
        tex.img.magfilter(Texture.Filter.LINEAR).minfilter(Texture.Filter.LINEAR).mipfilter(Texture.Filter.LINEAR);
        /* Keep every state of the stock material (colours, light, cel shading) but the texture. */
        List<Pipe.Op> states = new ArrayList<>();
        boolean swapped = flatten(mat.states, states, orig, tex);
        if (!swapped)
            throw (new RuntimeException("no texture state in " + res.name + "'s material"));
        Pipe.Op[] dyn = (mat.dynstates == Pipe.Op.nil) ? new Pipe.Op[0] : new Pipe.Op[] {mat.dynstates};
        return (new Material(states.toArray(new Pipe.Op[0]), dyn));
    }

    private static boolean flatten(Pipe.Op op, List<Pipe.Op> out, TexRender orig, TexRender tex) {
        if (op instanceof Pipe.Op.Composed) {
            boolean swapped = false;
            for (Pipe.Op p : ((Pipe.Op.Composed) op).ops)
                swapped |= flatten(p, out, orig, tex);
            return (swapped);
        }
        if ((op instanceof TexRender.TexDraw) && (((TexRender.TexDraw) op).tex == orig)) {
            out.add(tex.draw);
            return (true);
        }
        if ((op instanceof TexRender.TexClip) && (((TexRender.TexClip) op).tex == orig)) {
            out.add(tex.clip);
            return (true);
        }
        if (op != Pipe.Op.nil)
            out.add(op);
        return (false);
    }

    /* ------------------------------------------------------------- painting */

    /** Upscale: the lesions are finer than the stock texture's pixels. */
    private static final int UP = 2;
    /** Mid skin of the stock texture; shading is taken relative to its luminance. */
    private static final float[] REF = {0.98f, 0.82f, 0.66f};
    /** Rot greens the skin is mottled between. */
    private static final float[] SICK = {0.56f, 0.60f, 0.26f}, ROT = {0.36f, 0.43f, 0.22f};
    private static final float[] BLOOD = {0.55f, 0.03f, 0.04f}, PUS = {0.82f, 0.76f, 0.30f};
    private static final int LESIONS = 42, FACE_LESIONS = 5, SCARS = 7, BLISTERS = 7, SUTURES = 3, CUTS = 5;
    /** The face's area on the body texture (fractions); both genders share the layout. */
    private static final float FX0 = 0.32f, FX1 = 0.68f, FY0 = 0.19f, FY1 = 0.42f;

    /** Paints the plague onto a stock body texture; the result is {@link #UP} times its size. */
    static BufferedImage paint(BufferedImage in, long seed) {
        return (new Painter(in, seed).run());
    }

    private static class Painter {
        final BufferedImage in;
        final Random rnd;
        final int sw, sh, w, h;
        /** Per source pixel: how much it is skin (recolour weight), and whether a lesion may cover it. */
        final float[][] skin, place;
        final float[] r, g, b;

        Painter(BufferedImage in, long seed) {
            this.in = in;
            this.rnd = new Random(seed);
            sw = in.getWidth();
            sh = in.getHeight();
            w = sw * UP;
            h = sh * UP;
            skin = new float[sh][sw];
            place = new float[sh][sw];
            r = new float[w * h];
            g = new float[w * h];
            b = new float[w * h];
        }

        BufferedImage run() {
            classify();
            recolour();
            /* Old wounds first, fresh ones on top: scars, then sores and boils, pus, stitched
             * wounds and last the cuts, whose blood runs over whatever lies below them. */
            for (int n = 0, tries = 0; (n < SCARS) && (tries < 2000); tries++)
                if (scar())
                    n++;
            int placed = 0;
            for (int tries = 0; (placed < FACE_LESIONS) && (tries < 4000); tries++) {
                float cx = (FX0 + rnd.nextFloat() * (FX1 - FX0)) * sw, cy = (FY0 + rnd.nextFloat() * (FY1 - FY0)) * sh;
                float rad = 2f + rnd.nextFloat() * 3f;
                if (fits(cx, cy, rad * 1.3f)) {
                    ulcer(cx, cy, rad);
                    placed++;
                }
            }
            placed = 0;
            for (int tries = 0; (placed < LESIONS) && (tries < 20000); tries++) {
                float cx = rnd.nextFloat() * sw, cy = rnd.nextFloat() * sh;
                float rad = 3f + rnd.nextFloat() * rnd.nextFloat() * 14f;
                if (!fits(cx, cy, rad * 1.3f))
                    continue;
                if (rnd.nextFloat() < 0.3f)
                    cluster(cx, cy, rad * 1.2f);
                else
                    ulcer(cx, cy, rad);
                placed++;
            }
            for (int n = 0, tries = 0; (n < BLISTERS) && (tries < 4000); tries++) {
                float cx = rnd.nextFloat() * sw, cy = rnd.nextFloat() * sh;
                float rad = 2f + rnd.nextFloat() * 3.5f;
                if (fits(cx, cy, rad * 1.4f)) {
                    blister(cx, cy, rad);
                    n++;
                }
            }
            for (int n = 0, tries = 0; (n < SUTURES) && (tries < 2000); tries++)
                if (suture())
                    n++;
            for (int n = 0, tries = 0; (n < CUTS) && (tries < 2000); tries++)
                if (cut())
                    n++;
            BufferedImage out = TexI.mkbuf(Coord.of(w, h));
            for (int i = 0; i < w * h; i++)
                out.setRGB(i % w, i / w, 0xff000000 | rgb(r[i], g[i], b[i]));
            return (out);
        }

        /* Skin is the texture's orange hue band; eyes, irises, lips and dark beard fall outside it.
         * Lesions additionally avoid the saturated, flat UV background and the darkest shadows. */
        void classify() {
            float[] hsv = new float[3];
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    int p = in.getRGB(x, y);
                    java.awt.Color.RGBtoHSB((p >> 16) & 255, (p >> 8) & 255, p & 255, hsv);
                    float k = (1f - smooth(Math.abs(hsv[0] * 360f - 27f), 14f, 24f)) * smooth(hsv[1], 0.08f, 0.20f);
                    skin[y][x] = k;
                    place[y][x] = k * (1f - smooth(hsv[1], 0.48f, 0.55f)) * smooth(hsv[2], 0.75f, 0.85f);
                }
            }
        }

        /* The rot-green skin. Besides the colour it gets a surface: a height map of gentle lumps,
         * a few sagging folds, fine wrinkles and pores, baked into the shading as if lit from the
         * upper left. It is kept subtle so the stock texture's anatomy still reads through. Faint
         * veins and dark necrotic blotches go on top. Everything is weighted by how much a pixel
         * is skin. Noise fields are rotated and warped so no grid shows. */
        void recolour() {
            Field mottle = new Field(9), grain = new Field(28), lumps = new Field(5), folds = new Field(9),
                    wrinkles = new Field(10, 38, 0.35f), pores = new Field(120), veins = new Field(8), veinmask = new Field(3),
                    necro = new Field(6), warp = new Field(4);
            float[] ht = new float[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float X = x / (float) w, Y = 2f * y / h;
                    float wx = X + 0.06f * (warp.at(X, Y) - 0.5f), wy = Y + 0.06f * (warp.at(Y, X) - 0.5f);
                    ht[y * w + x] = 0.6f * lumps.at(wx, wy)
                            - 0.25f * pow(ridge(folds.at(wx, wy)), 6)
                            - 0.10f * pow(ridge(wrinkles.at(wx, wy)), 12)
                            + 0.05f * pores.at(X, Y);
                }
            }
            float lref = lum(REF[0], REF[1], REF[2]);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float sx = (x + 0.5f) / UP - 0.5f, sy = (y + 0.5f) / UP - 0.5f;
                    float X = x / (float) w, Y = 2f * y / h;
                    int p = bilinear(sx, sy);
                    float cr = ((p >> 16) & 255) / 255f, cg = ((p >> 8) & 255) / 255f, cb = (p & 255) / 255f;
                    float sk = sample(skin, sx, sy);
                    float m = mottle.at(X, Y) * 0.7f + grain.at(X, Y) * 0.3f;
                    float shade = (float) Math.pow(lum(cr, cg, cb) / lref, 1.8);
                    /* Emboss: the height's slope towards the light brightens, away from it darkens. */
                    int x0 = Math.max(x - 1, 0), x1 = Math.min(x + 1, w - 1), y0 = Math.max(y - 1, 0), y1 = Math.min(y + 1, h - 1);
                    float slope = (ht[y0 * w + x0] - ht[y1 * w + x1]);
                    float relief = 1f + clampf(slope * 4f, -0.15f, 0.15f);
                    float tr = lerp(SICK[0], ROT[0], m), tg = lerp(SICK[1], ROT[1], m), tb = lerp(SICK[2], ROT[2], m);
                    /* necrotic blotches: dark purplish-brown dead patches */
                    float nb = smooth(necro.at(X, Y), 0.66f, 0.82f) * 0.5f;
                    tr = lerp(tr, 0.34f, nb); tg = lerp(tg, 0.27f, nb); tb = lerp(tb, 0.24f, nb);
                    /* veins: thin dark purple lines, only in some patches */
                    float vn = pow(ridge(veins.at(X, Y)), 16) * smooth(veinmask.at(X, Y), 0.5f, 0.7f);
                    tr = lerp(tr, 0.26f, vn * 0.6f); tg = lerp(tg, 0.20f, vn * 0.6f); tb = lerp(tb, 0.28f, vn * 0.6f);
                    float lit = shade * relief;
                    int i = y * w + x;
                    r[i] = cr + (Math.min(1f, tr * lit) - cr) * sk;
                    g[i] = cg + (Math.min(1f, tg * lit) - cg) * sk;
                    b[i] = cb + (Math.min(1f, tb * lit) - cb) * sk;
                }
            }
        }

        /* Smooth value noise over the texture in square units (x 0..1, y 0..2), at {@code freq}
         * cells per unit, turned by a random angle so its grid lines never line up with others. */
        class Field {
            final float[][] g;
            final float fx, fy, cos, sin;
            final int half;

            Field(float freq) {
                this(freq, freq, rnd.nextFloat() * 6.28f);
            }

            /** Stretched: {@code fx} and {@code fy} cells per unit across and down, turned by {@code a} radians. */
            Field(float fx, float fy, float a) {
                this.fx = fx;
                this.fy = fy;
                float freq = Math.max(fx, fy);
                cos = (float) Math.cos(a);
                sin = (float) Math.sin(a);
                half = (int) (freq * 1.2f) + 3;
                g = new float[half * 2][half * 2];
                for (float[] row : g)
                    for (int i = 0; i < row.length; i++)
                        row[i] = rnd.nextFloat();
            }

            float at(float x, float y) {
                float dx = x - 0.5f, dy = y - 1f;
                return (sample(g, (dx * cos - dy * sin) * fx + half, (dx * sin + dy * cos) * fy + half));
            }
        }

        /** Whether a lesion of radius {@code rad} (source pixels) lies wholly on skin. */
        boolean fits(float cx, float cy, float rad) {
            for (float dy = -rad; dy <= rad; dy++) {
                for (float dx = -rad; dx <= rad; dx++) {
                    if (dx * dx + dy * dy > rad * rad)
                        continue;
                    int x = (int) (cx + dx), y = (int) (cy + dy);
                    if ((x < 0) || (y < 0) || (x >= sw) || (y >= sh) || (place[y][x] < 0.6f))
                        return (false);
                }
            }
            return (true);
        }

        /* An open sore: inflamed halo, raw red flesh with a torn pale rim, a dark wet crater and
         * sometimes a yellow pus core. The outline is wobbled by a few harmonics. */
        void ulcer(float cx, float cy, float rad) {
            float ax = 0.7f + rnd.nextFloat() * 0.6f, ay = 0.7f + rnd.nextFloat() * 0.6f;
            float[] ph = new float[6], am = new float[6];
            for (int k = 0; k < 6; k++) {
                ph[k] = rnd.nextFloat() * 6.28f;
                am[k] = (0.22f / (k + 1)) * (0.5f + rnd.nextFloat());
            }
            boolean pus = rnd.nextFloat() < 0.55f;
            float pcx = cx * UP, pcy = cy * UP, pr = rad * UP;
            int R = (int) (pr * 2.6f) + 2;
            for (int y = (int) pcy - R; y <= pcy + R; y++) {
                for (int x = (int) pcx - R; x <= pcx + R; x++) {
                    if ((x < 0) || (y < 0) || (x >= w) || (y >= h))
                        continue;
                    float dx = (x - pcx) / ax, dy = (y - pcy) / ay;
                    float ang = (float) Math.atan2(dy, dx), edge = 1f;
                    for (int k = 0; k < 6; k++)
                        edge += am[k] * (float) Math.sin(ang * (k + 2) + ph[k]);
                    float d = (float) Math.sqrt(dx * dx + dy * dy) / (pr * edge);
                    int i = y * w + x;
                    float speck = hash(x, y);
                    if (d < 2.4f)
                        tint(i, 0.55f, 0.28f, 0.22f, (1f - smooth(d, 0.9f, 2.4f)) * 0.6f);
                    if (d < 1.1f)
                        paintc(i, 0.62f + 0.15f * speck, 0.12f, 0.10f, 1f - smooth(d, 0.92f, 1.1f));
                    if ((d < 1.0f) && (d > 0.8f))
                        paintc(i, 0.85f, 0.45f, 0.38f, 0.5f * (1f - Math.abs(d - 0.9f) / 0.1f));
                    if (d < 0.7f)
                        paintc(i, 0.30f, 0.02f, 0.03f, (1f - smooth(d, 0.25f, 0.7f)) * (0.6f + 0.4f * speck));
                    if (pus && (d < 0.35f))
                        paintc(i, 0.80f, 0.70f, 0.25f, (1f - smooth(d, 0.1f, 0.35f)) * 0.9f);
                    if ((speck > 0.93f) && (d < 1.0f))
                        paintc(i, 0.95f, 0.75f, 0.70f, 0.6f);
                }
            }
            /* Some sores weep: pus from the yellow ones, blood from the others. */
            float roll = rnd.nextFloat();
            if (pus && (roll < 0.5f))
                drip(pcx + (rnd.nextFloat() - 0.5f) * pr * 0.6f, pcy + pr * 0.85f * ay, 3f + rnd.nextFloat() * 7f, 1.3f, PUS);
            else if (roll < 0.25f)
                drip(pcx + (rnd.nextFloat() - 0.5f) * pr * 0.6f, pcy + pr * 0.85f * ay, 3f + rnd.nextFloat() * 8f, 0.8f, BLOOD);
        }

        /* Packed yellow boils with dark rims on an inflamed patch. */
        void cluster(float cx, float cy, float rad) {
            disc(cx, cy, rad * 1.7f, 0.60f, 0.25f, 0.20f, 0.7f);
            int n = 4 + rnd.nextInt(6);
            for (int k = 0; k < n; k++) {
                float a = rnd.nextFloat() * 6.28f, dist = rnd.nextFloat() * rad;
                float pcx = (cx + (float) Math.cos(a) * dist) * UP, pcy = (cy + (float) Math.sin(a) * dist) * UP;
                float pr = (1.6f + rnd.nextFloat() * 2.4f) * UP;
                int R = (int) (pr * 1.4f) + 1;
                for (int y = (int) pcy - R; y <= pcy + R; y++) {
                    for (int x = (int) pcx - R; x <= pcx + R; x++) {
                        if ((x < 0) || (y < 0) || (x >= w) || (y >= h))
                            continue;
                        float dd = (float) Math.hypot(x - pcx, y - pcy) / pr;
                        int i = y * w + x;
                        if (dd < 1.35f)
                            paintc(i, 0.35f, 0.06f, 0.05f, 1f - smooth(dd, 1.0f, 1.35f));
                        if (dd < 1.0f) {
                            float sh = 1f - dd * 0.5f;
                            paintc(i, 0.86f * sh, 0.66f * sh, 0.22f * sh, 1f - smooth(dd, 0.75f, 1.0f));
                        }
                        float hd = (float) Math.hypot(x - (pcx - pr * 0.3f), y - (pcy - pr * 0.3f)) / (pr * 0.3f);
                        if (hd < 1f)
                            paintc(i, 1f, 1f, 0.9f, (1f - hd) * 0.7f);
                    }
                }
            }
        }

        /* An old healed scar: a pale, slightly raised pink line with a darker edge. */
        boolean scar() {
            float[][] pts = path(16f + rnd.nextFloat() * 26f, 0.12f, 2f);
            if (pts == null)
                return (false);
            float wd = (0.8f + rnd.nextFloat() * 0.8f) * UP;
            stroke(pts, wd * 2.2f, (i, d, t) -> {
                float taper = smooth(t, 0f, 0.2f) * smooth(1f - t, 0f, 0.2f);
                float ww = wd * (0.4f + 0.6f * taper);
                if (d < ww * 2.2f)
                    tint(i, 0.70f, 0.52f, 0.50f, (1f - smooth(d, ww, ww * 2.2f)) * 0.35f * taper);
                if (d < ww)
                    paintc(i, 0.68f + 0.06f * hash(i, 7), 0.58f, 0.50f, (1f - smooth(d, ww * 0.5f, ww)) * 0.75f * taper);
            });
            return (true);
        }

        /* A stitched wound: a crusted red line, inflamed around it, closed with dark cross-stitches. */
        boolean suture() {
            float[][] pts = path(14f + rnd.nextFloat() * 14f, 0.2f, 3f);
            if (pts == null)
                return (false);
            stroke(pts, 2.6f * UP, (i, d, t) -> {
                float taper = smooth(t, 0f, 0.1f) * smooth(1f - t, 0f, 0.1f);
                if (d < 2.6f * UP)
                    tint(i, 0.85f, 0.42f, 0.36f, (1f - smooth(d, 0.6f * UP, 2.6f * UP)) * 0.55f);
                if (d < 0.7f * UP * taper)
                    paintc(i, 0.38f, 0.06f, 0.05f, 1f - smooth(d, 0.3f * UP * taper, 0.7f * UP * taper));
            });
            int step = (int) (3.2f / 0.75f);
            for (int k = step / 2; k < pts.length - 1; k += step) {
                float tx = pts[k + 1][0] - pts[k - (k > 0 ? 1 : 0)][0], ty = pts[k + 1][1] - pts[k - (k > 0 ? 1 : 0)][1];
                float tl = (float) Math.hypot(tx, ty);
                if (tl == 0)
                    continue;
                float tilt = (rnd.nextFloat() - 0.5f) * 0.5f;
                float nx = -ty / tl + tx / tl * tilt, ny = tx / tl + ty / tl * tilt;
                float hl = (1.8f + rnd.nextFloat() * 0.6f) * UP;
                float ax = pts[k][0] - nx * hl, ay = pts[k][1] - ny * hl, bx = pts[k][0] + nx * hl, by = pts[k][1] + ny * hl;
                dot(ax, ay, 0.7f * UP, 0.45f, 0.05f, 0.05f);
                dot(bx, by, 0.7f * UP, 0.45f, 0.05f, 0.05f);
                stroke(new float[][] {{ax, ay}, {bx, by}}, 0.6f * UP, (i, d, t) -> {
                    if (d < 0.45f * UP)
                        paintc(i, 0.09f, 0.07f, 0.05f, 1f - smooth(d, 0.2f * UP, 0.45f * UP));
                });
            }
            return (true);
        }

        /* A fresh cut: a dark gash in wet red flesh, with blood running down from it. */
        boolean cut() {
            float[][] pts = path(8f + rnd.nextFloat() * 14f, 0.12f, 2f);
            if (pts == null)
                return (false);
            stroke(pts, 2.2f * UP, (i, d, t) -> {
                float taper = smooth(t, 0f, 0.3f) * smooth(1f - t, 0f, 0.3f);
                if (d < 2.2f * UP * taper)
                    tint(i, 0.80f, 0.35f, 0.32f, (1f - smooth(d, 0.8f * UP, 2.2f * UP)) * 0.5f * taper);
                if (d < 1.2f * UP * taper)
                    paintc(i, BLOOD[0], BLOOD[1], BLOOD[2], 1f - smooth(d, 0.7f * UP * taper, 1.2f * UP * taper));
                if (d < 0.45f * UP * taper)
                    paintc(i, 0.16f, 0.0f, 0.02f, 1f - smooth(d, 0.2f * UP * taper, 0.45f * UP * taper));
            });
            int drips = 1 + rnd.nextInt(4);
            for (int k = 0; k < drips; k++) {
                float[] at = pts[(int) (pts.length * (0.2f + 0.6f * rnd.nextFloat()))];
                drip(at[0], at[1], 5f + rnd.nextFloat() * 14f, 0.7f + rnd.nextFloat() * 0.5f, BLOOD);
            }
            return (true);
        }

        /* A swollen pus blister: glossy yellow-green, dark-rimmed, some burst and running. */
        void blister(float cx, float cy, float rad) {
            float pcx = cx * UP, pcy = cy * UP, pr = rad * UP;
            float[] ph = new float[4];
            for (int k = 0; k < 4; k++)
                ph[k] = rnd.nextFloat() * 6.28f;
            int R = (int) (pr * 1.7f) + 2;
            for (int y = (int) pcy - R; y <= pcy + R; y++) {
                for (int x = (int) pcx - R; x <= pcx + R; x++) {
                    if ((x < 0) || (y < 0) || (x >= w) || (y >= h))
                        continue;
                    float ang = (float) Math.atan2(y - pcy, x - pcx), edge = 1f;
                    for (int k = 0; k < 4; k++)
                        edge += (0.12f / (k + 1)) * (float) Math.sin(ang * (k + 2) + ph[k]);
                    float d = (float) Math.hypot(x - pcx, y - pcy) / (pr * edge);
                    int i = y * w + x;
                    if (d < 1.7f)
                        tint(i, 0.75f, 0.40f, 0.30f, (1f - smooth(d, 0.9f, 1.7f)) * 0.5f);
                    if (d < 1.12f)
                        paintc(i, 0.42f, 0.26f, 0.08f, 1f - smooth(d, 1.0f, 1.12f));
                    if (d < 1.0f) {
                        float sh = 1f - 0.4f * d;
                        paintc(i, PUS[0] * sh, PUS[1] * sh, PUS[2] * sh, 1f - smooth(d, 0.85f, 1.0f));
                    }
                    float hd = (float) Math.hypot(x - (pcx - pr * 0.35f), y - (pcy - pr * 0.35f)) / (pr * 0.25f);
                    if (hd < 1f)
                        paintc(i, 1f, 1f, 0.85f, (1f - hd) * 0.8f);
                }
            }
            if (rnd.nextFloat() < 0.5f)
                drip(pcx, pcy + pr * 0.95f, 3f + rnd.nextFloat() * 8f, 1.2f, PUS);
        }

        /* A runnel down the skin (texture down is body down on both sheets), stopping where the
         * skin ends; it thins as it goes and ends in a drop. Position in upscaled pixels. */
        void drip(float x, float y, float len, float width, float[] col) {
            List<float[]> pts = new ArrayList<>();
            float wob = rnd.nextFloat() * 6.28f;
            for (float l = 0; l <= len * UP; l += UP * 0.5f) {
                float px = x + (float) Math.sin(wob + l * 0.15f) * 0.6f * UP, py = y + l;
                int sx = (int) (px / UP), sy = (int) (py / UP);
                if ((sx < 0) || (sy < 0) || (sx >= sw) || (sy >= sh) || (skin[sy][sx] < 0.5f))
                    break;
                pts.add(new float[] {px, py});
            }
            if (pts.size() < 3)
                return;
            float[][] path = pts.toArray(new float[0][]);
            float wd = width * UP;
            stroke(path, wd * 1.6f, (i, d, t) -> {
                /* blood runs in an even line; pus smears wide at the source and thins out */
                float ww = (col == PUS) ? wd * (1.3f - 0.9f * t) : wd * (1f - 0.4f * t);
                if (d < ww)
                    paintc(i, col[0], col[1], col[2], (1f - smooth(d, ww * 0.4f, ww)) * ((col == PUS) ? 0.6f * (1f - 0.5f * t) : 0.92f));
            });
            if (col == BLOOD) {
                float[] end = path[path.length - 1];
                dot(end[0], end[1] + wd * 0.3f, wd * 1.1f, col[0], col[1], col[2]);
                dot(end[0] - wd * 0.35f, end[1], wd * 0.35f, 0.85f, 0.45f, 0.45f);
            }
        }

        /** A random wandering line lying wholly on skin, in upscaled pixels, or null if it leaves it. */
        float[][] path(float len, float curl, float margin) {
            float x = rnd.nextFloat() * sw, y = rnd.nextFloat() * sh, a = rnd.nextFloat() * 6.28f, da = 0;
            int n = (int) (len / 0.75f) + 1;
            float[][] pts = new float[n][];
            for (int k = 0; k < n; k++) {
                if (!fits(x, y, margin))
                    return (null);
                pts[k] = new float[] {x * UP, y * UP};
                da = (da + (rnd.nextFloat() - 0.5f) * curl) * 0.85f;
                a += da;
                x += (float) Math.cos(a) * 0.75f;
                y += (float) Math.sin(a) * 0.75f;
            }
            return (pts);
        }

        interface Brush {
            void at(int i, float d, float t);
        }

        /** Calls {@code brush} for every pixel within {@code reach} of the polyline, with its distance and position along it. */
        void stroke(float[][] pts, float reach, Brush brush) {
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (float[] p : pts) {
                x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]);
                x1 = Math.max(x1, p[0]); y1 = Math.max(y1, p[1]);
            }
            float[] lens = new float[pts.length];
            for (int k = 1; k < pts.length; k++)
                lens[k] = lens[k - 1] + (float) Math.hypot(pts[k][0] - pts[k - 1][0], pts[k][1] - pts[k - 1][1]);
            float total = Math.max(lens[pts.length - 1], 1e-3f);
            for (int y = Math.max(0, (int) (y0 - reach)); y <= Math.min(h - 1, (int) (y1 + reach)); y++) {
                for (int x = Math.max(0, (int) (x0 - reach)); x <= Math.min(w - 1, (int) (x1 + reach)); x++) {
                    float best = Float.MAX_VALUE, bt = 0;
                    for (int k = 1; k < pts.length; k++) {
                        float ax = pts[k - 1][0], ay = pts[k - 1][1], dx = pts[k][0] - ax, dy = pts[k][1] - ay;
                        float ll = dx * dx + dy * dy;
                        float f = (ll == 0) ? 0 : clampf(((x - ax) * dx + (y - ay) * dy) / ll, 0f, 1f);
                        float ex = x - (ax + dx * f), ey = y - (ay + dy * f);
                        float dd = ex * ex + ey * ey;
                        if (dd < best) {
                            best = dd;
                            bt = (lens[k - 1] + f * (lens[k] - lens[k - 1])) / total;
                        }
                    }
                    float d = (float) Math.sqrt(best);
                    if (d < reach)
                        brush.at(y * w + x, d, bt);
                }
            }
        }

        /** A soft-edged round spot, in upscaled pixels. */
        void dot(float cx, float cy, float rad, float cr, float cg, float cb) {
            int R = (int) rad + 2;
            for (int y = (int) cy - R; y <= cy + R; y++) {
                for (int x = (int) cx - R; x <= cx + R; x++) {
                    if ((x < 0) || (y < 0) || (x >= w) || (y >= h))
                        continue;
                    float d = (float) Math.hypot(x - cx, y - cy) / rad;
                    if (d < 1f)
                        paintc(y * w + x, cr, cg, cb, 1f - smooth(d, 0.6f, 1f));
                }
            }
        }

        void disc(float cx, float cy, float rad, float cr, float cg, float cb, float a) {
            float pcx = cx * UP, pcy = cy * UP, pr = rad * UP;
            int R = (int) pr + 1;
            for (int y = (int) pcy - R; y <= pcy + R; y++) {
                for (int x = (int) pcx - R; x <= pcx + R; x++) {
                    if ((x < 0) || (y < 0) || (x >= w) || (y >= h))
                        continue;
                    float d = (float) Math.hypot(x - pcx, y - pcy) / pr;
                    if (d < 1f)
                        tint(y * w + x, cr, cg, cb, (1f - smooth(d, 0.4f, 1f)) * a);
                }
            }
        }

        /** Paints over, towards a flat colour. */
        void paintc(int i, float cr, float cg, float cb, float t) {
            if (t <= 0)
                return;
            r[i] = lerp(r[i], cr, t);
            g[i] = lerp(g[i], cg, t);
            b[i] = lerp(b[i], cb, t);
        }

        /** Tints multiplicatively, keeping the shading under it. */
        void tint(int i, float cr, float cg, float cb, float t) {
            if (t <= 0)
                return;
            r[i] = lerp(r[i], Math.min(1f, r[i] * cr * 1.6f), t);
            g[i] = lerp(g[i], Math.min(1f, g[i] * cg * 1.6f), t);
            b[i] = lerp(b[i], Math.min(1f, b[i] * cb * 1.6f), t);
        }

        float[][] noise(int n) {
            float[][] f = new float[n * 2 + 2][n + 2];
            for (float[] row : f)
                for (int i = 0; i < row.length; i++)
                    row[i] = rnd.nextFloat();
            return (f);
        }

        int bilinear(float x, float y) {
            x = Math.max(0, Math.min(sw - 1.001f, x));
            y = Math.max(0, Math.min(sh - 1.001f, y));
            int ix = (int) x, iy = (int) y;
            float fx = x - ix, fy = y - iy;
            int c00 = in.getRGB(ix, iy), c10 = in.getRGB(ix + 1, iy), c01 = in.getRGB(ix, iy + 1), c11 = in.getRGB(ix + 1, iy + 1);
            int out = 0;
            for (int s = 0; s < 24; s += 8) {
                float v = lerp(lerp((c00 >> s) & 255, (c10 >> s) & 255, fx), lerp((c01 >> s) & 255, (c11 >> s) & 255, fx), fy);
                out |= ((int) v) << s;
            }
            return (out);
        }
    }

    static float sample(float[][] f, float x, float y) {
        x = Math.max(0, Math.min(f[0].length - 1.001f, x));
        y = Math.max(0, Math.min(f.length - 1.001f, y));
        int ix = (int) x, iy = (int) y;
        float fx = x - ix, fy = y - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        return (lerp(lerp(f[iy][ix], f[iy][ix + 1], fx), lerp(f[iy + 1][ix], f[iy + 1][ix + 1], fx), fy));
    }

    static float hash(int x, int y) {
        int v = x * 374761393 + y * 668265263;
        v = (v ^ (v >>> 13)) * 1274126177;
        return (((v ^ (v >>> 16)) & 0xffff) / 65535f);
    }

    static float lum(float r, float g, float b) {
        return (0.3f * r + 0.59f * g + 0.11f * b);
    }

    /** Ridged noise: peaks along the middle of a noise field, a net of fold lines. */
    static float ridge(float n) {
        return (1f - Math.abs(2f * n - 1f));
    }

    static float pow(float v, int e) {
        float r = 1f;
        for (int k = 0; k < e; k++)
            r *= v;
        return (r);
    }

    static float clampf(float v, float lo, float hi) {
        return (Math.max(lo, Math.min(hi, v)));
    }

    static float lerp(float a, float b, float t) {
        return (a + (b - a) * t);
    }

    static float smooth(float v, float lo, float hi) {
        float t = Math.max(0f, Math.min(1f, (v - lo) / (hi - lo)));
        return (t * t * (3 - 2 * t));
    }

    static int rgb(float r, float g, float b) {
        return (((int) (clamp(r) * 255) << 16) | ((int) (clamp(g) * 255) << 8) | (int) (clamp(b) * 255));
    }

    static float clamp(float v) {
        return (Math.max(0f, Math.min(1f, v)));
    }
}
