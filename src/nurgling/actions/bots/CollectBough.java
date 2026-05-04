package nurgling.actions.bots;

import haven.Coord;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CollectBough extends CollectFromTreeBot {
    public CollectBough() {
        super("Take bough", "gfx/borka/treepickan", new Coord(2, 1),
              new NAlias("Bough", "bough"),
              new NAlias(new ArrayList<>(List.of("gfx/terobjs/tree")), new ArrayList<>(Arrays.asList("log", "oldtrunk", "stump"))),
              false,
              "baubles/boughStart", "baubles/boughPiles",
              "Please select area with trees", "Please select area for piles");
    }
}
