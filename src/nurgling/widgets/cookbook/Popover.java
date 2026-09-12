package nurgling.widgets.cookbook;

import haven.*;

/**
 * A panel that drops down under its toolbar button. The cookbook window owns opening, placing and
 * closing it (on Escape, a click elsewhere, or the button again); the panel only lays itself out,
 * draws its frame and keeps clicks from falling through to the table underneath.
 */
public class Popover extends Widget {
    protected static final int PAD = UI.scale(10);
    public final PillButton owner;

    public Popover(PillButton owner) {
        super(Coord.z);
        this.owner = owner;
        hide();
    }

    /** Re-reads the model. Called as the panel opens and whenever the model changes while it is open. */
    public void syncFromModel() {
    }

    /** Sizes the panel around its children, which are laid out from (PAD, PAD). */
    protected void fit() {
        resize(contentsz().add(PAD, PAD));
    }

    @Override
    public void draw(GOut g) {
        CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.popBg);
        CookbookTheme.frame(g, Coord.z, sz, CookbookTheme.accent);
        super.draw(g);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        ev.propagate(this);
        return true;
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        ev.propagate(this);
        return true;
    }
}
