package nurgling.widgets.quest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The quests one character shares with the village, and their JSON form in {@code quest_shares.data}.
 *
 * <p>Objectives travel as the raw server text rather than as parsed fields. The receiver rebuilds each
 * one with {@link QCond}, so there is only one parser, and a receiver on a newer client understands
 * verbs its sender's client did not.
 *
 * <p>Deliberately free of {@code haven.Text} and widgets so it can be unit tested.
 */
public final class SharedQuests
{
    /** Payload format this client writes and fully understands. */
    public static final int FORMAT = 1;

    private SharedQuests()
    {
    }

    /** One objective line as the server sent it. */
    public static final class Cond
    {
        public final String desc;
        public final String status;
        public final boolean ready;

        public Cond(String desc, String status, boolean ready)
        {
            this.desc = (desc == null) ? "" : desc;
            this.status = status;
            this.ready = ready;
        }
    }

    /** One quest of a sharing character. */
    public static final class Quest
    {
        public final String res;
        public final String title;
        public final QuestKind kind;
        public final String giver;
        /** False while the sender had not received the objectives yet. */
        public final boolean loaded;
        public final List<Cond> conds;

        public Quest(String res, String title, QuestKind kind, String giver, boolean loaded, List<Cond> conds)
        {
            this.res = res;
            this.title = (title == null) ? "" : title;
            this.kind = (kind == null) ? QuestKind.UNKNOWN : kind;
            this.giver = giver;
            this.loaded = loaded;
            this.conds = Collections.unmodifiableList(new ArrayList<>(conds));
        }

        /** Identity of the quest across villagers: the same quest resource merges into one group. */
        public String key()
        {
            return (res != null) ? res : ("title:" + title);
        }
    }

    /** Result of reading a payload. */
    public static final class Decoded
    {
        /** The payload's own format number, or 0 when it could not be read at all. */
        public final int format;
        public final List<Quest> quests;

        Decoded(int format, List<Quest> quests)
        {
            this.format = format;
            this.quests = Collections.unmodifiableList(quests);
        }

        /** Written by a newer client in a format this one does not know; never guessed at. */
        public boolean tooNew()
        {
            return format > FORMAT;
        }
    }

    /**
     * Quests never shared, by title: the "Beginning of Farming" family every character gets as a
     * tutorial. Nobody can help with them, so they would only clutter every villager's tab.
     */
    public static boolean excluded(String title)
    {
        return title != null && title.toLowerCase(Locale.ROOT).contains("beginning");
    }

    /**
     * Whether every quest's real title is known yet. Until then {@link #fromModel} leaves those quests
     * out, since a stand-in title could hide an {@link #excluded} one, and the caller should ask again.
     */
    public static boolean titlesKnown(Collection<QuestModel.TQuest> quests)
    {
        for(QuestModel.TQuest q : quests) {
            if(q.kind != QuestKind.UNKNOWN && !q.titleKnown())
                return false;
        }
        return true;
    }

    /**
     * What this character shares: every quest whose kind and real title are known, with its objectives
     * as received, minus the {@link #excluded} ones.
     */
    public static List<Quest> fromModel(Collection<QuestModel.TQuest> quests)
    {
        List<Quest> out = new ArrayList<>();
        for(QuestModel.TQuest q : quests) {
            if(q.kind == QuestKind.UNKNOWN || !q.titleKnown() || excluded(q.title()))
                continue;
            List<Cond> conds = new ArrayList<>(q.conds.size());
            for(QCond c : q.conds)
                conds.add(new Cond(c.desc, c.status, c.ready));
            out.add(new Quest(q.resnm, q.title(), q.kind, q.giver, q.condsLoaded, conds));
        }
        return out;
    }

    public static String encode(List<Quest> quests)
    {
        JSONArray jq = new JSONArray();
        for(Quest q : quests) {
            JSONObject o = new JSONObject();
            if(q.res != null)
                o.put("res", q.res);
            o.put("title", q.title);
            o.put("kind", q.kind.name());
            if(q.giver != null)
                o.put("giver", q.giver);
            o.put("loaded", q.loaded);
            JSONArray jc = new JSONArray();
            for(Cond c : q.conds) {
                JSONObject co = new JSONObject();
                co.put("d", c.desc);
                if(c.status != null && !c.status.isEmpty())
                    co.put("s", c.status);
                co.put("r", c.ready);
                jc.put(co);
            }
            o.put("conds", jc);
            jq.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("v", FORMAT);
        root.put("quests", jq);
        return root.toString();
    }

    /**
     * Read a payload. Malformed input comes back empty with format 0, and a newer format comes back
     * empty with {@link Decoded#tooNew()} set, rather than either throwing on the sync worker.
     */
    public static Decoded decode(String json)
    {
        List<Quest> out = new ArrayList<>();
        if(json == null)
            return new Decoded(0, out);
        try {
            JSONObject root = new JSONObject(json);
            int format = root.optInt("v", 0);
            if(format > FORMAT || format <= 0)
                return new Decoded(format, out);
            JSONArray jq = root.optJSONArray("quests");
            if(jq == null)
                return new Decoded(format, out);
            for(int i = 0; i < jq.length(); i++) {
                JSONObject o = jq.optJSONObject(i);
                if(o == null)
                    continue;
                List<Cond> conds = new ArrayList<>();
                JSONArray jc = o.optJSONArray("conds");
                if(jc != null) {
                    for(int k = 0; k < jc.length(); k++) {
                        JSONObject co = jc.optJSONObject(k);
                        if(co != null)
                            conds.add(new Cond(co.optString("d", ""), co.optString("s", null), co.optBoolean("r", false)));
                    }
                }
                out.add(new Quest(o.optString("res", null), o.optString("title", ""), kind(o.optString("kind", null)),
                                  o.optString("giver", null), o.optBoolean("loaded", true), conds));
            }
            return new Decoded(format, out);
        } catch(JSONException e) {
            return new Decoded(0, new ArrayList<>());
        }
    }

    private static QuestKind kind(String s)
    {
        if(s == null)
            return QuestKind.UNKNOWN;
        try {
            return QuestKind.valueOf(s);
        } catch(IllegalArgumentException e) {
            return QuestKind.UNKNOWN;
        }
    }
}
