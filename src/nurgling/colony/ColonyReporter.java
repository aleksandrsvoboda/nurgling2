package nurgling.colony;

import haven.GItem;
import haven.SkillWnd;
import haven.WItem;
import haven.Widget;
import haven.res.ui.stackinv.ItemStack;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUI;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.MemberReport;
import nurgling.widgets.NEquipory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds each session's {@link ColonistReport} in that session's own tick, so nothing reads another session's
 * widgets. Only while Commander mode is on (the colony lives there), every {@link #EVERY_MS}.
 *
 * <p>Inventories are read from the widget tree, not through NInventory queries (those wait on this very thread).
 * Stacked items sit in the stack's {@code contents}, not among the inventory's children, so stacks are opened.
 */
public final class ColonyReporter {
    private static final long EVERY_MS = 3000;

    private ColonyReporter() {}

    public static void tick(NUI ui) {
        if (ui == null || ui.sess == null)
            return;
        SessionManager sm = SessionManager.getInstance();
        if (!sm.squad().commander())
            return;
        SessionContext ctx = sm.findBySession(ui.sess);
        NGameUI gui = ui.gui;
        if (ctx == null || gui == null || gui.map == null)
            return;
        long now = System.currentTimeMillis();
        ColonistReport last = ctx.colonyReport;
        if (last != null && now - last.time < EVERY_MS)
            return;
        ctx.colonyReport = build(ctx, gui, now);
    }

    private static ColonistReport build(SessionContext ctx, NGameUI gui, long now) {
        Set<String> skills = new HashSet<>();
        Map<String, Integer> buyable = new HashMap<>();
        int lp = -1;
        if (gui.chrwdg != null) {
            lp = gui.chrwdg.exp;
            SkillWnd sw = gui.chrwdg.skill;
            if (sw != null && sw.skg != null) {
                for (SkillWnd.Skill s : sw.skg.csk.items)
                    skills.add(s.nm);
                for (SkillWnd.Skill s : sw.skg.nsk.items)
                    buyable.put(s.nm, s.cost);
            }
        }
        Map<ColonyItems.Tool, String> tools = new EnumMap<>(ColonyItems.Tool.class);
        Map<ColonyItems.Material, Integer> materials = new EnumMap<>(ColonyItems.Material.class);
        NEquipory eq = equipory(gui);
        if (eq != null) {
            for (WItem w : eq.quickslots) {
                if (w == null)
                    continue;
                note(w.item, tools, materials);
                /* A belt (or anything worn that holds items): its contents count as carried. */
                if (w.item.contents != null && !(w.item.contents instanceof ItemStack))
                    walk(w.item.contents, tools, materials);
            }
        }
        if (gui.maininv != null)
            walk(gui.maininv, tools, materials);
        int free = (gui.maininv instanceof NInventory) ? ((NInventory) gui.maininv).calcFreeSpace() : -1;
        MemberReport mr = ctx.squadReport;
        String name = (gui.chrid == null || gui.chrid.isEmpty()) ? ctx.getDisplayName() : gui.chrid;
        String genus = gui.getGenus();
        Colony colony = SessionManager.getInstance().colony();
        String played = colony.worlds.takePlayed(gui.ui);
        if (played != null)
            colony.config.rememberWorld(genus, played);
        return new ColonistReport(ctx.sessionId, genus + "|" + name, name, ctx.username,
                                  (mr == null) ? null : mr.pos, skills, buyable, lp, tools, materials, genus,
                                  colony.config.worldOf(genus), free, now);
    }

    private static NEquipory equipory(NGameUI gui) {
        if (gui.equwnd == null)
            return null;
        for (Widget w = gui.equwnd.lchild; w != null; w = w.prev)
            if (w instanceof NEquipory)
                return (NEquipory) w;
        return null;
    }

    /** Every item in an inventory widget, stacks opened. */
    private static void walk(Widget inv, Map<ColonyItems.Tool, String> tools, Map<ColonyItems.Material, Integer> materials) {
        for (Widget w = inv.child; w != null; w = w.next) {
            if (!(w instanceof WItem))
                continue;
            GItem item = ((WItem) w).item;
            if (item.contents instanceof ItemStack) {
                for (GItem g : ((ItemStack) item.contents).order)
                    note(g, tools, materials);
            } else {
                note(item, tools, materials);
            }
        }
    }

    /** How many items of a colony material an inventory widget holds, stacks opened. */
    public static int count(Widget inv, ColonyItems.Material m) {
        int[] n = {0};
        each(inv, name -> {
            if (ColonyItems.material(name) == m)
                n[0]++;
        });
        return n[0];
    }

    /** How many items of exactly this name an inventory widget holds, stacks opened. */
    public static int countNamed(Widget inv, String name) {
        int[] n = {0};
        each(inv, nm -> {
            if (nm.equals(name))
                n[0]++;
        });
        return n[0];
    }

    private static void each(Widget inv, java.util.function.Consumer<String> f) {
        if (inv == null)
            return;
        for (Widget w = inv.child; w != null; w = w.next) {
            if (!(w instanceof WItem))
                continue;
            GItem item = ((WItem) w).item;
            if (item.contents instanceof ItemStack) {
                for (GItem g : ((ItemStack) item.contents).order)
                    if (g instanceof NGItem && ((NGItem) g).name() != null)
                        f.accept(((NGItem) g).name());
            } else if (item instanceof NGItem && ((NGItem) item).name() != null) {
                f.accept(((NGItem) item).name());
            }
        }
    }

    private static void note(GItem item, Map<ColonyItems.Tool, String> tools, Map<ColonyItems.Material, Integer> materials) {
        if (!(item instanceof NGItem))
            return;
        String name = ((NGItem) item).name();
        if (name == null)
            return;
        ColonyItems.Tool t = ColonyItems.tool(name);
        if (t != null)
            tools.putIfAbsent(t, name);
        ColonyItems.Material m = ColonyItems.material(name);
        if (m != null)
            materials.merge(m, 1, Integer::sum);
    }
}
