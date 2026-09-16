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

    public NAvaview(Coord sz, long avagob, String camnm) {
        super(sz, avagob, camnm);
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
