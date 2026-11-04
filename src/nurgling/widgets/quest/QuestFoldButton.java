package nurgling.widgets.quest;

import haven.*;

/** The quest tracker's minimize button; its chevron points to the corner the tracker folds into. */
public class QuestFoldButton extends Widget
{
    private final boolean flat;
    private final Runnable action;
    private boolean down = false;
    private boolean hover = false;

    public QuestFoldButton(boolean flat, Coord sz, Runnable action)
    {
        super(sz);
        this.flat = flat;
        this.action = action;
        settip("Minimize the quest tracker");
    }

    /** The classic button's own size, to match the other classic toolbar buttons. */
    public static Coord classicSize()
    {
        return QuestFoldArt.classicSize();
    }

    public void pointDown(boolean down)
    {
        this.down = down;
    }

    @Override
    public void draw(GOut g)
    {
        QuestFoldArt.draw(g, Coord.z, sz, down, hover, flat);
    }

    @Override
    public void mousemove(MouseMoveEvent ev)
    {
        hover = ev.c.isect(Coord.z, sz);
        super.mousemove(ev);
    }

    @Override
    public boolean mousehover(MouseHoverEvent ev, boolean hovering)
    {
        if(!hovering)
            hover = false;
        return super.mousehover(ev, hovering);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev)
    {
        if(ev.b == 1) {
            action.run();
            return true;
        }
        return super.mousedown(ev);
    }
}
