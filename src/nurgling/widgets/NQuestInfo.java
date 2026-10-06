package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NGItem;
import nurgling.NStyle;
import nurgling.NUI;
import nurgling.conf.FontSettings;
import nurgling.conf.NQuestTrackerProp;
import nurgling.styles.UITheme;
import nurgling.widgets.nsettings.Fonts;
import nurgling.widgets.quest.QCond;
import nurgling.widgets.quest.QuestObjectiveAction;
import nurgling.widgets.quest.QuestObjectiveActionButton;
import nurgling.widgets.quest.QuestObjectiveActionResolver;
import nurgling.widgets.quest.QuestObjectiveRowLayout;
import nurgling.widgets.quest.QuestKind;
import nurgling.widgets.quest.QuestMenu;
import nurgling.widgets.quest.QuestModel;
import nurgling.widgets.quest.SharedQuests;
import nurgling.widgets.quest.VillageQuestStore;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The HUD quest tracker.
 *
 * A header of filters over a scrollable list of real row widgets, driven by
 * {@link QuestModel}. Groups are collapsed by default, so a character with twenty active
 * quests gets a dozen rows rather than sixty.
 *
 * The world-space side of this widget - {@link #huntingT}, {@link #forageT}, the marker
 * table and {@link #isQuestedItem} - is read from gob-tick threads by
 * {@link nurgling.NGob}, {@link nurgling.NGItem} and {@link haven.MiniMap}, so it is kept
 * separate from the view and published as immutable sets.
 */
public class NQuestInfo extends Widget
{
    /* ------------------------------------------------------------------ layout */

    private static final Coord PAD = UI.scale(new Coord(4, 3));
    private static final int INDENT = UI.scale(14);
    private static final int CHEV_W = UI.scale(10);
    private static final Coord CHIP_SZ = UI.scale(new Coord(17, 15));
    private static final Coord DEF_SZ = UI.scale(new Coord(252, 216));

    /* ------------------------------------------------------------------ overlay API */

    /**
     * Bumped whenever the tracked set changes. {@link nurgling.NGob} and {@link NGItem}
     * poll this to know when to re-evaluate their cached quest highlighting.
     */
    public final AtomicInteger lastUpdate = new AtomicInteger(0);

    /** Gob-name fragments of unfinished {@code Kill} objectives. Replaced wholesale, never mutated. */
    public volatile Set<String> huntingT = Collections.emptySet();
    /** Gob-name fragments of unfinished {@code Pick} objectives. */
    public volatile Set<String> forageT = Collections.emptySet();
    /** Lowercased item names of unfinished {@code Bring} objectives. */
    private volatile Set<String> bringItems = Collections.emptySet();

    /**
     * Bumped whenever {@link #villageWanters} would answer differently. Separate from
     * {@link #lastUpdate} so a villager's change does not make every gob re-evaluate itself.
     */
    public final AtomicInteger villageUpdate = new AtomicInteger(0);

    /** One villager wanting an item, for the item frame and its tooltip. */
    public static final class Want
    {
        public final String name;
        public final String text;
        public final boolean online;

        Want(String name, String text, boolean online)
        {
            this.name = name;
            this.text = text;
            this.online = online;
        }

        @Override
        public boolean equals(Object o)
        {
            if(!(o instanceof Want))
                return false;
            Want w = (Want)o;
            return name.equals(w.name) && text.equals(w.text) && online == w.online;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(name, text, online);
        }
    }

    /** Lowercased bring-item name to the villagers wanting it. Replaced wholesale, never mutated. */
    private volatile Map<String, List<Want>> villageBring = Collections.emptyMap();

    /* ------------------------------------------------------------------ state */

    private final QuestModel model = new QuestModel();

    private final QuestObjectiveActionResolver actionResolver = new QuestObjectiveActionResolver();
    private NQuestTrackerProp prop = null;
    private NQuestTrackerProp fallback = null;
    private boolean needRebuild = true;

    private Scrollport body;
    private ACheckBox modebtn, searchbtn, gearbtn;
    /* New UI (decided at client start, like the fonts): flat icon toolbar. */
    private final boolean flat = nurgling.styles.UIResources.active();
    private KindChip[] chips;
    private TextEntry searchbox;
    private String search = "";
    private int headerH = 0;

    private FontSettings fontsrc = null;
    private Text.Foundry groupFnd, condFnd;
    private int rowH = UI.scale(14);

    /** Giver names whose marker props we set last rebuild, so vanished ones can be cleared. */
    private final Set<String> markedGivers = new HashSet<>();
    /** The prop sets we published last rebuild, to tell a real change from a rebuild. */
    private final Map<String, HashSet<String>> markedProps = new HashMap<>();

    /* village tab */
    private final TabStrip tabs;
    /** Database up with quest sharing available; the tab strip only exists while this holds. */
    private boolean connected = false;
    private int villageRev = -1;
    /** Villagers with at least one quest, for the tab label. */
    private int villageCount = 0;
    /* what was last offered for publishing, so the JSON is only rebuilt when it can differ */
    private int offeredRev = -1;
    private boolean offeredShare = false;
    private boolean offeredSettled = false;
    /** When to rebuild the offer again because some quest titles were still loading; 0 = not needed. */
    private double titlesPendingAt = 0;
    private Text.Foundry chipFnd;
    private final Map<String, Tex> chipCache = new HashMap<>();

    public NQuestInfo()
    {
        super(DEF_SZ);
        fonts();
        tabs = add(new TabStrip());
        tabs.hide();
        modebtn = add(flat ? new NToolbarToggle(NToolbarToggle.Glyph.GROUP, "Group by quest giver / by task")
                           : new NMiniMapWnd.NMenuCheckBox("nurgling/hud/buttons/questmode", null, "Group by quest giver / by task"));
        modebtn.changed(a -> {
            prop().mode = a ? NQuestTrackerProp.Mode.TASKS : NQuestTrackerProp.Mode.GIVERS;
            prop().save();
            needRebuild = true;
        });
        chips = new KindChip[] {
            add(new KindChip(QuestKind.NPC, "N", NToolbarToggle.Glyph.NPC, NStyle.questGiver, "Quests from quest givers")),
            add(new KindChip(QuestKind.CREDO, "C", NToolbarToggle.Glyph.CREDO, NStyle.questCredo, "Credo quests")),
            add(new KindChip(QuestKind.WORLD, "W", NToolbarToggle.Glyph.WORLD, NStyle.questWorld, "World quests")),
        };
        searchbtn = add(flat ? new NToolbarToggle(NToolbarToggle.Glyph.SEARCH, "Search quests")
                             : new NMiniMapWnd.NMenuCheckBox("nurgling/hud/buttons/lsearch", null, "Search quests"));
        searchbtn.changed(a -> {
            search = "";
            if(searchbox != null)
                searchbox.settext("");
            relayout();
            needRebuild = true;
        });
        gearbtn = add(flat ? new NToolbarToggle(NToolbarToggle.Glyph.SETTINGS, "Tracker options")
                           : new NMiniMapWnd.NMenuCheckBox("nurgling/hud/buttons/settings", null, "Tracker options"));
        gearbtn.changed(a -> {
            gearbtn.a = false;
            openGearMenu();
        });
        searchbox = add(new TextEntry(DEF_SZ.x - PAD.x * 2, "") {
            @Override
            protected void changed()
            {
                super.changed();
                NQuestInfo.this.search = text().trim().toLowerCase();
                NQuestInfo.this.needRebuild = true;
            }
        });
        searchbox.hide();
        body = add(new Scrollport(DEF_SZ));
        relayout();
    }

    /* ------------------------------------------------------------------ settings */

    private NQuestTrackerProp prop()
    {
        if(prop == null) {
            prop = (ui instanceof NUI) ? NQuestTrackerProp.get((NUI)ui) : null;
            if(prop == null) {
                // Character not resolved yet: run on defaults (which never persist, see
                // NQuestTrackerProp.save) and pick up the real settings once login finishes.
                if(fallback == null)
                    fallback = new NQuestTrackerProp("", "");
                return fallback;
            }
            modebtn.a = (prop.mode == NQuestTrackerProp.Mode.TASKS);
            for(KindChip c : chips)
                c.a = prop.kinds.contains(c.kind);
            // Settings arrived after the first rebuild ran on defaults - redo it with them.
            needRebuild = true;
        }
        return prop;
    }

    /** Rebuild the three text roles from the user's chosen Quests font. */
    private void fonts()
    {
        Object cur = NConfig.get(NConfig.Key.fonts);
        if(!(cur instanceof FontSettings) || cur == fontsrc)
            return;
        fontsrc = (FontSettings)cur;
        Text.Foundry base = fontsrc.getFoundary(Fonts.FontType.QUESTS);
        if(base == null)
            base = new Text.Foundry(Text.sans, 12);
        java.awt.Font f = base.font;
        groupFnd = new Text.Foundry(f.deriveFont(java.awt.Font.BOLD), Color.WHITE).aa(true);
        condFnd = new Text.Foundry(f.deriveFont(Math.max(8f, f.getSize2D() - UI.scale(1f))),
                                   NStyle.questCond).aa(true);
        rowH = groupFnd.height() + UI.scale(3);
        chipFnd = new Text.Foundry(f.deriveFont(java.awt.Font.BOLD, Math.max(8f, f.getSize2D() - UI.scale(2f))),
                                   NStyle.infoBg).aa(true);
        for(Tex t : chipCache.values())
            t.dispose();
        chipCache.clear();
        needRebuild = true;
    }

    /* ------------------------------------------------------------------ layout */

    @Override
    public void resize(Coord sz)
    {
        super.resize(sz);
        relayout();
        needRebuild = true;
    }

    private void relayout()
    {
        int x = PAD.x, top = PAD.y;
        if(connected) {
            tabs.show();
            tabs.c = Coord.z;
            tabs.resize(new Coord(sz.x, rowH + UI.scale(4)));
            top += tabs.sz.y;
        } else {
            tabs.hide();
        }
        if(flat) {
            // Same-sized icon buttons in one row, centred on the tallest.
            int toolbarH = Math.max(chips[0].sz.y, Math.max(modebtn.sz.y, Math.max(searchbtn.sz.y, gearbtn.sz.y)));
            int gap = UI.scale(2);
            modebtn.c = new Coord(x, top + (toolbarH - modebtn.sz.y) / 2);
            x += modebtn.sz.x + gap;
            for(KindChip c : chips) {
                c.c = new Coord(x, top + (toolbarH - c.sz.y) / 2);
                x += c.sz.x + gap;
            }
            int rx = sz.x - PAD.x - gearbtn.sz.x;
            gearbtn.c = new Coord(rx, top + (toolbarH - gearbtn.sz.y) / 2);
            rx -= searchbtn.sz.x + gap;
            searchbtn.c = new Coord(rx, top + (toolbarH - searchbtn.sz.y) / 2);
        } else {
            modebtn.c = new Coord(x, top);
            x += modebtn.sz.x + PAD.x;
            for(KindChip c : chips) {
                c.c = new Coord(x, top + (modebtn.sz.y - c.sz.y) / 2);
                x += c.sz.x + UI.scale(2);
            }
            int rx = sz.x - PAD.x - gearbtn.sz.x;
            gearbtn.c = new Coord(rx, top);
            rx -= searchbtn.sz.x + PAD.x;
            searchbtn.c = new Coord(rx, top);
        }

        int y = top + modebtn.sz.y + PAD.y;
        if(searchbtn.a) {
            searchbox.show();
            searchbox.resize(Math.max(UI.scale(40), sz.x - PAD.x * 2));
            searchbox.c = new Coord(PAD.x, y);
            y += searchbox.sz.y + PAD.y;
        } else {
            searchbox.hide();
        }
        headerH = y;
        body.c = new Coord(0, headerH);
        body.resize(new Coord(sz.x, Math.max(rowH, sz.y - headerH)));
    }

    /* ------------------------------------------------------------------ tick */

    @Override
    public void tick(double dt)
    {
        super.tick(dt);
        fonts();
        NGameUI gui = getparent(NGameUI.class);
        if(model.tick(dt, (gui != null) ? gui.chrwdg : null))
            needRebuild = true;
        tickVillage(gui);
        if(needRebuild) {
            needRebuild = false;
            rebuild();
        }
    }

    /* ------------------------------------------------------------------ village sync */

    /** The shared database is up and has quest sharing. Two field reads, cheap enough per tick. */
    private static boolean dbConnected()
    {
        nurgling.db.DatabaseManager dm = nurgling.NCore.databaseManager;
        return dm != null && dm.getQuestShareService() != null;
    }

    private static nurgling.db.service.QuestShareDbService shareService()
    {
        nurgling.db.DatabaseManager dm = nurgling.NCore.databaseManager;
        return (dm != null) ? dm.getQuestShareService() : null;
    }

    /** True while the Village tab is the one on screen. */
    private boolean villageShown()
    {
        return connected && prop().villageTab;
    }

    private void tickVillage(NGameUI gui)
    {
        boolean conn = dbConnected();
        if(conn != connected) {
            connected = conn;
            if(!conn && gui != null && gui.villageQuests != null)
                gui.villageQuests.clear();
            if(conn && prop().villageTab)
                requestRead();
            relayout();
            needRebuild = true;
        }
        if(gui == null || gui.villageQuests == null)
            return;
        VillageQuestStore store = gui.villageQuests;
        int rev = store.revision();
        if(rev != villageRev) {
            villageRev = rev;
            rebuildVillageIndex(store);
            if(villageShown())
                needRebuild = true;
        }
        offer(gui, false);
    }

    /**
     * Hand this character's quests to the sync worker. The JSON is only rebuilt when the model, the
     * share flag or the login guard moved; the store itself drops offers that did not change.
     */
    private void offer(NGameUI gui, boolean force)
    {
        if(gui == null || gui.villageQuests == null || gui.chrid == null || prop == null)
            return;
        boolean share = prop.shareQuests;
        boolean settled = model.settled();
        boolean retry = (titlesPendingAt > 0) && (Utils.rtime() >= titlesPendingAt);
        if(!force && !retry && offeredRev == model.revision() && offeredShare == share && offeredSettled == settled)
            return;
        offeredRev = model.revision();
        offeredShare = share;
        offeredSettled = settled;
        String data = null;
        titlesPendingAt = 0;
        if(share && settled) {
            data = SharedQuests.encode(SharedQuests.fromModel(model.quests()));
            // A quest whose resource is still loading was left out (its stand-in name could hide a
            // "Beginning" quest). Loading does not move the model's revision, so look again shortly.
            if(!SharedQuests.titlesKnown(model.quests()))
                titlesPendingAt = Utils.rtime() + 1.0;
        }
        gui.villageQuests.offer(gui.chrid, share, data);
    }

    private void requestRead()
    {
        nurgling.db.service.QuestShareDbService svc = shareService();
        if(svc != null)
            svc.requestRead();
    }

    private void setShare(boolean on)
    {
        NQuestTrackerProp p = prop();
        if(p.shareQuests == on)
            return;
        p.shareQuests = on;
        p.save();
        NGameUI gui = getparent(NGameUI.class);
        // Offer first: the worker must see "not sharing" before the withdraw it is about to run.
        offer(gui, true);
        if(!on && gui != null) {
            nurgling.db.service.QuestShareDbService svc = shareService();
            if(svc != null)
                svc.withdraw(gui.getGenus(), gui.chrid);
        }
        needRebuild = true;
    }

    private void setVillageTab(boolean village)
    {
        NQuestTrackerProp p = prop();
        if(p.villageTab == village)
            return;
        p.villageTab = village;
        p.save();
        if(village)
            requestRead();
        needRebuild = true;
    }

    private boolean villagerVisible(VillageQuestStore.Villager v, NQuestTrackerProp p)
    {
        return !p.hiddenVillagers.contains(v.name);
    }

    /** Rebuild the bring index the item frames read, and the tab's villager count. */
    private void rebuildVillageIndex(VillageQuestStore store)
    {
        NQuestTrackerProp p = prop();
        Map<String, List<Want>> idx = new HashMap<>();
        int count = 0;
        if(connected) {
            for(VillageQuestStore.Villager v : store.villagers().values()) {
                if(!villagerVisible(v, p))
                    continue;
                if(!v.quests.isEmpty())
                    count++;
                for(VillageQuestStore.VQuest q : v.quests) {
                    for(QCond c : q.conds) {
                        if(c.verb != QCond.Verb.BRING || c.ready || c.bringItem == null)
                            continue;
                        idx.computeIfAbsent(c.bringItem, k -> new ArrayList<>()).add(new Want(v.name, c.text, v.online()));
                    }
                }
            }
        }
        villageCount = count;
        if(!idx.equals(villageBring)) {
            Map<String, List<Want>> frozen = new HashMap<>();
            for(Map.Entry<String, List<Want>> e : idx.entrySet())
                frozen.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
            villageBring = Collections.unmodifiableMap(frozen);
            villageUpdate.incrementAndGet();
        }
    }

    /* ------------------------------------------------------------------ view model */

    private static class Row
    {
        final String text;
        final boolean ready;
        final int questId;
        final boolean secondary;
        /** The objective this row was built from, so the row can offer an action button. */
        final QCond cond;
        /** Villagers this row belongs to, drawn as name chips. Empty on the Mine tab. */
        final List<VillageQuestStore.Villager> holders = new ArrayList<>();

        Row(String text, boolean ready, int questId, boolean secondary, QCond cond)
        {
            this.text = text;
            this.ready = ready;
            this.questId = questId;
            this.secondary = secondary;
            this.cond = cond;
        }
    }

    private static class Group
    {
        String key;
        String title;
        QuestKind kind = QuestKind.NPC;
        String giver;
        String questKey;
        int questId = -1;
        boolean ready;
        boolean idle;
        boolean pinned;
        int done, total;
        final List<Row> rows = new ArrayList<>();
        /** Villagers holding this quest; non-empty only on the Village tab. */
        final List<VillageQuestStore.Villager> holders = new ArrayList<>();
        boolean village;

        Color titleColor()
        {
            if(ready)
                return NStyle.questReady;
            if(idle)
                return NStyle.questGiverIdle;
            switch(kind) {
                case CREDO: return NStyle.questCredo;
                case WORLD: return NStyle.questWorld;
                default:    return NStyle.questGiver;
            }
        }
    }

    private void rebuild()
    {
        NQuestTrackerProp p = prop();
        List<Group> groups;
        if(villageShown())
            groups = (p.mode == NQuestTrackerProp.Mode.TASKS) ? villageTaskGroups(p) : villageQuestGroups(p);
        else
            groups = (p.mode == NQuestTrackerProp.Mode.TASKS) ? taskGroups(p) : giverGroups(p);
        boolean overlays = applyMarkerProps();
        filterAndSort(groups, p);
        layoutRows(groups, p);
        QuestModel.Snapshot s = model.snapshot();
        if(!s.hunt.equals(huntingT) || !s.forage.equals(forageT) || !s.bring.equals(bringItems)) {
            huntingT = s.hunt;
            forageT = s.forage;
            bringItems = s.bring;
            overlays = true;
        }
        // Only wake the gob overlays when what they read actually changed - collapsing a group
        // is a view change, and should not make every gob in the world re-evaluate itself.
        if(overlays)
            lastUpdate.incrementAndGet();
    }

    /** Should this quest be considered at all, before per-group filtering? */
    private boolean visible(QuestModel.TQuest q, NQuestTrackerProp p)
    {
        if(q.kind == QuestKind.UNKNOWN)
            return false;
        if(p.hiddenQuests.contains(q.key()))
            return false;
        return p.kinds.contains(q.kind) || p.pinned.contains(q.key());
    }

    private List<Group> giverGroups(NQuestTrackerProp p)
    {
        Map<String, Group> byGiver = new LinkedHashMap<>();
        List<Group> out = new ArrayList<>();
        for(QuestModel.TQuest q : model.quests()) {
            if(!visible(q, p))
                continue;
            if(q.kind == QuestKind.CREDO || q.kind == QuestKind.WORLD || q.giver == null) {
                Group g = new Group();
                g.key = q.key();
                g.questKey = q.key();
                g.kind = q.kind;
                g.questId = q.id;
                g.title = q.title();
                g.ready = q.readyToTurnIn();
                for(QCond c : q.conds) {
                    if(c.verb == QCond.Verb.TELL)
                        continue;
                    g.rows.add(new Row(c.text, c.ready, q.id, false, c));
                }
                g.total = g.rows.size();
                g.done = 0;
                for(Row r : g.rows) {
                    if(r.ready)
                        g.done++;
                }
                out.add(g);
                continue;
            }
            Group g = group(byGiver, q.giver);
            g.questKey = q.key();
            if(g.questId < 0)
                g.questId = q.id;
            if(q.readyToTurnIn())
                g.ready = true;
            for(QCond c : q.conds) {
                if(c.verb == QCond.Verb.TELL)
                    continue;
                g.rows.add(new Row(c.text, c.ready, q.id, false, c));
            }
        }
        // Objectives that point at a giver but belong to somebody else's quest - "bring X to
        // Jenny" shows under Jenny too, so her group tells you what she is waiting for.
        for(QuestModel.TQuest q : model.quests()) {
            if(!visible(q, p))
                continue;
            for(QCond c : q.conds) {
                if(c.verb == QCond.Verb.TELL || c.ready || c.giver == null)
                    continue;
                String target = model.canonGiver(c.giver);
                if(target.equals(q.giver))
                    continue;
                Group g = group(byGiver, target);
                if(g.questId < 0)
                    g.questId = q.id;
                g.rows.add(new Row(c.text, false, q.id, true, c));
            }
        }
        for(Group g : byGiver.values()) {
            g.idle = true;
            g.total = g.rows.size();
            for(Row r : g.rows) {
                if(r.ready)
                    g.done++;
                if(!r.secondary)
                    g.idle = false;
            }
            out.add(g);
        }
        return out;
    }

    private Group group(Map<String, Group> byGiver, String name)
    {
        Group g = byGiver.get(name);
        if(g == null) {
            g = new Group();
            g.key = "giver:" + name;
            g.giver = name;
            g.title = name;
            g.kind = QuestKind.NPC;
            byGiver.put(name, g);
        }
        return g;
    }

    private static final Object[] TASK_CATS = {
        "Bring", new QCond.Verb[] {QCond.Verb.BRING},
        "Foraging", new QCond.Verb[] {QCond.Verb.PICK},
        "Hunting", new QCond.Verb[] {QCond.Verb.KILL},
        "Conversation", new QCond.Verb[] {QCond.Verb.GREET, QCond.Verb.RAGE, QCond.Verb.WAVE, QCond.Verb.LAUGH},
        "Attributes", new QCond.Verb[] {QCond.Verb.GAIN},
        "Craft", new QCond.Verb[] {QCond.Verb.CREATE},
        "Other", new QCond.Verb[] {QCond.Verb.CAVE, QCond.Verb.LIGHT, QCond.Verb.FELL, QCond.Verb.OTHER},
    };

    private List<Group> taskGroups(NQuestTrackerProp p)
    {
        List<Group> out = new ArrayList<>();
        for(int i = 0; i < TASK_CATS.length; i += 2) {
            String name = (String)TASK_CATS[i];
            Set<QCond.Verb> verbs = new HashSet<>(Arrays.asList((QCond.Verb[])TASK_CATS[i + 1]));
            Group g = new Group();
            g.key = "task:" + name;
            g.title = name;
            g.kind = QuestKind.NPC;
            for(QuestModel.TQuest q : model.quests()) {
                if(!visible(q, p))
                    continue;
                for(QCond c : q.conds) {
                    if(c.ready || !verbs.contains(c.verb))
                        continue;
                    if(g.questId < 0)
                        g.questId = q.id;
                    g.rows.add(new Row(c.text, false, q.id, false, c));
                }
            }
            if(g.rows.isEmpty())
                continue;
            g.total = g.rows.size();
            out.add(g);
        }
        return out;
    }

    /** The villagers to show, in name order, skipping the ones the player hid. */
    private List<VillageQuestStore.Villager> shownVillagers(NQuestTrackerProp p)
    {
        List<VillageQuestStore.Villager> out = new ArrayList<>();
        NGameUI gui = getparent(NGameUI.class);
        if(gui == null || gui.villageQuests == null)
            return out;
        for(VillageQuestStore.Villager v : gui.villageQuests.villagers().values()) {
            if(villagerVisible(v, p))
                out.add(v);
        }
        return out;
    }

    /** Village tab, grouped by quest: the same quest held by several villagers is one group. */
    private List<Group> villageQuestGroups(NQuestTrackerProp p)
    {
        Map<String, Group> byKey = new LinkedHashMap<>();
        List<Group> out = new ArrayList<>();
        for(VillageQuestStore.Villager v : shownVillagers(p)) {
            if(v.tooNew) {
                Group g = new Group();
                g.key = "vnew:" + v.name;
                g.title = v.name + " shares quests in a newer format - update nurgling";
                g.kind = QuestKind.WORLD;
                g.idle = true;
                g.village = true;
                g.holders.add(v);
                out.add(g);
                continue;
            }
            for(VillageQuestStore.VQuest q : v.quests) {
                String key = "v:" + q.key();
                if(q.quest.kind == QuestKind.UNKNOWN || p.hiddenQuests.contains(key))
                    continue;
                if(!p.kinds.contains(q.quest.kind) && !p.pinned.contains(key))
                    continue;
                Group g = byKey.get(key);
                if(g == null) {
                    g = new Group();
                    g.key = key;
                    g.questKey = key;
                    g.title = q.quest.title;
                    g.kind = q.quest.kind;
                    g.village = true;
                    byKey.put(key, g);
                }
                g.holders.add(v);
                if(q.readyToTurnIn())
                    g.ready = true;
                if(!q.quest.loaded) {
                    Row r = new Row("objectives not loaded yet", false, -1, true, null);
                    r.holders.add(v);
                    g.rows.add(r);
                    continue;
                }
                for(QCond c : q.conds) {
                    if(c.verb == QCond.Verb.TELL)
                        continue;
                    Row r = new Row(c.text, c.ready, -1, false, c);
                    r.holders.add(v);
                    g.rows.add(r);
                }
            }
        }
        for(Group g : byKey.values()) {
            // With one holder the group row already says whose quest it is.
            if(g.holders.size() == 1) {
                for(Row r : g.rows)
                    r.holders.clear();
            }
            g.total = g.rows.size();
            for(Row r : g.rows) {
                if(r.ready)
                    g.done++;
            }
            out.add(g);
        }
        return out;
    }

    /** Village tab, grouped by task: what the village needs, identical objectives merged. */
    private List<Group> villageTaskGroups(NQuestTrackerProp p)
    {
        List<VillageQuestStore.Villager> shown = shownVillagers(p);
        List<Group> out = new ArrayList<>();
        for(int i = 0; i < TASK_CATS.length; i += 2) {
            String name = (String)TASK_CATS[i];
            Set<QCond.Verb> verbs = new HashSet<>(Arrays.asList((QCond.Verb[])TASK_CATS[i + 1]));
            Group g = new Group();
            g.key = "vtask:" + name;
            g.title = name;
            g.kind = QuestKind.NPC;
            g.village = true;
            Map<String, Row> byText = new LinkedHashMap<>();
            for(VillageQuestStore.Villager v : shown) {
                for(VillageQuestStore.VQuest q : v.quests) {
                    String key = "v:" + q.key();
                    if(q.quest.kind == QuestKind.UNKNOWN || p.hiddenQuests.contains(key))
                        continue;
                    if(!p.kinds.contains(q.quest.kind) && !p.pinned.contains(key))
                        continue;
                    for(QCond c : q.conds) {
                        if(c.ready || !verbs.contains(c.verb))
                            continue;
                        Row r = byText.get(c.text);
                        if(r == null)
                            byText.put(c.text, r = new Row(c.text, false, -1, false, c));
                        if(!r.holders.contains(v))
                            r.holders.add(v);
                    }
                }
            }
            if(byText.isEmpty())
                continue;
            g.rows.addAll(byText.values());
            g.total = g.rows.size();
            out.add(g);
        }
        return out;
    }

    private void filterAndSort(List<Group> groups, final NQuestTrackerProp p)
    {
        for(Iterator<Group> i = groups.iterator(); i.hasNext(); ) {
            Group g = i.next();
            if(g.giver != null && p.hiddenGivers.contains(g.giver)) {
                i.remove();
                continue;
            }
            if(g.giver != null && g.rows.isEmpty() && !g.ready) {
                i.remove();
                continue;
            }
            g.pinned = p.pinned.contains(g.key);
            if(!search.isEmpty() && !matches(g)) {
                i.remove();
            }
        }
        Collections.sort(groups, new Comparator<Group>() {
            public int compare(Group a, Group b)
            {
                if(a.pinned != b.pinned)
                    return a.pinned ? -1 : 1;
                if(a.ready != b.ready)
                    return a.ready ? -1 : 1;
                int ka = kindOrder(a.kind), kb = kindOrder(b.kind);
                if(ka != kb)
                    return ka - kb;
                if(a.idle != b.idle)
                    return a.idle ? 1 : -1;
                return String.CASE_INSENSITIVE_ORDER.compare(nz(a.title), nz(b.title));
            }
        });
    }

    private static String nz(String s)
    {
        return (s == null) ? "" : s;
    }

    private static int kindOrder(QuestKind k)
    {
        switch(k) {
            case CREDO: return 0;
            case NPC:   return 1;
            default:    return 2;
        }
    }

    private boolean matches(Group g)
    {
        if(nz(g.title).toLowerCase().contains(search))
            return true;
        for(Row r : g.rows) {
            if(r.text.toLowerCase().contains(search))
                return true;
        }
        return false;
    }

    /** Collapsed unless the player expanded it; the credo being pursued starts expanded. */
    private boolean collapsed(Group g, NQuestTrackerProp p)
    {
        if(!search.isEmpty())
            return false;
        if(p.collapsed.contains(g.key))
            return true;
        if(p.expanded.contains(g.key))
            return false;
        return !(g.kind == QuestKind.CREDO && g.questId == model.pursuedCredoId());
    }

    private void layoutRows(List<Group> groups, NQuestTrackerProp p)
    {
        for(Widget w = body.cont.child; w != null; ) {
            Widget next = w.next;
            w.destroy();
            w = next;
        }
        int w = body.cont.sz.x - PAD.x * 2;
        int y = 0, shown = 0, hidden = 0;
        for(Group g : groups) {
            boolean expand = !collapsed(g, p);
            if(!g.pinned && p.maxrows > 0 && shown >= p.maxrows) {
                hidden += 1 + (expand ? g.rows.size() : 0);
                continue;
            }
            add(new GroupRow(g, w, !expand), shown, y);
            y += rowH;
            shown++;
            if(!expand)
                continue;
            for(Row r : g.rows) {
                if(!g.pinned && p.maxrows > 0 && shown >= p.maxrows) {
                    hidden++;
                    continue;
                }
                add(new CondRow(r, w), shown, y);
                y += rowH;
                shown++;
            }
        }
        boolean capped = hidden > 0;
        if(capped) {
            add(new MoreRow(hidden, w), shown, y);
        } else if(shown == 0 && villageShown()) {
            add(new EmptyRow(w, "No villagers are sharing quests", null), shown, y);
            if(!p.shareQuests && canShare())
                add(new EmptyRow(w, "Share this character's quests", () -> setShare(true)), shown + 1, y + rowH);
        } else if(shown == 0) {
            add(new EmptyRow(w, "No quests to show", null), shown, y);
        }
        body.cont.update();
    }

    private void add(ARow row, int idx, int y)
    {
        row.idx = idx;
        body.cont.add(row, new Coord(PAD.x, y));
    }

    /* ------------------------------------------------------------------ marker props */

    /**
     * Recompute the icon set drawn over each quest giver's map marker.
     * Mirrors the tags {@link nurgling.overlays.NQuestGiver} draws.
     */
    private boolean applyMarkerProps()
    {
        Map<String, HashSet<String>> props = new HashMap<>();
        for(QuestModel.TQuest q : model.quests()) {
            if(q.giver != null && q.readyToTurnIn())
                tag(props, q.giver, "tell");
            for(QCond c : q.conds) {
                if(c.ready || c.giver == null)
                    continue;
                String t = c.markerTag();
                if(t != null)
                    tag(props, model.canonGiver(c.giver), t);
            }
        }
        boolean changed = false;
        for(String gone : markedGivers) {
            if(!props.containsKey(gone)) {
                setMarkersProp(gone, null);
                changed = true;
            }
        }
        for(Map.Entry<String, HashSet<String>> e : props.entrySet()) {
            if(!e.getValue().equals(markedProps.get(e.getKey())))
                changed = true;
            setMarkersProp(e.getKey(), e.getValue());
        }
        markedGivers.clear();
        markedGivers.addAll(props.keySet());
        markedProps.clear();
        markedProps.putAll(props);
        return changed;
    }

    private static void tag(Map<String, HashSet<String>> props, String giver, String tag)
    {
        HashSet<String> s = props.get(giver);
        if(s == null)
            props.put(giver, s = new HashSet<>());
        s.add(tag);
    }

    /* ------------------------------------------------------------------ menus */

    private void openGearMenu()
    {
        final NQuestTrackerProp p = prop();
        List<QuestMenu.Item> items = new ArrayList<>();
        items.add(new QuestMenu.Item("Max rows: " + ((p.maxrows > 0) ? String.valueOf(p.maxrows) : "all"),
            () -> {
                p.maxrows = nextCap(p.maxrows);
                p.save();
                needRebuild = true;
            }));
        items.add(new QuestMenu.Item("Expand all", () -> {
            p.collapsed.clear();
            expandAllGroups(p);
            p.save();
            needRebuild = true;
        }));
        items.add(new QuestMenu.Item("Collapse all", () -> {
            p.expanded.clear();
            collapseAllGroups(p);
            p.save();
            needRebuild = true;
        }));
        if(!p.hiddenQuests.isEmpty() || !p.hiddenGivers.isEmpty()) {
            items.add(new QuestMenu.Item(
                "Unhide all (" + (p.hiddenQuests.size() + p.hiddenGivers.size()) + ")", () -> {
                    p.hiddenQuests.clear();
                    p.hiddenGivers.clear();
                    p.save();
                    needRebuild = true;
                }));
        }
        if(!p.pinned.isEmpty()) {
            items.add(new QuestMenu.Item("Clear pins (" + p.pinned.size() + ")", () -> {
                p.pinned.clear();
                p.save();
                needRebuild = true;
            }));
        }
        if(connected) {
            if(canShare()) {
                items.add(new QuestMenu.Item((p.shareQuests ? "☑" : "☐") + " Share this character's quests",
                    () -> setShare(!p.shareQuests)));
            } else {
                items.add(new QuestMenu.Item("☐ Share quests (read-only database login)", () -> {
                    NGameUI gui = getparent(NGameUI.class);
                    if(gui != null)
                        gui.msg("This database login is read-only, so it can see villagers' quests but not share its own.");
                }));
            }
        }
        if(!p.hiddenVillagers.isEmpty()) {
            items.add(new QuestMenu.Item("Unhide villagers (" + p.hiddenVillagers.size() + ")", () -> {
                p.hiddenVillagers.clear();
                p.save();
                villageRev = -1;
                needRebuild = true;
            }));
        }
        popup(items);
    }

    private void expandAllGroups(NQuestTrackerProp p)
    {
        for(Widget w = body.cont.child; w != null; w = w.next) {
            if(w instanceof GroupRow)
                p.expanded.add(((GroupRow)w).group.key);
        }
    }

    private void collapseAllGroups(NQuestTrackerProp p)
    {
        for(Widget w = body.cont.child; w != null; w = w.next) {
            if(w instanceof GroupRow)
                p.collapsed.add(((GroupRow)w).group.key);
        }
    }

    private void popup(List<QuestMenu.Item> items)
    {
        if(items.isEmpty())
            return;
        ui.root.add(new QuestMenu(items), ui.mc);
    }

    private static int nextCap(int cur)
    {
        if(cur <= 0)
            return 8;
        if(cur < 12)
            return 12;
        if(cur < 20)
            return 20;
        if(cur < 30)
            return 30;
        return 0;
    }

    private void openQuest(int questId)
    {
        NGameUI gui = getparent(NGameUI.class);
        if(gui == null || gui.chrwdg == null || questId < 0)
            return;
        gui.chrwdg.show();
        gui.chrwdg.raise();
        gui.chrwdg.questtab.showtab();
        if(gui.chrwdg.quest != null)
            gui.chrwdg.quest.wdgmsg("qsel", questId);
    }

    private void rowMenu(final Group g)
    {
        final NQuestTrackerProp p = prop();
        List<QuestMenu.Item> items = new ArrayList<>();
        final boolean pinned = p.pinned.contains(g.key);
        items.add(new QuestMenu.Item(pinned ? "Unpin" : "Pin to top", () -> {
            if(pinned)
                p.pinned.remove(g.key);
            else
                p.pinned.add(g.key);
            p.save();
            needRebuild = true;
        }));
        // Only offered for a group that IS one quest - a giver group can hold several, and
        // "hide this quest" would silently pick one of them.
        if(g.giver == null && g.questKey != null) {
            items.add(new QuestMenu.Item("Hide this quest", () -> {
                p.hiddenQuests.add(g.questKey);
                p.save();
                needRebuild = true;
            }));
        }
        if(g.giver != null) {
            items.add(new QuestMenu.Item("Hide everything from " + g.giver, () -> {
                p.hiddenGivers.add(g.giver);
                p.save();
                needRebuild = true;
            }));
        }
        if(g.questId >= 0)
            items.add(new QuestMenu.Item("Open in Quest Log", () -> openQuest(g.questId)));
        if(g.village) {
            for(final VillageQuestStore.Villager v : g.holders) {
                if(items.size() >= 6)
                    break;
                items.add(new QuestMenu.Item("Hide quests from " + v.name, () -> hideVillager(v.name)));
            }
        }
        popup(items);
    }

    private void hideVillager(String name)
    {
        NQuestTrackerProp p = prop();
        p.hiddenVillagers.add(name);
        p.save();
        villageRev = -1;
        needRebuild = true;
    }

    /** False for a read-only database login, which can see the village but not publish. */
    private boolean canShare()
    {
        NGameUI gui = getparent(NGameUI.class);
        return gui == null || gui.villageQuests == null || gui.villageQuests.canWrite();
    }

    /* ------------------------------------------------------------------ rows */

    private static String elide(Text.Foundry f, String s, int maxw)
    {
        if(maxw <= 0 || f.strsize(s).x <= maxw)
            return s;
        int lo = 0, hi = s.length();
        while(lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if(f.strsize(s.substring(0, mid) + "…").x <= maxw)
                lo = mid;
            else
                hi = mid - 1;
        }
        return (lo <= 0) ? "…" : (s.substring(0, lo).trim() + "…");
    }

    /* ------------------------------------------------------------------ villager chips */

    /** At most this many name chips per row; the rest collapse into a "+n" chip. */
    private static final int MAX_CHIPS = 2;
    private static final int CHIP_NAME = 8;

    private List<Tex> chips(List<VillageQuestStore.Villager> holders)
    {
        if(holders.isEmpty())
            return Collections.emptyList();
        List<Tex> out = new ArrayList<>();
        for(int i = 0; i < holders.size() && i < MAX_CHIPS; i++)
            out.add(chip(holders.get(i)));
        if(holders.size() > MAX_CHIPS)
            out.add(chipTex("+" + (holders.size() - MAX_CHIPS), NStyle.questDim, 255));
        return out;
    }

    private Tex chip(VillageQuestStore.Villager v)
    {
        Color col = NStyle.questHolders[Math.floorMod(v.name.hashCode(), NStyle.questHolders.length)];
        String label = (v.name.length() > CHIP_NAME) ? (v.name.substring(0, CHIP_NAME - 1) + "…") : v.name;
        return chipTex(label, col, v.online() ? 255 : 110);
    }

    private Tex chipTex(String label, Color col, int alpha)
    {
        String key = label + "|" + col.getRGB() + "|" + alpha;
        Tex t = chipCache.get(key);
        if(t == null) {
            java.awt.image.BufferedImage txt = chipFnd.render(label).img;
            int pad = UI.scale(3);
            int h = Math.min(rowH - UI.scale(2), txt.getHeight() + UI.scale(1));
            java.awt.image.BufferedImage img = TexI.mkbuf(new Coord(txt.getWidth() + pad * 2, h));
            java.awt.Graphics2D gr = img.createGraphics();
            gr.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), alpha));
            gr.fillRect(0, 0, img.getWidth(), img.getHeight());
            gr.drawImage(txt, pad, (h - txt.getHeight()) / 2, null);
            gr.dispose();
            chipCache.put(key, t = new TexI(img));
        }
        return t;
    }

    private static int chipsWidth(List<Tex> chips)
    {
        int w = 0;
        for(Tex t : chips)
            w += t.sz().x + UI.scale(3);
        return w;
    }

    /** Draw chips right-aligned so the last one ends at {@code right}. */
    private void drawChips(GOut g, List<Tex> chips, int right)
    {
        int x = right - chipsWidth(chips) + UI.scale(3);
        for(Tex t : chips) {
            g.image(t, new Coord(x, (g.sz().y - t.sz().y) / 2));
            x += t.sz().x + UI.scale(3);
        }
    }

    private static String holdersText(List<VillageQuestStore.Villager> holders)
    {
        StringBuilder sb = new StringBuilder();
        for(VillageQuestStore.Villager v : holders) {
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(v.name).append(v.online() ? " (online)" : " (offline " + age(v.ageMillis) + ")");
        }
        return sb.toString();
    }

    private static String age(long ms)
    {
        long m = ms / 60000;
        if(m < 60)
            return m + " min";
        long h = m / 60;
        if(h < 48)
            return h + " h";
        return (h / 24) + " d";
    }

    private abstract class ARow extends Widget
    {
        boolean hover = false;
        int idx = 0;

        ARow(int w)
        {
            super(new Coord(w, rowH));
        }

        @Override
        public void mousemove(MouseMoveEvent ev)
        {
            hover = ev.c.isect(Coord.z, sz);
            super.mousemove(ev);
        }

        void band(GOut g)
        {
            g.chcolor(((idx % 2) == 0) ? NStyle.rowEven : NStyle.rowOdd);
            g.frect(Coord.z, sz);
            if(hover) {
                g.chcolor(NStyle.questHover);
                g.frect(Coord.z, sz);
            }
            g.chcolor();
        }

        int ty(Tex t)
        {
            return (sz.y - t.sz().y) / 2;
        }
    }

    private class GroupRow extends ARow
    {
        final Group group;
        final boolean collapsed;
        private final Tex chev, title, counter;
        private final List<Tex> chips;

        GroupRow(Group g, int w, boolean collapsed)
        {
            super(w);
            this.group = g;
            this.collapsed = collapsed;
            this.chev = groupFnd.render(collapsed ? "▸" : "▾", NStyle.questDim).tex();
            String pin = g.pinned ? "◆ " : "";
            String cnt = (g.total > 0) ? (g.done + "/" + g.total) : "";
            this.counter = cnt.isEmpty() ? null : condFnd.render(cnt, NStyle.questDim).tex();
            this.chips = chips(g.holders);
            int cw = (counter != null) ? counter.sz().x + UI.scale(6) : 0;
            cw += chipsWidth(chips);
            this.title = groupFnd.render(
                elide(groupFnd, pin + nz(g.title), w - CHEV_W - cw), g.titleColor()).tex();
        }

        @Override
        public void draw(GOut g)
        {
            band(g);
            g.image(chev, new Coord(0, ty(chev)));
            g.image(title, new Coord(CHEV_W, ty(title)));
            int right = sz.x;
            if(counter != null) {
                g.image(counter, new Coord(sz.x - counter.sz().x, ty(counter)));
                right -= counter.sz().x + UI.scale(6);
            }
            drawChips(g, chips, right);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev)
        {
            if(ev.b == 3) {
                rowMenu(group);
                return true;
            }
            if(ev.b == 1) {
                NQuestTrackerProp p = prop();
                if(collapsed) {
                    p.collapsed.remove(group.key);
                    p.expanded.add(group.key);
                } else {
                    p.expanded.remove(group.key);
                    p.collapsed.add(group.key);
                }
                p.save();
                needRebuild = true;
                return true;
            }
            if(ev.b == 2) {
                openQuest(group.questId);
                return true;
            }
            return super.mousedown(ev);
        }

        @Override
        public Object tooltip(Coord c, Widget prev)
        {
            String tip = nz(group.title) + " - left-click to " + (collapsed ? "expand" : "collapse")
                       + ", right-click for options";
            return group.holders.isEmpty() ? tip : (holdersText(group.holders) + "\n" + tip);
        }
    }

    private class CondRow extends ARow
    {
        final Row row;
        private final Tex glyph, text;
        private final String full;
        private final QuestObjectiveActionButton actionButton;
        private final List<Tex> chips;

        CondRow(Row r, int w)
        {
            super(w);
            this.row = r;
            this.full = r.text;
            Color col = r.ready ? NStyle.questCondDone
                      : (r.secondary ? NStyle.questDim : NStyle.questCond);
            this.glyph = condFnd.render(r.ready ? "✓" : "•", col).tex();
            int off = INDENT + glyph.sz().x + UI.scale(4);
            QuestObjectiveAction potential = actionResolver.resolve(r.cond);
            if(potential != null) {
                actionButton = add(new QuestObjectiveActionButton(r.cond));
                actionButton.c = new Coord(w - actionButton.sz.x - UI.scale(2), (rowH - actionButton.sz.y) / 2);
            } else {
                actionButton = null;
            }
            this.chips = chips(r.holders);
            int textWidth = Math.max(0, QuestObjectiveRowLayout.textWidth(w, off, actionButton != null)
                                        - chipsWidth(chips));
            this.text = condFnd.render(elide(condFnd, r.text, textWidth), col).tex();
        }

        @Override
        public void draw(GOut g)
        {
            band(g);
            g.image(glyph, new Coord(INDENT, ty(glyph)));
            g.image(text, new Coord(INDENT + glyph.sz().x + UI.scale(4), ty(text)));
            drawChips(g, chips, (actionButton != null) ? actionButton.c.x - UI.scale(2) : sz.x);
            super.draw(g);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev)
        {
            /* Let the action button claim the click before the row opens the quest. */
            if(ev.propagate(this))
                return true;
            if(ev.b == 1) {
                openQuest(row.questId);
                return true;
            }
            return super.mousedown(ev);
        }

        @Override
        public Object tooltip(Coord c, Widget prev)
        {
            return row.holders.isEmpty() ? full : (full + "\n" + holdersText(row.holders));
        }
    }

    private class MoreRow extends ARow
    {
        private final Tex text;

        MoreRow(int n, int w)
        {
            super(w);
            this.text = condFnd.render("+ " + n + " more…", NStyle.questDim).tex();
        }

        @Override
        public void draw(GOut g)
        {
            band(g);
            g.image(text, new Coord(INDENT, ty(text)));
        }

        @Override
        public boolean mousedown(MouseDownEvent ev)
        {
            if(ev.b == 1) {
                NQuestTrackerProp p = prop();
                p.maxrows = 0;
                p.save();
                needRebuild = true;
                return true;
            }
            return super.mousedown(ev);
        }

        @Override
        public Object tooltip(Coord c, Widget prev)
        {
            return "Click to show every row (max rows: unlimited)";
        }
    }

    private class EmptyRow extends ARow
    {
        private final Tex text;
        /** Makes the row a link when set. */
        private final Runnable action;

        EmptyRow(int w, String msg, Runnable action)
        {
            super(w);
            this.action = action;
            this.text = condFnd.render(msg, (action != null) ? NStyle.questVillage : NStyle.questDim).tex();
        }

        @Override
        public void draw(GOut g)
        {
            if(action != null && hover) {
                g.chcolor(NStyle.questHover);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
            g.image(text, new Coord(INDENT, ty(text)));
        }

        @Override
        public boolean mousedown(MouseDownEvent ev)
        {
            if(action != null && ev.b == 1) {
                action.run();
                return true;
            }
            return super.mousedown(ev);
        }
    }

    /** Toggle for one {@link QuestKind}. Compact on purpose - the panel can be narrow. */
    private class KindChip extends ACheckBox
    {
        final QuestKind kind;
        private final Color col;
        private final String tip;
        private final Tex on, off;
        private final String glyph;
        private boolean hover = false;

        KindChip(QuestKind kind, String letter, NToolbarToggle.Glyph glyph, Color col, String tip)
        {
            super(flat ? NToolbarToggle.SIZE : CHIP_SZ);
            this.glyph = glyph.name().toLowerCase(java.util.Locale.ROOT);
            this.kind = kind;
            this.col = col;
            this.tip = tip;
            this.a = true;
            Text.Foundry f = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 10).aa(true);
            this.on = f.render(letter, NStyle.infoBg).tex();
            this.off = f.render(letter, col).tex();
        }

        @Override
        public void draw(GOut g)
        {
            if(flat) {
                // New UI: the kind's icon, dimmed while the kind is filtered out.
                NToolbarToggle.drawGlyph(g, sz, glyph, hover, !a);
                return;
            }
            g.chcolor(a ? col : NStyle.titleBg);
            g.frect(Coord.z, sz);
            g.chcolor(a ? col : NStyle.questDim);
            g.rect(Coord.z, sz);
            g.chcolor();
            Tex t = a ? on : off;
            g.image(t, sz.sub(t.sz()).div(2));
            if(hover) {
                g.chcolor(NStyle.questHover);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
        }

        @Override
        public void mousemove(MouseMoveEvent ev)
        {
            hover = ev.c.isect(Coord.z, sz);
            super.mousemove(ev);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev)
        {
            if(ev.b == 1) {
                a = !a;
                NQuestTrackerProp p = prop();
                if(a)
                    p.kinds.add(kind);
                else
                    p.kinds.remove(kind);
                p.save();
                needRebuild = true;
                return true;
            }
            return super.mousedown(ev);
        }

        @Override
        public Object tooltip(Coord c, Widget prev)
        {
            return tip;
        }
    }

    /** Mine | Village switch. Only shown while the shared database is connected. */
    private class TabStrip extends Widget
    {
        private int hover = -1;

        TabStrip()
        {
            super(new Coord(DEF_SZ.x, UI.scale(18)));
        }

        private String label(int i)
        {
            if(i == 0)
                return "Mine";
            return (villageCount > 0) ? ("Village (" + villageCount + ")") : "Village";
        }

        @Override
        public void draw(GOut g)
        {
            boolean village = prop().villageTab;
            int half = sz.x / 2;
            int line = Math.max(1, UI.scale(2));
            for(int i = 0; i < 2; i++) {
                boolean on = (i == 1) == village;
                int x0 = i * half, w = (i == 0) ? half : sz.x - half;
                if(hover == i) {
                    g.chcolor(NStyle.questHover);
                    g.frect(new Coord(x0, 0), new Coord(w, sz.y));
                }
                Color col = on ? ((i == 1) ? NStyle.questVillage : Color.WHITE) : NStyle.questDim;
                String key = "tab|" + label(i) + "|" + col.getRGB();
                Tex t = chipCache.get(key);
                if(t == null)
                    chipCache.put(key, t = groupFnd.render(label(i), col).tex());
                int tx = x0 + (w - t.sz().x) / 2;
                g.chcolor();
                g.image(t, new Coord(tx, (sz.y - line - t.sz().y) / 2));
                if(i == 0 && prop().shareQuests) {
                    // This character is sharing: a small violet dot after "Mine".
                    int d = UI.scale(5);
                    g.chcolor(NStyle.questVillage);
                    g.frect(new Coord(tx + t.sz().x + UI.scale(4), (sz.y - line - d) / 2), new Coord(d, d));
                }
                if(on) {
                    g.chcolor(NStyle.border);
                    g.frect(new Coord(x0, sz.y - line), new Coord(w, line));
                }
                g.chcolor();
            }
            g.chcolor(NStyle.separator);
            g.frect(new Coord(0, sz.y - UI.scale(1)), new Coord(sz.x, UI.scale(1)));
            g.chcolor();
        }

        @Override
        public void mousemove(MouseMoveEvent ev)
        {
            hover = ev.c.isect(Coord.z, sz) ? ((ev.c.x < sz.x / 2) ? 0 : 1) : -1;
            super.mousemove(ev);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev)
        {
            if(ev.b == 1) {
                setVillageTab(ev.c.x >= sz.x / 2);
                return true;
            }
            return super.mousedown(ev);
        }

        @Override
        public Object tooltip(Coord c, Widget prev)
        {
            if(c.x < sz.x / 2)
                return prop().shareQuests ? "Your quests - shared with the village" : "Your quests";
            return "Quests of villagers sharing theirs on the database";
        }
    }

    /* ------------------------------------------------------------------ drawing */

    @Override
    public void draw(GOut g)
    {
        boolean nui = UITheme.on();
        if(nui)
            UITheme.panel(g, Coord.z, sz, UITheme.PANEL, null);
        else
            NDraggableWidget.drawBg(g, sz, ui);
        g.chcolor(NStyle.titleBg);
        g.frect(Coord.z, new Coord(sz.x, headerH));
        g.chcolor(NStyle.separator);
        g.frect(new Coord(0, headerH - UI.scale(1)), new Coord(sz.x, UI.scale(1)));
        g.chcolor();
        super.draw(g);
        if(nui) {
            UITheme.panel(g, Coord.z, sz, null, NStyle.border);
            return;
        }
        int bw = Math.max(2, UI.scale(2));
        g.chcolor(NStyle.border);
        g.frect(Coord.z, new Coord(sz.x, bw));
        g.frect(new Coord(0, sz.y - bw), new Coord(sz.x, bw));
        g.frect(Coord.z, new Coord(bw, sz.y));
        g.frect(new Coord(sz.x - bw, 0), new Coord(bw, sz.y));
        g.chcolor();
    }

    /* ------------------------------------------------------------------ server hooks */

    /** From {@code Quest.Box.uimsg("conds")} via {@link nurgling.NUtils#setQuestConds}. */
    public void updateConds(int id, Object[] args)
    {
        model.setConds(id, args);
    }

    /** From {@code QuestWnd.uimsg} via {@link nurgling.NUtils#removeQuest}. */
    public void removeQuest(int id)
    {
        model.removeQuest(id);
    }

    /** From {@code QuestWnd.uimsg} via {@link nurgling.NUtils#addQuest}. */
    public void addQuest(int id)
    {
        model.addQuest(id);
    }

    /* ------------------------------------------------------------------ overlay queries */

    public boolean isHuntingTarget(String target)
    {
        return matchesAny(huntingT, target);
    }

    public boolean isForageTarget(String target)
    {
        return matchesAny(forageT, target);
    }

    private static boolean matchesAny(Set<String> set, String target)
    {
        if(target == null)
            return false;
        for(String s : set) {
            if(target.contains(s))
                return true;
        }
        return false;
    }

    /**
     * Villagers wanting this item for an unfinished {@code Bring} objective, or null when nobody does.
     * Matches the way {@link #isQuestedItem} does. Read from {@link NGItem#tick} on the UI thread.
     */
    public List<Want> villageWanters(NGItem item)
    {
        String nm = (item == null) ? null : item.name();
        Map<String, List<Want>> idx = villageBring;
        if(nm == null || idx.isEmpty())
            return null;
        String lc = nm.toLowerCase();
        List<Want> out = null;
        for(Map.Entry<String, List<Want>> e : idx.entrySet()) {
            if(lc.contains(e.getKey())) {
                if(out == null)
                    out = new ArrayList<>();
                out.addAll(e.getValue());
            }
        }
        return out;
    }

    public boolean isQuestedItem(NGItem item)
    {
        String nm = (item == null) ? null : item.name();
        if(nm == null)
            return false;
        String lc = nm.toLowerCase();
        for(String want : bringItems) {
            if(lc.contains(want))
                return true;
        }
        return false;
    }

    /* ------------------------------------------------------------------ markers */

    public class MarkerInfo
    {
        public String name;
        public Coord2d coord;
        public long seg;
        public HashSet<String> prop;

        public MarkerInfo(String name, Coord2d coord, long seg)
        {
            this.name = name;
            this.coord = coord;
            this.seg = seg;
        }
    }

    private final HashSet<MarkerInfo> markers = new HashSet<>();

    public void addMarkerCoord(Coord2d tmp, String nm, long seg)
    {
        model.noteGiverName(nm);
        synchronized(markers) {
            for(MarkerInfo mi : markers) {
                if(mi.name.equals(nm)) {
                    mi.coord = tmp;
                    mi.seg = seg;
                    return;
                }
            }
            markers.add(new MarkerInfo(nm, tmp, seg));
        }
        lastUpdate.incrementAndGet();
    }

    public MarkerInfo getMarkerInfo(NGameUI gui, Gob gob)
    {
        if(gui == null || gui.mapfile == null || gob == null)
            return null;
        synchronized(markers) {
            for(MarkerInfo mi : markers) {
                if(mi.coord != null && gui.mapfile.playerSegmentId() == mi.seg
                   && gob.rc.dist(mi.coord) < 1)
                    return mi;
            }
        }
        return null;
    }

    void setMarkersProp(String name, HashSet<String> props)
    {
        if(name == null)
            return;
        synchronized(markers) {
            for(MarkerInfo mi : markers) {
                if(mi.name != null && mi.name.equals(name)) {
                    mi.prop = props;
                    return;
                }
            }
            MarkerInfo mi = new MarkerInfo(name, null, -1);
            mi.prop = props;
            markers.add(mi);
        }
    }

    @Override
    public void dispose()
    {
        synchronized(markers) {
            markers.clear();
        }
        super.dispose();
    }
}
