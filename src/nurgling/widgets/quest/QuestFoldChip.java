package nurgling.widgets.quest;

import haven.*;
import nurgling.NStyle;
import nurgling.widgets.NQuestInfo;

/**
 * The minimized quest tracker: an expand chevron, the number of tracked quests and, in green,
 * how many are ready to turn in. A click anywhere on it expands the tracker again.
 */
public class QuestFoldChip extends Widget
{
    private static final int PAD = UI.scale(4);
    private static final int GAP = UI.scale(3);

    private final boolean flat;
    private final Runnable expand;
    private boolean down = true;
    private boolean hover = false;
    private int count = -1, ready = -1;
    private Text.Foundry fnd = null;
    private Tex countT, readyT;

    public QuestFoldChip(boolean flat, Runnable expand)
    {
        super(UI.scale(new Coord(40, 22)));
        this.flat = flat;
        this.expand = expand;
    }

    /** Re-renders only when something shown changed. */
    public void set(int count, int ready, Text.Foundry fnd)
    {
        if(count == this.count && ready == this.ready && fnd == this.fnd)
            return;
        this.count = count;
        this.ready = ready;
        this.fnd = fnd;
        if(countT != null) countT.dispose();
        if(readyT != null) readyT.dispose();
        countT = fnd.render(String.valueOf(count), java.awt.Color.WHITE).tex();
        readyT = (ready > 0) ? fnd.render(readyLabel(fnd, ready), NStyle.questReady).tex() : null;
        int h = Math.max(UI.scale(22), countT.sz().y + PAD);
        int arrow = h - PAD;
        int w = PAD + arrow + GAP + countT.sz().x + ((readyT != null) ? GAP * 2 + readyT.sz().x : 0) + PAD;
        resize(new Coord(w, h));
    }

    private static String readyLabel(Text.Foundry fnd, int ready)
    {
        return fnd.font.canDisplay('✓') ? ("✓" + ready) : (ready + " ready");
    }

    /** The chevron points into the tracker: down when the chip sits at its top. */
    public void pointDown(boolean down)
    {
        this.down = down;
    }

    @Override
    public void draw(GOut g)
    {
        NQuestInfo.drawPanelBg(g, sz, ui);
        int arrow = sz.y - PAD;
        QuestFoldArt.draw(g, new Coord(PAD / 2, PAD / 2), new Coord(arrow, arrow), down, hover, flat);
        int x = PAD / 2 + arrow + GAP;
        if(countT != null) {
            g.image(countT, new Coord(x, (sz.y - countT.sz().y) / 2));
            x += countT.sz().x + GAP * 2;
        }
        if(readyT != null)
            g.image(readyT, new Coord(x, (sz.y - readyT.sz().y) / 2));
        NQuestInfo.drawPanelBorder(g, sz);
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
            expand.run();
            return true;
        }
        return super.mousedown(ev);
    }

    @Override
    public Object tooltip(Coord c, Widget prev)
    {
        return "Quests: " + count + " tracked" + ((ready > 0) ? (", " + ready + " ready to turn in") : "")
            + ". Click to expand.";
    }
}
