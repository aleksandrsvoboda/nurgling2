package nurgling.actions.bots;

import haven.*;
import haven.res.gfx.fx.eq.Equed;
import nurgling.NGameUI;
import nurgling.NGItem;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.tasks.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.NEquipory;

import java.util.ArrayList;

public class LightObject implements Action {

    private final Gob target;
    private static final Coord TORCH_SIZE = new Coord(1, 1);

    private static final int SOURCE_EQUIPMENT = 0;
    private static final int SOURCE_INVENTORY = 1;

    public LightObject(Gob target) {
        this.target = target;
    }

    // --- Config system ---

    static class LightConfig {
        final String displayName;
        final int fireFlag;
        final boolean checkFuel;
        final int embersAttr; // -1 if no embers state

        LightConfig(String displayName, int fireFlag, boolean checkFuel, int embersAttr) {
            this.displayName = displayName;
            this.fireFlag = fireFlag;
            this.checkFuel = checkFuel;
            this.embersAttr = embersAttr;
        }
    }

    public static LightConfig getConfig(String gobName) {
        if (gobName.contains("gfx/terobjs/pow")) {
            return new LightConfig("Fire Place", 4, true, 11);
        } else if (gobName.contains("gfx/terobjs/cauldron")) {
            return new LightConfig("Cauldron", 2, true, -1);
        } else if (gobName.contains("gfx/terobjs/brazier")) {
            return new LightConfig("Brazier", 8, true, -1);
        }
        return null;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        String gobName = target.ngob.name;
        if (gobName == null)
            return Results.ERROR("Cannot determine object type");

        LightConfig config = getConfig(gobName);
        if (config == null)
            return Results.ERROR("Unsupported object type: " + gobName);

        if ((target.ngob.getModelAttribute() & config.fireFlag) != 0) {
            gui.msg(config.displayName + " is already lit");
            return Results.SUCCESS();
        }

        if (config.checkFuel && (target.ngob.getModelAttribute() & 1) == 0) {
            gui.error(config.displayName + " has no fuel");
            return Results.FAIL();
        }

        // Priority 0: Embers - can light directly via "Light My Fire"
        if (config.embersAttr != -1 && target.ngob.getModelAttribute() == config.embersAttr) {
            gui.msg(config.displayName + " has embers, lighting directly");
            new PathFinder(target).run(gui);
            Results result = new SelectFlowerAction("Light My Fire", target).run(gui);
            if (result.IsSuccess()) {
                NUtils.getUI().core.addTask(new WaitGobModelAttr(target, config.fireFlag));
                return Results.SUCCESS();
            }
        }
        if (isLit(config))
            return Results.SUCCESS();

        // Priority 1: Lit torch in equipment
        gui.msg("No embers, checking for equipped lit torch");
        if (tryEquippedLitTorch(gui, config))
            return Results.SUCCESS();
        if (isLit(config))
            return Results.SUCCESS();

        // Priority 2: Unlit torch (equipment or inventory) + lit brazier
        gui.msg("No equipped lit torch, checking for torch and nearby brazier");
        if (tryTorchWithBrazier(gui, config))
            return Results.SUCCESS();
        if (isLit(config))
            return Results.SUCCESS();

        // Priority 3: Lit candelabrum
        gui.msg("No torch with brazier found, looking for lit candelabrum");
        if (tryLitCandelabrum(gui, config))
            return Results.SUCCESS();
        if (isLit(config))
            return Results.SUCCESS();

        // Priority 4: Lit torch on torchpost
        gui.msg("Lit candelabrum not found, looking for lit torch on torchpost");
        if (tryLitTorchOnTorchpost(gui, config))
            return Results.SUCCESS();
        if (isLit(config))
            return Results.SUCCESS();

        // Priority 5: Unlit torch on torchpost + lit brazier
        gui.msg("Lit torch on torchpost not found, looking for unlit torch on torchpost and brazier");
        if (tryUnlitTorchpostWithBrazier(gui, config))
            return Results.SUCCESS();
        if (isLit(config))
            return Results.SUCCESS();

        // Priority 6: Sticks (branches)
        gui.msg("No torch or brazier found, using branches");
        return new LightFire(target).run(gui);
    }

    private boolean isLit(LightConfig config) {
        return (target.ngob.getModelAttribute() & config.fireFlag) != 0;
    }

    // --- Priority 1: Lit torch in equipment ---

    private boolean tryEquippedLitTorch(NGameUI gui, LightConfig config) throws InterruptedException {
        NEquipory equip = NUtils.getEquipment();
        if (equip == null)
            return false;

        int sourceSlot = -1;
        WItem torch = null;
        for (int i = 0; i < equip.quickslots.length; i++) {
            WItem item = equip.quickslots[i];
            if (item == null)
                continue;
            Resource res = item.item.getres();
            if (res != null && res.name.endsWith("torch-l")) {
                sourceSlot = i;
                torch = item;
                break;
            }
        }
        if (torch == null)
            return false;

        NUtils.takeItemToHand(torch);
        NUtils.getUI().core.addTask(new WaitItemInHand());

        new PathFinder(target).run(gui);
        NUtils.activateItem(target);
        waitForProgress(gui);

        if (gui.vhand != null) {
            NUtils.getEquipment().wdgmsg("drop", sourceSlot);
            NUtils.getUI().core.addTask(new WaitFreeHand());
        }
        return true;
    }

    // --- Priority 2: Unlit torch (equipment or inventory) + lit brazier ---

    private boolean tryTorchWithBrazier(NGameUI gui, LightConfig config) throws InterruptedException {
        Gob litBrazier = findLitBrazier();
        if (litBrazier == null)
            return false;

        NEquipory equip = NUtils.getEquipment();
        int torchSource = -1;
        int equipSlot = -1;
        WItem torch = null;

        if (equip != null) {
            for (int i = 0; i < equip.quickslots.length; i++) {
                WItem item = equip.quickslots[i];
                if (item == null)
                    continue;
                Resource res = item.item.getres();
                if (res != null && res.name.endsWith("torch") && !res.name.endsWith("torch-l")) {
                    torch = item;
                    equipSlot = i;
                    torchSource = SOURCE_EQUIPMENT;
                    break;
                }
            }
        }

        if (torch == null) {
            ArrayList<WItem> invTorches = gui.getInventory().getItems(new NAlias("Torch"));
            for (WItem t : invTorches) {
                Resource res = t.item.getres();
                if (res != null && res.name.endsWith("torch") && !res.name.endsWith("torch-l")) {
                    torch = t;
                    torchSource = SOURCE_INVENTORY;
                    break;
                }
            }
        }

        if (torch == null)
            return false;

        NUtils.takeItemToHand(torch);
        NUtils.getUI().core.addTask(new WaitItemInHand());

        new PathFinder(litBrazier).run(gui);
        NUtils.activateItem(litBrazier);
        waitForProgress(gui);

        if (gui.vhand != null) {
            new PathFinder(target).run(gui);
            NUtils.activateItem(target);
            waitForProgress(gui);
        }

        // Extinguish torch and put back where it came from
        if (gui.vhand != null) {
            if (torchSource == SOURCE_EQUIPMENT) {
                extinguishAndReturnToEquip(gui, equipSlot);
            } else {
                // From inventory - dropping to inv extinguishes it
                NUtils.dropToInv();
                NUtils.getUI().core.addTask(new WaitFreeHand());
            }
        }
        return isLit(config);
    }

    // --- Priority 3: Lit candelabrum ---

    private boolean tryLitCandelabrum(NGameUI gui, LightConfig config) throws InterruptedException {
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

        PathFinder pf = new PathFinder(target);
        pf.isHardMode = true;
        pf.run(gui);
        NUtils.activateGob(target);
        NUtils.getUI().core.addTask(new WaitGobModelAttr(target, config.fireFlag));

        new PlaceObject(litCandelabrum, originalPos, 0).run(gui);
        return true;
    }

    // --- Priority 4: Lit torch on torchpost ---

    private boolean tryLitTorchOnTorchpost(NGameUI gui, LightConfig config) throws InterruptedException {
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

        new PathFinder(litTorchpost).run(gui);
        Results flowerResult = new SelectFlowerAction("Take torch", litTorchpost).run(gui);
        if (!flowerResult.IsSuccess())
            return false;

        NUtils.getUI().core.addTask(new WaitItemInHand());

        new PathFinder(target).run(gui);
        NUtils.activateItem(target);
        waitForProgress(gui);

        if (gui.vhand != null) {
            new PathFinder(litTorchpost).run(gui);
            NUtils.activateItem(litTorchpost);
            NUtils.getUI().core.addTask(new WaitFreeHand());
        }
        return true;
    }

    // --- Priority 5: Unlit torch on torchpost + lit brazier ---

    private boolean tryUnlitTorchpostWithBrazier(NGameUI gui, LightConfig config) throws InterruptedException {
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

        Gob litBrazier = findLitBrazier();
        if (litBrazier == null)
            return false;

        new PathFinder(unlitTorchpost).run(gui);
        NUtils.rclickGob(unlitTorchpost);
        NUtils.getUI().core.addTask(new WaitItemInHand());

        new PathFinder(litBrazier).run(gui);
        NUtils.activateItem(litBrazier);
        waitForProgress(gui);

        if (gui.vhand != null) {
            new PathFinder(target).run(gui);
            NUtils.activateItem(target);
            waitForProgress(gui);
        }

        // Extinguish torch and place back on torchpost
        if (gui.vhand != null) {
            extinguishAndReturnToTorchpost(gui, unlitTorchpost);
        }
        return isLit(config);
    }

    // --- Extinguish helpers ---

    private void extinguishAndReturnToEquip(NGameUI gui, int equipSlot) throws InterruptedException {
        if (gui.vhand == null)
            return;

        if (gui.getInventory().getNumberFreeCoord(TORCH_SIZE) > 0) {
            NUtils.dropToInv();
            NUtils.getUI().core.addTask(new WaitFreeHand());
            WItem torch = gui.getInventory().getItem("Torch");
            if (torch != null) {
                torch.item.wdgmsg("take", Coord.z);
                NUtils.getUI().core.addTask(new WaitItemInHand());
                NUtils.getEquipment().wdgmsg("drop", equipSlot);
                NUtils.getUI().core.addTask(new WaitFreeHand());
            }
        } else {
            NUtils.getEquipment().wdgmsg("drop", equipSlot);
            NUtils.getUI().core.addTask(new WaitFreeHand());
        }
    }

    private void extinguishAndReturnToTorchpost(NGameUI gui, Gob torchpost) throws InterruptedException {
        if (gui.vhand == null)
            return;

        if (gui.getInventory().getNumberFreeCoord(TORCH_SIZE) > 0) {
            NUtils.dropToInv();
            NUtils.getUI().core.addTask(new WaitFreeHand());
            WItem torch = gui.getInventory().getItem("Torch");
            if (torch != null) {
                torch.item.wdgmsg("take", Coord.z);
                NUtils.getUI().core.addTask(new WaitItemInHand());
                new PathFinder(torchpost).run(gui);
                NUtils.activateItem(torchpost);
                NUtils.getUI().core.addTask(new WaitFreeHand());
            }
        } else {
            new PathFinder(torchpost).run(gui);
            NUtils.activateItem(torchpost);
            NUtils.getUI().core.addTask(new WaitFreeHand());
        }
    }

    // --- Shared helpers ---

    private Gob findLitBrazier() {
        ArrayList<Gob> braziers = Finder.findGobs(new NAlias("gfx/terobjs/brazier"));
        for (Gob b : braziers) {
            if (b.ngob.getModelAttribute() == 8)
                return b;
        }
        return null;
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
