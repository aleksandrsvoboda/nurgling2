package nurgling.actions;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.Arrays;

public class CollectItemsToPile implements Action{

    Pair<Coord2d,Coord2d> out;
    Pair<Coord2d,Coord2d> in;

    NAlias items;
    public CollectItemsToPile(Pair<Coord2d, Coord2d> input, Pair<Coord2d, Coord2d> output, NAlias items)
    {
        this.out = output;
        this.in = input;
        this.items = items;
    }

    CollectItemsToPile(NArea input, NArea output, NAlias items)
    {
        this(input.getRCArea(), output.getRCArea(),items);
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        // Preserve any exceptions the caller passed (e.g. "seed" to keep seeds out of a
        // vegetable pile) and add the standard container exclusions on top.
        ArrayList<String> exceptions = new ArrayList<>(items.exceptions);
        exceptions.add("stockpile");
        exceptions.add("barrel");
        // A growing crop and the item it yields share a name - "gfx/terobjs/plants/garlic"
        // vs "gfx/terobjs/items/garlic" - so an item alias like "Garlic" (substring match)
        // also hits the planted crop. Picking up from the earth can never apply to a plant,
        // so exclude the plant path outright: on a partially planted field takeFromEarth
        // would otherwise wait forever for a gob that never goes away.
        exceptions.add("plants/");
        NAlias collected_items = new NAlias(items.keys, exceptions);

        while ( !Finder.findGobs (in,collected_items ).isEmpty () ){
            ArrayList<WItem> testItems = null;
            if(!(testItems = gui.getInventory ().getItems(items)).isEmpty()) {
                if (gui.getInventory().getNumberFreeCoord(testItems.get(0)) == 0) {
                    new TransferToPiles(out, items ).run(gui);
                }
            }

            Gob item = Finder.findGob (in, collected_items );
            if(item == null)
                break;
            if(item.rc.dist(gui.map.player().rc)> MCache.tilesz2.x) {
                PathFinder pf = new PathFinder(item);
                pf.run(gui);
            }
            NUtils.takeFromEarth ( item );
        }

        new TransferToPiles(out, items ).run ( gui );
        return Results.SUCCESS();
    }
}
