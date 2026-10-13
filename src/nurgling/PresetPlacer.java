package nurgling;

import haven.MapView;

/** Something being placed on the map from a preset; it takes the map's clicks and keys until it is done. */
public interface PresetPlacer extends MapView.Grabber
{
    /** Keys while placing; {@code code} is an AWT key code. Returns true if the key was used. */
    boolean keydown(int code);
}
