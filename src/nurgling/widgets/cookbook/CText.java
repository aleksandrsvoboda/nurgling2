package nurgling.widgets.cookbook;

import haven.*;

import java.awt.Color;

/** A line of text that re-renders only when its text or colour changes, and sizes itself to fit. */
public class CText extends Widget {
    private final Text.Foundry fnd;
    private Color color;
    private String text = null;
    private Tex tex = null;

    public CText(Text.Foundry fnd, Color color) {
        super(Coord.z);
        this.fnd = fnd;
        this.color = color;
    }

    public CText set(String text) {
        return set(text, color);
    }

    public CText set(String text, Color color) {
        String t = (text == null) ? "" : text;
        if(t.equals(this.text) && color.equals(this.color))
            return this;
        this.text = t;
        this.color = color;
        tex = t.isEmpty() ? null : fnd.render(t, color).tex();
        resize((tex != null) ? tex.sz() : Coord.z);
        return this;
    }

    public String text() {
        return (text == null) ? "" : text;
    }

    @Override
    public void draw(GOut g) {
        if(tex != null)
            g.image(tex, Coord.z);
    }
}
