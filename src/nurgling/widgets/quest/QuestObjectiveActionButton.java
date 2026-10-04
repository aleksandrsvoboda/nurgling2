package nurgling.widgets.quest;

import haven.Coord;
import haven.GOut;
import haven.UI;
import haven.Widget;


/** Small objective-side button shared by the compact tracker and the quest journal. */
public class QuestObjectiveActionButton extends Widget {
    private static final QuestObjectiveActionResolver RESOLVER = new QuestObjectiveActionResolver();
    private final QCond cond;
    private static final double RECHECK_INTERVAL = 0.5;
    private QuestObjectiveAction action;
    private boolean hover;
    private double recheck = 0;

    public QuestObjectiveActionButton(QCond cond) {
        super(UI.scale(new Coord(16, 16)));
        this.cond = cond;
        QuestObjectiveAction potential = RESOLVER.resolve(cond);
        this.action = potential;
        if(potential != null && potential.kind == QuestObjectiveAction.Kind.CRAFT)
            hide();
    }

    public static String glyphFor(QuestObjectiveAction action) {
        return action != null && action.kind == QuestObjectiveAction.Kind.CRAFT ? "C" : "M";
    }

    static boolean consumesClick(int button) {
        return button == 1;
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        /* Availability of a CRAFT action means scanning the whole pagina set under its
         * lock, so re-check on an interval rather than every frame. */
        if((recheck -= dt) > 0)
            return;
        recheck = RECHECK_INTERVAL;
        action = QuestObjectiveActions.available(this, cond);
        show(action != null);
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        hover = ev.c.isect(Coord.z, sz);
        super.mousemove(ev);
    }

    @Override
    public void draw(GOut g) {
        nurgling.styles.GeneratedButtons.plate(g, Coord.z, sz, hover ? nurgling.styles.GeneratedButtons.State.HOVER : nurgling.styles.GeneratedButtons.State.NORMAL);
        int pad = UI.scale(3);
        nurgling.styles.GeneratedButtons.icon(g, action != null && action.kind == QuestObjectiveAction.Kind.CRAFT ? "credo" : "world", new Coord(pad, pad), Math.max(1, sz.x - 2 * pad));
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(consumesClick(ev.b)) {
            QuestObjectiveActions.execute(this, QuestObjectiveActions.available(this, cond));
            return true;
        }
        return super.mousedown(ev);
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        return QuestObjectiveActions.tooltip(action);
    }
}
