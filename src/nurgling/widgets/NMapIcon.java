package nurgling.widgets;

import haven.*;
import nurgling.styles.GeneratedButtons;
import nurgling.styles.UITheme;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/** ImageGen glyphs with identical, pixel-aligned square map button frames. */
public class NMapIcon extends ICheckBox {
    private static final Map<String, Tex> textures = new HashMap<>();

    public NMapIcon(String glyph) {
        super(texture(glyph, false, false), texture(glyph, true, false),
              texture(glyph, false, true), texture(glyph, true, true));
    }

    private static synchronized Tex texture(String glyph, boolean active, boolean hover) {
        String key = glyph + "/" + UI.scale(24) + "/" + active + "/" + hover;
        return textures.computeIfAbsent(key, k -> new TexI(buttonImage(glyph, active, hover)));
    }

    public static BufferedImage buttonImage(String glyph, boolean active, boolean hover) {
        int side = UI.scale(24), edge = Math.max(1, UI.scale(1)), iconSide = UI.scale(18);
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g = image.createGraphics();
        g.setColor(active ? new Color(90, 64, 38) : UITheme.PANEL);
        g.fillRect(0, 0, side, side);
        if(hover) { g.setColor(new Color(255, 255, 255, 24)); g.fillRect(0, 0, side, side); }
        BufferedImage icon = GeneratedButtons.iconImage("map-" + glyph, iconSide);
        Graphics2D ig = icon.createGraphics();
        ig.setComposite(AlphaComposite.SrcIn);
        ig.setColor(hover ? UITheme.ACCENT.brighter() : UITheme.ACCENT);
        ig.fillRect(0, 0, iconSide, iconSide);
        ig.dispose();
        g.drawImage(icon, (side - iconSide) / 2, (side - iconSide) / 2, null);
        g.setColor(hover ? UITheme.ACCENT.brighter() : UITheme.ACCENT);
        g.fillRect(0, 0, side, edge); g.fillRect(0, side - edge, side, edge);
        g.fillRect(0, 0, edge, side); g.fillRect(side - edge, 0, edge, side);
        g.dispose();
        return image;
    }
}
