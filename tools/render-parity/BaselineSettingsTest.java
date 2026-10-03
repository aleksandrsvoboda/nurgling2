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
        require(settings.plights == 0, "Baseline adds point-light shadows");
        require(settings.aniso == 1, "Baseline overrides texture filtering");
        require(!settings.hdr(), "Baseline changes the scene framebuffer to HDR");
    }

    public static void main(String[] args) throws Exception {
        baseline(NGfx.classic);
        NGfx.Settings customized = NGfx.Preset.ULTRA.settings(NGfx.classic)
            .with("tilt", true).with("upscale", true).with("exposure", 1.7f);
        Map<String, Object> saved = customized.map();
        baseline(NGfx.Preset.CLASSIC.settings(customized));
        baseline(NGfx.effective(customized, false));
        baseline(NGfx.effective(customized.with("enabled", false), true));
        require(saved.equals(customized.map()), "Disabling enhancements changes saved choices");
        require(NGfx.effective(customized, true) == customized, "Explicit enhancements are lost");
        for(NGfx.Preset preset : new NGfx.Preset[]{NGfx.Preset.ENHANCED, NGfx.Preset.ULTRA}) {
            NGfx.Settings settings = preset.settings(NGfx.classic);
            require(settings.enabled && settings.relief && settings.fire && settings.bloom,
                    preset + " does not opt in to enhancements");
        }
        // Previously saved individual effects must not implicitly opt into a new look.
        NGfx.Settings legacy = NGfx.classic.with("fire", true).with("relief", true).with("bloom", true);
        baseline(NGfx.effective(legacy, true));
        System.out.println("Baseline settings: PASS (defaults, Classic reset, OpenGL, opt-in and saved choices)");
    }
}
