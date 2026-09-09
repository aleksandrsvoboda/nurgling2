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

    @Override
    public boolean check()
    {
        Window wnd = NUtils.getGameUI().getWindow(name);
        if(wnd == null)
        {
            /* Nothing left to open. The caller reads a null box off getStockpile() and treats
             * the visit as "took nothing", which is exactly what happened. */
            return tracked && Finder.findGob(gobid) == null;
        }
        for(Widget w2 = wnd.lchild ; w2 !=null ; w2= w2.prev )
        {
            if ( w2 instanceof NISBox ) {
                return true;
            }
        }
        return false;
    }
}
