package nurgling.plugins;

import nurgling.NGameUI;
import nurgling.widgets.charsel.NCharselScreen;

/**
 * Contract every external plugin implements. The public client knows nothing
 * about what a plugin does — it only loads signed plugin jars (at client startup)
 * and notifies them when a session reaches character selection or its game UI is ready.
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

    /**
     * Called each time a session shows the character-selection screen, once its character list
     * is in place. Plugins can add entries with {@link NCharselScreen#addAction} and show their
     * own content in the list's place with {@link NCharselScreen#showPanel}. Optional.
     */
    default void onCharsel(NCharselScreen screen) {}
}
