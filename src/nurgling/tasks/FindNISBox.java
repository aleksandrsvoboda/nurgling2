package nurgling.tasks;

import haven.*;
import nurgling.*;
import nurgling.tools.Finder;

public class FindNISBox extends NTask
{
    public FindNISBox(String name)
    {
        this(name, null);
    }

    /**
     * @param gob the container that was clicked, when it is one that can cease to exist while
     *            we wait. Taking a stockpile's last item destroys it, and the destroy message
     *            can land after the last item does, so a click aimed at it reaches nothing and
     *            no window ever arrives. This task is infinite by default, which parks the bot
     *            there for good; knowing the gob lets it give up instead.
     */
    public FindNISBox(String name, Gob gob)
    {
        this.name = name;
        this.gobid = (gob == null) ? -1 : gob.id;
        this.tracked = (gob != null);
    }

    String name;
    long gobid;
    boolean tracked;

    /**
     * Checks the gob must stay missing before we believe it was destroyed.
     *
     * <p>Finder.findGob returns null for a gob that is merely out of range, still streaming in, or
     * absent across a server hiccup, exactly as it does for one that no longer exists. Believing
     * the first miss ends the visit with no window and no items - and the caller reports that as
     * "no items available from source" and abandons the whole run, which is how a single map
     * stutter left feeding cupboards unfilled and sent a generation of cocoons to be killed
     * instead of bred. A pile destroyed by having its last item taken stays gone, so waiting a
     * moment costs nothing and only ever happens once per genuinely dead pile.
     */
    private static final int GONE_FOR = 150;
    private int missing = 0;

    @Override
    public boolean check()
    {
        Window wnd = NUtils.getGameUI().getWindow(name);
        if(wnd == null)
        {
            if(!tracked)
                return false;
            if(Finder.findGob(gobid) != null)
            {
                missing = 0; // still there - the window is simply not open yet
                return false;
            }
            /* Gone, and stayed gone. The caller reads a null box off getStockpile() and treats
             * the visit as "took nothing", which is exactly what happened. */
            return ++missing >= GONE_FOR;
        }
        missing = 0;
        for(Widget w2 = wnd.lchild ; w2 !=null ; w2= w2.prev )
        {
            if ( w2 instanceof NISBox ) {
                return true;
            }
        }
        return false;
    }
}
