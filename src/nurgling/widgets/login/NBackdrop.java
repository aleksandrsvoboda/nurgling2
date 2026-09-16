package nurgling.widgets.login;

import haven.*;
import haven.render.Texture;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;

/**
 * Backdrop for the login and character-selection screens: the art drawn at its own size, exactly
 * as the plain Img used to draw it, with a dark scrim fading in from the left edge so the controls
 * sitting on it stay legible without a panel or window around them.
 */
public class NBackdrop extends Widget {
    public static final int SCRIMW = UI.scale(520);
    private static final Color SCRIM = new Color(17, 24, 25);
    private static Tex ramp = null;
    private final Supplier<Tex> art;
    private final int scrimw;

    /** The art is asked for on every frame, so a supplier that follows a setting just works. */
    public NBackdrop(Supplier<Tex> art, int scrimw) {
        super(Coord.z);
        this.art = art;
        this.scrimw = scrimw;
    }

    /** Scrim width at the current size; never more than half the backdrop. */
    public int scrimw() {
        return (Math.min(scrimw, sz.x / 2));
    }

    /* Mostly opaque for the first 60%, where the controls sit, then fading out into the art. */
    private static Tex ramp() {
        if (ramp == null) {
            BufferedImage img = new BufferedImage(256, 1, BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < img.getWidth(); x++) {
                double t = x / (double) (img.getWidth() - 1);
                double a = (t < 0.6) ? (0.93 - (0.07 * (t / 0.6))) : (0.86 * (1.0 - ((t - 0.6) / 0.4)));
                img.setRGB(x, 0, ((int) Math.round(a * 255) << 24) | (SCRIM.getRGB() & 0xffffff));
            }
            ramp = new TexI(img).magfilter(Texture.Filter.LINEAR);
        }
        return (ramp);
    }

    protected void added() {
        presize();
    }

    public void presize() {
        resize(parent.sz);
    }

    public void draw(GOut g) {
        Tex t = art.get();
        if (t != null)
            g.image(t, Coord.z);
        int w = scrimw();
        if (w > 0)
            g.image(ramp(), Coord.z, Coord.of(w, sz.y));
    }
}
