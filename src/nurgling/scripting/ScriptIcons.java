package nurgling.scripting;

import haven.Resource;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Turns a script's own PNG into bot-menu button images (up, down, hover) that look like the
 * built-in ones: the user's picture is scaled into the standard square frame of the "script" icon.
 */
public final class ScriptIcons {
    /** The frame the built-in bot icons have: 2 px on each side of a 32 px button. */
    private static final int INSET = 2;
    private static final String[] STATES = {"u", "d", "h"};
    /** Pressed buttons are drawn darker and hovered ones lighter, as the built-in icon variants are. */
    private static final float[] SHADE = {1.0f, 0.6f, 1.15f};

    /** Up, down and hover images for {@code png}, or null if it cannot be read. */
    public static BufferedImage[] load(File png) {
        BufferedImage src;
        try {
            src = ImageIO.read(png);
        } catch (IOException e) {
            System.out.println("[Scripting] cannot read icon " + png + ": " + e.getMessage());
            return null;
        }
        if (src == null) {
            System.out.println("[Scripting] " + png + " is not an image ImageIO can read (use PNG)");
            return null;
        }
        BufferedImage[] res = new BufferedImage[3];
        for (int i = 0; i < 3; i++)
            res[i] = compose(Resource.loadsimg("nurgling/bots/icons/script/" + STATES[i]), src, SHADE[i]);
        return res;
    }

    private static BufferedImage compose(BufferedImage frame, BufferedImage pic, float shade) {
        int w = frame.getWidth(), h = frame.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(frame, 0, 0, null);
        // Clear the frame's own picture: fill the inside with the frame's background colour.
        g.setColor(new Color(frame.getRGB(INSET + 1, INSET + 1), true));
        g.fillRect(INSET, INSET, w - 2 * INSET, h - 2 * INSET);
        // Fit the picture inside, keeping its aspect ratio.
        int iw = w - 2 * INSET, ih = h - 2 * INSET;
        double s = Math.min((double) iw / pic.getWidth(), (double) ih / pic.getHeight());
        int pw = Math.max(1, (int) Math.round(pic.getWidth() * s)), ph = Math.max(1, (int) Math.round(pic.getHeight() * s));
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(shaded(pic, shade), INSET + (iw - pw) / 2, INSET + (ih - ph) / 2, pw, ph, null);
        g.dispose();
        return out;
    }

    private static BufferedImage shaded(BufferedImage pic, float f) {
        if (f == 1.0f) return pic;
        BufferedImage out = new BufferedImage(pic.getWidth(), pic.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < pic.getHeight(); y++) {
            for (int x = 0; x < pic.getWidth(); x++) {
                int argb = pic.getRGB(x, y);
                int r = Math.min(255, (int) (((argb >> 16) & 0xff) * f));
                int g = Math.min(255, (int) (((argb >> 8) & 0xff) * f));
                int b = Math.min(255, (int) ((argb & 0xff) * f));
                out.setRGB(x, y, (argb & 0xff000000) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private ScriptIcons() {}
}
