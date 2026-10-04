package nurgling.render;

import haven.Coord3f;
import haven.DirLight;
import haven.FColor;
import haven.render.Pipe;
import haven.render.State;
import haven.render.sl.ProgramContext;
import haven.render.sl.ShaderMacro;

/** Optional daily palette for sunlight and ambient world lighting. */
public final class WorldLighting {
    /** Use server light before night vision or debug overrides. The target switches
     * environments immediately, without waiting for the light transition to finish. */
    public static boolean outdoors(java.awt.Color current, java.awt.Color target) {
        java.awt.Color raw = target != null ? target : current;
        return raw != null && (raw.getRGB() & 0xffffff) != 0;
    }

    /** Per-view marker; classic materials keep their original cel ramp when absent. */
    public static final class Smooth extends State {
        public static final Slot<Smooth> slot = new Slot<>(Slot.Type.DRAW, Smooth.class);
        private static final class Marker {}
        private static final Marker marker = new Marker();
        private static final ShaderMacro shader = prog -> prog.module(marker);
        public ShaderMacro shader() { return shader; }
        public void apply(Pipe pipe) { pipe.put(slot, this); }
        public static boolean active(ProgramContext prog) { return prog.getmod(Marker.class) != null; }
    }
    public static final Smooth smooth = new Smooth();
    private static final FColor MOON = new FColor(.48f, .62f, 1f);
    private static final FColor HORIZON = new FColor(1f, .68f, .46f);
    private static final FColor NOON = new FColor(1f, .95f, .84f);
    // Keep shaded surfaces readable with continuous material lighting.
    private static final FColor NIGHT_AMBIENT = new FColor(.24f, .31f, .44f);
    private static final FColor DAY_AMBIENT = new FColor(.38f, .40f, .44f);

    public static DirLight apply(DirLight original, double fraction, float strength) {
        return apply(original, fraction, strength, 0, false);
    }

    /** Smoothly remove the clear-weather lift in any rain, restore it after rain stops. */
    public static float approachClearWeather(float current, float rain, double dt) {
        float target = rain > 0 ? 0 : 1;
        return current + (target - current) * (float)(1 - Math.exp(-Math.max(0, Math.min(dt, 1)) / (target < current ? 2 : 6)));
    }

    public static DirLight apply(DirLight original, double fraction, float strength, float clearWeather, boolean autoExposure) {
        if(original == null || !Double.isFinite(fraction) || !Float.isFinite(strength) || strength <= 0)
            return original;
        // A black outdoor light is also used underground; do not manufacture daylight there.
        if(original.dif[0] + original.dif[1] + original.dif[2] < .0001f)
            return original;
        fraction -= Math.floor(fraction);
        float hour = (float)(fraction * 24);
        float day = smooth((hour - 5.75f) / .5f) * (1 - smooth((hour - 17.75f) / .5f));
        float sunHeight = Math.max(0, (float)Math.sin((fraction - .25) * Math.PI * 2));
        float noon = smooth(sunHeight / .55f);
        FColor sun = spherical(HORIZON, NOON, noon);
        FColor direct = spherical(MOON, sun, day).mul(.9f + .25f * noon * day);
        // Keep gloss bounded separately from the daytime diffuse-light maximum.
        FColor specular = color(original.spc).mul(direct.mul(1 / peak(direct)));
        // The server's very faint night light should not cap our readable moonlit palette.
        // Black outdoor lights (indoors/underground) were excluded above.
        float directPeak = Math.max(peak(color(original.dif)), .60f * (1 - day));
        direct = direct.mul(Math.min(1, directPeak / peak(direct)));
        // Concentrate the lift around midday; preserve night, horizon colors and ambient shadows.
        float middayGain = 1 + .35f * day * sunHeight * sunHeight * sunHeight * sunHeight;
        direct = direct.mul(middayGain);
        FColor ambient = spherical(NIGHT_AMBIENT, DAY_AMBIENT, day);
        // Auto-exposure already lifts dark scenes. Without it, clear skies need
        // more ambient fill and sunlight; preserve the existing rainy palette.
        if(!autoExposure && Float.isFinite(clearWeather)) {
            float lift = Math.max(0, Math.min(1, clearWeather)) * (.5f + .5f * day);
            ambient = ambient.mul(1 + .40f * lift);
            direct = direct.mul(1 + .25f * lift);
        }
        float amount = Math.min(strength, 1);
        DirLight result = new DirLight(color(original.amb).blend(ambient, amount),
                color(original.dif).blend(direct, amount), color(original.spc).blend(specular, amount),
                new Coord3f(original.dir[0], original.dir[1], original.dir[2]));
        result.prio(original.prio);
        return result;
    }

    private static FColor color(float[] c) { return new FColor(c[0], c[1], c[2], c[3]); }
    private static float peak(FColor c) { return Math.max(c.r, Math.max(c.g, c.b)); }

    private static float smooth(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Interpolate hue directions separately from brightness, without darkening the midpoint. */
    private static FColor spherical(FColor a, FColor b, float t) {
        double la = Math.sqrt(a.r * a.r + a.g * a.g + a.b * a.b);
        double lb = Math.sqrt(b.r * b.r + b.g * b.g + b.b * b.b);
        double dot = (a.r * b.r + a.g * b.g + a.b * b.b) / (la * lb);
        double angle = Math.acos(Math.max(-1, Math.min(1, dot)));
        if(angle < .00001) return a.blend(b, t);
        double length = la + (lb - la) * t;
        double wa = Math.sin((1 - t) * angle) / Math.sin(angle) * length / la;
        double wb = Math.sin(t * angle) / Math.sin(angle) * length / lb;
        return new FColor((float)(a.r * wa + b.r * wb), (float)(a.g * wa + b.g * wb),
                          (float)(a.b * wa + b.b * wb));
    }
}
