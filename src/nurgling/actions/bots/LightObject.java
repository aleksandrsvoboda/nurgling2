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
        final int fuelFlag; // 0 = don't check fuel, otherwise bitmask for fuel bit
        final int embersAttr; // -1 if no embers state

        LightConfig(String displayName, int fireFlag, int fuelFlag, int embersAttr) {
            this.displayName = displayName;
            this.fireFlag = fireFlag;
            this.fuelFlag = fuelFlag;
            this.embersAttr = embersAttr;
        }
    }

    public static LightConfig getConfig(String gobName) {
        if (gobName.contains("gfx/terobjs/pow")) {
            return new LightConfig("Fire Place", 4, 1, 11);
        } else if (gobName.contains("gfx/terobjs/cauldron")) {
            return new LightConfig("Cauldron", 2, 1, -1);
        } else if (gobName.contains("gfx/terobjs/brazier")) {
            return new LightConfig("Brazier", 8, 1, -1);
        } else if (gobName.contains("gfx/terobjs/oven")) {
            return new LightConfig("Oven", 4, 0, -1);
        } else if (gobName.contains("gfx/terobjs/smokeshed")) {
            return new LightConfig("Smoke Shed", 16, 0, -1);
        } else if (gobName.contains("gfx/terobjs/primsmelter")) {
            return new LightConfig("Stack Furnace", 2, 0, -1);
        } else if (gobName.contains("gfx/terobjs/smelter")) {
            return new LightConfig("Ore Smelter", 2, 0, -1);
        } else if (gobName.contains("gfx/terobjs/kiln")) {
            return new LightConfig("Kiln", 1, 0, -1);
        } else if (gobName.contains("gfx/terobjs/tarkiln")) {
            return new LightConfig("Tar Kiln", 16, 0, -1);
        } else if (gobName.contains("gfx/terobjs/fineryforge")) {
            return new LightConfig("Finery Forge", 8, 4, -1);
        } else if (gobName.contains("gfx/terobjs/steelcrucible")) {
            return new LightConfig("Steel Crucible", 4, 0, -1);
        } else if (gobName.contains("gfx/terobjs/crucible")) {
            return new LightConfig("Crucible", 4, 2, -1);
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

        if (config.fuelFlag != 0 && (target.ngob.getModelAttribute() & config.fuelFlag) == 0) {
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
        gui.msg("No equipped lit torch, checking for torch and nearby fire source");
        if (tryTorchWithBrazier(gui, config))
            return Results.SUCCESS();
        if (isLit(config))
            return Results.SUCCESS();

        // Priority 3: Lit candelabrum
        gui.msg("No torch with fire source found, looking for lit candelabrum");
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
        gui.msg("Lit torch on torchpost not found, looking for unlit torch on torchpost and fire source");
        if (tryUnlitTorchpostWithBrazier(gui, config))
            return Results.SUCCESS();
        if (isLit(config))
            return Results.SUCCESS();

        // Priority 6: Sticks (branches)
        gui.msg("No torch or fire source found, using branches");
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
        Gob litBrazier = findLitFireSource();
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

        Gob litBrazier = findLitFireSource();
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

    private static final NAlias FIRE_SOURCE_ALIAS = new NAlias(
            "gfx/terobjs/brazier",
            "gfx/terobjs/pow",
            "gfx/terobjs/oven",
            "gfx/terobjs/kiln",
            "gfx/terobjs/primsmelter",
            "gfx/terobjs/smelter",
            "gfx/terobjs/fineryforge",
            "gfx/terobjs/steelcrucible",
            "gfx/terobjs/crucible"
    );

    private Gob findLitFireSource() {
        ArrayList<Gob> sources = Finder.findGobs(FIRE_SOURCE_ALIAS);
        Gob closest = null;
        double closestDist = Double.MAX_VALUE;
        Coord2d playerPos = NUtils.player().rc;

        for (Gob gob : sources) {
            if (gob.id == target.id)
                continue;
            if (gob.ngob == null || gob.ngob.name == null)
                continue;
            LightConfig config = getConfig(gob.ngob.name);
            if (config == null)
                continue;
            if ((gob.ngob.getModelAttribute() & config.fireFlag) == 0)
                continue;
            double dist = gob.rc.dist(playerPos);
            if (dist < closestDist) {
                closestDist = dist;
                closest = gob;
            }
        }
        return closest;
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
