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
    public static boolean active() {
        if(active == null) {
            Object on = nurgling.NConfig.get(nurgling.NConfig.Key.newUi);
            if(on == null)
                return false;
            active = !Boolean.FALSE.equals(on);
        }
        return active;
    }

    private static final Color HOVER = new Color(255, 187, 112), PRESSED = new Color(201, 126, 59);
    private static final Color SQUARE_HIT = new Color(UITheme.PANEL.getRed(), UITheme.PANEL.getGreen(), UITheme.PANEL.getBlue(), 128);

    /* Textured backgrounds that become the flat panel colour. */
    private static final java.util.Set<String> PANELS = new java.util.HashSet<>(java.util.Arrays.asList(
        "nurgling/hud/wnd/bg", "nurgling/hud/wnd/bgl", "nurgling/hud/wnd/bgr",
        "gfx/hud/chantex", "gfx/hud/csearch-bg", "gfx/hud/lbtn-bg",
        "gfx/hud/hb-main", "gfx/hud/mmap/fgwdg", "nurgling/hud/chat/cbtng"));
    /* Ornate frames that become a flat 1px line. */
    private static final java.util.Set<String> FRAMES = new java.util.HashSet<>(java.util.Arrays.asList(
        "gfx/hud/buffs/frame", "gfx/hud/buffs/cframe", "gfx/hud/bosq", "gfx/hud/brframe",
        "gfx/hud/chr/foodm", "gfx/hud/chr/glutm", "gfx/hud/chr/yrkirframe", "gfx/hud/chr/yrkirsframe",
        "gfx/hud/combat/indframe", "gfx/hud/combat/indbframe", "gfx/hud/combat/lastframe"));
    /* Chat chrome: dividers become flat lines, the selected-channel marker orange. */
    private static final java.util.Set<String> CHAT = new java.util.HashSet<>(java.util.Arrays.asList(
        "nurgling/hud/chat/csel", "nurgling/hud/chat/lc", "nurgling/hud/chat/rc",
        "nurgling/hud/chat/hori", "nurgling/hud/chat/vert"));

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
            // Clicks are tested against image opacity: a half-opaque backing makes the whole
            // square clickable, not just the glyph's own pixels. The window close cross stays
            // bare; its buttons take clicks on their whole square and highlight it on hover.
            if(!name.startsWith("nurgling/hud/icons/close/cross")) {
                g.setColor(SQUARE_HIT);
                g.fillRect(0, 0, w, h);
            }
            int side = Math.min(w, h);
            BufferedImage icon = GeneratedButtons.iconImage(glyph, side);
            Color state = glyph.equals("close") ? closeTint(name) : null;
            if(state != null)
                tint(icon, state);
            g.drawImage(icon, (w - side) / 2, (h - side) / 2, null);
            g.dispose();
            return result;
        }
        boolean panel = PANELS.contains(name), frame = FRAMES.contains(name), chat = CHAT.contains(name);
        if(!panel && !frame && !chat)
            return null;
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g = result.createGraphics();
        int b = Math.max(1, Math.round(scale));
        if(panel) {
            g.setColor(UITheme.PANEL);
            g.fillRect(0, 0, w, h);
        } else if(chat && !name.endsWith("csel")) {
            g.setColor(UITheme.LINE);
            g.fillRect(0, 0, w, h);
        } else {
            g.setColor(name.endsWith("csel") ? UITheme.ACCENT : UITheme.LINE);
            g.fillRect(0, 0, w, b); g.fillRect(0, h - b, w, b);
            g.fillRect(0, 0, b, h); g.fillRect(w - b, 0, b, h);
        }
        g.dispose();
        return result;
    }
}
