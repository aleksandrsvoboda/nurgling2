package nurgling.render;

import haven.*;
import haven.res.gfx.fx.rain.Rain;
import java.util.Collection;

/** Cloud cover changes the existing outdoor light, never manufactures daylight. */
public final class RainLighting {
    public static float intensity(Collection<Glob.Weather> weather) {
        float rate = 0;
        for(Glob.Weather effect : weather)
            if(effect instanceof Rain && Float.isFinite(((Rain)effect).rate))
                rate = Math.max(rate, ((Rain)effect).rate);
        return Math.min(3, Math.max(0, rate / 12000f));
    }

    public static float approach(float current, float rain, double dt) {
        float target = Math.min(1, rain * .85f);
        return current + (target - current) * (float)(1 - Math.exp(-Math.max(0, Math.min(dt, 1)) / (target > current ? 2 : 6)));
    }

    private static FColor overcast(float[] c, float amount, float dim) {
        float gray = c[0] * .2126f + c[1] * .7152f + c[2] * .0722f;
        float saturation = 1 - .80f * amount, gain = 1 - dim * amount;
        return new FColor((gray + (c[0] - gray) * saturation) * gain,
                          (gray + (c[1] - gray) * saturation) * gain,
                          (gray + (c[2] - gray) * saturation) * gain, c[3]);
    }

    public static DirLight apply(DirLight original, float cover) {
        if(original == null || cover <= .001f) return original;
        cover = Math.min(1, cover);
        DirLight result = new DirLight(overcast(original.amb, cover, .12f),
                overcast(original.dif, cover, .48f), overcast(original.spc, cover, .55f),
                new Coord3f(original.dir[0], original.dir[1], original.dir[2]));
        result.prio(original.prio);
        return result;
    }
}
