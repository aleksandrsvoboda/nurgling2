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
    public static final class Settings {
	/* Tone mapping and color grading */
	public final boolean grade;
	public final float exposure, contrast, saturation, warmth;
	public final boolean vignette;
	/* Anti-aliasing and sharpening */
	public final boolean fxaa, sharpen;
	public final float sharpness;
	/* Ambient occlusion; aoq is 0 = half resolution, 1 = full */
	public final boolean ssao;
	public final int aoq;
	public final float aostrength;
	/* Bloom */
	public final boolean bloom;
	public final float bloomstrength;
	/* Soft shadows; shadowq is 0 = soft, 1 = softer (more taps) */
	public final boolean softshadow;
	public final int shadowq;
	/* Anisotropic filtering level (1 = off) */
	public final int aniso;
	/* Terrain relief and local contrast */
	public final boolean relief, clarity, objrelief;
	public final float reliefstrength, claritystrength, objreliefstrength;
	/* Shadows from point lights: how many lights, and map size
	 * (0 = normal, 1 = high) */
	public final int plights, plightres;
	/* Realistic fire (flames, embers) and smoke */
	public final boolean fire, smoke;
	/* Water reflections */
	public final boolean water;
	/* World: fair-weather clouds, time-of-day grading, wet ground in
	 * rain, fire glow, trees swaying in gusts, ambient particles,
	 * heat shimmer, light shafts, tilt-shift. */
	public final boolean clouds, tod, wet, glow, sway, particles, heat, shafts, tilt;
	public final float tiltstrength;
	/* Temporal anti-aliasing, edge-aware upscaling, auto-exposure;
	 * snow settling, parallax ground, water details (caustics, wading
	 * rings), footstep effects, butterflies,
	 * lightning. */
	public final boolean taa, upscale, autoexp, snow, parallax, waterfx, steps, wildlife, lightning;

	private Settings(Map<String, Object> m) {
	    grade = b(m, "grade", false);
	    exposure = f(m, "exposure", 1.0f);
	    contrast = f(m, "contrast", 1.05f);
	    saturation = f(m, "saturation", 1.1f);
	    warmth = f(m, "warmth", 0.1f);
	    vignette = b(m, "vignette", false);
	    fxaa = b(m, "fxaa", false);
	    sharpen = b(m, "sharpen", false);
	    sharpness = f(m, "sharpness", 0.4f);
	    ssao = b(m, "ssao", false);
	    aoq = i(m, "aoq", 0);
	    aostrength = f(m, "aostrength", 1.0f);
	    bloom = b(m, "bloom", false);
	    bloomstrength = f(m, "bloomstrength", 0.5f);
	    softshadow = b(m, "softshadow", false);
	    shadowq = i(m, "shadowq", 0);
	    aniso = i(m, "aniso", 1);
	    water = b(m, "water", false);
	    relief = b(m, "relief", false);
	    reliefstrength = f(m, "reliefstrength", 1.0f);
	    clarity = b(m, "clarity", false);
	    claritystrength = f(m, "claritystrength", 0.35f);
	    objrelief = b(m, "objrelief", false);
	    objreliefstrength = f(m, "objreliefstrength", 0.6f);
	    plights = Math.max(0, Math.min(4, i(m, "plights", 0)));
	    plightres = i(m, "plightres", 0);
	    fire = b(m, "fire", false);
	    clouds = b(m, "clouds", false);
	    tod = b(m, "tod", false);
	    wet = b(m, "wet", false);
	    glow = b(m, "glow", false);
	    sway = b(m, "sway", false);
	    particles = b(m, "particles", false);
	    heat = b(m, "heat", false);
	    shafts = b(m, "shafts", false);
	    tilt = b(m, "tilt", false);
	    tiltstrength = f(m, "tiltstrength", 0.8f);
	    taa = b(m, "taa", false);
	    upscale = b(m, "upscale", false);
	    autoexp = b(m, "autoexp", false);
	    snow = b(m, "snow", false);
	    parallax = b(m, "parallax", false);
	    waterfx = b(m, "waterfx", false);
	    steps = b(m, "steps", false);
	    wildlife = b(m, "wildlife", false);
	    lightning = b(m, "lightning", false);
	    smoke = b(m, "smoke", false);
	}

	public Map<String, Object> map() {
	    Map<String, Object> m = new HashMap<>();
	    m.put("grade", grade); m.put("exposure", exposure); m.put("contrast", contrast);
	    m.put("saturation", saturation); m.put("warmth", warmth); m.put("vignette", vignette);
	    m.put("fxaa", fxaa); m.put("sharpen", sharpen); m.put("sharpness", sharpness);
	    m.put("ssao", ssao); m.put("aoq", aoq); m.put("aostrength", aostrength);
	    m.put("bloom", bloom); m.put("bloomstrength", bloomstrength);
	    m.put("softshadow", softshadow); m.put("shadowq", shadowq);
	    m.put("aniso", aniso); m.put("water", water);
	    m.put("relief", relief); m.put("reliefstrength", reliefstrength);
	    m.put("clarity", clarity); m.put("claritystrength", claritystrength);
	    m.put("objrelief", objrelief); m.put("objreliefstrength", objreliefstrength);
	    m.put("plights", plights); m.put("plightres", plightres);
	    m.put("fire", fire); m.put("smoke", smoke);
	    m.put("clouds", clouds); m.put("tod", tod); m.put("wet", wet); m.put("glow", glow);
	    m.put("sway", sway); m.put("particles", particles); m.put("heat", heat); m.put("shafts", shafts);
	    m.put("tilt", tilt); m.put("tiltstrength", tiltstrength);
	    m.put("taa", taa); m.put("upscale", upscale); m.put("autoexp", autoexp);
	    m.put("snow", snow);
	    m.put("parallax", parallax); m.put("waterfx", waterfx); m.put("steps", steps);
	    m.put("wildlife", wildlife); m.put("lightning", lightning);
	    return(m);
	}

	public Settings with(String key, Object val) {
	    Map<String, Object> m = map();
	    m.put(key, val);
	    return(new Settings(m));
	}

	/* Whether the scene should be rendered in HDR (float) color. */
	public boolean hdr() {
	    return(grade || bloom);
	}

	private static boolean b(Map<String, Object> m, String k, boolean def) {
	    Object v = m.get(k);
	    return((v instanceof Boolean) ? (Boolean)v : def);
	}

	private static float f(Map<String, Object> m, String k, float def) {
	    Object v = m.get(k);
	    return((v instanceof Number) ? ((Number)v).floatValue() : def);
	}

	private static int i(Map<String, Object> m, String k, int def) {
	    Object v = m.get(k);
	    return((v instanceof Number) ? ((Number)v).intValue() : def);
	}
    }

    public enum Preset {
	CLASSIC, ENHANCED, ULTRA;

	public Settings settings(Settings base) {
	    Map<String, Object> m = base.map();
	    boolean on = (this != CLASSIC), ultra = (this == ULTRA);
	    m.put("grade", on);
	    m.put("vignette", ultra);
	    m.put("fxaa", on);
	    m.put("sharpen", ultra);
	    m.put("ssao", on);
	    m.put("aoq", ultra ? 1 : 0);
	    m.put("bloom", on);
	    m.put("softshadow", on);
	    m.put("shadowq", ultra ? 1 : 0);
	    m.put("aniso", ultra ? 16 : (on ? 8 : 1));
	    m.put("relief", on);
	    m.put("clarity", on);
	    m.put("objrelief", on);
	    m.put("plights", ultra ? 2 : (on ? 1 : 0));
	    m.put("plightres", ultra ? 1 : 0);
	    m.put("fire", on);
	    m.put("smoke", on);
	    m.put("water", on);
	    m.put("clouds", on);
	    m.put("tod", on);
	    m.put("wet", on);
	    m.put("glow", on);
	    m.put("sway", on);
	    m.put("particles", on);
	    m.put("heat", on);
	    m.put("shafts", ultra);
	    m.put("taa", on);
	    m.put("autoexp", on);
	    m.put("snow", on);
	    m.put("parallax", on);
	    m.put("waterfx", on);
	    m.put("steps", on);
	    m.put("wildlife", on);
	    m.put("lightning", on);
	    return(new Settings(m));
	}
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
	Settings s = get();
	return(supported(env) ? s : Preset.CLASSIC.settings(s));
    }
}
