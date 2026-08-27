package nurgling.widgets.nsettings;

import haven.Coord;
import haven.GOut;
import haven.Text;
import haven.UI;
import haven.Widget;

import java.awt.Color;

/**
 * A titled block of controls that can be collapsed down to just its header - lets a settings
 * page built from several logically-separate groups (e.g. Forager Settings' planned Actions/
 * Routes/Guarding sections) stay navigable as more get added, rather than always showing every
 * group's full content at once. Add whatever controls a section owns to its {@link #content}
 * widget (not this widget directly), then call {@link #pack()} once to size it correctly.
 */
public class CollapsibleSection extends Widget {
    private static final int HEADER_H = UI.scale(22);
    private static final Color HEADER_BG = new Color(40, 40, 40, 200);

    public final Widget content;
    private boolean expanded;
    private Text.Line title;
    private Runnable onToggle;

    public CollapsibleSection(String title, int width, boolean startExpanded) {
        super(new Coord(width, HEADER_H));
        this.expanded = startExpanded;
        renderTitle(title);
        content = add(new Widget(new Coord(width, 0)), new Coord(0, HEADER_H));
        content.visible = expanded;
    }

    private void renderTitle(String text) {
        if (title != null) {
            title.dispose();
        }
        this.title = Text.render((expanded ? "▼ " : "▶ ") + text);
    }

    /** Notified after every expand/collapse, so the owning panel can reposition whatever
     *  follows this section and refresh its scroll range. */
    public void setOnToggle(Runnable onToggle) {
        this.onToggle = onToggle;
    }

    public boolean isExpanded() {
        return expanded;
    }

    /** (Re)computes this section's height from its content's current natural size - call once
     *  after populating {@link #content} (or again if that content's size changes later). */
    public void pack() {
        // content itself must be resized too, not just this wrapper - draw()'s default child
        // traversal clips each child to its OWN size (see Widget.draw(GOut, boolean)), so a
        // content widget left at its construction-time height of 0 clips away everything inside
        // it (and blocks mouse hit-testing the same way) regardless of how big this wrapper is.
        int contentHeight = content.contentsz().y;
        content.resize(new Coord(content.sz.x, contentHeight));
        resize(new Coord(sz.x, HEADER_H + (expanded ? contentHeight : 0)));
    }

    private void toggle() {
        expanded = !expanded;
        renderTitle(title.text.substring(2));
        content.visible = expanded;
        pack();
        if (onToggle != null) {
            onToggle.run();
        }
    }

    @Override
    public void draw(GOut g) {
        g.chcolor(HEADER_BG);
        g.frect(Coord.z, new Coord(sz.x, HEADER_H));
        g.chcolor();
        g.image(title.tex(), new Coord(UI.scale(5), (HEADER_H - title.sz().y) / 2));
        super.draw(g);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (ev.c.y < HEADER_H) {
            if (ev.b == 1) {
                toggle();
            }
            return true;
        }
        return super.mousedown(ev);
    }

    @Override
    public void dispose() {
        title.dispose();
        super.dispose();
    }
}
