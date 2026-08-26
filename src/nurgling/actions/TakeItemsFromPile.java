package nurgling.actions;

import haven.*;
import nurgling.*;
import nurgling.tasks.*;
import nurgling.tools.StockpileUtils;

import java.util.ArrayList;

public class TakeItemsFromPile implements Action
{
    /** How long the transfer may go without a single item arriving before it is called done. */
    private static final long IDLE_MS = 5000;

    NISBox pile;
    Gob gpile;
    String cap;
    int target_size = Integer.MAX_VALUE;
    int took = 0;
    ArrayList<NGItem> items = new ArrayList<>();

    public TakeItemsFromPile(Gob gob, NISBox pile, int target_size)
    {
        this.pile = pile;
        this.target_size = target_size;
        this.gpile = gob;
        String gobcap = StockpileUtils.capFor(gob);
        this.cap = (gobcap != null) ? gobcap : StockpileUtils.STOCKPILE_CAP;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        while (took < target_size)
        {
            // The caller passes the box it opened for this gob, which is the right one even when
            // several storages share a caption. It dies with its window, though - an emptied
            // stockpile disappears, and a reopened window builds a new widget - so fall back to
            // looking the window up by caption once the old widget is gone.
            NISBox box = (pile != null && pile.parent != null) ? pile : gui.getStockpile(cap);
            if (box == null)
                break;
            pile = box;
            /* total() waits for the box's label to parse. calcCount() can still answer -1
             * here, and asking for -1 items transfers nothing while leaving the caller with
             * no progress to subtract, which is how a fuel run ends up walking to the same
             * pile forever. A produce sack also stays open when emptied, unlike a stockpile,
             * which just disappears. */
            int left = box.total();
            int count = Math.min(left, target_size - took);
            if (count <= 0)
                break;
            int before = gui.getInventory().getItems().size();
            ((NUI) gui.ui).enableMonitor(gui.maininv);
            box.transfer(count);
            // Give up on the transfer when the items stop arriving, not after a fixed number of
            // frames: a large load legitimately takes seconds, and a counted-out task exits
            // critically, which aborts the whole bot instead of letting the loop below decide.
            WaitItemFromPile wifp = new WaitItemFromPile(gui.getInventory(), before, count)
            {
                private long idleUntil = System.currentTimeMillis() + IDLE_MS;
                private int seen = 0;

                @Override
                public boolean check()
                {
                    if(super.check())
                        return true;
                    int arrived = getTotalItemCount();
                    if(arrived != seen)
                    {
                        seen = arrived;
                        idleUntil = System.currentTimeMillis() + IDLE_MS;
                    }
                    return System.currentTimeMillis() > idleUntil;
                }
            };
            NUtils.getUI().core.addTask(wifp);
            int taken = Math.max(0, wifp.getTotalItemCount());
            took += taken;
            ((NUI) gui.ui).disableMonitor();
            items.addAll(wifp.getResult());
            // Nothing arrived, or less than asked while the box kept its content: the items do not
            // fit the free cells by shape. Repeating would only park on the next transfer.
            if (taken <= 0 || (taken < count && box.calcCount() >= left))
                break;
        }

        return Results.SUCCESS();
    }

    public int getResult()
    {
        return took;
    }

    public ArrayList<NGItem> newItems(){
        return items;
    }
}
