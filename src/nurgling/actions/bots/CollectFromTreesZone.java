package nurgling.actions.bots;

import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.widgets.Specialisation;

import java.util.Map;

/**
 * Zone version of the three tree-collection bots, driven entirely by a scenario step's settings
 * so it needs no prompts and no bot-menu entry. The "resource" setting picks which of them to
 * run against the Tree Orchard zone.
 * <p>
 * It delegates to the existing bots rather than restating their configuration: each of bough,
 * bark and leaf differs in four ways (flower action, item size, pose, and which gobs count), and
 * duplicating that here would leave two copies to keep in step.
 */
public class CollectFromTreesZone implements Action {

    public static final String RESOURCE_SETTING = "resource";
    public static final String BOUGH = "Bough";
    public static final String BARK = "Bark";
    public static final String LEAF = "Leaf";

    private final String resource;

    public CollectFromTreesZone() {
        this.resource = BOUGH;
    }

    public CollectFromTreesZone(Map<String, Object> settings) {
        Object value = settings == null ? null : settings.get(RESOURCE_SETTING);
        this.resource = value instanceof String ? (String) value : BOUGH;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Specialisation.SpecName zone = Specialisation.SpecName.treeOrchard;
        CollectFromTreeBot bot;
        switch (resource) {
            case BARK:
                bot = new CollectBark(zone);
                break;
            case LEAF:
                bot = new CollectLeaf(zone);
                break;
            default:
                bot = new CollectBough(zone);
                break;
        }
        return bot.run(gui);
    }
}
