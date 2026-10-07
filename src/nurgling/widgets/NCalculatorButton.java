package nurgling.widgets;

import haven.*;
import nurgling.NStyle;
import nurgling.i18n.L10n;
import nurgling.styles.GeneratedButtons;
import nurgling.styles.UITheme;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Atlas action opening the quality calculator: a square icon button with a flat calculator glyph. */
public final class NCalculatorButton extends Button {
    public NCalculatorButton(Runnable action) {
        // Same footprint as the other square icon buttons (e.g. the tunneler's direction buttons).
        super(UI.scale(SIDE),content());
        resize(UI.scale(SIDE,SIDE)); action(action);
        settip(L10n.get("flow.title"));
    }
    /* The text-button art only covers a normal button's height, so this square draws its own. */
    @Override
    public void draw(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        if(UITheme.on()) {
            GeneratedButtons.plate(g, sz.x, sz.y, GeneratedButtons.state(hovered(), pressed(), false, false));
        } else {
            int b = Math.max(1, UI.scale(1));
            g.setColor(NStyle.infoBg);
            g.fillRect(0, 0, sz.x, sz.y);
            g.setColor(NStyle.border);
            g.fillRect(0, 0, sz.x, b); g.fillRect(0, sz.y - b, sz.x, b);
            g.fillRect(0, 0, b, sz.y); g.fillRect(sz.x - b, 0, b, sz.y);
        }
        int off = pressed() ? UI.scale(1) : 0;
        g.drawImage(cont, (sz.x - cont.getWidth()) / 2 + off, (sz.y - cont.getHeight()) / 2 + off, null);
        g.dispose();
    }

    private static final int SIDE = 28;

    private static BufferedImage content() {
        int side=UI.scale(20);
        BufferedImage icon=GeneratedButtons.iconImage("quality-calculator",side);
        Graphics2D tint=icon.createGraphics();
        tint.setComposite(AlphaComposite.SrcIn); tint.setColor(UITheme.ACCENT);
        tint.fillRect(0,0,side,side); tint.dispose();
        return icon;
    }
}
