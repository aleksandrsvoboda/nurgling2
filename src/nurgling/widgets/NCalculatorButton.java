package nurgling.widgets;

import haven.*;
import nurgling.i18n.L10n;
import nurgling.styles.GeneratedButtons;
import nurgling.styles.UITheme;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Large atlas action using the shared button skin and a flat calculator glyph. */
public final class NCalculatorButton extends Button {
    public NCalculatorButton(Runnable action) {
        super(UI.scale(64),content());
        resize(UI.scale(64,64)); action(action);
        settip(L10n.get("flow.title"));
    }
    private static BufferedImage content() {
        int side=UI.scale(48);
        BufferedImage icon=GeneratedButtons.iconImage("quality-calculator",side);
        Graphics2D tint=icon.createGraphics();
        tint.setComposite(AlphaComposite.SrcIn); tint.setColor(UITheme.ACCENT);
        tint.fillRect(0,0,side,side); tint.dispose();
        return icon;
    }
}
