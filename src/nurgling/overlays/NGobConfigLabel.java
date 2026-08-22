package nurgling.overlays;

import haven.*;
import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.RenderTree;
import nurgling.tools.GobCustomize;

import java.awt.Color;
import java.awt.Font;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A caption drawn just under every gob of one resource type, set from the "Configure" window.
 *
 * <p>The text is read from {@link GobCustomize} on every frame rather than being baked into the
 * sprite, so editing it in the window updates every object of the type as it is typed.
 *
 * <p>Like {@link NGobConfigMarker} it removes itself instead of being removed - see that class for
 * why - by reporting done from {@link #tick} once the option is switched off or the text is cleared.
 */
public class NGobConfigLabel extends Sprite implements RenderTree.Node, PView.Render2D {
    private static final Text.Furnace fnd = new PUtils.BlurFurn(
            new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 14, Color.WHITE).aa(true),
            UI.scale(1), UI.scale(1), Color.BLACK);

    /**
     * Rendered captions, shared by every gob of every type using the same text. Typing a label
     * leaves a trail of half-finished words behind, hence the cap.
     */
    private static final Map<String, Text> cache = new ConcurrentHashMap<>();

    private static Text render(String text) {
        Text t = cache.get(text);
        if (t == null) {
            if (cache.size() > 64)
                cache.clear();
            t = fnd.render(text);
            cache.put(text, t);
        }
        return t;
    }

    private final String res;

    public NGobConfigLabel(Gob owner, String res) {
        super(owner, null);
        this.res = res;
    }

    private String text() {
        GobCustomize.Settings s = GobCustomize.settings(res);
        return s.label ? s.labelText : "";
    }

    @Override
    public boolean tick(double dt) {
        return text().isEmpty();
    }

    @Override
    public void draw(GOut g, Pipe state) {
        String text = text();
        if (text.isEmpty())
            return;
        // The gob's own origin sits at ground level, so top-anchoring the caption there puts it
        // directly under the object rather than over it.
        Coord sc = Homo3D.obj2view(Coord3f.o, state, Area.sized(g.sz())).round2();
        if (sc == null)
            return;
        g.aimage(render(text).tex(), sc.add(0, UI.scale(4)), 0.5, 0.0);
    }
}
