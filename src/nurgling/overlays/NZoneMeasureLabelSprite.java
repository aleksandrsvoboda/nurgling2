package nurgling.overlays;

import haven.*;
import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.RenderTree;

import java.awt.*;

/**
 * Sprite for rendering zone dimension labels in 3D space.
 * Extends Sprite to be added to virtual Gobs for render tree integration.
 */
public class NZoneMeasureLabelSprite extends Sprite implements RenderTree.Node, PView.Render2D {
    private static final Text.Foundry labelFnd = new Text.Foundry(Text.sans, 16).aa(true);
    private static final Text.Foundry countFnd = new Text.Foundry(Text.sans, 12).aa(true);

    protected Coord3f pos;
    private TexI label;

    // Written by the measure tool, rendered lazily by draw()
    private volatile int gobCount = -1;
    private int renderedCount = -1;
    private TexI countLabel;

    public NZoneMeasureLabelSprite(Owner owner, int width, int height) {
        super(owner, null);
        this.pos = new Coord3f(0, 0, 5);
        this.label = new TexI(labelFnd.render(width + "x" + height, Color.WHITE).img);
    }

    public void setGobCount(int count) {
        this.gobCount = count;
    }

    @Override
    public boolean tick(double dt) {
        // Return true to remove, false to keep
        return false;
    }

    @Override
    public void draw(GOut g, Pipe state) {
        Coord sc = Homo3D.obj2view(pos, state, Area.sized(g.sz())).round2();
        if (sc != null && label != null) {
            updateCountLabel();
            drawLabelWithBackground(g, sc);
        }
    }

    private void updateCountLabel() {
        int count = gobCount;
        if (count == renderedCount)
            return;
        if (countLabel != null)
            countLabel.dispose();
        countLabel = (count < 0) ? null
                : new TexI(countFnd.render(count + (count == 1 ? " gob" : " gobs"), new Color(220, 220, 220)).img);
        renderedCount = count;
    }

    private void drawLabelWithBackground(GOut g, Coord pos) {
        Coord sz = label.sz();
        if (countLabel != null)
            sz = new Coord(Math.max(sz.x, countLabel.sz().x), sz.y + countLabel.sz().y);
        Coord bgUL = pos.sub(sz.div(2)).sub(3, 2);
        Coord bgBR = pos.add(sz.div(2)).add(3, 2);

        g.chcolor(new Color(0, 0, 0, 200));
        g.frect2(bgUL, bgBR);
        g.chcolor();

        int top = pos.y - sz.y / 2;
        g.aimage(label, new Coord(pos.x, top), 0.5, 0);
        if (countLabel != null)
            g.aimage(countLabel, new Coord(pos.x, top + label.sz().y), 0.5, 0);
    }
}
