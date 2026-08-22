package nurgling.widgets.quest;

import haven.*;

import java.util.*;

/**
 * The quest tracker's model.
 *
 * Instead of keeping a shadow copy of the quest log fed by id-only hooks, this reads the
 * real thing off {@link CharWnd} every tick - {@link QuestWnd.QuestList#quests} for titles,
 * resources, state and mtime, and {@link SkillWnd.CredoGrid} for the credo lists. That is
 * what makes {@link #classify} exact rather than a guess about condition text.
 *
 * Conditions are the one thing the client cannot read passively: the server only sends them
 * for the selected quest, so they still have to be harvested with {@code qsel}. The sweep here
 * runs one request at a time, times out, and puts the player's own selection back when it is
 * done - see {@link #pumpConds}.
 *
 * Lives on the UI thread. {@link #snapshot()} publishes an immutable view for the gob-overlay
 * code, which runs on pool threads.
 */
public class QuestModel
{
    /**
     * Credo resource leaves known at the time of writing, harvested from the resource cache.
     * Only consulted while the server's own {@code ccr}/{@code ncr} lists are still empty
     * (the first moments after login); the server lists win as soon as they arrive.
     */
    private static final Set<String> KNOWN_CREDO_LEAVES = new HashSet<>(Arrays.asList(
        "farming", "firecraft", "fishing", "forage", "forging", "hunting",
        "lumber", "metalworking", "pottery", "steelmaking", "stonework", "tanning"));

    private static final String QPATH = "paginae/quest/";
    /** Give up on a condition request after this long and move on, so the sweep can't stall. */
    private static final double COND_TIMEOUT = 5.0;

    /** One quest in the current (not completed) log. */
    public static class TQuest
    {
        public final int id;
        public Indir<Resource> res;
        public String stitle;
        public int done = QuestWnd.Quest.QST_PEND;
        public int mtime = Integer.MIN_VALUE;
        public String resnm;
        public QuestKind kind = QuestKind.UNKNOWN;
        public List<QCond> conds = Collections.emptyList();
        public boolean condsLoaded = false;
        public boolean condsStale = true;
        /** Giver of this quest, from its own {@code Tell} objective. Canonicalised. */
        public String giver = null;

        TQuest(int id)
        {
            this.id = id;
        }

        /** Display name: server title, else the resource tooltip, else the resource leaf. */
        public String title()
        {
            if(stitle != null && !stitle.isEmpty())
                return stitle;
            if(res != null) {
                try {
                    Resource.Tooltip tt = res.get().layer(Resource.tooltip);
                    if(tt != null && tt.t != null && !tt.t.isEmpty())
                        return tt.t;
                } catch(Loading ignore) {
                }
            }
            return leaf();
        }

        /** Last path element of the quest resource, or {@code "#id"} while unresolved. */
        public String leaf()
        {
            if(resnm == null)
                return "#" + id;
            int i = resnm.lastIndexOf('/');
            return (i < 0) ? resnm : resnm.substring(i + 1);
        }

        /** Stable key for hide/pin/collapse persistence - survives a quest being re-issued. */
        public String key()
        {
            return (resnm != null) ? resnm : ("id:" + id);
        }

        /** True once every non-{@code Tell} objective is satisfied, i.e. ready to hand in. */
        public boolean readyToTurnIn()
        {
            if(!condsLoaded || conds.isEmpty())
                return false;
            for(QCond c : conds) {
                if(c.verb != QCond.Verb.TELL && !c.ready)
                    return false;
            }
            return true;
        }
    }

    /** Immutable derived data the gob overlays and item highlighting read from other threads. */
    public static class Snapshot
    {
        public final Set<String> hunt;
        public final Set<String> forage;
        public final Set<String> bring;

        Snapshot(Set<String> hunt, Set<String> forage, Set<String> bring)
        {
            this.hunt = Collections.unmodifiableSet(hunt);
            this.forage = Collections.unmodifiableSet(forage);
            this.bring = Collections.unmodifiableSet(bring);
        }
    }

    private final Map<Integer, TQuest> quests = new LinkedHashMap<>();
    private final Set<Integer> pendingRemove = new HashSet<>();
    private final Set<Integer> pendingAdd = new HashSet<>();
    private volatile Snapshot snap = new Snapshot(
        Collections.<String>emptySet(), Collections.<String>emptySet(), Collections.<String>emptySet());

    /** Bumped whenever anything a view or an overlay cares about changed. */
    private int revision = 0;
    private boolean dirty = true;

    /* condition sweep state */
    private int inflight = -1;
    private double inflightAge = 0;
    private int savedSel = -1;
    private boolean sweeping = false;

    /** Names of quest givers we have seen a map marker for - used to canonicalise parsed names. */
    private final Set<String> knownGivers = new HashSet<>();

    /** Quest id of the credo currently being pursued, or 0. Straight from the server. */
    private int pqid = 0;

    public int pursuedCredoId()
    {
        return pqid;
    }

    public int revision()
    {
        return revision;
    }

    public Snapshot snapshot()
    {
        return snap;
    }

    public Collection<TQuest> quests()
    {
        return quests.values();
    }

    /* ------------------------------------------------------------------ server hooks */

    /** From {@code QuestWnd.uimsg}: a quest entered the current list. */
    public void addQuest(int id)
    {
        synchronized(pendingAdd) {
            pendingAdd.add(id);
        }
    }

    /** From {@code QuestWnd.uimsg}: a quest left the current list (completed, failed or gone). */
    public void removeQuest(int id)
    {
        synchronized(pendingRemove) {
            pendingRemove.add(id);
        }
    }

    /** From {@code Quest.Box.uimsg("conds")}: the objectives of the selected quest. */
    public void setConds(int id, Object[] args)
    {
        TQuest q = quests.get(id);
        if(q == null) {
            q = new TQuest(id);
            quests.put(id, q);
        }
        List<QCond> nc = new ArrayList<>();
        int a = 0;
        while(a < args.length) {
            String desc = PType.STR.of(args[a++]);
            if(a >= args.length)
                break;
            int st = PType.INT.of(args[a++]);
            String status = (a < args.length) ? PType.STR.of(args[a++]) : null;
            if((a < args.length) && PType.OBJS.is(args[a]))
                a++;
            nc.add(new QCond(id, st != 0, desc, status));
        }
        q.conds = nc;
        q.condsLoaded = true;
        q.condsStale = false;
        if(inflight == id)
            inflight = -1;
        dirty = true;
    }

    /** A quest giver marker was discovered on the minimap; its name is authoritative. */
    public void noteGiverName(String nm)
    {
        if(nm == null || nm.isEmpty())
            return;
        if(knownGivers.add(nm))
            dirty = true;
    }

    /* ------------------------------------------------------------------ tick */

    /**
     * Reconcile with the character sheet and keep the condition sweep moving.
     *
     * @return true if the derived state changed and the view should be rebuilt.
     */
    public boolean tick(double dt, CharWnd chr)
    {
        drainPending();
        if(chr != null) {
            syncQuests(chr.quest);
            classifyAll(chr.skill);
            pumpConds(dt, chr.quest);
        }
        if(!dirty)
            return false;
        rederive();
        dirty = false;
        revision++;
        return true;
    }

    private void drainPending()
    {
        synchronized(pendingRemove) {
            for(Integer id : pendingRemove) {
                if(quests.remove(id) != null)
                    dirty = true;
                if(inflight == id)
                    inflight = -1;
            }
            pendingRemove.clear();
        }
        synchronized(pendingAdd) {
            for(Integer id : pendingAdd) {
                if(!quests.containsKey(id)) {
                    quests.put(id, new TQuest(id));
                    dirty = true;
                }
            }
            pendingAdd.clear();
        }
    }

    /**
     * Mirror the current-quest list. This is also where {@code mtime} finally gets used: the
     * server bumps it whenever a quest changes, which is our cue that the cached objectives
     * are out of date. The old tracker parsed mtime and then never looked at it, so condition
     * progress stayed frozen at whatever it was when the quest was first seen.
     */
    private void syncQuests(QuestWnd qw)
    {
        if(qw == null || qw.cqst == null)
            return;
        Set<Integer> live = new HashSet<>();
        for(QuestWnd.Quest q : qw.cqst.quests) {
            live.add(q.id);
            TQuest t = quests.get(q.id);
            if(t == null) {
                t = new TQuest(q.id);
                quests.put(q.id, t);
                dirty = true;
            }
            if(t.res != q.res) {
                t.res = q.res;
                t.resnm = resnm(q.res);
                t.kind = QuestKind.UNKNOWN;
                dirty = true;
            } else if(t.resnm == null && t.res != null) {
                // The resource id can arrive before its name does; keep retrying, or the quest
                // would stay UNKNOWN forever and never make it into the panel.
                String rn = resnm(t.res);
                if(rn != null) {
                    t.resnm = rn;
                    dirty = true;
                }
            }
            if(!Utils.eq(t.stitle, q.title)) {
                t.stitle = q.title;
                dirty = true;
            }
            if(t.done != q.done) {
                t.done = q.done;
                dirty = true;
            }
            if(t.mtime != q.mtime) {
                if(t.mtime != Integer.MIN_VALUE)
                    t.condsStale = true;
                t.mtime = q.mtime;
                dirty = true;
            }
        }
        for(Iterator<Map.Entry<Integer, TQuest>> i = quests.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry<Integer, TQuest> e = i.next();
            if(!live.contains(e.getKey())) {
                i.remove();
                if(inflight == e.getKey())
                    inflight = -1;
                dirty = true;
            }
        }
    }

    /** Resource name without forcing a load - {@code CachedRes.Ref} knows its own name. */
    private static String resnm(Indir<Resource> res)
    {
        if(res == null)
            return null;
        if(res instanceof Session.CachedRes.Ref) {
            String nm = ((Session.CachedRes.Ref)res).resnm();
            if(nm != null)
                return nm;
        }
        try {
            return res.get().name;
        } catch(Loading ignore) {
            return null;
        }
    }

    private void classifyAll(SkillWnd sk)
    {
        SkillWnd.CredoGrid cr = (sk != null) ? sk.credos : null;
        Set<String> credoLeaves = credoLeaves(cr);
        int pqid = (cr != null) ? cr.pqid : 0;
        if(pqid != this.pqid) {
            this.pqid = pqid;
            dirty = true;
        }
        for(TQuest q : quests.values()) {
            QuestKind k = classify(q, pqid, credoLeaves);
            if(k != q.kind) {
                q.kind = k;
                dirty = true;
            }
        }
    }

    /**
     * Decide what kind of quest this is.
     *
     * The pursued credo's quest id comes straight from the server's {@code pcr} message, so it
     * is exact. Everything else falls out of the resource path: {@code paginae/quest/act/*} are
     * the quests NPCs hand out, {@code paginae/quest/<credo>} are the credo quests, and the
     * remaining leaves under {@code paginae/quest/} are world quests (ancestral quest, bury the
     * dead, wind quest). A quest whose resource name is not known yet stays UNKNOWN and is left
     * out of the panel for a frame rather than being dumped into the credo bucket.
     */
    public static QuestKind classify(TQuest q, int pqid, Set<String> credoLeaves)
    {
        if(pqid != 0 && q.id == pqid)
            return QuestKind.CREDO;
        if(q.resnm == null)
            return QuestKind.UNKNOWN;
        int i = q.resnm.indexOf(QPATH);
        if(i < 0)
            return QuestKind.UNKNOWN;
        String leaf = q.resnm.substring(i + QPATH.length());
        if(leaf.startsWith("act/"))
            return QuestKind.NPC;
        if(credoLeaves.contains(leaf))
            return QuestKind.CREDO;
        return QuestKind.WORLD;
    }

    /** Credo resource leaves the server has told us about, falling back to the known set. */
    private static Set<String> credoLeaves(SkillWnd.CredoGrid cr)
    {
        Set<String> out = new HashSet<>();
        if(cr != null) {
            addCredo(out, cr.ccr);
            addCredo(out, cr.ncr);
            if(cr.pcr != null)
                addCredo(out, Collections.singletonList(cr.pcr));
        }
        if(out.isEmpty())
            return KNOWN_CREDO_LEAVES;
        out.addAll(KNOWN_CREDO_LEAVES);
        return out;
    }

    private static void addCredo(Set<String> out, List<SkillWnd.Credo> l)
    {
        if(l == null)
            return;
        for(SkillWnd.Credo c : l) {
            if(c.nm != null)
                out.add(c.nm.toLowerCase());
            String rn = resnm(c.res);
            if(rn != null) {
                int i = rn.lastIndexOf('/');
                out.add(((i < 0) ? rn : rn.substring(i + 1)).toLowerCase());
            }
        }
    }

    /**
     * Ask the server for the objectives of one quest at a time.
     *
     * {@code qsel} is the selection message, so harvesting conditions necessarily moves the
     * Quest Log's selection. The old tracker fired one per quest in a single tick and left the
     * log parked on whichever it asked for last; this remembers what the player had selected,
     * runs one request at a time, and puts the selection back once the queue drains - unless
     * the player picked something else in the meantime, in which case their choice stands.
     */
    private void pumpConds(double dt, QuestWnd qw)
    {
        if(qw == null)
            return;
        if(inflight >= 0) {
            inflightAge += dt;
            if(inflightAge < COND_TIMEOUT)
                return;
            TQuest stuck = quests.get(inflight);
            if(stuck != null) {
                // Server never answered; accept an empty objective list so we stop asking.
                stuck.condsLoaded = true;
                stuck.condsStale = false;
            }
            inflight = -1;
        }
        TQuest next = null;
        for(TQuest q : quests.values()) {
            if(!q.condsLoaded || q.condsStale) {
                next = q;
                break;
            }
        }
        if(next != null) {
            if(!sweeping) {
                savedSel = selection(qw);
                sweeping = true;
            }
            inflight = next.id;
            inflightAge = 0;
            qw.wdgmsg("qsel", next.id);
            return;
        }
        if(sweeping) {
            sweeping = false;
            // Only put the selection back if the log is still showing what we asked for -
            // if the player clicked a quest themselves mid-sweep, leave their choice alone.
            int cur = selection(qw);
            if(cur != savedSel && quests.containsKey(cur)) {
                if(savedSel >= 0)
                    qw.wdgmsg("qsel", savedSel);
                else
                    qw.wdgmsg("qsel", (Object)null);
            }
            savedSel = -1;
        }
    }

    private static int selection(QuestWnd qw)
    {
        return (qw.quest != null) ? qw.quest.questid() : -1;
    }

    /* ------------------------------------------------------------------ derived state */

    private void rederive()
    {
        Set<String> hunt = new HashSet<>(), forage = new HashSet<>(), bring = new HashSet<>();
        for(TQuest q : quests.values()) {
            q.giver = null;
            for(QCond c : q.conds) {
                if(c.verb == QCond.Verb.TELL && c.giver != null)
                    q.giver = canonGiver(c.giver);
                if(c.ready)
                    continue;
                if(c.verb == QCond.Verb.KILL && c.gobTarget != null)
                    hunt.add(c.gobTarget);
                else if(c.verb == QCond.Verb.PICK && c.gobTarget != null)
                    forage.add(c.gobTarget);
                else if(c.verb == QCond.Verb.BRING && c.bringItem != null)
                    bring.add(c.bringItem);
            }
        }
        snap = new Snapshot(hunt, forage, bring);
    }

    /**
     * Map a name sliced out of condition text onto the real NPC name.
     *
     * {@code Tell} objectives only yield the first word of the giver's name, while
     * {@code Bring ... to <name>} yields all of it - which used to split one NPC across two
     * groups. Map markers carry the authoritative name, so prefer those on a word boundary.
     */
    public String canonGiver(String nm)
    {
        if(nm == null)
            return null;
        if(knownGivers.contains(nm))
            return nm;
        String best = null;
        for(String k : knownGivers) {
            if(k.length() > nm.length() && k.startsWith(nm) && k.charAt(nm.length()) == ' ') {
                if(best == null || k.length() < best.length())
                    best = k;
            }
        }
        return (best != null) ? best : nm;
    }
}
