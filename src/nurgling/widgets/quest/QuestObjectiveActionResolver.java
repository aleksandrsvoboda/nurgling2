package nurgling.widgets.quest;

import nurgling.tools.Forageables;
import nurgling.tools.RockResourceMapper;
import nurgling.tools.Trees;
import nurgling.tools.VSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class QuestObjectiveActionResolver {
    private final Map<String, Forageables.Entry> forageByName;

    public QuestObjectiveActionResolver() {
        Map<String, Forageables.Entry> entries = new LinkedHashMap<>();
        for(Forageables.Entry entry : Forageables.all()) {
            String key = normalize(entry.name);
            if(!key.isEmpty() && !entries.containsKey(key))
                entries.put(key, entry);
        }
        forageByName = Collections.unmodifiableMap(entries);
    }

    public QuestObjectiveAction resolve(QCond cond) {
        if(cond == null || cond.ready || cond.itemTarget == null)
            return null;
        if(cond.verb == QCond.Verb.CREATE)
            return new QuestObjectiveAction(QuestObjectiveAction.Kind.CRAFT,
                    Collections.singletonList(normalize(cond.itemTarget)));
        if(cond.verb == QCond.Verb.FELL)
            return treeTerrainFromName(cond.itemTarget);
        if(cond.verb != QCond.Verb.PICK && cond.verb != QCond.Verb.BRING)
            return null;

        Forageables.Entry forage = forageByName.get(normalize(cond.itemTarget));
        if(forage != null && !forage.terrains.isEmpty())
            return new QuestObjectiveAction(QuestObjectiveAction.Kind.FORAGE_TERRAIN, forage.terrains);

        Set<String> rocks = RockResourceMapper.getTileResourcesForItem(cond.itemTarget);
        if(!rocks.isEmpty())
            return new QuestObjectiveAction(QuestObjectiveAction.Kind.ROCK_TERRAIN, rocks);
        return treeTerrainFromProduct(cond.itemTarget);
    }

    public Set<String> treeResources(QCond cond) {
        if(cond == null || cond.ready || cond.itemTarget == null)
            return Collections.emptySet();
        Set<String> products = VSpec.treeResourcesForProduct(cond.itemTarget);
        if(!products.isEmpty())
            return products;
        Trees.Entry tree = Trees.find(cond.itemTarget);
        if(tree != null && !tree.resource.isEmpty())
            return Collections.singleton(tree.resource);
        return Collections.emptySet();
    }

    private static QuestObjectiveAction treeTerrainFromName(String name) {
        Trees.Entry tree = Trees.find(name);
        if(tree == null || tree.terrains.isEmpty())
            return null;
        return new QuestObjectiveAction(QuestObjectiveAction.Kind.TREE_TERRAIN, tree.terrains);
    }

    private static QuestObjectiveAction treeTerrainFromProduct(String product) {
        LinkedHashSet<String> terrains = new LinkedHashSet<>();
        for(String resource : VSpec.treeResourcesForProduct(product)) {
            Trees.Entry tree = Trees.findByResource(resource);
            if(tree != null)
                terrains.addAll(tree.terrains);
        }
        if(terrains.isEmpty())
            return null;
        return new QuestObjectiveAction(QuestObjectiveAction.Kind.TREE_TERRAIN, terrains);
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
