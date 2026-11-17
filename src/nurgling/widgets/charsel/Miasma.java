package nurgling.widgets.charsel;

import haven.*;
import haven.render.Texture;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Plague miasma: green haze drifting around the feet of the character on the selection screen,
 * with a few wisps curling up past the body. Shown unless the screen's "Smoke" switch is off
 * ({@link nurgling.tools.PlagueSkin#charselSmoke}), fading in and out with it. Drawn in 2D on the
 * screen rather than in the avatar's 3D view, so it is not boxed in by the view's rectangle.
 * <p>
 * One simulation feeds two layers: {@link #back} is drawn behind the avatar and {@link #front} in
 * front of it, so the haze surrounds the character. Positions are in avatar units: x across from
 * the feet (one unit = the character's height), y up from the feet.
 */
public class Miasma {
    /** Puff textures; several, so the haze does not repeat. */
    private static final int TEXTURES = 6, TEXSZ = 256;
    /** Puffs alive at once, roughly. */
    private static final int PUFFS = 46;
    /** Share of puffs drawn in front of the character. */
    private static final float FRONT = 0.3f;
    /** Peak opacity of one puff behind and in front of the character. */
    private static final float BACK_ALPHA = 0.44f, FRONT_ALPHA = 0.29f;

    private final Sim sim = new Sim(new Random());
    /** Per puff texture, the same image at 256, 128, 64 and 32 px; see {@link #levels}. */
    private Defer.Future<Tex[][]> texs = null;
    public final Layer back = new Layer(false), front = new Layer(true);
    /** Where the character's feet are, and how tall the character is, in screen pixels. */
    private Coord feet = Coord.z;
    private int height = 1;
    /** 0..1: how far the haze has faded in; starts in if the smoke is on, so it does not fade in on open. */
    private float fade = nurgling.tools.PlagueSkin.charselSmoke() ? 1f : 0f;
    /** Seconds to fade fully in or out when the switch is flipped. */
    private static final float FADETIME = 0.8f;

    /** Places the haze: the character's feet and its height on screen. */
    public void anchor(Coord feet, int height) {
        this.feet = feet;
        this.height = Math.max(height, 1);
        int w = (int) (height * 1.9f), h = (int) (height * 1.25f);
        Coord sz = Coord.of(w, h), ul = feet.sub(w / 2, (int) (height * 1.05f));
        back.move(ul);
        back.resize(sz);
        front.move(ul);
        front.resize(sz);
    }

    public class Layer extends Widget {
        private final boolean isfront;

        Layer(boolean isfront) {
            super(Coord.z);
            this.isfront = isfront;
        }

        public void tick(double dt) {
            super.tick(dt);
            /* One layer steps the shared simulation. */
            if (!isfront) {
                float step = (float) Math.min(dt, 0.1);
                boolean on = nurgling.tools.PlagueSkin.charselSmoke();
                fade = Math.max(0f, Math.min(1f, fade + (on ? step : -step) / FADETIME));
                if (fade > 0)
                    sim.step(step);
            }
        }

        public void draw(GOut g) {
            if (fade <= 0)
                return;
            if (texs == null)
                texs = Defer.later(() -> {
                    Tex[][] ret = new Tex[TEXTURES][];
                    for (int i = 0; i < TEXTURES; i++)
                        ret[i] = levels(puffImage(TEXSZ, 0x5eed0000L + i));
                    return (ret);
                });
            Tex[][] t;
            try {
                t = texs.get();
            } catch (Loading l) {
                return;
            }
            /* Positions and sizes stay fractional all the way to the screen: the puffs move a few
             * pixels a second, and rounding to whole pixels makes them hop. */
            float ox = feet.x - this.c.x + g.tx.x, oy = feet.y - this.c.y + g.tx.y;
            for (Sim.Puff p : sim.puffs) {
                if (p.front != isfront)
                    continue;
                float a = p.alpha() * (isfront ? FRONT_ALPHA : BACK_ALPHA) * fade;
                if (a <= 0.003f)
                    continue;
                float pw = p.w() * height, ph = p.h() * height;
                Tex tex = level(t[p.tex % TEXTURES], Math.max(pw, ph));
                float x0 = ox + p.x * height - pw / 2, y0 = oy - p.y * height - ph / 2, x1 = x0 + pw, y1 = y0 + ph;
                Coord ts = tex.sz();
                g.chcolor(255, 255, 255, Math.min(255, Math.round(a * 255)));
                tex.render(g, new float[] {x0, y0, x1, y0, x1, y1, x0, y1}, new float[] {0, 0, ts.x, 0, ts.x, ts.y, 0, ts.y});
            }
            g.chcolor();
        }
    }

    /* The puffs are drawn at many sizes and scale a little every frame. Plain sampling of one big
     * image shimmers when shrunk, so each puff comes pre-shrunk to several sizes (mipmaps done by
     * hand), all linearly filtered, and the draw picks the closest. These are ordinary 2D UI
     * textures: a TexL (3D, mipmapped) uploads from a loader job and throws "not done" inside
     * UI drawing, which kills the UI thread. */
    private static Tex[] levels(BufferedImage img) {
        List<Tex> ret = new ArrayList<>();
        while (true) {
            ret.add(new TexI(img).filter(Texture.Filter.LINEAR));
            if (img.getWidth() <= 32)
                break;
            img = half(img);
        }
        return (ret.toArray(new Tex[0]));
    }

    /** The smallest level still at least {@code size} across, so it is only ever shrunk a little. */
    private static Tex level(Tex[] lv, float size) {
        for (int i = lv.length - 1; i > 0; i--)
            if (lv[i].sz().x >= size)
                return (lv[i]);
        return (lv[0]);
    }

    /** Halves an image by averaging 2x2 blocks, weighting colour by alpha so edges do not darken. */
    private static BufferedImage half(BufferedImage in) {
        int w = in.getWidth() / 2, h = in.getHeight() / 2;
        BufferedImage out = TexI.mkbuf(Coord.of(w, h));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float r = 0, g = 0, b = 0, a = 0;
                for (int k = 0; k < 4; k++) {
                    int p = in.getRGB(x * 2 + (k & 1), y * 2 + (k >> 1));
                    float pa = ((p >>> 24) & 255) / 255f;
                    r += ((p >> 16) & 255) * pa;
                    g += ((p >> 8) & 255) * pa;
                    b += (p & 255) * pa;
                    a += pa;
                }
                int ca = Math.round(a / 4 * 255);
                if (a > 0) {
                    r /= a;
                    g /= a;
                    b /= a;
                }
                out.setRGB(x, y, (ca << 24) | (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(b));
            }
        }
        return (out);
    }

    /* ------------------------------------------------------------- simulation */

    /** The drifting puffs, in avatar units; no UI types, so it can be run offline. */
    public static class Sim {
        final Random rnd;
        public final List<Puff> puffs = new ArrayList<>();
        private float spawn = 0, time = 0;

        public Sim(Random rnd) {
            this.rnd = rnd;
            /* Start already settled, not puffing out of nothing. */
            for (int i = 0; i < 400; i++)
                step(0.05f);
        }

        public class Puff {
            public float x, y, vx, vy, age, life, size, stretch, phase;
            public int tex;
            public boolean front, wisp;

            public float w() {
                return (size * grow() / stretch);
            }

            public float h() {
                return (size * grow() * stretch);
            }

            float grow() {
                return (0.75f + 0.7f * (age / life));
            }

            /** Fades in, lingers and fades out; thins as it rises. */
            public float alpha() {
                float t = age / life;
                float a = smooth(t, 0f, 0.25f) * (1f - smooth(t, 0.55f, 1f));
                return (wisp ? a * (1f - smooth(y, 0.55f, 0.95f)) : a * (1f - smooth(y, 0.3f, 0.7f)));
            }
        }

        public void step(float dt) {
            time += dt;
            /* about PUFFS alive: spawn at PUFFS / mean life per second */
            spawn += dt * PUFFS / 11f;
            while (spawn >= 1f) {
                spawn -= 1f;
                puffs.add(spawn());
            }
            for (Iterator<Puff> i = puffs.iterator(); i.hasNext(); ) {
                Puff p = i.next();
                p.age += dt;
                if (p.age >= p.life) {
                    i.remove();
                    continue;
                }
                /* a slow sideways sway, so the haze curls rather than slides */
                p.x += (p.vx + 0.03f * (float) Math.sin(time * 0.35f + p.phase)) * dt;
                p.y += p.vy * dt;
            }
        }

        Puff spawn() {
            Puff p = new Puff();
            p.wisp = rnd.nextFloat() < 0.22f;
            p.front = !p.wisp && (rnd.nextFloat() < FRONT);
            p.x = (float) rnd.nextGaussian() * (p.wisp ? 0.14f : 0.32f);
            p.y = p.wisp ? rnd.nextFloat() * 0.1f : rnd.nextFloat() * rnd.nextFloat() * 0.3f - 0.02f;
            p.vx = (float) rnd.nextGaussian() * 0.012f;
            p.vy = p.wisp ? 0.05f + rnd.nextFloat() * 0.04f : 0.008f + rnd.nextFloat() * 0.022f;
            p.life = 8f + rnd.nextFloat() * 6f;
            p.size = p.wisp ? 0.28f + rnd.nextFloat() * 0.12f : 0.38f + rnd.nextFloat() * 0.3f;
            /* ground haze lies flat; wisps stand tall */
            p.stretch = p.wisp ? 1.5f + rnd.nextFloat() * 0.4f : 0.55f + rnd.nextFloat() * 0.2f;
            p.phase = rnd.nextFloat() * 6.28f;
            p.tex = rnd.nextInt(TEXTURES);
            return (p);
        }
    }

    /* ------------------------------------------------------------- puff texture */

    /**
     * One soft puff: sickly green, its alpha a turbulent noise that falls off to nothing well
     * inside the edge, so no puff ever shows a border.
     */
    public static BufferedImage puffImage(int sz, long seed) {
        Random rnd = new Random(seed);
        int n = 4;
        float[][][] oct = new float[5][][];
        for (int o = 0; o < oct.length; o++) {
            int gs = (n << o) + 2;
            oct[o] = new float[gs][gs];
            for (float[] row : oct[o])
                for (int i = 0; i < gs; i++)
                    row[i] = rnd.nextFloat();
        }
        float ox = rnd.nextFloat() * 0.3f - 0.15f, oy = rnd.nextFloat() * 0.3f - 0.15f;
        BufferedImage img = TexI.mkbuf(Coord.of(sz, sz));
        for (int y = 0; y < sz; y++) {
            for (int x = 0; x < sz; x++) {
                float u = (x + 0.5f) / sz, v = (y + 0.5f) / sz;
                float f = 0, amp = 0.5f, tot = 0;
                for (int o = 0; o < oct.length; o++) {
                    int cells = n << o;
                    f += amp * sample(oct[o], u * cells, v * cells);
                    tot += amp;
                    amp *= 0.55f;
                }
                f /= tot;
                /* turbulence: folds the noise into billows and streaks, like real smoke */
                float turb = Math.abs(f - 0.5f) * 2f;
                float billow = 1f - turb;
                float dx = (u - 0.5f - ox * (1 - Math.abs(u - 0.5f) * 2)) * 2, dy = (v - 0.5f - oy * (1 - Math.abs(v - 0.5f) * 2)) * 2;
                float r = (float) Math.sqrt(dx * dx + dy * dy);
                /* the noise eats into the outline, so each puff has a ragged, wispy shape */
                float fall = 1f - smooth(r + (f - 0.5f) * 0.6f, 0.25f, 0.95f);
                float a = fall * (0.2f + 0.8f * smooth(billow, 0.3f, 0.95f)) * smooth(f, 0.22f, 0.55f);
                /* billow crests pale and murky, the folds between them darker olive */
                float d = smooth(billow, 0.4f, 0.95f);
                int cr = (int) (lerp(78, 150, d)), cg = (int) (lerp(98, 168, d)), cb = (int) (lerp(34, 70, d));
                int ca = (int) (Math.max(0f, Math.min(1f, a)) * 255);
                img.setRGB(x, y, (ca << 24) | (cr << 16) | (cg << 8) | cb);
            }
        }
        return (img);
    }

    static float sample(float[][] f, float x, float y) {
        int ix = (int) x, iy = (int) y;
        float fx = x - ix, fy = y - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        return (lerp(lerp(f[iy][ix], f[iy][ix + 1], fx), lerp(f[iy + 1][ix], f[iy + 1][ix + 1], fx), fy));
    }

    static float lerp(float a, float b, float t) {
        return (a + (b - a) * t);
    }

    static float smooth(float v, float lo, float hi) {
        float t = Math.max(0f, Math.min(1f, (v - lo) / (hi - lo)));
        return (t * t * (3 - 2 * t));
    }
}
