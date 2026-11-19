package nurgling.colony;

import haven.Charlist;
import haven.UI;
import haven.Widget;
import nurgling.plugins.UiEvents;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learns which world each character lives in, so a new colonist is made in the colony's world. Character select lists
 * every character with its world ("World 16.2"); when one is played, its session will later show the world's genus.
 * {@link ColonyReporter} pairs the two and the colony remembers genus to world name.
 */
final class ColonyWorlds implements UiEvents.Listener {
    /* Per character-select UI: character name -> world. */
    private final Map<UI, Map<String, String>> listed = new ConcurrentHashMap<>();
    /* The world of the character a UI just played, until its game UI shows the genus. */
    private final Map<UI, String> played = new ConcurrentHashMap<>();

    @Override
    public void onUiMsg(UI ui, int id, Widget wdg, String msg, Object[] args) {
        if (wdg instanceof Charlist && "srv".equals(msg) && args.length >= 2 && args[0] instanceof String && args[1] instanceof String)
            listed.computeIfAbsent(ui, k -> new ConcurrentHashMap<>()).put((String) args[0], (String) args[1]);
    }

    @Override
    public void onOutgoing(UI ui, int id, Widget sender, String msg, Object[] args) {
        if (sender instanceof Charlist && "play".equals(msg) && args.length >= 1 && args[0] instanceof String) {
            Map<String, String> m = listed.get(ui);
            String world = (m == null) ? null : m.get(args[0]);
            if (world != null)
                played.put(ui, world);
        }
    }

    /** The world of the character {@code ui} played last, once; null when none is waiting. */
    String takePlayed(UI ui) {
        listed.remove(ui);
        return played.remove(ui);
    }
}
