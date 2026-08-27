package nurgling.widgets.options;

import haven.Button;
import haven.Coord;
import haven.Label;
import haven.UI;
import haven.Widget;
import haven.Window;
import nurgling.i18n.L10n;

import java.awt.Color;

/**
 * "Are you sure?" for throwing away the shared map.
 *
 * <p>Everything destructive about the action is stated before the button that does it: how much
 * disk it wins back, which tables it empties, what it leaves alone, and - the part that is easy to
 * forget on a village database - that it is everyone's copy, not this player's.
 *
 * <p>Only Yes acts. The No button, the title-bar cross and Escape are the same answer - Window turns
 * the player's own close key into the {@code close} message this handles - and that answer is where
 * the window starts.
 */
public class ClearMapConfirm extends Window {
    private final Runnable onYes;

    /**
     * @param sizeText what the map currently costs, already formatted - the number the player is
     *                 looking at on the panel behind this window
     * @param onYes    run on the UI thread when the player confirms
     */
    public ClearMapConfirm(String sizeText, Runnable onYes) {
        super(UI.scale(new Coord(400, 60)), L10n.get("database.clearmap.title"));
        this.onYes = onYes;

        int margin = UI.scale(10);
        int y = margin;

        y = line(L10n.get("database.clearmap.question"), Color.WHITE, margin, y);
        y = line(L10n.get("database.clearmap.frees", sizeText), Color.WHITE, margin, y);
        y = line(L10n.get("database.clearmap.tables",
            String.join(", ", nurgling.db.service.DbStorageService.sharedMapTables())),
            Color.LIGHT_GRAY, margin, y);
        y = line(L10n.get("database.clearmap.keeps_markers"), Color.LIGHT_GRAY, margin, y);
        y = line(L10n.get("database.clearmap.shared"), Color.ORANGE, margin, y);
        y += UI.scale(6);

        Button yes = add(new Button(UI.scale(120), L10n.get("database.clearmap.yes")) {
            public void click() {
                super.click();
                confirm();
            }
        }, new Coord(margin, y));
        add(new Button(UI.scale(80), L10n.get("database.clearmap.no")) {
            public void click() {
                super.click();
                cancel();
            }
        }, new Coord(margin + yes.sz.x + UI.scale(10), y));

        pack();
    }

    private int line(String text, Color color, int x, int y) {
        Label l = add(new Label(text), new Coord(x, y));
        l.setcolor(color);
        return y + l.sz.y + UI.scale(3);
    }

    private void confirm() {
        /* Destroyed before the work starts: the action re-measures and re-draws the panel behind
         * this window, and a confirmation still sitting over it would read as "did that happen?". */
        destroy();
        onYes.run();
    }

    private void cancel() {
        destroy();
    }

    /** The cross on the title bar. A window this client built gets no reply, so it closes itself. */
    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (msg.equals("close")) {
            cancel();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }
}
