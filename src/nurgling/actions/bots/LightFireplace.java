package nurgling.actions.bots;

import haven.Coord2d;
import haven.Gob;
import haven.OCache;
import haven.res.gfx.fx.eq.Equed;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitFreeHand;
import nurgling.tasks.WaitGobModelAttr;
import nurgling.tasks.WaitItemInHand;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;

public class LightFireplace implements Action {

    private final Gob fireplace;
    private static final int FIRE_FLAG = 4;

    public LightFireplace(Gob fireplace) {
        this.fireplace = fireplace;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if ((fireplace.ngob.getModelAttribute() & FIRE_FLAG) != 0) {
            gui.msg("Fireplace is already lit");
            return Results.SUCCESS();
        }

        if ((fireplace.ngob.getModelAttribute() & 1) == 0) {
            gui.error("Fireplace has no fuel");
            return Results.FAIL();
        }

        // Priority 0: Embers - can light directly via "Light My Fire"
        if (fireplace.ngob.getModelAttribute() == 11) {
            gui.msg("Fireplace has embers, lighting directly");
            new PathFinder(fireplace).run(gui);
            Results result = new SelectFlowerAction("Light My Fire", fireplace).run(gui);
            if (result.IsSuccess()) {
                NUtils.getUI().core.addTask(new WaitGobModelAttr(fireplace, FIRE_FLAG));
                return Results.SUCCESS();
            }
        }
        if (isFireLit())
            return Results.SUCCESS();

        // Priority 1: Lit candelabrum
        gui.msg("Looking for lit candelabrum");
        if (tryLitCandelabrum(gui))
            return Results.SUCCESS();
        if (isFireLit())
            return Results.SUCCESS();

        // Priority 2: Lit torch on torchpost
        gui.msg("Lit candelabrum not found, looking for lit torch");
        if (tryLitTorch(gui))
            return Results.SUCCESS();
        if (isFireLit())
            return Results.SUCCESS();

        // Priority 3: Unlit torch on torchpost + lit brazier
        gui.msg("Lit torch not found, looking for unlit torch and brazier");
        if (tryUnlitTorchWithBrazier(gui))
            return Results.SUCCESS();
        if (isFireLit())
            return Results.SUCCESS();

        // Priority 4: Sticks (branches)
        gui.msg("No torch or brazier found, using branches");
        return new LightFire(fireplace).run(gui);
    }

    private boolean isFireLit() {
        return (fireplace.ngob.getModelAttribute() & FIRE_FLAG) != 0;
    }

    private boolean tryLitCandelabrum(NGameUI gui) throws InterruptedException {
        ArrayList<Gob> candelabrums = Finder.findGobs(new NAlias("gfx/terobjs/candelabrum"));
        Gob litCandelabrum = null;
        for (Gob c : candelabrums) {
            if (c.ngob.getModelAttribute() == 3) {
                litCandelabrum = c;
                break;
            }
        }
        if (litCandelabrum == null)
            return false;

        Coord2d originalPos = new Coord2d(litCandelabrum.rc.x, litCandelabrum.rc.y);
        new LiftObject(litCandelabrum).run(gui);

        PathFinder pf = new PathFinder(fireplace);
        pf.isHardMode = true;
        pf.run(gui);
        NUtils.activateGob(fireplace);
        NUtils.getUI().core.addTask(new WaitGobModelAttr(fireplace, FIRE_FLAG));

        new PlaceObject(litCandelabrum, originalPos, 0).run(gui);
        return true;
    }

    private boolean tryLitTorch(NGameUI gui) throws InterruptedException {
        ArrayList<Gob> torchposts = Finder.findGobs(new NAlias("torchpost"));
        Gob litTorchpost = null;
        for (Gob tp : torchposts) {
            TorchpostState state = getTorchpostState(tp);
            if (state.hasTorch && state.isLit) {
                litTorchpost = tp;
                break;
            }
        }
        if (litTorchpost == null)
            return false;

        // Take lit torch via flower menu
        new PathFinder(litTorchpost).run(gui);
        Results flowerResult = new SelectFlowerAction("Take torch", litTorchpost).run(gui);
        if (!flowerResult.IsSuccess())
            return false;

        NUtils.getUI().core.addTask(new WaitItemInHand());

        // Use torch on fireplace
        new PathFinder(fireplace).run(gui);
        NUtils.activateItem(fireplace);
        waitForProgress(gui);

        // Place torch back on torchpost
        if (gui.vhand != null) {
            new PathFinder(litTorchpost).run(gui);
            NUtils.activateItem(litTorchpost);
            NUtils.getUI().core.addTask(new WaitFreeHand());
        }
        return true;
    }

    private boolean tryUnlitTorchWithBrazier(NGameUI gui) throws InterruptedException {
        // Find torchpost with unlit torch
        ArrayList<Gob> torchposts = Finder.findGobs(new NAlias("torchpost"));
        Gob unlitTorchpost = null;
        for (Gob tp : torchposts) {
            TorchpostState state = getTorchpostState(tp);
            if (state.hasTorch && !state.isLit) {
                unlitTorchpost = tp;
                break;
            }
        }
        if (unlitTorchpost == null)
            return false;

        // Find lit brazier
        ArrayList<Gob> braziers = Finder.findGobs(new NAlias("gfx/terobjs/brazier"));
        Gob litBrazier = null;
        for (Gob b : braziers) {
            if (b.ngob.getModelAttribute() == 8) {
                litBrazier = b;
                break;
            }
        }
        if (litBrazier == null)
            return false;

        // Take unlit torch (just right-click, no flower menu)
        new PathFinder(unlitTorchpost).run(gui);
        NUtils.rclickGob(unlitTorchpost);
        NUtils.getUI().core.addTask(new WaitItemInHand());

        // Light torch on brazier
        new PathFinder(litBrazier).run(gui);
        NUtils.activateItem(litBrazier);
        waitForProgress(gui);

        // Use lit torch on fireplace
        if (gui.vhand != null) {
            new PathFinder(fireplace).run(gui);
            NUtils.activateItem(fireplace);
            waitForProgress(gui);
        }

        // Place torch back on torchpost
        if (gui.vhand != null) {
            new PathFinder(unlitTorchpost).run(gui);
            NUtils.activateItem(unlitTorchpost);
            NUtils.getUI().core.addTask(new WaitFreeHand());
        }
        return isFireLit();
    }

    private void waitForProgress(NGameUI gui) throws InterruptedException {
        NUtils.getUI().core.addTask(new NTask() {
            @Override
            public boolean check() {
                return (gui.prog != null) && (gui.prog.prog > 0);
            }
        });
        NUtils.getUI().core.addTask(new NTask() {
            @Override
            public boolean check() {
                return (gui.prog == null) || (gui.prog.prog <= 0);
            }
        });
    }

    private static TorchpostState getTorchpostState(Gob torchpost) {
        TorchpostState state = new TorchpostState();
        for (Gob.Overlay ol : torchpost.ols) {
            if (ol.spr instanceof Equed && ol.sm instanceof OCache.OlSprite) {
                state.hasTorch = true;
                byte[] sdt = ((OCache.OlSprite) ol.sm).sdt;
                state.isLit = sdt.length > 0 && (sdt[sdt.length - 1] & 0xFF) == 1;
                break;
            }
        }
        return state;
    }

    private static class TorchpostState {
        boolean hasTorch = false;
        boolean isLit = false;
    }
}
