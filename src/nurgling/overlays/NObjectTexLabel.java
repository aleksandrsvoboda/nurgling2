package nurgling.overlays;

import haven.*;
import haven.render.Camera;
import haven.render.Homo3D;
import haven.render.Location;
import haven.render.Projection;
import haven.render.Pipe;
import haven.render.RenderTree;
import nurgling.*;

import java.awt.*;
import java.awt.image.BufferedImage;

public class NObjectTexLabel extends Sprite implements RenderTree.Node, PView.Render2D{
    protected Coord3f pos;
    public TexI label = null;
    protected TexI img = null;
    boolean forced = false;
    
    // Cached config values for performance
    private static boolean disableZoomHide = false;
    private static long lastConfigCheck = 0;
    private static final long CONFIG_CHECK_INTERVAL = 1000; // Check every 1 second
    
    public NObjectTexLabel(Owner owner) {
        super(owner, null);
        pos = new Coord3f(0,0,5);
    }
    
    private static void updateConfigCache() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCheck > CONFIG_CHECK_INTERVAL) {
            Object val = NConfig.get(NConfig.Key.treeScaleDisableZoomHide);
            disableZoomHide = val instanceof Boolean && (Boolean) val;
            lastConfigCheck = now;
        }
    }

    /* Clip-space w of the last project() call. */
    private float clipw;

    private static float[] xform(Matrix4f mat, float[] v) {
        float[] m = mat.m;
        float x = v[0], y = v[1], z = v[2], w = v[3];
        v[0] = (m[ 0] * x) + (m[ 4] * y) + (m[ 8] * z) + (m[12] * w);
        v[1] = (m[ 1] * x) + (m[ 5] * y) + (m[ 9] * z) + (m[13] * w);
        v[2] = (m[ 2] * x) + (m[ 6] * y) + (m[10] * z) + (m[14] * w);
        v[3] = (m[ 3] * x) + (m[ 7] * y) + (m[11] * z) + (m[15] * w);
        return(v);
    }

    /* Homo3D.obj2view(pos, state, Area.sized(sz)).round2() without its
     * per-call temporaries: every label in view projects each frame, and
     * those were about half of all the client's allocation. */
    private final float[] cv = new float[4];
    private Coord project(Pipe state, Coord sz) {
        float[] v = cv;
        v[0] = pos.x; v[1] = pos.y; v[2] = pos.z; v[3] = 1.0f;
        Location.Chain s_loc = state.get(Homo3D.loc);
        if(s_loc != null) xform(s_loc.fin(Matrix4f.id), v);
        Camera s_cam = state.get(Homo3D.cam);
        if(s_cam != null) xform(s_cam.fin(Matrix4f.id), v);
        Projection s_prj = state.get(Homo3D.prj);
        if(s_prj != null) xform(s_prj.fin(Matrix4f.id), v);
        clipw = v[3];
        float nx = v[0] / v[3], ny = v[1] / v[3];
        return(Coord.of(Math.round(((nx + 1) * 0.5f) * sz.x), Math.round(((-ny + 1) * 0.5f) * sz.y)));
    }

    @Override
    public void draw(GOut g, Pipe state) {
        if(NUtils.getGameUI()!=null) {
            updateConfigCache();
            MapView.Camera cam = NUtils.getGameUI().map.camera;

            // If zoom hide is disabled, always show full label
            boolean showFullLabel = forced || disableZoomHide;

            if (NUtils.getGameUI().map.camera instanceof MapView.FreeCam) {
                Coord sc = project(state, g.sz());
                if (clipw > 1000 && !showFullLabel) {
                    if (img != null)
                        g.aimage(img, sc, 0.5, 0.5);
                } else {
                    if (label != null)
                        g.aimage(label, sc, 0.5, 0.5);
                }
            } else if (NUtils.getGameUI().map.camera instanceof MapView.OrthoCam) {
                Coord sc = project(state, g.sz());
                if (((MapView.OrthoCam) cam).field > 400 && !showFullLabel) {
                    if (img != null)
                        g.aimage(img, sc, 0.5, 0.5);
                } else {
                    if (label != null)
                        g.aimage(label, sc, 0.5, 0.5);
                }
            } else if (NUtils.getGameUI().map.camera instanceof MapView.SimpleCam) {
                Coord sc = project(state, g.sz());
                if (((MapView.SimpleCam) cam).dist > 600 && !showFullLabel) {
                    if (img != null)
                        g.aimage(img, sc, 0.5, 0.5);
                } else {
                    if (label != null)
                        g.aimage(label, sc, 0.5, 0.5);
                }
            } else {
                Coord sc = project(state, g.sz());
                if (label != null)
                    g.aimage(label, sc, 0.5, 0.5);
            }
        }
    }
}
