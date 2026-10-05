package nurgling.render;

import nurgling.i18n.L10n;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Rasterized at 10 Hz by the HUD, independent of the game window for previewing. */
public class FpsGraph {
    public static final int W = 420, H = 320;
    private static final Color GOOD = new Color(74, 205, 130), WARN = new Color(235, 190, 57), BAD = new Color(235, 83, 75);
    /** Also used by the offline preview; graph coordinates represent elapsed time. */
    public static BufferedImage render(FrameHistory.Snapshot s, String backend, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        render(s,backend,img);
        return img;
    }
    /** Worker-owned raster can be reused; reset alpha as well as RGB each time. */
    static void render(FrameHistory.Snapshot s,String backend,BufferedImage img) {
        int width=img.getWidth(),height=img.getHeight();
        Graphics2D g = img.createGraphics();
        try {
            g.scale(width / (double)W, height / (double)H);
            g.setComposite(java.awt.AlphaComposite.Src);
            g.setColor(new Color(17, 20, 24, 245)); g.fillRect(0, 0, W, H);
            g.setComposite(java.awt.AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            g.setColor(new Color(105, 214, 155));
            g.drawString(String.format("FPS  %.1f", s.fps), 10, 22);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.setColor(new Color(180, 194, 207));
            g.drawString(backend + "  |  " + L10n.get("fps.client"), 160, 21);
            g.setColor(new Color(225, 230, 235));
            g.drawString(String.format(L10n.get("fps.stats"), s.average, s.minimum, s.low), 10, 43);
            g.drawString(String.format(L10n.get("fps.times"), s.frameMs, s.p99Ms, s.maxMs), 10, 62);
            g.setColor(new Color(164, 175, 187));
            g.drawString(L10n.get("fps.history"), 10, 82);
            double fpsTop = 60;
            for(double dt : s.seconds) fpsTop = Math.max(fpsTop, Math.ceil((1 / dt) / 60 - 1e-6) * 60);
            graph(g, s, 101, 82, fpsTop, false);
            g.setColor(new Color(164, 175, 187));
            g.drawString(String.format(L10n.get("fps.spikes"), s.slow), 10, 205);
            double msTop = Math.max(50, Math.ceil(s.maxMs / 50) * 50);
            graph(g, s, 224, 68, msTop, true);
            g.setColor(new Color(164, 175, 187));
            g.drawString("-10 s", 49, 311);
            g.drawString("-5 s", 222, 311);
            g.drawString(L10n.get("fps.now"), 370, 311);
        } finally {
            g.dispose();
        }
    }

    private static void graph(Graphics2D g, FrameHistory.Snapshot s, int top, int height, double limit, boolean ms) {
        final int left = 49, width = 359;
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        double middle = ms ? 1000.0 / 60 : 30;
        for(double value : new double[]{0, middle, limit}) {
            int y = top + height - (int)Math.round(value / limit * height);
            g.setColor(new Color(47, 57, 67)); g.drawLine(left, y, left + width, y);
            g.setColor(new Color(146, 159, 172));
            g.drawString(value == middle && ms ? "16.7" : String.format("%.0f", value), 10, y + 4);
        }
        int px = -1, py = 0;
        for(int i = 0; i < s.seconds.length; i++) {
            int x = left + (int)Math.round((s.times[i] - (s.now - FrameHistory.SECONDS)) / FrameHistory.SECONDS * width);
            x = Math.max(left, Math.min(left + width, x));
            double value = ms ? s.seconds[i] * 1000 : 1 / s.seconds[i];
            int y = top + height - (int)Math.round(Math.min(limit, value) / limit * height);
            double fps = 1 / Math.max(s.seconds[i], i == 0 ? s.seconds[i] : s.seconds[i - 1]);
            g.setColor(fps < 30 ? BAD : fps < 55 ? WARN : GOOD);
            // Draw every sample, including vertical ranges sharing a pixel: do not average away spikes.
            if(px >= 0) g.drawLine(px, py, x, y);
            else g.drawLine(x, y, x, y);
            px = x; py = y;
        }
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
    }
}
