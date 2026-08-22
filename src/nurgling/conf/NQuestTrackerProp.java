package nurgling.conf;

import nurgling.NConfig;
import nurgling.NUI;
import nurgling.widgets.quest.QuestKind;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * Quest tracker settings, stored per {@code (username, chrid)}.
 *
 * Per character on purpose: quests are per character, and the flag this replaces
 * ({@code NConfig.Key.hidecredo}) lived in the genus-shared config, so hiding credo on one
 * alt hid it on all of them.
 */
public class NQuestTrackerProp implements JConf
{
    /** Grouping mode of the tracker body. */
    public enum Mode
    {
        GIVERS, TASKS
    }

    public final String username;
    public final String chrid;

    /** Quest kinds the panel shows. {@link QuestKind#UNKNOWN} is never listed. */
    public final EnumSet<QuestKind> kinds = EnumSet.of(QuestKind.CREDO, QuestKind.NPC, QuestKind.WORLD);
    public Mode mode = Mode.GIVERS;
    /** Quest resource names the player hid. */
    public final Set<String> hiddenQuests = new LinkedHashSet<>();
    /** Quest giver names the player hid. */
    public final Set<String> hiddenGivers = new LinkedHashSet<>();
    /** Group keys pinned to the top; exempt from the kind filter and the row cap. */
    public final Set<String> pinned = new LinkedHashSet<>();
    /** Groups the player explicitly expanded. Groups are collapsed by default. */
    public final Set<String> expanded = new LinkedHashSet<>();
    /** Groups the player explicitly collapsed, overriding a default-expanded group. */
    public final Set<String> collapsed = new LinkedHashSet<>();
    /** Maximum rendered rows before the panel cuts off with a "+N more" row. 0 = unlimited. */
    public int maxrows = 12;

    public NQuestTrackerProp(String username, String chrid)
    {
        this.username = username;
        this.chrid = chrid;
    }

    @SuppressWarnings("unchecked")
    public NQuestTrackerProp(HashMap<String, Object> values)
    {
        username = str(values.get("username"));
        chrid = str(values.get("chrid"));
        Object k = values.get("kinds");
        if(k instanceof Collection) {
            kinds.clear();
            for(Object o : (Collection<Object>)k) {
                try {
                    QuestKind kind = QuestKind.valueOf(String.valueOf(o));
                    if(kind != QuestKind.UNKNOWN)
                        kinds.add(kind);
                } catch(IllegalArgumentException ignore) {
                }
            }
        }
        if(values.get("mode") != null) {
            try {
                mode = Mode.valueOf(String.valueOf(values.get("mode")));
            } catch(IllegalArgumentException ignore) {
            }
        }
        readStrings(values.get("hiddenQuests"), hiddenQuests);
        readStrings(values.get("hiddenGivers"), hiddenGivers);
        readStrings(values.get("pinned"), pinned);
        readStrings(values.get("expanded"), expanded);
        readStrings(values.get("collapsed"), collapsed);
        if(values.get("maxrows") instanceof Number)
            maxrows = ((Number)values.get("maxrows")).intValue();
    }

    private static String str(Object o)
    {
        return (o == null) ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static void readStrings(Object src, Set<String> dst)
    {
        if(!(src instanceof Collection))
            return;
        for(Object o : (Collection<Object>)src) {
            if(o != null)
                dst.add(String.valueOf(o));
        }
    }

    @Override
    public JSONObject toJson()
    {
        JSONObject j = new JSONObject();
        j.put("type", "NQuestTrackerProp");
        j.put("username", username);
        j.put("chrid", chrid);
        JSONArray jk = new JSONArray();
        for(QuestKind k : kinds)
            jk.put(k.name());
        j.put("kinds", jk);
        j.put("mode", mode.name());
        j.put("hiddenQuests", new JSONArray(hiddenQuests));
        j.put("hiddenGivers", new JSONArray(hiddenGivers));
        j.put("pinned", new JSONArray(pinned));
        j.put("expanded", new JSONArray(expanded));
        j.put("collapsed", new JSONArray(collapsed));
        j.put("maxrows", maxrows);
        return j;
    }

    /** Persist this instance, replacing any previous entry for the same character. */
    public void save()
    {
        if(chrid.isEmpty())
            // Defaults instance handed out before login resolved the character - persisting it
            // would write a nameless entry that no character ever reads back.
            return;
        @SuppressWarnings("unchecked")
        ArrayList<NQuestTrackerProp> props =
            (ArrayList<NQuestTrackerProp>)NConfig.get(NConfig.Key.questtrackerprop);
        if(props == null)
            props = new ArrayList<>();
        for(Iterator<NQuestTrackerProp> i = props.iterator(); i.hasNext(); ) {
            NQuestTrackerProp old = i.next();
            if(old.username.equals(username) && old.chrid.equals(chrid)) {
                i.remove();
                break;
            }
        }
        props.add(this);
        NConfig.set(NConfig.Key.questtrackerprop, props);
    }

    /**
     * Settings for the character this UI is logged in as, or null if that is not known yet.
     *
     * Resolves the character from the given {@link NUI} rather than from the active game UI,
     * so two sessions running side by side keep their own settings.
     */
    public static NQuestTrackerProp get(NUI ui)
    {
        if(ui == null || ui.sessInfo == null || ui.sessInfo.characterInfo == null)
            return null;
        String username = (ui.sessInfo.username == null) ? "" : ui.sessInfo.username;
        String chrid = ui.sessInfo.characterInfo.chrid;
        if(chrid == null)
            return null;
        @SuppressWarnings("unchecked")
        ArrayList<NQuestTrackerProp> props =
            (ArrayList<NQuestTrackerProp>)NConfig.get(NConfig.Key.questtrackerprop);
        if(props != null) {
            for(NQuestTrackerProp p : props) {
                if(p.username.equals(username) && p.chrid.equals(chrid))
                    return p;
            }
        }
        NQuestTrackerProp fresh = new NQuestTrackerProp(username, chrid);
        // One-shot migration off the old genus-wide flag, so a player who had credo hidden
        // keeps it hidden on the character they set it on.
        Object legacy = NConfig.get(NConfig.Key.hidecredo);
        if((legacy instanceof Boolean) && (Boolean)legacy)
            fresh.kinds.remove(QuestKind.CREDO);
        return fresh;
    }
}
