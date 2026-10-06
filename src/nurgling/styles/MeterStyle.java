package nurgling.styles;

import haven.*;
import java.awt.Color;
import java.awt.Font;

/** New UI chrome for the HUD meters (health, stamina, energy, water...): a flat bar and
 * frame, the original meter icon, and bold outlined text that stays readable on every
 * bar colour. */
public final class MeterStyle {
    private MeterStyle() {}

    private static final Text.Furnace text = new PUtils.BlurFurn(
        new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 13, Color.WHITE).aa(true), UI.scale(2), UI.scale(2), Color.BLACK);
    /** Width of the icon at the left of every meter image. */
    private static final int ICON_W = UI.scale(30);

    public static Tex renderText(String value) {
        return text.render(value.trim()).tex();
    }

    public static void background(GOut g, Color color) {
        UITheme.panel(g, IMeter.off, IMeter.msz, color, null);
    }

    /** Frame, original icon and text over a bar already filled at IMeter.off/msz. */
    public static void finish(GOut g, Tex original, Tex text) {
        Coord off = IMeter.off, size = IMeter.msz;
        int edge = Math.max(1, UI.scale(2));
        GeneratedButtons.frame(g, off.sub(edge, edge), size.add(edge, edge * 2), GeneratedButtons.State.SELECTED);
        g.image(original, Coord.z, Coord.z, new Coord(ICON_W, original.sz().y));
        if(text != null)
            g.image(text, off.add(size.sub(text.sz()).div(2)));
    }
}
