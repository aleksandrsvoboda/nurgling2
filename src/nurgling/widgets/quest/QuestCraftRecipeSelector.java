package nurgling.widgets.quest;

import java.util.List;
import java.util.Locale;

public final class QuestCraftRecipeSelector {
    private QuestCraftRecipeSelector() {
    }

    public static int uniqueExactName(List<String> candidateNames, String target) {
        String wanted = normalize(target);
        if(wanted.isEmpty())
            return -1;
        int found = -1;
        for(int i = 0; i < candidateNames.size(); i++) {
            if(normalize(candidateNames.get(i)).equals(wanted)) {
                if(found >= 0)
                    return -1;
                found = i;
            }
        }
        return found;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
