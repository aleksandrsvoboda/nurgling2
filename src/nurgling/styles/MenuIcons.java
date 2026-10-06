package nurgling.styles;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;

/** Main-menu button art: the classic buttons are a coloured glyph on a brown matte
 * (#422e09, with black drop shadows baked in) inside a frame. */
public final class MenuIcons {
    private MenuIcons() {}
    private static final int MATTE = 0x422e09;

    private static int inset(BufferedImage source) {
        return Math.max(1, source.getHeight() / 15);
    }

    /** Flood-fills the matte from the inner edge of the frame, so glyph pixels of a similar
     * colour that do not touch the matte are kept. */
    private static boolean[] matte(BufferedImage source) {
        int w = source.getWidth(), h = source.getHeight(), inset = inset(source);
        boolean[] seen = new boolean[w * h], hit = new boolean[w * h];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for(int y = inset; y < h - inset; y++) {
            pending.add(y * w + inset);
            pending.add(y * w + w - inset - 1);
        }
        for(int x = inset; x < w - inset; x++) {
            pending.add(inset * w + x);
            pending.add((h - inset - 1) * w + x);
        }
        while(!pending.isEmpty()) {
            int p = pending.removeFirst();
            if(seen[p]) continue;
            seen[p] = true;
            int x = p % w, y = p / w, rgb = source.getRGB(x, y);
            int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            if(r > 70 || g > 49 || b > 14 ||
               Math.abs(r - g * 66.0 / 46) > 3 || Math.abs(b - g * 9.0 / 46) > 3)
                continue;
            hit[p] = true;
            if(x > inset) pending.add(p - 1);
            if(x < w - inset - 1) pending.add(p + 1);
            if(y > inset) pending.add(p - w);
            if(y < h - inset - 1) pending.add(p + w);
        }
        return hit;
    }

    /** The glyph alone: the matte becomes transparent, its shadows keep their darkness as alpha. */
    public static BufferedImage stripMatte(BufferedImage source) {
        int w = source.getWidth(), h = source.getHeight();
        boolean[] hit = matte(source);
        BufferedImage result = copy(source);
        for(int p = 0; p < hit.length; p++) {
            if(!hit[p]) continue;
            int green = (source.getRGB(p % w, p / w) >> 8) & 255;
            int alpha = Math.max(0, Math.min(255, Math.round(255 * (1 - green / 46f))));
            result.setRGB(p % w, p / w, alpha << 24);
        }
        return result;
    }

    /** The frame and matte alone: everything inside the frame becomes plain matte. */
    public static BufferedImage emptyPlate(BufferedImage source) {
        int w = source.getWidth(), h = source.getHeight(), inset = inset(source);
        BufferedImage result = copy(source);
        Graphics2D g = result.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(MATTE));
        g.fillRect(inset, inset, w - inset * 2, h - inset * 2);
        g.dispose();
        return result;
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D g = result.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return result;
    }
}
