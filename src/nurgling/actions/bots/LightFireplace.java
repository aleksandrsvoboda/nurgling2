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

public class LightFireplace implements Action {

    private final Gob fireplace;
    private static final int FIRE_FLAG = 4;
    private static final Coord TORCH_SIZE = new Coord(1, 1);

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

        // Priority 1: Lit torch in equipment
        gui.msg("No embers, checking for equipped lit torch");
        if (tryEquippedLitTorch(gui))
            return Results.SUCCESS();
        if (isFireLit())
            return Results.SUCCESS();

        // Priority 2: Unlit torch (equipment or inventory) + lit brazier
        gui.msg("No equipped lit torch, checking for torch and nearby brazier");
        if (tryTorchWithBrazier(gui))
            return Results.SUCCESS();
        if (isFireLit())
            return Results.SUCCESS();

        // Priority 3: Lit candelabrum
        gui.msg("No torch with brazier found, looking for lit candelabrum");
        if (tryLitCandelabrum(gui))
            return Results.SUCCESS();
        if (isFireLit())
            return Results.SUCCESS();

        // Priority 4: Lit torch on torchpost
        gui.msg("Lit candelabrum not found, looking for lit torch on torchpost");
        if (tryLitTorchOnTorchpost(gui))
            return Results.SUCCESS();
        if (isFireLit())
            return Results.SUCCESS();

        // Priority 5: Unlit torch on torchpost + lit brazier
        gui.msg("Lit torch on torchpost not found, looking for unlit torch on torchpost and brazier");
        if (tryUnlitTorchpostWithBrazier(gui))
            return Results.SUCCESS();
        if (isFireLit())
            return Results.SUCCESS();

        // Priority 6: Sticks (branches)
        gui.msg("No torch or brazier found, using branches");
        return new LightFire(fireplace).run(gui);
    }

    private boolean isFireLit() {
        return (fireplace.ngob.getModelAttribute() & FIRE_FLAG) != 0;
    }

    // --- Priority 1: Lit torch in equipment ---

    private boolean tryEquippedLitTorch(NGameUI gui) throws InterruptedException {
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

        new PathFinder(fireplace).run(gui);
        NUtils.activateItem(fireplace);
        waitForProgress(gui);

        if (gui.vhand != null) {
            NUtils.getEquipment().wdgmsg("drop", sourceSlot);
            NUtils.getUI().core.addTask(new WaitFreeHand());
        }
        return true;
    }

    // --- Priority 2: Unlit torch (equipment or inventory) + lit brazier ---

    private static final int SOURCE_EQUIPMENT = 0;
    private static final int SOURCE_INVENTORY = 1;

    private boolean tryTorchWithBrazier(NGameUI gui) throws InterruptedException {
        // Find lit brazier first
        Gob litBrazier = findLitBrazier();
        if (litBrazier == null)
            return false;

        // Find unlit torch in equipment
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

        // If not in equipment, check inventory
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

        // Take torch to hand
        NUtils.takeItemToHand(torch);
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
        return isFireLit();
    }

    // --- Priority 3: Lit candelabrum ---

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

    // --- Priority 4: Lit torch on torchpost ---

    private boolean tryLitTorchOnTorchpost(NGameUI gui) throws InterruptedException {
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

    // --- Priority 5: Unlit torch on torchpost + lit brazier ---

    private boolean tryUnlitTorchpostWithBrazier(NGameUI gui) throws InterruptedException {
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

        // Extinguish torch and place back on torchpost
        if (gui.vhand != null) {
            extinguishAndReturnToTorchpost(gui, unlitTorchpost);
        }
        return isFireLit();
    }

    // --- Extinguish helpers ---

    private void extinguishAndReturnToEquip(NGameUI gui, int equipSlot) throws InterruptedException {
        if (gui.vhand == null)
            return;

        if (gui.getInventory().getNumberFreeCoord(TORCH_SIZE) > 0) {
            // Drop to inventory to extinguish
            NUtils.dropToInv();
            NUtils.getUI().core.addTask(new WaitFreeHand());
            // Pick up extinguished torch and re-equip
            WItem torch = gui.getInventory().getItem("Torch");
            if (torch != null) {
                NUtils.takeItemToHand(torch);
                NUtils.getUI().core.addTask(new WaitItemInHand());
                NUtils.getEquipment().wdgmsg("drop", equipSlot);
                NUtils.getUI().core.addTask(new WaitFreeHand());
            }
        } else {
            // No inventory space, return lit
            NUtils.getEquipment().wdgmsg("drop", equipSlot);
            NUtils.getUI().core.addTask(new WaitFreeHand());
        }
    }

    private void extinguishAndReturnToTorchpost(NGameUI gui, Gob torchpost) throws InterruptedException {
        if (gui.vhand == null)
            return;

        if (gui.getInventory().getNumberFreeCoord(TORCH_SIZE) > 0) {
            // Drop to inventory to extinguish
            NUtils.dropToInv();
            NUtils.getUI().core.addTask(new WaitFreeHand());
            // Pick up extinguished torch and return to torchpost
            WItem torch = gui.getInventory().getItem("Torch");
            if (torch != null) {
                NUtils.takeItemToHand(torch);
                NUtils.getUI().core.addTask(new WaitItemInHand());
                new PathFinder(torchpost).run(gui);
                NUtils.activateItem(torchpost);
                NUtils.getUI().core.addTask(new WaitFreeHand());
            }
        } else {
            // No inventory space, return lit
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
