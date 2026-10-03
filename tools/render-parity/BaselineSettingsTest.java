package nurgling.render;

import java.lang.reflect.Field;
import java.util.Map;

/** Tests the user-visible baseline/opt-in contract without opening a window. */
public class BaselineSettingsTest {
    private static void require(boolean condition, String message) {
        if(!condition) throw(new AssertionError(message));
    }

    private static void baseline(NGfx.Settings settings) throws Exception {
        for(Field field : NGfx.Settings.class.getFields()) {
            if(field.getType() == boolean.class)
                require(!field.getBoolean(settings), "Baseline enables " + field.getName());
        }
        require(settings.aniso == 1, "Baseline overrides texture filtering");
        require(!settings.hdr(), "Baseline changes the scene framebuffer to HDR");
    }

    public static void main(String[] args) throws Exception {
        baseline(NGfx.classic);
        NGfx.Settings customized = NGfx.Preset.ULTRA.settings(NGfx.classic)
            .with("upscale", true).with("exposure", 1.7f);
        require(!customized.snow && !customized.with("snow", true).snow,
                "Temporarily disabled snow enabled by preset or saved setting");
        Map<String, Object> saved = customized.map();
        NGfx.Settings noReflections=customized.with("waterreflections",false);
        require(noReflections.water && !noReflections.waterreflections,
                "Reflection switch disables water itself");
        require(Boolean.FALSE.equals(noReflections.with("water",false).with("water",true).map().get("waterreflections")),
                "Water toggle loses reflection preference");
        java.lang.reflect.Constructor<NGfx.Settings> ctor=NGfx.Settings.class.getDeclaredConstructor(Map.class);
        ctor.setAccessible(true);
        Map<String,Object> oldWater=new java.util.HashMap<>();oldWater.put("water",true);
        require(ctor.newInstance(oldWater).waterreflections,"Existing water settings lose reflections during migration");
        require(!ctor.newInstance(noReflections.map()).waterreflections,"Saved reflection preference is lost");
        for(String removed : new String[]{"sway", "particles", "steps", "wildlife", "waterfx"})
            require(!customized.with(removed, true).map().containsKey(removed),
                    "Removed effect survives saved settings or presets: " + removed);
        baseline(NGfx.Preset.CLASSIC.settings(customized));
        baseline(NGfx.effective(customized, false));
        baseline(NGfx.effective(customized.with("enabled", false), true));
        require(saved.equals(customized.map()), "Disabling enhancements changes saved choices");
        require(NGfx.effective(customized, true) == customized, "Explicit enhancements are lost");
        for(NGfx.Preset preset : new NGfx.Preset[]{NGfx.Preset.ENHANCED, NGfx.Preset.ULTRA}) {
            NGfx.Settings settings = preset.settings(NGfx.classic);
            require(settings.enabled && settings.relief && settings.fire && settings.grade,
                    preset + " does not opt in to enhancements");
        }
        // Previously saved individual effects must not implicitly opt into a new look.
        NGfx.Settings legacy = NGfx.classic.with("fire", true).with("relief", true);
        baseline(NGfx.effective(legacy, true));
        System.out.println("Baseline settings: PASS (defaults, Classic reset, OpenGL, opt-in and saved choices)");
    }
}
