package nurgling.overlays;

import haven.Gob;
import haven.Resource;
import haven.TexI;
import nurgling.tools.GobCustomize;

/**
 * The permanent version of the marker the Ctrl+F object search drops above its hits, switched on
 * per object type from the "Configure" window.
 *
 * <p>A distinct class rather than a plain {@link NTexMarker} so it can be told apart from the
 * search's own markers, which use the same texture and can sit on the same gob.
 *
 * <p>It removes itself rather than being removed: {@link haven.Gob.Overlay} drops an overlay whose
 * tick reports done, and {@link NTexMarker} reports done when its condition holds. Making the
 * condition "the setting is off" means switching the option off cannot race an add that is still
 * sitting in the gob's deferred queue.
 */
public class NGobConfigMarker extends NTexMarker {
    private static TexI tex = null;

    /** Loaded on demand: resources are not available when classes are initialised. */
    private static synchronized TexI tex() {
        if (tex == null)
            tex = new TexI(Resource.loadsimg("nurgling/hud/buttons/down_v2/u"));
        return tex;
    }

    public NGobConfigMarker(Gob owner, String res) {
        super(owner, tex(), () -> !GobCustomize.settings(res).marker);
    }
}
