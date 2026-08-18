package nurgling.tools;

import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

/**
 * The single authority on the flat-world terrain toggle.
 *
 * <p>Flatness is not baked into the map data. Every consumer - the cut meshes built by
 * {@link haven.MapMesh}, the height lookups in {@link haven.MCache}, ridge and flavour
 * geometry - reads {@link NConfig.Key#flatsurface} while it builds. Flipping the key therefore
 * only needs the geometry that was built under the old value thrown away, which is what
 * {@link #apply} does. The setting used to demand a client restart purely because nothing ever
 * asked for that rebuild.
 */
public class FlatWorld {
    private FlatWorld() {}

    public static boolean isEnabled() {
        Object v = NConfig.get(NConfig.Key.flatsurface);
        return (v instanceof Boolean) && (Boolean) v;
    }

    public static void toggle() {
        set(!isEnabled());
    }

    /** Stores the setting and rebuilds the world, if the value actually changed. */
    public static void set(boolean val) {
        if(isEnabled() == val)
            return;
        NConfig.set(NConfig.Key.flatsurface, val);
        // Held in lockstep with the live key so the load-time copy in NConfig.read() is a no-op
        // for configs this build writes, while still migrating a change an older build staged.
        NConfig.set(NConfig.Key.nextflatsurface, val);
        apply();
    }

    /**
     * Drops the terrain geometry of every open session so it rebuilds at the new height. Sessions
     * share one config, so a toggle in one of them has to reach all of the others too.
     */
    public static void apply() {
        for(SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            NGameUI gui = ctx.getGameUI();
            if(gui == null || gui.ui == null || gui.ui.sess == null || gui.ui.sess.glob == null)
                continue;
            gui.ui.sess.glob.map.invalidateAll();
        }
    }
}
