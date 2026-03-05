package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.awt.*;
import java.util.Arrays;

public class TestTreeAction implements GobContextAction {
    private static final NAlias TREE_ALIAS = new NAlias(
            new java.util.ArrayList<>(Arrays.asList("gfx/terobjs/trees/")),
            new java.util.ArrayList<>(Arrays.asList("log", "trunk", "oldtrunk"))
    );

    @Override
    public boolean appliesTo(Gob gob) {
        return NParser.checkName(gob.ngob.name, TREE_ALIAS);
    }

    @Override
    public String label() {
        return "Test: Log Tree Name";
    }

    @Override
    public Action create(Gob gob) {
        return gui -> {
            gui.msg("Gob: " + gob.ngob.name + " (id: " + gob.id + ")", Color.GREEN);
            return Results.SUCCESS();
        };
    }
}
