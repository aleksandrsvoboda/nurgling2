package nurgling.scripting.ui;

import haven.*;
import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.RenderTree;

import java.awt.Color;

/** Outlined text floating above a gob (or a virtual gob standing at a world position). */
public class ScriptLabel extends Sprite implements RenderTree.Node, PView.Render2D {
    private final Coord3f pos;
    private final String text;
    private final Color color;
    private Tex tex;

    public ScriptLabel(Owner owner, String text, Color color, float height) {
        super(owner, null);
        this.pos = new Coord3f(0, 0, height);
        this.text = text;
        this.color = color;
    }

    @Override
    public void draw(GOut g, Pipe state) {
        if (tex == null) tex = Text.renderstroked(text, color, Color.BLACK).tex();
        Coord sc = Homo3D.obj2view(pos, state, Area.sized(g.sz())).round2();
        g.aimage(tex, sc, 0.5, 1.0);
    }

    @Override
    public void dispose() {
        if (tex != null) tex.dispose();
        tex = null;
        super.dispose();
    }
}
