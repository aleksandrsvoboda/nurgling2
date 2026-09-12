package nurgling.widgets.cookbook;

import haven.*;

/** A text entry that shows a muted hint while empty, reports every edit, and clears on Escape. */
public class HintTextEntry extends TextEntry {
    private final Tex hint;
    private final Runnable onChange;
    private boolean focused = false;

    public HintTextEntry(int w, String hint, Runnable onChange) {
        super(w, "");
        this.hint = CookbookTheme.render(CookbookTheme.body, hint, CookbookTheme.muted);
        this.onChange = onChange;
    }

    @Override
    protected void changed() {
        super.changed();
        /* Null while TextEntry's constructor sets the initial text. */
        if(onChange != null)
            onChange.run();
    }

    @Override
    public void gotfocus() {
        super.gotfocus();
        focused = true;
    }

    @Override
    public void lostfocus() {
        super.lostfocus();
        focused = false;
    }

    @Override
    public void draw(GOut g) {
        super.draw(g);
        if(!focused && (hint != null) && text().isEmpty())
            g.image(hint, Coord.of(toffx, (sz.y - hint.sz().y) / 2));
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if(key_esc.match(ev) && !text().isEmpty()) {
            settext("");
            if(onChange != null)
                onChange.run();
            return true;
        }
        return super.keydown(ev);
    }
}
