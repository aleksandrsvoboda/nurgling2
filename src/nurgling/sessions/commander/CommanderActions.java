package nurgling.sessions.commander;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.actions.Action;
import nurgling.actions.ActionWithFinal;
import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;
import nurgling.i18n.L10n;
import nurgling.sessions.BotExecutor;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.Squad;
import nurgling.sessions.squad.SquadCommand;

import java.util.Collections;
import java.util.List;

/**
 * What the action UI does in Commander mode: the regular bot menu, the action menu and the Commander quick bar act on
 * the selected characters, each in its own session, instead of on the character on screen. A bot starts its own copy
 * on every selected character; a game action (a menu entry) is used by every selected character from its own action
 * menu; a scenario runs in every selected character's session.
 */
public final class CommanderActions {
    private CommanderActions() {}

    /** Commander mode is on and {@code ui} is the session on screen: the action UI is the commander's. */
    public static boolean commanding(UI ui) {
        SessionManager sm = SessionManager.getInstance();
        if (!sm.squad().commander() || ui == null)
            return false;
        SessionContext me = sm.findByUI(ui);
        return me != null && !me.isHeadless();
    }

    private static List<SessionContext> selection(NGameUI gui) {
        List<SessionContext> m = SessionManager.getInstance().squad().members();
        if (m.isEmpty() && gui != null)
            gui.msg(L10n.get("commander.msg.select_first"));
        return m;
    }

    /** Start this bot on every selected character. Members running a bot are skipped unless "Bots: stop". */
    public static void runBot(BotDescriptor d, NGameUI gui) {
        if (d == null)
            return;
        List<SessionContext> m = selection(gui);
        if (m.isEmpty())
            return;
        int started = 0, skipped = 0;
        for (SessionContext ctx : m) {
            NGameUI mg = ctx.getGameUI();
            if (mg == null || mg.biw == null)
                continue;
            String running = mg.biw.firstBotExcept(ctx.squadState.thread());
            if (running != null) {
                if (!Squad.stopBots()) {
                    skipped++;
                    continue;
                }
                mg.biw.interruptAll();
            }
            /* Squad orders and the bot would both drive the character: the bot wins. */
            ctx.squadInbox.add(new SquadCommand.Stop());
            Action a = d.instantiate(Collections.emptyMap());
            Runnable fin = (a instanceof ActionWithFinal) ? ((ActionWithFinal) a)::endAction : null;
            if (BotExecutor.runWithSupportsOn(ctx, d.id, a, d.disStacks, fin) != null)
                started++;
        }
        if (gui != null)
            gui.msg(L10n.get("commander.bots.started", d.getDisplayName(), started, skipped));
    }

    /** The bot behind a bot menu path (its icon path, as the belts store it), or null. */
    public static BotDescriptor botOfPath(String path) {
        if (path == null)
            return null;
        for (BotDescriptor d : BotRegistry.all())
            if (path.equals(d.iconPath))
                return d;
        return null;
    }

    /** Every selected character uses this action (a menu entry, by its resource name) from its own action menu. */
    public static void useAction(String resName, NGameUI gui) {
        List<SessionContext> m = selection(gui);
        for (SessionContext ctx : m)
            ctx.squadInbox.add(new SquadCommand.UseAction(resName));
        if (gui != null && !m.isEmpty())
            gui.msg(L10n.get("commander.msg.action", m.size()));
    }

    /** Every selected character runs this scenario in its own session. */
    public static void runScenario(String name, NGameUI gui) {
        List<SessionContext> m = selection(gui);
        for (SessionContext ctx : m)
            ctx.squadInbox.add(new SquadCommand.RunScenario(name));
        if (gui != null && !m.isEmpty())
            gui.msg(L10n.get("commander.msg.scenario", name, m.size()));
    }

    /** The resource name of a menu entry, or null for one that is not a resource (server-made entries). */
    public static String resName(MenuGrid.Pagina pag) {
        if (pag == null || !(pag.id instanceof Indir))
            return null;
        try {
            return pag.res().name;
        } catch (Loading l) {
            return null;
        }
    }

    /**
     * The action menu was used in Commander mode: a leaf entry goes to the selection instead of the character on
     * screen. True when it was taken.
     */
    public static boolean menuUsed(MenuGrid menu, MenuGrid.PagButton b) {
        if (!commanding(menu.ui))
            return false;
        String name = resName(b.pag);
        if (name == null)
            return false;
        /* A building is placed by the commander, on the character on screen, as a planner ghost (NMapView), and
         * built later with the Build order. */
        if (name.startsWith("paginae/bld/"))
            return false;
        useAction(name, ((NUI) menu.ui).gui);
        return true;
    }

    /** The planner layer Commander puts ghosts in: the active one, else a "Commander" layer, made when missing. */
    public static void ensureGhostLayer(nurgling.planning.PlanningLayerManager plm) {
        if (plm.getActiveLayer() != null)
            return;
        for (nurgling.planning.PlanningNode n : plm.getRoots()) {
            if (n instanceof nurgling.planning.PlanningLayer && "Commander".equals(n.name)) {
                plm.setActiveLayer(n.id);
                return;
            }
        }
        nurgling.planning.PlanningLayer l = plm.createLayer("Commander", null);
        plm.setActiveLayer(l.id);
    }

    /**
     * Build: the planner ghosts inside a ground rectangle go to the selected characters on the camera's theater, split
     * into one contiguous share each, and every one builds its share with the Blueprint Builder.
     */
    public static void build(NGameUI gui, Coord2d a, Coord2d b) {
        nurgling.planning.PlanningLayerManager plm = ((NUI) gui.ui).core.planningLayer;
        Coord2d min = Coord2d.of(Math.min(a.x, b.x), Math.min(a.y, b.y)), max = Coord2d.of(Math.max(a.x, b.x), Math.max(a.y, b.y));
        List<nurgling.planning.PlanningGhost> ghosts = new java.util.ArrayList<>(plm.visibleGhostsIn(min, max));
        if (ghosts.isEmpty()) {
            gui.msg(L10n.get("commander.msg.no_ghosts"));
            return;
        }
        Squad sq = SessionManager.getInstance().squad();
        List<SessionContext> m = sq.inTheater(selection(gui));
        if (m.isEmpty())
            return;
        /* Sorted along the longer side of the rectangle, so each share is one piece of it. */
        boolean wide = (max.x - min.x) >= (max.y - min.y);
        ghosts.sort(java.util.Comparator.comparingDouble(g -> {
            Coord2d p = plm.worldPosOf(g);
            return (p == null) ? 0 : (wide ? p.x : p.y);
        }));
        int n = Math.min(m.size(), ghosts.size());
        for (int i = 0; i < n; i++) {
            List<nurgling.planning.PlanningGhost> share = new java.util.ArrayList<>(
                ghosts.subList(i * ghosts.size() / n, (i + 1) * ghosts.size() / n));
            SessionContext ctx = m.get(i);
            ctx.squadInbox.add(new SquadCommand.Stop());
            BotExecutor.runWithSupportsOn(ctx, "blueprint_builder",
                                          new nurgling.actions.bots.blueprint.BlueprintBuilder(share), false, null);
        }
        gui.msg(L10n.get("commander.msg.build", ghosts.size(), n));
    }
}
