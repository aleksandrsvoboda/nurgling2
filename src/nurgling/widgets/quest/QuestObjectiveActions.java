package nurgling.widgets.quest;

import haven.GameUI;
import haven.Loading;
import haven.MenuGrid;
import haven.Resource;
import haven.Widget;
import nurgling.widgets.MapToolsWindow;

import java.util.ArrayList;
import java.util.List;

/** Availability checks and UI dispatch for quest-objective action buttons. */
public final class QuestObjectiveActions {
    private static final QuestObjectiveActionResolver RESOLVER = new QuestObjectiveActionResolver();

    private QuestObjectiveActions() {
    }

    public static QuestObjectiveAction available(Widget origin, QCond cond) {
        QuestObjectiveAction action = RESOLVER.resolve(cond);
        if(action == null)
            return null;
        if(action.kind == QuestObjectiveAction.Kind.CRAFT && craftPagina(origin, action.targets.get(0)) == null)
            return null;
        return action;
    }

    public static boolean execute(Widget origin, QuestObjectiveAction action) {
        if(origin == null || action == null)
            return false;
        switch(action.kind) {
            case FORAGE_TERRAIN:
            case TREE_TERRAIN:
                MapToolsWindow.openTerrainSearch(action.targets);
                return true;
            case ROCK_TERRAIN:
                MapToolsWindow.openTerrainResources(action.targets);
                return true;
            case CRAFT:
                MenuGrid.Pagina pagina = craftPagina(origin, action.targets.get(0));
                if(pagina == null)
                    return false;
                try {
                    GameUI gui = gui(origin);
                    if(gui == null || gui.menu == null)
                        return false;
                    gui.menu.use(pagina.button(),
                            new MenuGrid.Interaction(1, origin.ui.modflags()), false);
                    return true;
                } catch(Loading | Resource.BadResourceException e) {
                    return false;
                }
            default:
                return false;
        }
    }

    public static String tooltip(QuestObjectiveAction action) {
        return action != null && action.kind == QuestObjectiveAction.Kind.CRAFT
                ? "Open crafting recipe" : "Show gathering terrain";
    }

    private static MenuGrid.Pagina craftPagina(Widget origin, String target) {
        if(origin == null)
            return null;
        GameUI gui = gui(origin);
        if(gui == null || gui.menu == null)
            return null;
        List<MenuGrid.Pagina> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        synchronized(gui.menu.paginae) {
            for(MenuGrid.Pagina pagina : gui.menu.paginae) {
                try {
                    names.add(pagina.button().name());
                    candidates.add(pagina);
                } catch(Loading | Resource.BadResourceException ignored) {
                }
            }
        }
        int index = QuestCraftRecipeSelector.uniqueExactName(names, target);
        return index < 0 ? null : candidates.get(index);
    }

    private static GameUI gui(Widget origin) {
        return origin == null ? null : origin.getparent(GameUI.class);
    }
}
