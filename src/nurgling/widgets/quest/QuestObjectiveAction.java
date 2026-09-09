package nurgling.widgets.quest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.List;

public final class QuestObjectiveAction {
    public enum Kind {
        FORAGE_TERRAIN,
        TREE_TERRAIN,
        ROCK_TERRAIN,
        CRAFT
    }

    public final Kind kind;
    public final List<String> targets;

    public QuestObjectiveAction(Kind kind, Collection<String> targets) {
        this.kind = kind;
        this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
    }
}
