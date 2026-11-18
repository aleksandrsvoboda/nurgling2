package nurgling.overlays;

import haven.Gob;
import haven.render.Render;
import haven.res.lib.tree.TreeScale;
import nurgling.NConfig;

import java.awt.*;

/**
 * A ring at a mine support's holding radius, shown with showMineSupportRadius. The radius is the
 * one the mining overlay uses (NMiningSupport.specFor); a tower cap's grows with the tree, the same
 * way its coverage does.
 */
public class NMineSupportRadius extends NAreaRad {
    private final int baseRadius;
    private float shown;

    public NMineSupportRadius(Gob owner, int baseRadius) {
        super(owner, radiusFor(owner, baseRadius, (Boolean) NConfig.get(NConfig.Key.showMineSupportRadius)),
              new Color(140, 72, 220, 96),    // Fill: violet - red, green, cyan and yellow are taken by the
              new Color(190, 120, 255, 255)); // animal, beehive, trough and mound bed rings
        this.baseRadius = baseRadius;
        this.shown = radiusFor(owner, baseRadius, (Boolean) NConfig.get(NConfig.Key.showMineSupportRadius));
    }

    private static float radiusFor(Gob gob, int baseRadius, boolean on) {
        if (!on)
            return 0f;
        TreeScale ts = gob.getattr(TreeScale.class);
        if (ts != null)
            return Math.round(baseRadius * (ts.scale - 0.1) / 0.9);
        return baseRadius;
    }

    @Override
    public void gtick(Render g) {
        float want = radiusFor((Gob) owner, baseRadius, (Boolean) NConfig.get(NConfig.Key.showMineSupportRadius));
        if (want != shown) {
            shown = want;
            setR(g, want);
        }
        if (shown > 0)
            super.gtick(g);
    }
}
