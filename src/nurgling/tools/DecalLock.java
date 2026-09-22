package nurgling.tools;

import haven.*;
import nurgling.NConfig;
import nurgling.NUtils;

/**
 * "Lock decals": while on, a click that lands on a parchment decal is sent to the container the
 * decal is stuck to, as if the decal were not there. A plain right-click on the decal would
 * otherwise take it off. {@link #takeDecal} still removes one on purpose.
 */
public class DecalLock {
    public static boolean enabled() {
        Boolean locked = (Boolean) NConfig.get(NConfig.Key.lockDecals);
        return locked != null && locked;
    }

    public static boolean isDecal(Gob.Overlay ol) {
        Sprite spr = ol.spr;
        return spr != null && spr.res != null && CustomizeResLayer.PARCHMENT_DECAL.equals(spr.res.name);
    }

    /** True when a click on this overlay should go to its gob instead. */
    public static boolean passesThrough(Gob.Overlay ol) {
        return isDecal(ol) && enabled();
    }

    public static Gob.Overlay findDecal(Gob gob) {
        for (Gob.Overlay ol : gob.ols) {
            if (isDecal(ol))
                return ol;
        }
        return null;
    }

    /** Sends the right-click a real click on the decal would have sent, bypassing the lock. */
    public static void takeDecal(Gob gob, Gob.Overlay decal) {
        FastMesh.MeshRes mesh = decal.spr.res.layer(FastMesh.MeshRes.class);
        int meshid = (mesh != null) ? mesh.id : -1;
        Coord rc = gob.rc.floor(OCache.posres);
        NUtils.getGameUI().map.wdgmsg("click", Coord.z, rc, 3, 0, 1, (int) gob.id, rc, decal.id, meshid);
    }
}
