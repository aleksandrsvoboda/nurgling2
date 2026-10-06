package nurgling.styles;

import java.awt.*;
import java.awt.image.BufferedImage;

/** New UI replacements for named UI images, applied as each resource image is loaded.
 * Only explicitly named assets are replaced; sizes, offsets and resource IDs stay unchanged.
 * Resources are cached for the whole run, so the choice is made once: toggling New UI
 * takes effect for these images after a client restart. Never load Haven resources here
 * (this is called from Resource.Image). */
public final class UIResources {
    private UIResources() {}

    private static Boolean active;

    /** New UI as configured when the client started. Undecided until the config exists. */
    private static boolean active() {
        if(active == null) {
            if(haven.MainFrame.config == null)
                return false;
            active = UITheme.on();
        }
        return active;
    }

    private static final Color HOVER = new Color(255, 187, 112), PRESSED = new Color(201, 126, 59);

    private static String glyph(String name) {
        if(name.startsWith("nurgling/hud/buttons/lock/"))
            return name.endsWith("/d") || name.endsWith("/dh") ? "lock" : "unlock";
        if(name.startsWith("nurgling/hud/buttons/vis/"))
            return name.endsWith("/d") || name.endsWith("/dh") ? "eye-open" : "eye-closed";
        if(name.startsWith("nurgling/hud/icons/close/cross") || name.startsWith("nurgling/hud/buttons/square/cross/"))
            return "close";
        if(name.startsWith("nurgling/hud/buttons/settings/"))
            return "settings";
        if(name.startsWith("nurgling/hud/buttons/removeItem/"))
            return "trash";
        if(name.startsWith("nurgling/hud/buttons/inv/")) {
            String rest = name.substring("nurgling/hud/buttons/inv/".length());
            String kind = rest.substring(0, rest.indexOf('/') < 0 ? rest.length() : rest.indexOf('/'));
            switch(kind) {
            case "eye": return "expand";
            case "search": return "search";
            case "stacksort": return "grid";
            case "sort": return "sort";
            case "trash": return "trash";
            case "sortarrow": return rest.endsWith("/d") || rest.endsWith("/dh") ? "up" : "down";
            }
        }
        return null;
    }

    /** Hover and pressed variants of the close button, so it still gives feedback. */
    private static Color closeTint(String name) {
        if(name.endsWith("cross_hover") || name.endsWith("/h"))
            return HOVER;
        if(name.endsWith("cross_push") || name.endsWith("/d"))
            return PRESSED;
        return null;
    }

    private static void tint(BufferedImage img, Color color) {
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.SrcIn);
        g.setColor(color);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.dispose();
    }

    /** The replacement for a named UI image, or null to keep the original. */
    public static BufferedImage image(String name, int w, int h, float scale) {
        if(!active() || name == null)
            return null;
        if(name.equals("nurgling/hud/wnd/sizer") || name.startsWith("nurgling/hud/wnd/sizer/")) {
            BufferedImage result = GeneratedButtons.squareButtonImage("resize-corner", Math.min(w, h));
            tint(result, name.endsWith("/h") ? HOVER : name.endsWith("/d") ? PRESSED : UITheme.ACCENT);
            return result;
        }
        String glyph = glyph(name);
        if(glyph != null) {
            BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR);
            Graphics2D g = result.createGraphics();
            int side = Math.min(w, h);
            BufferedImage icon = GeneratedButtons.iconImage(glyph, side);
            Color state = glyph.equals("close") ? closeTint(name) : null;
            if(state != null)
                tint(icon, state);
            g.drawImage(icon, (w - side) / 2, (h - side) / 2, null);
            g.dispose();
            return result;
        }
        return null;
    }
}
