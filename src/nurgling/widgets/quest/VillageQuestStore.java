package nurgling.widgets.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One session's side of quest sharing, in both directions.
 *
 * <p>Outgoing: the tracker (UI thread) {@link #offer offers} what this character shares, and the sync
 * worker reads it. Incoming: the worker {@link #apply applies} everyone's rows, and the tracker reads
 * {@link #villagers()} and polls {@link #revision()}.
 *
 * <p>Everything crossing threads is an immutable object behind a volatile field, like
 * {@link nurgling.PeerPositionService}. Nothing here blocks or touches a widget.
 */
public class VillageQuestStore
{
    /** A villager whose heartbeat is older than this is shown as offline. */
    public static final long ONLINE_MS = 3 * 60 * 1000L;
    /** A villager not heard from for this long is not shown at all. */
    public static final long HIDE_MS = 7L * 24 * 60 * 60 * 1000L;

    /* ------------------------------------------------------------------ outgoing */

    /** What this session's character wants published. */
    public static final class Outgoing
    {
        public final String charName;
        public final boolean share;
        /** Encoded quests, or null while the quest log has not settled yet. */
        public final String data;
        /** When {@link #data} last changed, for the worker's debounce. */
        public final long changedAt;

        Outgoing(String charName, boolean share, String data, long changedAt)
        {
            this.charName = charName;
            this.share = share;
            this.data = data;
            this.changedAt = changedAt;
        }
    }

    private volatile Outgoing outgoing = null;

    /** Called from the UI thread whenever the tracker's view of this character may have changed. */
    public void offer(String charName, boolean share, String data)
    {
        Outgoing cur = outgoing;
        if(cur != null && eq(cur.charName, charName) && cur.share == share && eq(cur.data, data))
            return;
        long changed = (cur != null && eq(cur.data, data)) ? cur.changedAt : System.currentTimeMillis();
        outgoing = new Outgoing(charName, share, data, changed);
    }

    public Outgoing outgoing()
    {
        return outgoing;
    }

    /* ------------------------------------------------------------------ incoming */

    /** One quest of a villager, with its objectives parsed by this client. */
    public static final class VQuest
    {
        public final SharedQuests.Quest quest;
        public final List<QCond> conds;

        VQuest(SharedQuests.Quest quest)
        {
            this.quest = quest;
            List<QCond> c = new ArrayList<>(quest.conds.size());
            for(SharedQuests.Cond sc : quest.conds)
                c.add(new QCond(-1, sc.ready, sc.desc, sc.status));
            this.conds = Collections.unmodifiableList(c);
        }

        public String key()
        {
            return quest.key();
        }

        public boolean readyToTurnIn()
        {
            if(!quest.loaded || conds.isEmpty())
                return false;
            for(QCond c : conds) {
                if(c.verb != QCond.Verb.TELL && !c.ready)
                    return false;
            }
            return true;
        }
    }

    /** One sharing character as last read from the database. Immutable. */
    public static final class Villager
    {
        public final String name;
        public final int version;
        public final long ageMillis;
        /** Shared in a format newer than this client understands; {@link #quests} is then empty. */
        public final boolean tooNew;
        public final List<VQuest> quests;

        private Villager(String name, int version, long ageMillis, boolean tooNew, List<VQuest> quests)
        {
            this.name = name;
            this.version = version;
            this.ageMillis = ageMillis;
            this.tooNew = tooNew;
            this.quests = quests;
        }

        /** Decode one row. Runs on the sync worker. */
        public static Villager of(String name, int version, long ageMillis, String data)
        {
            SharedQuests.Decoded d = SharedQuests.decode(data);
            List<VQuest> qs = new ArrayList<>(d.quests.size());
            for(SharedQuests.Quest q : d.quests) {
                // Senders already leave these out; this covers rows written before they did.
                if(!SharedQuests.excluded(q.title))
                    qs.add(new VQuest(q));
            }
            return new Villager(name, version, ageMillis, d.tooNew(), Collections.unmodifiableList(qs));
        }

        /** Same content, newer age - what a poll yields for a row whose version did not move. */
        public Villager withAge(long ageMillis)
        {
            return new Villager(name, version, ageMillis, tooNew, quests);
        }

        public boolean online()
        {
            return ageMillis < ONLINE_MS;
        }
    }

    private volatile Map<String, Villager> villagers = Collections.emptyMap();
    private final AtomicInteger revision = new AtomicInteger(0);
    private volatile boolean canWrite = true;
    /** name|version|online of the last apply, to tell a real change from a re-read. */
    private String signature = "";

    /**
     * Replace what we know with one read's worth of villagers. {@code self} is this session's own
     * character, dropped here so the player's other characters still show.
     */
    public synchronized void apply(Collection<Villager> all, String self, boolean canWrite)
    {
        this.canWrite = canWrite;
        Map<String, Villager> next = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        StringBuilder sig = new StringBuilder();
        for(Villager v : all) {
            if(v.name == null || v.name.equals(self) || v.ageMillis >= HIDE_MS)
                continue;
            next.put(v.name, v);
        }
        for(Villager v : next.values())
            sig.append(v.name).append('|').append(v.version).append('|').append(v.online()).append('\n');
        villagers = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        String s = sig.toString();
        if(!s.equals(signature)) {
            signature = s;
            revision.incrementAndGet();
        }
    }

    /** Forget everyone - the database went away. */
    public synchronized void clear()
    {
        if(!villagers.isEmpty()) {
            villagers = Collections.emptyMap();
            revision.incrementAndGet();
        }
        signature = "";
    }

    /** Sharing villagers by name, excluding this character. */
    public Map<String, Villager> villagers()
    {
        return villagers;
    }

    /** Bumped whenever {@link #villagers()} changed in a way a view cares about. */
    public int revision()
    {
        return revision.get();
    }

    /** False for a read-only database login: this character can see the village but not share. */
    public boolean canWrite()
    {
        return canWrite;
    }

    private static boolean eq(Object a, Object b)
    {
        return (a == null) ? (b == null) : a.equals(b);
    }
}
