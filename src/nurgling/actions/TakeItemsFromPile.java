package nurgling.actions;

import haven.*;
import nurgling.*;
import nurgling.tasks.*;
import nurgling.tools.StockpileUtils;

import java.util.ArrayList;

public class TakeItemsFromPile implements Action
{
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
            NISBox box = gui.getStockpile(cap);
            if (box == null)
                break;
            /* total() waits for the pile's label to parse. calcCount() can still answer -1
             * here, and asking for -1 items transfers nothing while leaving the caller with
             * no progress to subtract, which is how a fuel run ends up walking to the same
             * pile forever. */
            int count = Math.min(box.total(), target_size - took);
            if (count <= 0)
                break;
            int before = gui.getInventory().getItems().size();
            ((NUI) gui.ui).enableMonitor(gui.maininv);
            box.transfer(count);
            WaitItemFromPile wifp = new WaitItemFromPile(gui.getInventory(), before, count);
            NUtils.getUI().core.addTask(wifp);
            took += Math.max(0, wifp.getTotalItemCount());
            ((NUI) gui.ui).disableMonitor();
            items.addAll(wifp.getResult());
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
