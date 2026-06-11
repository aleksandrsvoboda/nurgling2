package nurgling.plugins;

import nurgling.NGameUI;

/**
 * Contract every external plugin implements. The public client knows nothing
 * about what a plugin does — it only loads signed plugin jars and notifies them
 * when a session's UI is ready.
 *
 * The entry class is named in the jar's {@code plugin.properties} (key
 * {@code main=...}) and must have a public no-arg constructor.
 */
public interface NPlugin {

    /** Human-readable name for logging/UI. */
    String name();

    /**
     * Called once for each game session whose UI has finished initializing.
     * Implementations typically add their widgets here and register listeners.
     */
    void onLoad(NGameUI gui);

    /** Called when a session UI is being torn down. Optional. */
    default void onUnload(NGameUI gui) {}
}
