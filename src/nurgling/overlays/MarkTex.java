package nurgling.overlays;

import haven.*;
import haven.render.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One shared texture per mark image. Ring and marker overlays are created per gob, often in
 * bursts as a cluster of objects loads in; building a TexI in each constructor meant a separate
 * GL texture (and a CPU image conversion) for every copy of the same picture.
 */
public final class MarkTex {
    private static final Map<String, ColorTex> colortex = new ConcurrentHashMap<>();
    private static final Map<String, TexI> scaled = new ConcurrentHashMap<>();

    private MarkTex() {}

    /** Unscaled image as a ColorTex, for 3D ring overlays. */
    public static ColorTex ring(String res) {
        return colortex.computeIfAbsent(res, r -> new TexI(Resource.loadimg(r)).st());
    }

    /** UI-scaled image as a TexI, for screen-space markers. */
    public static TexI marker(String res) {
        return scaled.computeIfAbsent(res, r -> new TexI(Resource.loadsimg(r)));
    }
}
