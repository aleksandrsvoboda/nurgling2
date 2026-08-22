package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NGItem;
import nurgling.NStyle;
import nurgling.NUI;
import nurgling.conf.FontSettings;
import nurgling.conf.NQuestTrackerProp;
import nurgling.widgets.nsettings.Fonts;
import nurgling.widgets.quest.QCond;
import nurgling.widgets.quest.QuestKind;
import nurgling.widgets.quest.QuestMenu;
import nurgling.widgets.quest.QuestModel;

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

    /* ------------------------------------------------------------------ state */

    private final QuestModel model = new QuestModel();
    private NQuestTrackerProp prop = null;
    private NQuestTrackerProp fallback = null;
    private boolean needRebuild = true;

    private Scrollport body;
    private ICheckBox modebtn, searchbtn, gearbtn;
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

    public NQuestInfo()
    {
        super(DEF_SZ);
        fonts();
        modebtn = add(new NMiniMapWnd.NMenuCheckBox(
            "nurgling/hud/buttons/questmode", null, "Group by quest giver / by task"));
        modebtn.changed(a -> {
            prop().mode = a ? NQuestTrackerProp.Mode.TASKS : NQuestTrackerProp.Mode.GIVERS;
            prop().save();
            needRebuild = true;
        });
        chips = new KindChip[] {
            add(new KindChip(QuestKind.NPC, "N", NStyle.questGiver, "Quests from quest givers")),
            add(new KindChip(QuestKind.CREDO, "C", NStyle.questCredo, "Credo quests")),
            add(new KindChip(QuestKind.WORLD, "W", NStyle.questWorld, "World quests")),
        };
        searchbtn = add(new NMiniMapWnd.NMenuCheckBox(
            "nurgling/hud/buttons/lsearch", null, "Search quests"));
        searchbtn.changed(a -> {
            search = "";
            if(searchbox != null)
                searchbox.settext("");
            relayout();
            needRebuild = true;
        });
        gearbtn = add(new NMiniMapWnd.NMenuCheckBox(
            "nurgling/hud/buttons/settings", null, "Tracker options"));
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
        if(needRebuild) {
            needRebuild = false;
            rebuild();
        }
    }

    /* ------------------------------------------------------------------ view model */

    private static class Row
    {
        final String text;
        final boolean ready;
        final int questId;
        final boolean secondary;

        Row(String text, boolean ready, int questId, boolean secondary)
        {
            this.text = text;
            this.ready = ready;
            this.questId = questId;
            this.secondary = secondary;
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
        List<Group> groups = (p.mode == NQuestTrackerProp.Mode.TASKS) ? taskGroups(p) : giverGroups(p);
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
                    g.rows.add(new Row(c.text, c.ready, q.id, false));
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
                g.rows.add(new Row(c.text, c.ready, q.id, false));
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
                g.rows.add(new Row(c.text, false, q.id, true));
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
        "Other", new QCond.Verb[] {QCond.Verb.CAVE, QCond.Verb.LIGHT, QCond.Verb.OTHER},
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
                    g.rows.add(new Row(c.text, false, q.id, false));
                }
            }
            if(g.rows.isEmpty())
                continue;
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
        if(capped)
            add(new MoreRow(hidden, w), shown, y);
        else if(shown == 0)
            add(new EmptyRow(w), shown, y);
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
        popup(items);
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

        GroupRow(Group g, int w, boolean collapsed)
        {
            super(w);
            this.group = g;
            this.collapsed = collapsed;
            this.chev = groupFnd.render(collapsed ? "▸" : "▾", NStyle.questDim).tex();
            String pin = g.pinned ? "◆ " : "";
            String cnt = (g.total > 0) ? (g.done + "/" + g.total) : "";
            this.counter = cnt.isEmpty() ? null : condFnd.render(cnt, NStyle.questDim).tex();
            int cw = (counter != null) ? counter.sz().x + UI.scale(6) : 0;
            this.title = groupFnd.render(
                elide(groupFnd, pin + nz(g.title), w - CHEV_W - cw), g.titleColor()).tex();
        }

        @Override
        public void draw(GOut g)
        {
            band(g);
            g.image(chev, new Coord(0, ty(chev)));
            g.image(title, new Coord(CHEV_W, ty(title)));
            if(counter != null)
                g.image(counter, new Coord(sz.x - counter.sz().x, ty(counter)));
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
            return nz(group.title) + " - left-click to " + (collapsed ? "expand" : "collapse")
                 + ", right-click for options";
        }
    }

    private class CondRow extends ARow
    {
        final Row row;
        private final Tex glyph, text;
        private final String full;

        CondRow(Row r, int w)
        {
            super(w);
            this.row = r;
            this.full = r.text;
            Color col = r.ready ? NStyle.questCondDone
                      : (r.secondary ? NStyle.questDim : NStyle.questCond);
            this.glyph = condFnd.render(r.ready ? "✓" : "•", col).tex();
            int off = INDENT + glyph.sz().x + UI.scale(4);
            this.text = condFnd.render(elide(condFnd, r.text, w - off), col).tex();
        }

        @Override
        public void draw(GOut g)
        {
            band(g);
            g.image(glyph, new Coord(INDENT, ty(glyph)));
            g.image(text, new Coord(INDENT + glyph.sz().x + UI.scale(4), ty(text)));
        }

        @Override
        public boolean mousedown(MouseDownEvent ev)
        {
            if(ev.b == 1) {
                openQuest(row.questId);
                return true;
            }
            return super.mousedown(ev);
        }

        @Override
        public Object tooltip(Coord c, Widget prev)
        {
            return full;
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

        EmptyRow(int w)
        {
            super(w);
            this.text = condFnd.render("No quests to show", NStyle.questDim).tex();
        }

        @Override
        public void draw(GOut g)
        {
            g.image(text, new Coord(INDENT, ty(text)));
        }
    }

    /** Toggle for one {@link QuestKind}. Compact on purpose - the panel can be narrow. */
    private class KindChip extends ACheckBox
    {
        final QuestKind kind;
        private final Color col;
        private final String tip;
        private final Tex on, off;
        private boolean hover = false;

        KindChip(QuestKind kind, String letter, Color col, String tip)
        {
            super(CHIP_SZ);
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

    /* ------------------------------------------------------------------ drawing */

    @Override
    public void draw(GOut g)
    {
        NDraggableWidget.drawBg(g, sz, ui);
        g.chcolor(NStyle.titleBg);
        g.frect(Coord.z, new Coord(sz.x, headerH));
        g.chcolor(NStyle.separator);
        g.frect(new Coord(0, headerH - UI.scale(1)), new Coord(sz.x, UI.scale(1)));
        g.chcolor();
        super.draw(g);
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
