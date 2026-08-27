package nurgling.overlays;

import haven.*;
import haven.render.*;
import nurgling.*;

import java.awt.*;
import java.nio.ByteBuffer;
import java.util.*;

public class NModelBox extends Sprite implements RenderTree.Node {
    public static class NBoundingBox
    {

        public final ArrayList<Polygon> polygons;
        public int vertices = 0;
        public boolean blocks = true;

        public NBoundingBox(
                ArrayList<Polygon> polygons,
                boolean blocks
        )
        {
            this.polygons = polygons;
            for (Polygon pol : polygons)
            {
                vertices += 4;
            }
            this.blocks = blocks;
        }

        public static class Polygon
        {
            public final Coord2d[] vertices;
            public boolean neg;

            public Polygon(Coord2d[] vertices)
            {
                this.vertices = vertices;
            }
        }

        public static NBoundingBox getBoundingBox(NHitBox hitBox)
        {
            if (hitBox == null)
                return null;

            ArrayList<Polygon> polygons = new ArrayList<>();
            // inv() is a half-turn about the object's origin, and it belongs here only when
            // NHitBoxD would also apply its asymmetric half-turn - otherwise the drawn box and the
            // box the bots collide against disagree. That rule keys on x alone, so a box like the
            // smelter's (x symmetric, y running -20..11) used to be drawn mirrored in y, at
            // -11..20, while collision stayed put. For a compound footprint the decision belongs
            // to the union, exactly as in NHitShapeD: flipping each part on its own would draw the
            // pieces in each other's places.
            boolean flip = (hitBox.begin.x != -hitBox.end.x);
            NHitBox[] parts = hitBox.parts();
            if (parts == null)
            {
                polygons.add(quad(hitBox, flip));
            }
            else
            {
                for (NHitBox part : parts)
                    polygons.add(quad(part, flip));
            }
            return new NBoundingBox(polygons, true);
        }

        private static Polygon quad(NHitBox box, boolean flip)
        {
            Coord2d[] v = new Coord2d[4];
            v[0] = box.begin;
            v[1] = new Coord2d(box.end.x, box.begin.y);
            v[2] = box.end;
            v[3] = new Coord2d(box.begin.x, box.end.y);
            if (flip)
            {
                for (int i = 0; i < 4; i++)
                    v[i] = v[i].inv();
            }
            return new Polygon(v);
        }
    }

    public static final Color DEF_FILL = new Color(227, 28, 1, 195);
    public static final Color DEF_EDGE = new Color(224, 193, 79, 255);
    public static final int DEF_LINE_WIDTH = 4;
    public static final String DEF_MODE = "FILLED";

    /**
     * How a box should look and behave right now.
     *
     * <p>There are two independent reasons a box can be on screen, and they are styled from
     * different config keys: the object is <i>hidden</i> (the box is its only remaining click
     * target, so it must stay clickable), or the global "show object boundaries" overlay is on
     * (the model is still there, so the box must NOT steal clicks from it). When both apply,
     * hidden wins.
     */
    public static class BoxStyle {
        public final String mode;
        public final Color fill;
        public final Color edge;
        public final int lineWidth;
        public final boolean clickable;

        BoxStyle(String mode, Color fill, Color edge, int lineWidth, boolean clickable) {
            this.mode = (mode == null) ? DEF_MODE : mode;
            this.fill = fill;
            this.edge = edge;
            this.lineWidth = lineWidth;
            this.clickable = clickable;
        }

        public boolean filled() {
            return mode.equals("FILLED") || mode.equals("FILLED_ALWAYS");
        }

        public boolean outlined() {
            return mode.equals("OUTLINE") || mode.equals("OUTLINE_ALWAYS");
        }

        public boolean depthTested() {
            return !mode.equals("OUTLINE_ALWAYS") && !mode.equals("FILLED_ALWAYS");
        }

        public boolean equals(Object o) {
            if (!(o instanceof BoxStyle))
                return false;
            BoxStyle that = (BoxStyle) o;
            return this.clickable == that.clickable && this.lineWidth == that.lineWidth
                    && this.mode.equals(that.mode)
                    && this.fill.equals(that.fill) && this.edge.equals(that.edge);
        }

        public int hashCode() {
            return Objects.hash(mode, fill, edge, lineWidth, clickable);
        }
    }

    private static int intConf(NConfig.Key key, int def) {
        Object o = NConfig.get(key);
        return (o instanceof Number) ? ((Number) o).intValue() : def;
    }

    private static String strConf(NConfig.Key key, String def) {
        Object o = NConfig.get(key);
        return (o instanceof String) ? (String) o : def;
    }

    // Both styles are resolved once per settings change rather than per box per frame: tick()
    // consults them every frame for every visible box, and NConfig.getColor allocates a fresh Color
    // whenever the value is still in its serialized Map form.
    private static volatile int styleVersion = 1;
    private static volatile BoxStyle cachedHidden = null;
    private static volatile int cachedHiddenVer = 0;
    private static volatile BoxStyle cachedBoundary = null;
    private static volatile int cachedBoundaryVer = 0;

    /** Call after changing any of the box config keys so the cached styles are rebuilt. */
    public static void invalidateStyles() {
        styleVersion++;
    }

    /** Style used for gobs the hiding system has removed from the world. Always clickable. */
    public static BoxStyle hiddenStyle() {
        int v = styleVersion;
        BoxStyle s = cachedHidden;
        if (s == null || cachedHiddenVer != v) {
            s = new BoxStyle(strConf(NConfig.Key.hideBoxDisplayMode, DEF_MODE),
                    NConfig.getColor(NConfig.Key.hideBoxFillColor, DEF_FILL),
                    NConfig.getColor(NConfig.Key.hideBoxEdgeColor, DEF_EDGE),
                    intConf(NConfig.Key.hideBoxLineWidth, DEF_LINE_WIDTH),
                    true);
            cachedHidden = s;
            cachedHiddenVer = v;
        }
        return s;
    }

    /** Style used by the general "show object boundaries" overlay. Never clickable. */
    public static BoxStyle boundaryStyle() {
        int v = styleVersion;
        BoxStyle s = cachedBoundary;
        if (s == null || cachedBoundaryVer != v) {
            s = new BoxStyle(strConf(NConfig.Key.bbDisplayMode, DEF_MODE),
                    NConfig.getColor(NConfig.Key.boxFillColor, DEF_FILL),
                    NConfig.getColor(NConfig.Key.boxEdgeColor, DEF_EDGE),
                    intConf(NConfig.Key.boxLineWidth, DEF_LINE_WIDTH),
                    false);
            cachedBoundary = s;
            cachedBoundaryVer = v;
        }
        return s;
    }

    private static BoxStyle styleFor(Gob gob) {
        if (gob.ngob != null && gob.ngob.isHidden())
            return hiddenStyle();
        return boundaryStyle();
    }

    public static class HidePol extends Sprite implements RenderTree.Node {
        private Pipe.Op lmat;
        private Pipe.Op emat;
        final Model emod;
        final Model lmod;
        private NBoundingBox.Polygon pol;

        static final VertexArray.Layout pfmt = new VertexArray.Layout(
                new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 12));

        public HidePol(NBoundingBox.Polygon pol) {
            super(null, null);
            this.pol = pol;

            VertexArray va = new VertexArray(pfmt,
                    new VertexArray.Buffer((4) * pfmt.inputs[0].stride, DataBuffer.Usage.STATIC,
                            this::fill));
            short[] iarr = {0,1,2,3,0};
            Model.Indices indb = new Model.Indices(5, NumberFormat.UINT16, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(iarr));
            this.emod = new Model(Model.Mode.TRIANGLE_FAN, va, null);
            this.lmod = new Model(Model.Mode.LINE_STRIP, va, indb);

            updateMaterials(boundaryStyle());
        }

        public void updateMaterials(BoxStyle style) {
            // Build outline material
            ArrayList<Pipe.Op> lineOps = new ArrayList<>();
            lineOps.add(new Rendered.Order.Default(6000));
            if (!style.depthTested()) {
                lineOps.add(States.Depthtest.none);
                lineOps.add(States.maskdepth);
            }
            lineOps.add(FragColor.blend(new BlendMode(BlendMode.Function.ADD,
                    BlendMode.Factor.SRC_ALPHA, BlendMode.Factor.INV_SRC_ALPHA,
                    BlendMode.Function.ADD, BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA)));
            lineOps.add(new States.Facecull());
            lineOps.add(new States.LineWidth(style.lineWidth));
            if (!style.clickable) {
                lineOps.add(Clickable.No);
            }
            lineOps.add(new BaseColor(style.edge));
            this.lmat = Pipe.Op.compose(lineOps.toArray(new Pipe.Op[0]));

            // Build fill material
            ArrayList<Pipe.Op> fillOps = new ArrayList<>();
            fillOps.add(new Rendered.Order.Default(6000));
            fillOps.add(FragColor.blend(new BlendMode(BlendMode.Function.ADD,
                    BlendMode.Factor.SRC_ALPHA, BlendMode.Factor.INV_SRC_ALPHA,
                    BlendMode.Function.ADD, BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA)));
            if (!style.clickable) {
                fillOps.add(Clickable.No);
            }
            fillOps.add(new BaseColor(style.fill));
            this.emat = Pipe.Op.compose(fillOps.toArray(new Pipe.Op[0]));
        }

        private FillBuffer fill(VertexArray.Buffer dst, Environment env) {
            FillBuffer ret = env.fillbuf(dst);
            ByteBuffer buf = ret.push();
            if (pol.neg) {
                for (int i = 3; i >= 0; i--) {
                    buf.putFloat((float) pol.vertices[i].x).putFloat((float) -pol.vertices[i].y)
                            .putFloat(1.0f);
                }
            } else {
                for (int i = 0; i < 4; i++) {
                    buf.putFloat((float) pol.vertices[i].x).putFloat((float) pol.vertices[i].y)
                            .putFloat(1.0f);
                }
            }
            return (ret);
        }

        public void added(RenderTree.Slot slot) {
            try {
                BoxStyle style = boundaryStyle();
                updateMaterials(style);
                if (style.filled()) {
                    slot.add(emod, emat);
                    slot.add(lmod, lmat);
                } else if (style.outlined()) {
                    slot.add(lmod, lmat);
                }
            } catch (haven.Defer.NotDoneException e) {
                // Texture not ready yet, will retry on next tick
            }
        }
    }

    private final NBoundingBox bb;

    boolean isShow = false;

    boolean isVisible = false;

    Gob gob;

    public NModelBox(Gob gob)
    {
        super(null, null);
        this.gob = gob;
        this.bb = NBoundingBox.getBoundingBox(gob.ngob.hitBox);
    }

    Collection<RenderTree.Node> nodes = new ArrayList<>();
    RenderTree.Slot slot = null;

    public void added(RenderTree.Slot slot)
    {
        this.slot = slot;
        if (nodes.isEmpty())
        {
            for (NBoundingBox.Polygon pol : bb.polygons)
            {
                nodes.add(new HidePol(pol));
            }
        }

    }

    /**
     * Updates materials for rendering the bounding box with new colors.
     */
    public void updateMaterials() {
        currentStyle = null;
        if (isVisible && slot != null) {
            refreshDisplay();
        }
    }

    BoxStyle currentStyle = null;
    float currentGobScale = 1f;

    /**
     * Gobs resized for display (hide stockpiles, or any type resized through the Configure
     * window) scale their whole render subtree, hitbox included. Undo that here so the box keeps
     * showing the real footprint. The two scales compose, so both are folded in.
     */
    private float gobScale() {
        float scale = 1f;
        nurgling.gattrr.NHideStockpileScale s = gob.getattr(nurgling.gattrr.NHideStockpileScale.class);
        if (s != null && s.scale > 0)
            scale *= s.scale;
        nurgling.gattrr.NGobCustomScale c = gob.getattr(nurgling.gattrr.NGobCustomScale.class);
        if (c != null && c.scale > 0)
            scale *= c.scale;
        return scale;
    }

    private Pipe.Op withScale(Pipe.Op mat, float scale) {
        return (scale == 1f) ? mat : Pipe.Op.compose(mat, Location.scale(1f / scale));
    }

    private void refreshDisplay() {
        if (!isVisible || slot == null) return;

        slot.clear();

        BoxStyle style = styleFor(gob);
        currentStyle = style;
        float scale = gobScale();

        for (RenderTree.Node n : nodes) {
            try {
                if (n instanceof HidePol) {
                    HidePol hidePol = (HidePol) n;
                    hidePol.updateMaterials(style);

                    if (style.filled()) {
                        slot.add(hidePol.emod, withScale(hidePol.emat, scale));
                        slot.add(hidePol.lmod, withScale(hidePol.lmat, scale));
                    } else if (style.outlined()) {
                        slot.add(hidePol.lmod, withScale(hidePol.lmat, scale));
                    }
                }
            } catch (RenderTree.SlotRemoved e) {
                // Ignore removed slots
            } catch (haven.Defer.NotDoneException e) {
                // Texture not ready yet, will retry on next tick
            }
        }
    }

    @Override
    public boolean tick(double dt) {
        boolean newShowState = ((Boolean) NConfig.get(NConfig.Key.showBB)
                || (gob.ngob != null && gob.ngob.isHidden()));

        // Re-resolve the style every tick: the box can flip between the hidden and the boundary
        // style without the show state changing at all (e.g. showBB on, then the gob gets hidden).
        boolean needsRefresh = false;
        if (newShowState) {
            BoxStyle style = styleFor(gob);
            if (!style.equals(currentStyle)) {
                currentStyle = style;
                needsRefresh = true;
            }
        }
        float scale = gobScale();
        if (scale != currentGobScale) {
            currentGobScale = scale;
            needsRefresh = true;
        }
        if (needsRefresh && isVisible) {
            refreshDisplay();
        }

        if (newShowState != isShow) {
            isShow = newShowState;
            if (isShow && slot != null && slot.parent() != null) {
                if (!isVisible) {
                    isVisible = true;
                    refreshDisplay();
                }
            } else {
                isVisible = false;
                if (slot != null) slot.clear();
            }
        }
        return super.tick(dt);
    }


    @Override
    public void draw(GOut g)
    {
        super.draw(g);
    }
}
