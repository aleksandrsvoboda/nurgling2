package nurgling.widgets;

import nurgling.NConfig;
import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The bot menu's Favourites: named sections of bot ids, in the order the player arranged them.
 * Stored in {@link NConfig.Key#botFavourites}, one set shared by every character.
 * <p>
 * Ids rather than icon paths or positions, so a bot that changes icon or folder stays a favourite.
 * Ids of bots that no longer exist are dropped on load.
 */
public class BotFavourites {
    public static class Section {
        public String name;
        public final List<String> ids = new ArrayList<>();

        Section(String name) {
            this.name = name;
        }
    }

    private final List<Section> sections = new ArrayList<>();
    /** The config object last loaded or saved; a different one means another session saved. */
    private Object loaded = null;
    /** Section a bot dropped on the Favourites tab goes to. */
    private int lastSection = 0;

    public List<Section> sections() {
        return sections;
    }

    /** Reloads from the config when another session changed it. Returns true when it did. */
    public boolean sync() {
        Object cur = NConfig.get(NConfig.Key.botFavourites);
        if (cur == loaded)
            return false;
        loaded = cur;
        sections.clear();
        if (cur instanceof List) {
            for (Object o : (List<?>) cur) {
                if (!(o instanceof Map))
                    continue;
                Map<?, ?> m = (Map<?, ?>) o;
                Object name = m.get("name");
                Section s = new Section(name instanceof String ? (String) name : "");
                Object ids = m.get("ids");
                if (ids instanceof List) {
                    for (Object id : (List<?>) ids) {
                        BotDescriptor bot = (id instanceof String) ? BotRegistry.byId((String) id) : null;
                        if (bot != null && bot.allowedAsItemInBotMenu && !s.ids.contains(bot.id) && find(bot.id) == null)
                            s.ids.add(bot.id);
                    }
                }
                sections.add(s);
            }
        }
        lastSection = Math.max(0, Math.min(lastSection, sections.size() - 1));
        return true;
    }

    private void save() {
        ArrayList<HashMap<String, Object>> out = new ArrayList<>();
        for (Section s : sections) {
            HashMap<String, Object> m = new HashMap<>();
            m.put("name", s.name);
            m.put("ids", new ArrayList<>(s.ids));
            out.add(m);
        }
        loaded = out;
        NConfig.set(NConfig.Key.botFavourites, out);
    }

    public int count() {
        int n = 0;
        for (Section s : sections)
            n += s.ids.size();
        return n;
    }

    public boolean contains(String id) {
        return find(id) != null;
    }

    /** {section, index} of a bot, or null. */
    public int[] find(String id) {
        for (int i = 0; i < sections.size(); i++) {
            int j = sections.get(i).ids.indexOf(id);
            if (j >= 0)
                return new int[]{i, j};
        }
        return null;
    }

    /**
     * Puts a bot into a section at a position, moving it if it is already a favourite.
     * A null position or one past the end appends. Returns true when the bot was not a favourite before.
     */
    public boolean put(String id, int section, Integer pos, String defaultName) {
        if (sections.isEmpty()) {
            sections.add(new Section(defaultName));
            section = 0;
        }
        section = Math.max(0, Math.min(section, sections.size() - 1));
        int[] at = find(id);
        if (at != null) {
            sections.get(at[0]).ids.remove(at[1]);
            if (at[0] == section && pos != null && at[1] < pos)
                pos--;
        }
        List<String> ids = sections.get(section).ids;
        if (pos == null || pos > ids.size())
            pos = ids.size();
        ids.add(pos, id);
        lastSection = section;
        save();
        return at == null;
    }

    /** Adds to the section last used, at its end. */
    public boolean add(String id, String defaultName) {
        return put(id, lastSection, null, defaultName);
    }

    public void remove(String id) {
        int[] at = find(id);
        if (at != null) {
            sections.get(at[0]).ids.remove(at[1]);
            save();
        }
    }

    public int addSection(String name) {
        sections.add(new Section(name));
        lastSection = sections.size() - 1;
        save();
        return lastSection;
    }

    public void removeSection(int i) {
        if (i < 0 || i >= sections.size())
            return;
        sections.remove(i);
        lastSection = Math.max(0, Math.min(lastSection, sections.size() - 1));
        save();
    }

    public void renameSection(int i, String name) {
        if (i < 0 || i >= sections.size())
            return;
        sections.get(i).name = name;
        save();
    }

    /** Moves section {@code from} so it lands before the section now at {@code to} ({@code size()} = last). */
    public void moveSection(int from, int to) {
        if (from < 0 || from >= sections.size())
            return;
        Section s = sections.remove(from);
        if (to > from)
            to--;
        to = Math.max(0, Math.min(to, sections.size()));
        sections.add(to, s);
        if (lastSection == from)
            lastSection = to;
        save();
    }
}
