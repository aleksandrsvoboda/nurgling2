package nurgling.overlays;

import haven.Clickable;
import haven.Sprite;
import haven.render.BaseColor;
import haven.render.DataBuffer;
import haven.render.Homo3D;
import haven.render.Model;
import haven.render.NumberFormat;
import haven.render.Pipe;
import haven.render.Render;
import haven.render.RenderTree;
import haven.render.Rendered;
import haven.render.States;
import haven.render.VectorFormat;
import haven.render.VertexArray;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.areas.PileFillDirection;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;

/**
 * Ground-plane arrow at the centre of the selected zone showing which way it fills.
 *
 * It reads the same {@link PileFillDirection} the placement scan and the gob sort
 * read, so what the user sees cannot drift from what the bots do.
 *
 * Only drawn when the Areas window is open, this zone is the selected one, the zone
 * is loaded, and a direction has actually been chosen - a zone left on
 * {@link PileFillDirection#DEFAULT} shows nothing, since "default" has no direction
 * to point in.
 *
 * Purely diagnostic: no collision, no pathfinding, no influence on placement.
 */
public class NAreaDirectionArrow extends Sprite implements RenderTree.Node, Rendered {
    private static final VertexArray.Layout LAYOUT = new VertexArray.Layout(
            new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 12));
    private static final float Z_OFFSET = 0.5f;

    private final NArea area;
    private final Pipe.Op state;
    private final EnumMap<PileFillDirection, Model> models = new EnumMap<>(PileFillDirection.class);
    private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
    private volatile RenderKey renderKey;

    /**
     * What the last {@link #draw} decided to show. Kept so ticks only invalidate the
     * render slots when something actually changed.
     */
    private static class RenderKey {
        final boolean visible;
        final PileFillDirection direction;

        RenderKey(boolean visible, PileFillDirection direction) {
            this.visible = visible;
            this.direction = direction == null ? PileFillDirection.DEFAULT : direction;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof RenderKey))
                return false;
            RenderKey other = (RenderKey) object;
            return visible == other.visible && direction == other.direction;
        }

        @Override
        public int hashCode() {
            return (visible ? 1 : 0) * 31 + direction.hashCode();
        }
    }

    public NAreaDirectionArrow(Owner owner, NArea area) {
        super(owner, null);
        this.area = area;
        this.state = Pipe.Op.compose(
                new BaseColor(new Color(255, 190, 40, 185)),
                Clickable.No,
                new States.Facecull(States.Facecull.Mode.NONE),
                Pipe.Op.compose(Rendered.last, States.Depthtest.none, States.maskdepth)
        );
    }

    static boolean shouldDraw(boolean editorOpen, boolean selected, boolean locatable,
                              PileFillDirection direction) {
        return editorOpen && selected && locatable
                && direction != null && direction != PileFillDirection.DEFAULT;
    }

    /**
     * A shaft plus a head, lying flat, pointing along +x for LEFT_TO_RIGHT and
     * rotated in 90 degree steps for the others.
     */
    static float[] arrowVertices(PileFillDirection direction) {
        float[] vertices = new float[] {
                -9.5f, -2.5f, Z_OFFSET,   1.5f, -2.5f, Z_OFFSET,   1.5f, 2.5f, Z_OFFSET,
                -9.5f, -2.5f, Z_OFFSET,   1.5f, 2.5f, Z_OFFSET,   -9.5f, 2.5f, Z_OFFSET,
                1.5f, -5.5f, Z_OFFSET,   9.5f, 0.0f, Z_OFFSET,   1.5f, 5.5f, Z_OFFSET
        };
        PileFillDirection resolved = (direction == null ? PileFillDirection.DEFAULT : direction).forPlacement();
        for (int index = 0; index < vertices.length; index += 3) {
            float x = vertices[index];
            float y = vertices[index + 1];
            switch (resolved) {
                case RIGHT_TO_LEFT:
                    vertices[index] = -x;
                    vertices[index + 1] = -y;
                    break;
                case TOP_TO_BOTTOM:
                    vertices[index] = y;
                    vertices[index + 1] = -x;
                    break;
                case BOTTOM_TO_TOP:
                    vertices[index] = -y;
                    vertices[index + 1] = x;
                    break;
                default:
                    break;
            }
        }
        return vertices;
    }

    private Model modelFor(PileFillDirection direction) {
        PileFillDirection resolved = (direction == null ? PileFillDirection.DEFAULT : direction).forPlacement();
        Model model = models.get(resolved);
        if (model == null) {
            float[] vertices = arrowVertices(resolved);
            VertexArray.Buffer vbo = new VertexArray.Buffer(vertices.length * 4,
                    DataBuffer.Usage.STATIC, DataBuffer.Filler.of(vertices));
            model = new Model(Model.Mode.TRIANGLES, new VertexArray(LAYOUT, vbo), null);
            models.put(resolved, model);
        }
        return model;
    }

    void refreshRenderState(boolean editorOpen, boolean selected, boolean locatable,
                            PileFillDirection direction) {
        RenderKey next = new RenderKey(shouldDraw(editorOpen, selected, locatable, direction), direction);
        if (!next.equals(renderKey)) {
            renderKey = next;
            Collection<RenderTree.Slot> current;
            synchronized (slots) {
                current = new ArrayList<>(slots);
            }
            for (RenderTree.Slot slot : current) {
                try {
                    slot.update();
                } catch (RenderTree.SlotRemoved ignored) {
                }
            }
        }
    }

    @Override
    public boolean tick(double dt) {
        NGameUI gui = NUtils.getGameUI();
        boolean editorOpen = gui != null && gui.areas != null && gui.areas.visible();
        boolean selected = gui != null && gui.areas != null && gui.areas.al != null
                && gui.areas.al.sel != null && gui.areas.al.sel.area == area;
        boolean locatable = area != null && area.getRCArea(false) != null;
        refreshRenderState(editorOpen, selected, locatable,
                area == null ? null : area.pileFillDirection);
        return false;
    }

    @Override
    public void added(RenderTree.Slot slot) {
        slot.ostate(state);
        synchronized (slots) {
            slots.add(slot);
        }
    }

    @Override
    public void removed(RenderTree.Slot slot) {
        synchronized (slots) {
            slots.remove(slot);
        }
    }

    @Override
    public void draw(Pipe context, Render out) {
        RenderKey current = renderKey;
        if (current != null && current.visible)
            out.draw(context, modelFor(current.direction));
    }
}
