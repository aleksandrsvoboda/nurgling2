package nurgling.widgets.quest;

import haven.*;
import nurgling.widgets.NToolbarToggle;

import java.awt.image.BufferedImage;

/**
 * The up/down chevron of the quest tracker's minimize control, in either UI look.
 *
 * The new UI uses the generated {@code up}/{@code down} glyphs. The classic look turns the
 * minimap's collapse button art (a right-pointing chevron) a quarter turn, so the two toolbars
 * share one style and no new resources are needed.
 */
final class QuestFoldArt
{
    private static final String CLASSIC = "nurgling/hud/buttons/toggle_panel/collapse";
    /** up, up hovered, down, down hovered; built on first use (UI thread only). */
    private static Tex[] classic;

    private QuestFoldArt() {}

    static void draw(GOut g, Coord ul, Coord sz, boolean down, boolean hover, boolean flat)
    {
        if(flat) {
            NToolbarToggle.drawGlyph(g.reclip(ul, sz), sz, down ? "down" : "up", hover, false);
            return;
        }
        g.image(classic()[(down ? 2 : 0) + (hover ? 1 : 0)], ul, sz);
    }

    /** Native size of the classic art, which matches the other classic toolbar buttons. */
    static Coord classicSize()
    {
        return classic()[0].sz();
    }

    private static Tex[] classic()
    {
        if(classic == null) {
            BufferedImage u = Resource.loadrimg(CLASSIC + "/u").scaled();
            BufferedImage h = Resource.loadrimg(CLASSIC + "/h").scaled();
            classic = new Tex[] {
                new TexI(quarter(u, false)), new TexI(quarter(h, false)),
                new TexI(quarter(u, true)), new TexI(quarter(h, true)),
            };
        }
        return classic;
    }

    /** A quarter turn: clockwise makes the right-pointing chevron point down, counter-clockwise up. */
    private static BufferedImage quarter(BufferedImage src, boolean clockwise)
    {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(h, w, BufferedImage.TYPE_INT_ARGB);
        for(int y = 0; y < h; y++) {
            for(int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                if(clockwise)
                    out.setRGB(h - 1 - y, x, argb);
                else
                    out.setRGB(y, w - 1 - x, argb);
            }
        }
        return out;
    }
}
