package nurgling.widgets.login;

import haven.*;
import nurgling.conf.FontSettings;

/** A text link in the accent colour, underlined on hover; the links plugins add under the login form and the character list. */
public class NActionLink extends Widget {
    private static final Text.Foundry fnd = new Text.Foundry(FontSettings.getOpenSansSemibold(), 12, NLoginTheme.accent).aa(true);
    private final Text text;
    private final Runnable action;
    private boolean hover = false;

    public NActionLink(String label, Runnable action) {
        super(Coord.z);
        this.text = fnd.render(label);
        this.action = action;
        resize(text.sz().add(0, UI.scale(2)));
    }

    public void draw(GOut g) {
        g.image(text.tex(), Coord.z);
        if (hover) {
            g.chcolor(NLoginTheme.accent);
            g.frect(Coord.of(0, text.sz().y), Coord.of(text.sz().x, UI.scale(1)));
            g.chcolor();
        }
    }

    public void mousemove(MouseMoveEvent ev) {
        hover = ev.c.isect(Coord.z, sz);
    }

    public boolean mousedown(MouseDownEvent ev) {
        if (ev.b == 1) {
            action.run();
            return (true);
        }
        return (super.mousedown(ev));
    }
}
