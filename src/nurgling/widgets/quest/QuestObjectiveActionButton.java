package nurgling.widgets.quest;

import haven.Coord;
import haven.GOut;
import haven.Tex;
import haven.Text;
import haven.UI;
import haven.Widget;

import java.awt.Color;

/** Small objective-side button shared by the compact tracker and the quest journal. */
public class QuestObjectiveActionButton extends Widget {
    private static final QuestObjectiveActionResolver RESOLVER = new QuestObjectiveActionResolver();
    private final QCond cond;
    private final Tex glyph;
    private static final double RECHECK_INTERVAL = 0.5;
    private QuestObjectiveAction action;
    private boolean hover;
    private double recheck = 0;

    public QuestObjectiveActionButton(QCond cond) {
        super(UI.scale(new Coord(16, 16)));
        this.cond = cond;
        QuestObjectiveAction potential = RESOLVER.resolve(cond);
        this.action = potential;
        this.glyph = Text.render(glyphFor(potential), Color.WHITE).tex();
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
        g.chcolor(hover ? new Color(104, 129, 154, 230) : new Color(55, 72, 88, 220));
        g.frect(Coord.z, sz);
        g.chcolor(new Color(185, 205, 220, 255));
        g.rect(Coord.z, sz.sub(1, 1));
        g.chcolor();
        g.image(glyph, sz.sub(glyph.sz()).div(2));
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
