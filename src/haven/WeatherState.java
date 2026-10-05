package haven;

import haven.render.Pipe;
import haven.res.gfx.fx.wet.Wet;

/** Keep the scene's weather inheritance groups stable across weather transitions. */
public final class WeatherState {
    private WeatherState() {}

    /* These are the global draw slots supplied by the normal weather resources.
     * Removing a definition forces RenderTree.updtotal over every descendant;
     * changing its value to null instead uses the group/mask update path.
     * Vulkan only: OpenGL's mask path updates uniforms without rebuilding the
     * program, so MapView uses plain composition there.
     * Read the incoming value, rather than blindly clearing it, so a state
     * inherited from outside this weather operation still works normally.
     */
    private static final Pipe.Op defaults = p -> {
        p.put(Wet.slot, p.get(Wet.slot));
        p.put(CloudShadow.slot, p.get(CloudShadow.slot));
    };

    public static Pipe.Op compose(Pipe.Op... weather) {
        // Keep resource operations live: Clouds reads the current light and
        // its disable toggle when applied. Caching their evaluated states here
        // would lose both those changes and RenderTree's parent dependencies.
        return Pipe.Op.compose(defaults, Pipe.Op.compose(weather));
    }
}
