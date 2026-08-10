package nurgling.widgets;

import haven.KeyBinding;
import haven.KeyMatch;
import haven.RichText;
import haven.Text;
import haven.Coord;
import haven.GOut;
import haven.Widget;

import java.awt.event.KeyEvent;

/**
 * A button that displays a {@link KeyBinding} and rebinds it when clicked.
 *
 * <p>Lifted out of {@code OptWnd.BindingPanel.SetButton}, which was a non-static inner class and so
 * could not be reused anywhere else. Behaviour is unchanged; {@code BindingPanel} now uses this too,
 * so there is a single implementation of the rebinding interaction.
 */
public class NKeyBindButton extends KeyMatch.Capture {
    private static final Text tooltip = RichText.render("$col[255,255,0]{Escape}: Cancel input\n" +
            "$col[255,255,0]{Backspace}: Revert to default\n" +
            "$col[255,255,0]{Delete}: Disable keybinding", 0);

    public final KeyBinding cmd;

    public NKeyBindButton(int w, KeyBinding cmd) {
        super(w, cmd.key());
        this.cmd = cmd;
    }

    public void set(KeyMatch key) {
        super.set(key);
        cmd.set(key);
        nurgling.NConfig.needUpdate();
    }

    public void draw(GOut g) {
        // The binding can be changed elsewhere (another panel, a default reset), so re-read it
        // rather than trusting the label we last rendered.
        if (cmd.key() != key)
            super.set(cmd.key());
        super.draw(g);
    }

    protected KeyMatch mkmatch(KeyEvent ev) {
        return (KeyMatch.forevent(ev, ~cmd.modign));
    }

    protected boolean handle(KeyEvent ev) {
        if (ev.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
            cmd.set(null);
            super.set(cmd.key());
            return (true);
        }
        return (super.handle(ev));
    }

    public Object tooltip(Coord c, Widget prev) {
        return (tooltip.tex());
    }
}
