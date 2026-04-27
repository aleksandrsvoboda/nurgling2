package nurgling.actions.bots;

import haven.Coord;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CollectBark extends CollectFromTreeBot {
    public CollectBark() {
        super("Take bark", "gfx/borka/treepickan", new Coord(1, 1),
              new NAlias("Bark", "bark"),
              new NAlias(new ArrayList<>(List.of("gfx/terobjs/tree")), new ArrayList<>(Arrays.asList("log", "oldtrunk", "stump"))),
              false,
              "baubles/barkStart", "baubles/barkPiles",
              "Please select area with trees", "Please select area for piles");
    }
}
