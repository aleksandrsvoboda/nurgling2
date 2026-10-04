package nurgling.widgets;

import haven.Coord;
import haven.IButton;
import haven.UI;
import nurgling.styles.GeneratedButtons;
import nurgling.styles.UITheme;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Borderless ImageGen icon with a full square mouse target and centered artwork. */
public class NIconButton extends IButton {
    public NIconButton(String glyph, int buttonSize, int glyphSize) {
        super(state(glyph, buttonSize, glyphSize, 0), state(glyph, buttonSize, glyphSize, 2),
              state(glyph, buttonSize, glyphSize, 1), null);
    }

    private static BufferedImage state(String glyph, int buttonSize, int glyphSize, int state) {
        int size = UI.scale(buttonSize), iconSize = UI.scale(glyphSize);
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g = result.createGraphics();
        if(state != 0) {
            g.setColor(new Color(255, 255, 255, state == 2 ? 45 : 25));
            g.fillRect(0, 0, size, size);
        }
        BufferedImage icon = GeneratedButtons.iconImage(glyph, iconSize);
        Graphics2D tint = icon.createGraphics();
        tint.setComposite(AlphaComposite.SrcIn);
        tint.setColor(UITheme.ACCENT);
        tint.fillRect(0, 0, iconSize, iconSize);
        tint.dispose();
        g.drawImage(icon, (size - iconSize) / 2, (size - iconSize) / 2, null);
        g.dispose();
        return result;
    }

    @Override public boolean checkhit(Coord c) { return c.isect(Coord.z, sz); }
}
