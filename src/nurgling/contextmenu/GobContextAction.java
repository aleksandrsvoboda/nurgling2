package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;

public interface GobContextAction {
    boolean appliesTo(Gob gob);
    String label();
    Action create(Gob gob);
}
