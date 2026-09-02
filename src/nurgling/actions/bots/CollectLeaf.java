package nurgling.actions.bots;

import haven.Coord;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CollectLeaf extends CollectFromTreeBot {
    public CollectLeaf() {
        this(null);
    }

    protected CollectLeaf(Specialisation.SpecName zoneSpec) {
        super("Pick leaf", null, new Coord(1, 1),
              new NAlias("Leaf", "leaf", "Leaves", "leaves"),
              new NAlias(new ArrayList<>(List.of("gfx/terobjs/tree", "gfx/terobjs/bushes/teabush")), new ArrayList<>(Arrays.asList("log", "oldtrunk", "stump"))),
              true,
              "baubles/liefStart", "baubles/liefPiles",
              "Please select area with trees or bushes", "Please select area for piles",
              zoneSpec);
    }
}
