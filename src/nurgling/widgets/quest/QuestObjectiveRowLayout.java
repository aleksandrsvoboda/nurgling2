package nurgling.widgets.quest;

import haven.UI;

/** Pure sizing rules for compact quest-objective rows. */
public final class QuestObjectiveRowLayout {
    private static final int ACTION_WIDTH = UI.scale(20);

    private QuestObjectiveRowLayout() {
    }

    public static int textWidth(int rowWidth, int textOffset, boolean hasAction) {
        return Math.max(0, rowWidth - textOffset - (hasAction ? ACTION_WIDTH : 0));
    }
}
