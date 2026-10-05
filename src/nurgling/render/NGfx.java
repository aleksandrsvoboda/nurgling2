package nurgling.render;

import java.util.*;
import nurgling.NConfig;

/*
 * Visual quality settings for the extra rendering effects. Every
 * effect has its own toggle so weaker machines can drop the costly
 * ones; everything is off by default, which keeps the classic look.
 *
 * Settings are immutable snapshots; set() replaces the snapshot and
 * bumps the version so renderers can pick up changes cheaply.
 */
public class NGfx {
    // Temporarily unavailable: visual snow cover conflicts with game mechanics.
    // Retain the implementation so it can be revisited without restoring deleted code.
    public static final boolean SNOW_SETTLING_AVAILABLE = false;
    public static final class Settings {
	/* Choosing Vulkan must not opt in to a different visual style. */
	public final boolean enabled;
	/* Tone mapping and color grading */
	public final boolean grade;
	public final float exposure, contrast, saturation, warmth;
	public final boolean worldlight;
	public final float worldlightstrength;
	/* Anti-aliasing and sharpening */
	public final boolean fxaa, sharpen;
	public final float sharpness;
	/* Improved shadows */
	public final boolean bettershadows;
	/* Anisotropic filtering level (1 = off) */
	public final int aniso;
	/* Terrain relief */
	public final boolean relief, reliefpavingonly;
	public final float reliefstrength;
	/* Realistic fire (flames, embers) and smoke */
	public final boolean fire, smoke;
	/* Transparent water and its independently switchable reflections. */
	public final boolean water, waterreflections, rainripples;
	/* World: wet ground in
	 * rain, fire glow, heat shimmer and light shafts. */
	public final boolean wet, glow, heat, shafts, lightning, grass;
	public final float grassdensity;
	/* Temporal anti-aliasing, edge-aware upscaling, auto-exposure;
	 * snow settling and parallax ground. */
	public final boolean taa, upscale, autoexp, snow, parallax;

	private Settings(Map<String, Object> m) {
	    enabled = b(m, "enabled", false);
	    grade = b(m, "grade", false);
	    exposure = f(m, "exposure", 1.0f);
	    contrast = f(m, "contrast", 1.05f);
	    saturation = f(m, "saturation", 1.1f);
	    warmth = f(m, "warmth", 0.1f);
	    worldlight = b(m, "worldlight", false);
	    float lightStrength = f(m, "worldlightstrength", 1.0f);
	    worldlightstrength = Float.isFinite(lightStrength) ? Math.max(0, Math.min(1, lightStrength)) : 1;
	    fxaa = b(m, "fxaa", false);
	    sharpen = b(m, "sharpen", false);
	    sharpness = f(m, "sharpness", 0.4f);
	    bettershadows = b(m, "bettershadows", false);
	    aniso = i(m, "aniso", 1);
	    water = b(m, "water", false);
	    waterreflections = b(m, "waterreflections", water);
	    rainripples = b(m, "rainripples", false);
	    relief = b(m, "relief", false);
	    reliefpavingonly = b(m, "reliefpavingonly", false);
	    reliefstrength = f(m, "reliefstrength", 1.0f);
	    fire = b(m, "fire", false);
	    wet = b(m, "wet", false);
	    lightning = b(m, "lightningbolts", false);
	    grass = b(m, "animatedgrass", false);
	    grassdensity = bounded(f(m,"grassdensity",1),.25f,2,1);
	    glow = b(m, "glow", false);
	    heat = b(m, "heat", false);
	    shafts = b(m, "shafts", false);
	    taa = b(m, "taa", false);
	    upscale = b(m, "upscale", false);
	    autoexp = b(m, "autoexp", false);
	    snow = SNOW_SETTLING_AVAILABLE && b(m, "snow", false);
	    parallax = b(m, "parallax", false);
	    smoke = b(m, "smoke", false);
	}

	public Map<String, Object> map() {
	    Map<String, Object> m = new HashMap<>();
	    m.put("enabled", enabled);
	    m.put("grade", grade); m.put("exposure", exposure); m.put("contrast", contrast);
	    m.put("saturation", saturation); m.put("warmth", warmth);
	    m.put("worldlight", worldlight); m.put("worldlightstrength", worldlightstrength);
	    m.put("fxaa", fxaa); m.put("sharpen", sharpen); m.put("sharpness", sharpness);
	    m.put("bettershadows", bettershadows);
	    m.put("aniso", aniso); m.put("water", water);
	    m.put("waterreflections", waterreflections);
	    m.put("rainripples", rainripples);
	    m.put("relief", relief); m.put("reliefstrength", reliefstrength);
	    m.put("reliefpavingonly", reliefpavingonly);
	    m.put("fire", fire); m.put("smoke", smoke);
	    m.put("wet", wet); m.put("glow", glow);
	    m.put("lightningbolts", lightning);
	    m.put("animatedgrass", grass);
	    m.put("grassdensity",grassdensity);
	    m.put("heat", heat); m.put("shafts", shafts);
	    m.put("taa", taa); m.put("upscale", upscale); m.put("autoexp", autoexp);
	    m.put("snow", snow);
	    m.put("parallax", parallax);
	    return(m);
	}

	public Settings with(String key, Object val) {
	    Map<String, Object> m = map();
	    m.put(key, val);
	    return(new Settings(m));
	}

	/* Whether the scene should be rendered in HDR (float) color. */
	public boolean hdr() {
	    return(grade);
	}

	public boolean colorpass() {
	    return(grade || autoexp);
	}

	private static boolean b(Map<String, Object> m, String k, boolean def) {
	    Object v = m.get(k);
	    return((v instanceof Boolean) ? (Boolean)v : def);
	}

	private static float f(Map<String, Object> m, String k, float def) {
	    Object v = m.get(k);
	    return((v instanceof Number) ? ((Number)v).floatValue() : def);
	}
	private static float bounded(float value,float min,float max,float fallback) {
	    return Float.isFinite(value)?Math.max(min,Math.min(max,value)):fallback;
	}

	private static int i(Map<String, Object> m, String k, int def) {
	    Object v = m.get(k);
	    return((v instanceof Number) ? ((Number)v).intValue() : def);
	}
    }

    public enum Preset {
	CLASSIC, ENHANCED, ULTRA;

	public Settings settings(Settings base) {
	    if(this == CLASSIC)
		return(classic);
	    Map<String, Object> m = base.map();
	    boolean on = (this != CLASSIC), ultra = (this == ULTRA);
	    m.put("enabled", true);
	    m.put("grade", on);
	    m.put("fxaa", on);
	    m.put("sharpen", ultra);
	    m.put("aniso", ultra ? 16 : (on ? 8 : 1));
	    m.put("relief", on);
	    m.put("fire", on);
	    m.put("smoke", on);
	    m.put("water", on);
	    m.put("waterreflections", on);
	    m.put("rainripples", on);
	    m.put("wet", on);
	    m.put("glow", on);
	    m.put("heat", on);
	    m.put("shafts", ultra);
	    m.put("taa", on);
	    m.put("autoexp", on);
	    m.put("snow", SNOW_SETTLING_AVAILABLE && on);
	    m.put("parallax", on);
	    return(new Settings(m));
	}
    }

    /* Also clears optional effects which presets do not normally change
     * (such as upscaling). Never derive this from saved overrides. */
    public static final Settings classic = new Settings(Collections.emptyMap());

    public static Settings effective(Settings settings, boolean supported) {
	return((supported && settings.enabled) ? settings : classic);
    }

    private static volatile Settings cur = null;
    private static volatile int version = 0;

    public static Settings get() {
	Settings ret = cur;
	if(ret == null) {
	    Map<String, Object> m = NConfig.getAsMap(NConfig.Key.graphics);
	    cur = ret = new Settings((m == null) ? new HashMap<>() : m);
	    version++;
	}
	return(ret);
    }

    public static int version() {
	get();
	return(version);
    }

    public static void set(Settings s) {
	cur = s;
	version++;
	NConfig.set(NConfig.Key.graphics, s.map());
    }

    /* The effects are a Vulkan-renderer feature; under OpenGL the
     * client stays vanilla whatever the settings say. */
    public static boolean supported(haven.render.Environment env) {
	return(env instanceof haven.render.vk.VkEnvironment);
    }

    /* The settings in effect for a renderer. */
    public static Settings effective(haven.render.Environment env) {
	return(effective(get(), supported(env)));
    }
}
