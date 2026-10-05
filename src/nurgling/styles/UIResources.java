package nurgling.styles;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;

/** Compatibility skin for image-based controls, including widgets loaded by the server.
 * Only explicitly named UI assets are replaced. Layer sizes, offsets and resource IDs
 * remain unchanged. Never load Haven resources here (called by Resource.Image).
 */
public final class UIResources {
    private UIResources() {}
    /** Remove the legacy brown menu matte before scaling, retaining the coloured artwork
     * and its black shadows. ICheckBox supplies the shared plate and hover/selected states. */
    public static BufferedImage menuIcon(String name, BufferedImage source) {
        if(!name.matches("nurgling/hud/buttons/rbtn/(inv|equ|chr|bud|opt|areas|cookbook|blueprints|baseplanner|todo|routes|encyclopedia)/u"))
            return source;
        int w = source.getWidth(), h = source.getHeight();
        int inset = Math.max(1, h / 15);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g = result.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        boolean[] seen = new boolean[w * h];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for(int y = inset; y < h-inset; y++) {
            pending.add(y*w + inset);
            pending.add(y*w + w-inset-1);
        }
        for(int x = inset; x < w-inset; x++) {
            pending.add(inset*w + x);
            pending.add((h-inset-1)*w + x);
        }
        while(!pending.isEmpty()) {
            int p = pending.removeFirst();
            if(seen[p]) continue;
            seen[p] = true;
            int x = p % w, y = p / w, rgb = source.getRGB(x, y);
            int r = (rgb >> 16) & 255, green = (rgb >> 8) & 255, b = rgb & 255;
            // Original matte is #422e09, with black drop shadows baked over it.
            if(r > 70 || green > 49 || b > 14 ||
               Math.abs(r - green * 66.0 / 46) > 3 || Math.abs(b - green * 9.0 / 46) > 3)
                continue;
            int alpha = Math.max(0, Math.min(255, Math.round(255 * (1 - green / 46f))));
            result.setRGB(x, y, alpha << 24);
            if(x > inset) pending.add(p-1);
            if(x < w-inset-1) pending.add(p+1);
            if(y > inset) pending.add(p-w);
            if(y < h-inset-1) pending.add(p+w);
        }
        return result;
    }
    private static final Set<String> PANELS = new HashSet<>(Arrays.asList(
        "nurgling/hud/wnd/bg", "nurgling/hud/wnd/bgl", "nurgling/hud/wnd/bgr",
        "gfx/hud/equip/bg", "gfx/hud/chantex", "gfx/hud/csearch-bg", "gfx/hud/lbtn-bg",
        "gfx/hud/hb-main", "gfx/hud/mmap/fgwdg", "nurgling/hud/chat/cbtng"));
    private static final Set<String> FRAMES = new HashSet<>(Arrays.asList(
        "gfx/hud/buffs/frame", "gfx/hud/buffs/cframe", "gfx/hud/bosq", "gfx/hud/brframe",
        "gfx/hud/chr/foodm", "gfx/hud/chr/glutm", "gfx/hud/chr/yrkirframe", "gfx/hud/chr/yrkirsframe",
        "gfx/hud/combat/indframe", "gfx/hud/combat/indbframe", "gfx/hud/combat/lastframe"));
    private static String controlGlyph(String name) {
        if(name.startsWith("nurgling/hud/buttons/rbtn/storage/")) return "storage-items";
        if(name.startsWith("nurgling/hud/buttons/lock/"))
            return name.endsWith("/d") || name.endsWith("/dh") ? "lock" : "unlock";
        if(name.startsWith("nurgling/hud/buttons/vis/"))
            return name.endsWith("/d") || name.endsWith("/dh") ? "eye-open" : "eye-closed";
        if(name.startsWith("nurgling/hud/icons/close/cross") ||
           name.startsWith("nurgling/hud/buttons/square/cross/") ||
           name.startsWith("nurgling/hud/sessions/close/")) return "close";
        if(name.startsWith("nurgling/hud/icons/ability/plus")) return "plus";
        if(name.startsWith("nurgling/hud/icons/ability/minus")) return "minus";
        if(name.startsWith("nurgling/hud/buttons/settings/")) return "settings";
        if(name.startsWith("nurgling/hud/buttons/removeItem/")) return "trash";
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
    public static BufferedImage image(String name, int w, int h, float scale) {
        if(name.equals("nurgling/hud/wnd/sizer") || name.startsWith("nurgling/hud/wnd/sizer/")) {
            BufferedImage result = GeneratedButtons.squareButtonImage("resize-corner", Math.min(w, h));
            Graphics2D g = result.createGraphics();
            g.setComposite(AlphaComposite.SrcIn);
            g.setColor(name.endsWith("/h") ? new Color(255, 187, 112) :
                       name.endsWith("/d") ? new Color(201, 126, 59) : UITheme.ACCENT);
            g.fillRect(0, 0, result.getWidth(), result.getHeight());
            g.dispose();
            return result;
        }
        String glyph = controlGlyph(name);
        if(glyph != null) {
            BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR);
            Graphics2D g = result.createGraphics();
            int side = Math.min(w, h);
            // The main-menu checkbox crops off its old bezel before drawing the icon.
            if(glyph.equals("storage-items")) side -= 2 * Math.max(1, side / 10);
            g.drawImage(GeneratedButtons.iconImage(glyph, side), (w-side)/2, (h-side)/2, null);
            g.dispose();
            return result;
        }
        boolean panel = PANELS.contains(name), frame = FRAMES.contains(name);
        boolean chat = name.equals("nurgling/hud/chat/csel") || name.equals("nurgling/hud/chat/lc") ||
            name.equals("nurgling/hud/chat/rc") || name.equals("nurgling/hud/chat/hori") || name.equals("nurgling/hud/chat/vert");
        if(!panel && !frame && !chat) return null;
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g = result.createGraphics();
        int b = Math.max(1, Math.round(scale));
        if(panel) { g.setColor(UITheme.PANEL); g.fillRect(0, 0, w, h); }
        else if(frame || chat) {
            g.setColor(name.endsWith("csel") ? UITheme.ACCENT : UITheme.LINE);
            if(chat && !name.endsWith("csel")) g.fillRect(0, 0, w, h);
            else {
                g.fillRect(0, 0, w, b); g.fillRect(0, h - b, w, b);
                g.fillRect(0, 0, b, h); g.fillRect(w - b, 0, b, h);
            }
        }
        g.dispose();
        return result;
    }

}
