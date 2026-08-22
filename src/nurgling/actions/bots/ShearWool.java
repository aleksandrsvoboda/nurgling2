package nurgling.actions.bots;

import haven.Coord;
import haven.Gob;
import nurgling.NFlowerMenu;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NContext;
import nurgling.tasks.NFlowerMenuIsClosed;
import nurgling.tasks.WaitCollectState;
import nurgling.tasks.WaitPose;

import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

import static haven.OCache.posres;

public class ShearWool implements Action {
    NAlias type;
    Specialisation.SpecName spec;

    static final String ACTION = "Shear wool";

    public ShearWool(Specialisation.SpecName spec, NAlias type) {
        this.type = type;
        this.spec = spec;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);
        ArrayList<Gob> gobs = Finder.findGobs(context.goToArea(spec), type);
        gobs.sort(NUtils.d_comp);

        boolean needRestart = true;
        while (needRestart) {
            needRestart = false;
            for (Gob target : gobs) {
                if (NUtils.getGameUI().getInventory().getNumberFreeCoord(Coord.of(1, 1)) < 3) {
                    new FreeInventory2(context).run(gui);
                    gobs = Finder.findGobs(context.goToArea(spec), type);
                    gobs.sort(NUtils.d_comp);
                    needRestart = true;
                    break;
                }

                shear(gui, target);
            }
        }
        new FreeInventory2(context).run(gui);
        context.goToArea(spec);
        return Results.SUCCESS();
    }

    /**
     * Shears a single animal. The right-click has to happen from up close: at range the
     * server walks the character over first and only opens the petal menu on arrival,
     * which takes far longer than the menu wait allows, so the animal would be skipped.
     *
     * @return true if the shearing action was started, false if the animal was skipped.
     */
    static boolean shear(NGameUI gui, Gob target) throws InterruptedException {
        if (!PathFinder.isAvailable(target))
            return false;
        new DynamicPf(target).run(gui);

        // The animal grazes on, so re-read its position after walking.
        Gob actual = Finder.findGob(target.id);
        if (actual == null)
            return false;
        gui.map.wdgmsg("click", Coord.z, actual.rc.floor(posres), 3, 0, 1, (int) actual.id, actual.rc.floor(posres),
                0, -1);

        NFlowerMenu fm = NUtils.getFlowerMenu();
        if (fm == null)
            return false;
        if (!fm.hasOpt(ACTION)) {
            fm.wdgmsg("cl", -1);
            NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
            return false;
        }
        if (!fm.chooseOpt(ACTION)) {
            NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
            return false;
        }
        NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
        NUtils.getUI().core.addTask(new WaitPose(NUtils.player(), "gfx/borka/carving"));
        NUtils.getUI().core.addTask(new WaitCollectState(target, Coord.of(1, 1)));
        return true;
    }
}
