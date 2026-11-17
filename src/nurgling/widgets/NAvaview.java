package nurgling.widgets;

import haven.*;
import nurgling.widgets.login.NLoginTheme;

/**
 * Avatar view that shows a spinner while the character's resources are still loading, instead of
 * haven's "missing" question mark stretched across the whole view.
 * <p>
 * Only the pop-based views (the character-selection screen) are treated this way: when the view
 * follows a gob, haven draws the flat Avatar images first and this class stays out of the way.
 * Thumbnail-sized views simply stay empty - eight spinning rows would be worse than the wait.
 */
public class NAvaview extends Avaview {
    /** Views smaller than this get no spinner, just empty space. */
    private static final int SPINMIN = UI.scale(80);
    /** Set on the character-selection screen's views: they always show the plague skin. */
    public boolean charsel = false;
    /** The plague pattern shown on a selection-screen view; see {@link nurgling.tools.PlagueSkin#seed}. */
    public long plagueSeed = 0;
    private boolean moodlit = false;
    /** Whether the plague skin was on when this view was last built; see {@link #tick}. */
    private boolean plagueShown = false;

    public NAvaview(Coord sz, long avagob, String camnm) {
        super(sz, avagob, camnm);
    }

    /** Marks this as a selection-screen view showing {@code name}'s plague pattern. */
    public void plague(String name) {
        long seed = nurgling.tools.PlagueSkin.seed(name);
        if (!charsel || (seed != plagueSeed)) {
            charsel = true;
            plagueSeed = seed;
            rebuild();
        }
    }

    /**
     * Lights the character like the character screen's art instead of haven's plain white light. The
     * lights are fixed to the camera, not the model, so they keep their place on screen (and the
     * shading moves when the character is spun): a dim greenish ambient with a pale yellow-green
     * key from the upper left, where the art's moon is; a faint cool rim from behind the upper left
     * that keeps the silhouette off the dark trees; and a weak green fill from the lower right. The
     * lights are weak in red and blue, which dulls saturated clothes to the art's muted palette.
     */
    public void moodLight() {
        if (moodlit)
            return;
        moodlit = true;
        if (light != null)
            light.remove();
        light = basic.add(new EyeLight(new java.awt.Color(38, 46, 36), new java.awt.Color(96, 107, 80), new java.awt.Color(18, 21, 16),
                new Coord3f(-0.55f, 0.6f, 0.6f)), null);
        basic.add(new EyeLight(java.awt.Color.BLACK, new java.awt.Color(38, 58, 62), java.awt.Color.BLACK,
                new Coord3f(-0.6f, 0.5f, -0.7f)), null);
        basic.add(new EyeLight(java.awt.Color.BLACK, new java.awt.Color(24, 43, 33), java.awt.Color.BLACK,
                new Coord3f(0.7f, -0.2f, 0.5f)), null);
    }

    /**
     * A directional light whose direction is in eye space (x right, y up, z towards the viewer),
     * pointing at the light, instead of being turned with the scene like a plain DirLight.
     */
    private static class EyeLight extends DirLight {
        EyeLight(java.awt.Color amb, java.awt.Color dif, java.awt.Color spc, Coord3f dir) {
            super(amb, dif, spc, dir);
        }

        @Override
        public Object[] params(haven.render.GroupPipe state) {
            return (new Object[] {amb, dif, spc, dir, 0f, 0f, 0f, 0f});
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        /* The "Nurglingify" toggle was flipped: build the character again, skin included. */
        if (charsel) {
            boolean on = nurgling.tools.PlagueSkin.nurglingify();
            if (on != plagueShown) {
                plagueShown = on;
                rebuild();
            }
        }
    }

    @Override
    public void draw(GOut g) {
        if (avagob == -1) {
            try {
                /* The same call haven's draw makes; it throws until the skeleton and every
                 * equipment layer have arrived, which is exactly the placeholder's window. */
                updcomp();
            } catch (Loading l) {
                if ((sz.x >= SPINMIN) && (sz.y >= SPINMIN))
                    NLoginTheme.drawSpinner(g, sz.div(2), UI.scale(26), UI.scale(7));
                return;
            }
        }
        super.draw(g);
    }
}
