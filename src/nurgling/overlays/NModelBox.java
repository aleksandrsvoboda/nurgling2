package nurgling.overlays;

import haven.*;
import haven.render.*;
import nurgling.*;
import nurgling.widgets.nsettings.World;

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
            if (hitBox != null)
            {
                ArrayList<Polygon> polygons = new ArrayList<>();
                Coord2d[] polyVertexes = new Coord2d[4];
                polyVertexes[0] = hitBox.begin.inv();
                polyVertexes[1] = new Coord2d(hitBox.end.x, hitBox.begin.y).inv();
                polyVertexes[2] = hitBox.end.inv();
                polyVertexes[3] = new Coord2d(hitBox.begin.x, hitBox.end.y).inv();
                polygons.add(new Polygon(polyVertexes));

                return new NBoundingBox(polygons, true);
            }
            else
            {
                return null;
            }
        }
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
            
            updateMaterials();
        }

        public void updateMaterials() {
            updateMaterials((String) NConfig.get(NConfig.Key.bbDisplayMode), false);
        }

        public void updateMaterials(String mode, boolean clickable) {
            if (mode == null) mode = "FILLED";

            Color fillColor = NConfig.getColor(NConfig.Key.boxFillColor, new Color(227, 28, 1, 195));
            Color edgeColor = NConfig.getColor(NConfig.Key.boxEdgeColor, new Color(224, 193, 79, 255));
            boolean outlineDepthTest = !mode.equals("OUTLINE_ALWAYS") && !mode.equals("FILLED_ALWAYS");

            // Build outline material
            ArrayList<Pipe.Op> lineOps = new ArrayList<>();
            lineOps.add(new Rendered.Order.Default(6000));
            if (!outlineDepthTest) {
                lineOps.add(States.Depthtest.none);
                lineOps.add(States.maskdepth);
            }
            lineOps.add(FragColor.blend(new BlendMode(BlendMode.Function.ADD,
                    BlendMode.Factor.SRC_ALPHA, BlendMode.Factor.INV_SRC_ALPHA,
                    BlendMode.Function.ADD, BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA)));
            lineOps.add(new States.Facecull());
            lineOps.add(new States.LineWidth((Integer) NConfig.get(NConfig.Key.boxLineWidth)));
            if (!clickable) {
                lineOps.add(Clickable.No);
            }
            lineOps.add(new BaseColor(edgeColor));
            this.lmat = Pipe.Op.compose(lineOps.toArray(new Pipe.Op[0]));

            // Build fill material
            ArrayList<Pipe.Op> fillOps = new ArrayList<>();
            fillOps.add(new Rendered.Order.Default(6000));
            fillOps.add(FragColor.blend(new BlendMode(BlendMode.Function.ADD,
                    BlendMode.Factor.SRC_ALPHA, BlendMode.Factor.INV_SRC_ALPHA,
                    BlendMode.Function.ADD, BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA)));
            if (!clickable) {
                fillOps.add(Clickable.No);
            }
            fillOps.add(new BaseColor(fillColor));
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
                String mode = (String) NConfig.get(NConfig.Key.bbDisplayMode);
                if (mode == null) mode = "FILLED";
                
                // Update materials for current mode
                updateMaterials(mode, false);
                
                if (mode.equals("FILLED") || mode.equals("FILLED_ALWAYS")) {
                    slot.add(emod, emat);
                    slot.add(lmod, lmat);
                } else if (mode.equals("OUTLINE") || mode.equals("OUTLINE_ALWAYS")) {
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
        if (isVisible && slot != null) {
            refreshDisplay();
        }
    }

    String currentDisplayMode = null;
    boolean currentClickable = false;

    private void refreshDisplay() {
        if (!isVisible || slot == null) return;

        slot.clear();

        String mode = (String) NConfig.get(NConfig.Key.bbDisplayMode);
        if (mode == null) mode = "FILLED";
        boolean clickable = gob.ngob.natureHidden;

        for (RenderTree.Node n : nodes) {
            try {
                if (n instanceof HidePol) {
                    HidePol hidePol = (HidePol) n;
                    hidePol.updateMaterials(mode, clickable);

                    if (mode.equals("FILLED") || mode.equals("FILLED_ALWAYS")) {
                        slot.add(hidePol.emod, hidePol.emat);
                        slot.add(hidePol.lmod, hidePol.lmat);
                    } else if (mode.equals("OUTLINE") || mode.equals("OUTLINE_ALWAYS")) {
                        slot.add(hidePol.lmod, hidePol.lmat);
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
        boolean newShowState = ((Boolean) NConfig.get(NConfig.Key.showBB) ||
                gob.ngob.natureHidden);

        String mode = (String) NConfig.get(NConfig.Key.bbDisplayMode);
        if (mode == null) mode = "FILLED";
        boolean clickable = gob.ngob.natureHidden;

        // Check if display mode or clickable state changed
        boolean needsRefresh = false;
        if (currentDisplayMode != null && !currentDisplayMode.equals(mode)) {
            currentDisplayMode = mode;
            needsRefresh = true;
        } else if (currentDisplayMode == null) {
            currentDisplayMode = mode;
        }
        if (clickable != currentClickable) {
            currentClickable = clickable;
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
