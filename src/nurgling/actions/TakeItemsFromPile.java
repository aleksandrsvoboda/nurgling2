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
            if(box == null)
                break;
            // A produce sack stays open when emptied, unlike a stockpile, which just disappears
            int left = box.calcCount();
            if(left <= 0)
                break;
            int count = Math.min(left, target_size - took);
            ((NUI)gui.ui).enableMonitor(gui.maininv);
            box.transfer(count);
            WaitItemFromPile wifp = new WaitItemFromPile(count);
            NUtils.getUI().core.addTask(wifp);
            took += wifp.getTotalItemCount();
            ((NUI)gui.ui).disableMonitor();
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
