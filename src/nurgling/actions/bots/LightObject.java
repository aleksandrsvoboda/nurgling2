package nurgling.actions.bots;

import haven.*;
import haven.res.gfx.fx.eq.Equed;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.NEquipory;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

/**
 * Unified workstation lighter.
 *
 * <p>Lights one or more workstations (cauldron, fireplace, oven, kiln, smelter, ...) by trying a
 * prioritized list of fuel sources. Each batchable source is expressed as three phases so a whole
 * batch of gobs shares a single setup/teardown of the lighting implement:
 * <ul>
 *   <li><b>acquire</b> (once) — take the torch to hand / lift the candelabrum;</li>
 *   <li><b>apply</b> (per gob) — carry the implement to each target and ignite it;</li>
 *   <li><b>release</b> (once) — return the torch to its post/slot, place the candelabrum back.</li>
 * </ul>
 *
 * <p>Priority order: embers → equipped lit torch → unlit torch + brazier → in-view lit candelabrum →
 * torchpost (lit) → torchpost (unlit) + brazier → branches → (bot path only, last resort) fetch a lit
 * candelabrum from its designated spec area. The area fetch is last because it can be a long round-trip;
 * every nearby option is preferred over it.
 *
 * <p>Behavior contract:
 * <ul>
 *   <li><b>Fail-closed</b> — if any requested gob cannot be lit the whole run fails; if a gob lacks
 *       fuel the run fails <i>up front</i>, before any implement is acquired or firebrand crafted.</li>
 *   <li><b>Branches never batch</b> — a firebrand is single-use, so the branch tier re-crafts per gob.</li>
 *   <li><b>Torch stays equipped</b> — a lit torch is only lit while equipped/in-hand; the apply loop
 *       never routes it through inventory (that extinguishes it).</li>
 *   <li><b>Context menu unchanged</b> — the single-{@link Gob} constructor keeps today's behavior and
 *       does <i>not</i> fetch a candelabrum from its designated area; only the list constructor (used
 *       by bots) enables the area fetch, matching the old {@code LightGob} capability.</li>
 * </ul>
 */
public class LightObject implements Action {

    private final ArrayList<Gob> targets;
    private final boolean allowCandelabrumAreaFetch;

    private static final Coord TORCH_SIZE = new Coord(1, 1);

    private static final int SOURCE_EQUIPMENT = 0;
    private static final int SOURCE_INVENTORY = 1;
    private static final int SOURCE_BELT = 2;

    /** Single-target constructor used by the right-click context menu. Preserves legacy behavior:
     *  does not navigate to a candelabrum area. */
    public LightObject(Gob target) {
        this.targets = new ArrayList<>();
        this.targets.add(target);
        this.allowCandelabrumAreaFetch = false;
    }

    /** Batch constructor used by bots (via {@code LightGob}). Enables candelabrum-area fetch so it is a
     *  strict superset of the old {@code LightGob} behavior. */
    public LightObject(ArrayList<Gob> targets) {
        this.targets = new ArrayList<>(targets);
        this.allowCandelabrumAreaFetch = true;
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
        if (targets.isEmpty())
            return Results.SUCCESS();

        // --- Precheck all targets up front (fail-closed on fuel) ---
        ArrayList<Gob> remaining = new ArrayList<>();
        for (Gob t : targets) {
            if (t == null || t.ngob == null || t.ngob.name == null)
                return Results.ERROR("Cannot determine object type");
            LightConfig config = getConfig(t.ngob.name);
            if (config == null)
                return Results.ERROR("Unsupported object type: " + t.ngob.name);
            if (isLit(t, config))
                continue; // already lit, nothing to do
            if (config.fuelFlag != 0 && (t.ngob.getModelAttribute() & config.fuelFlag) == 0) {
                // No reason to acquire an implement or craft a firebrand for a gob that can't be lit.
                gui.error(config.displayName + " has no fuel");
                return Results.FAIL();
            }
            remaining.add(t);
        }
        if (remaining.isEmpty())
            return Results.SUCCESS();

        // Priority 0: Embers (per-gob self-light, no shared implement).
        lightEmbers(gui, remaining);
        if (remaining.isEmpty()) return Results.SUCCESS();

        // Priority 1: Lit torch in equipment.
        tryEquippedLitTorch(gui, remaining);
        if (remaining.isEmpty()) return Results.SUCCESS();

        // Priority 2: Unlit torch (equipment/inventory/belt) + nearby lit fire source.
        tryTorchWithBrazier(gui, remaining);
        if (remaining.isEmpty()) return Results.SUCCESS();

        // Priority 3: Lit candelabrum already in view (cheap — no area travel).
        tryLitCandelabrumInView(gui, remaining);
        if (remaining.isEmpty()) return Results.SUCCESS();

        // Priority 4: Lit torch on a torchpost.
        tryLitTorchOnTorchpost(gui, remaining);
        if (remaining.isEmpty()) return Results.SUCCESS();

        // Priority 5: Unlit torch on a torchpost + nearby lit fire source.
        tryUnlitTorchpostWithBrazier(gui, remaining);
        if (remaining.isEmpty()) return Results.SUCCESS();

        // Priority 6: Branches — always per-gob, never batched (firebrand is single-use).
        lightWithBranches(gui, remaining);
        if (remaining.isEmpty()) return Results.SUCCESS();

        // Last resort (bot/batch path only): fetch a lit candelabrum from its designated spec area.
        // This can be a long round-trip, so it runs only after every nearby option has been exhausted.
        if (allowCandelabrumAreaFetch)
            tryCandelabrumFromArea(gui, remaining);

        return remaining.isEmpty() ? Results.SUCCESS() : Results.FAIL();
    }

    private boolean isLit(Gob gob, LightConfig config) {
        return (gob.ngob.getModelAttribute() & config.fireFlag) != 0;
    }

    /** Order a set of targets nearest-first from the player's current position (snapshot). */
    private ArrayList<Gob> orderByProximity(ArrayList<Gob> gobs) {
        ArrayList<Gob> ordered = new ArrayList<>();
        for (Gob g : gobs)
            if (g != null && g.ngob != null)
                ordered.add(g);
        Coord2d p = NUtils.player().rc;
        ordered.sort((a, b) -> Double.compare(a.rc.dist(p), b.rc.dist(p)));
        return ordered;
    }

    // --- Priority 0: Embers ---

    private void lightEmbers(NGameUI gui, ArrayList<Gob> remaining) throws InterruptedException {
        for (Gob t : orderByProximity(remaining)) {
            LightConfig config = getConfig(t.ngob.name);
            if (config == null || config.embersAttr == -1)
                continue;
            if (t.ngob.getModelAttribute() != config.embersAttr)
                continue;
            new PathFinder(t).run(gui);
            Results result = new SelectFlowerAction("Light My Fire", t).run(gui);
            if (result.IsSuccess())
                NUtils.getUI().core.addTask(new WaitGobModelAttr(t, config.fireFlag));
            if (isLit(t, config))
                remaining.remove(t);
        }
    }

    /** Apply a lit torch (already in hand) to each remaining target, keeping the torch equipped. */
    private void applyTorchToTargets(NGameUI gui, ArrayList<Gob> remaining) throws InterruptedException {
        for (Gob t : orderByProximity(remaining)) {
            if (gui.vhand == null)
                break; // torch left our hand (extinguished / lost) — stop and release
            LightConfig config = getConfig(t.ngob.name);
            if (config == null)
                continue;
            if (isLit(t, config)) {
                remaining.remove(t);
                continue;
            }
            new PathFinder(t).run(gui);
            NUtils.activateItem(t);
            waitForProgress(gui);
            if (isLit(t, config))
                remaining.remove(t);
        }
    }

    // --- Priority 1: Lit torch in equipment ---

    private void tryEquippedLitTorch(NGameUI gui, ArrayList<Gob> remaining) throws InterruptedException {
        NEquipory equip = NUtils.getEquipment();
        if (equip == null)
            return;

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
            return;

        // Acquire (once)
        NUtils.takeItemToHand(torch);
        NUtils.getUI().core.addTask(new WaitItemInHand());

        // Apply (per gob)
        applyTorchToTargets(gui, remaining);

        // Release (once) — back to equip slot; the torch stays lit there.
        if (gui.vhand != null) {
            NUtils.getEquipment().wdgmsg("drop", sourceSlot);
            NUtils.getUI().core.addTask(new WaitFreeHand());
        }
    }

    // --- Priority 2: Unlit torch (equipment or inventory) + lit brazier ---

    private void tryTorchWithBrazier(NGameUI gui, ArrayList<Gob> remaining) throws InterruptedException {
        Gob litBrazier = findLitFireSource();
        if (litBrazier == null)
            return;

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

        if (torch == null) {
            NInventory beltInv = getBeltInventory();
            if (beltInv != null) {
                ArrayList<WItem> beltTorches = beltInv.getItems(new NAlias("Torch"));
                for (WItem t : beltTorches) {
                    Resource res = t.item.getres();
                    if (res != null && res.name.endsWith("torch") && !res.name.endsWith("torch-l")) {
                        torch = t;
                        torchSource = SOURCE_BELT;
                        break;
                    }
                }
            }
        }

        if (torch == null)
            return;

        // Acquire (once): take the torch and light it on the brazier.
        NUtils.takeItemToHand(torch);
        NUtils.getUI().core.addTask(new WaitItemInHand());

        new PathFinder(litBrazier).run(gui);
        NUtils.activateItem(litBrazier);
        waitForProgress(gui);

        // Apply (per gob)
        applyTorchToTargets(gui, remaining);

        // Release (once): extinguish and return the torch where it came from.
        if (gui.vhand != null) {
            if (torchSource == SOURCE_EQUIPMENT) {
                extinguishAndReturnToEquip(gui, equipSlot);
            } else if (torchSource == SOURCE_BELT) {
                extinguishAndReturnToBelt(gui);
            } else {
                // From inventory - dropping to inv extinguishes it
                NUtils.dropToInv();
                NUtils.getUI().core.addTask(new WaitFreeHand());
            }
        }
    }

    // --- Priority 3: Lit candelabrum already in view (no area travel) ---

    private void tryLitCandelabrumInView(NGameUI gui, ArrayList<Gob> remaining) throws InterruptedException {
        Gob litCandelabrum = findLitCandelabrumInView();
        if (litCandelabrum == null)
            return;
        // Lift once, light all, place back once.
        Coord2d originalPos = new Coord2d(litCandelabrum.rc.x, litCandelabrum.rc.y);
        new LiftObject(litCandelabrum).run(gui);
        applyCandelabrumToTargets(gui, remaining);
        new PlaceObject(litCandelabrum, originalPos, 0).run(gui);
    }

    // --- Last resort: fetch a lit candelabrum from its designated spec area (bot/batch path only) ---

    private void tryCandelabrumFromArea(NGameUI gui, ArrayList<Gob> remaining) throws InterruptedException {
        NContext context = new NContext(gui);
        String lastposid = context.createPlayerLastPos();
        NArea candArea = context.goToArea(Specialisation.SpecName.candelabrum);
        if (candArea == null)
            return;

        context.navigateToAreaIfNeeded(Specialisation.SpecName.candelabrum.toString());
        Gob areaCandelabrum = findLitCandelabrumInView();
        if (areaCandelabrum == null) {
            context.navigateToAreaIfNeeded(lastposid);
            return;
        }

        new LiftObject(areaCandelabrum).run(gui);
        context.navigateToAreaIfNeeded(lastposid);
        applyCandelabrumToTargets(gui, remaining);

        // Return the candelabrum to its area, then go back to where we were working.
        context.navigateToAreaIfNeeded(Specialisation.SpecName.candelabrum.toString());
        Gob lifted = Finder.findLiftedbyPlayer();
        if (lifted != null) {
            Coord2d pos = Finder.getFreePlace(
                    context.goToAreaById(Specialisation.SpecName.candelabrum.toString()).getRCArea(),
                    lifted.ngob.hitBox, 0);
            new PlaceObject(areaCandelabrum, pos, 0).run(gui);
        }
        context.navigateToAreaIfNeeded(lastposid);
    }

    private Gob findLitCandelabrumInView() {
        for (Gob c : Finder.findGobs(new NAlias("gfx/terobjs/candelabrum"))) {
            if (c != null && c.ngob.getModelAttribute() == 3)
                return c;
        }
        return null;
    }

    /** Apply a carried (lifted) lit candelabrum to each remaining target. */
    private void applyCandelabrumToTargets(NGameUI gui, ArrayList<Gob> remaining) throws InterruptedException {
        for (Gob t : orderByProximity(remaining)) {
            LightConfig config = getConfig(t.ngob.name);
            if (config == null)
                continue;
            if (isLit(t, config)) {
                remaining.remove(t);
                continue;
            }
            PathFinder pf = new PathFinder(t);
            pf.isHardMode = true;
            pf.run(gui);
            NUtils.activateGob(t);
            NUtils.getUI().core.addTask(new WaitGobModelAttr(t, config.fireFlag));
            remaining.remove(t);
        }
    }

    // --- Priority 4: Lit torch on torchpost ---

    private void tryLitTorchOnTorchpost(NGameUI gui, ArrayList<Gob> remaining) throws InterruptedException {
        Gob litTorchpost = null;
        for (Gob tp : Finder.findGobs(new NAlias("torchpost"))) {
            TorchpostState state = getTorchpostState(tp);
            if (state.hasTorch && state.isLit) {
                litTorchpost = tp;
                break;
            }
        }
        if (litTorchpost == null)
            return;

        // Acquire (once)
        new PathFinder(litTorchpost).run(gui);
        Results flowerResult = new SelectFlowerAction("Take torch", litTorchpost).run(gui);
        if (!flowerResult.IsSuccess())
            return;
        NUtils.getUI().core.addTask(new WaitItemInHand());

        // Apply (per gob)
        applyTorchToTargets(gui, remaining);

        // Release (once): return the torch to the post.
        if (gui.vhand != null) {
            new PathFinder(litTorchpost).run(gui);
            NUtils.activateItem(litTorchpost);
            NUtils.getUI().core.addTask(new WaitFreeHand());
        }
    }

    // --- Priority 5: Unlit torch on torchpost + lit brazier ---

    private void tryUnlitTorchpostWithBrazier(NGameUI gui, ArrayList<Gob> remaining) throws InterruptedException {
        Gob unlitTorchpost = null;
        for (Gob tp : Finder.findGobs(new NAlias("torchpost"))) {
            TorchpostState state = getTorchpostState(tp);
            if (state.hasTorch && !state.isLit) {
                unlitTorchpost = tp;
                break;
            }
        }
        if (unlitTorchpost == null)
            return;

        Gob litBrazier = findLitFireSource();
        if (litBrazier == null)
            return;

        // Acquire (once): take the unlit torch and light it on the brazier.
        new PathFinder(unlitTorchpost).run(gui);
        NUtils.rclickGob(unlitTorchpost);
        NUtils.getUI().core.addTask(new WaitItemInHand());

        new PathFinder(litBrazier).run(gui);
        NUtils.activateItem(litBrazier);
        waitForProgress(gui);

        // Apply (per gob)
        applyTorchToTargets(gui, remaining);

        // Release (once): extinguish and put the torch back on the post.
        if (gui.vhand != null) {
            extinguishAndReturnToTorchpost(gui, unlitTorchpost);
        }
    }

    // --- Priority 6: Branches (never batched — re-craft a firebrand per gob) ---

    private void lightWithBranches(NGameUI gui, ArrayList<Gob> remaining) throws InterruptedException {
        for (Gob t : new ArrayList<>(remaining)) {
            LightConfig config = getConfig(t.ngob.name);
            if (config == null)
                continue;
            if (isLit(t, config)) {
                remaining.remove(t);
                continue;
            }
            Results lightResult = new LightFire(t).run(gui);
            if (!lightResult.IsSuccess()) {
                // Leave unlit for the last-resort candelabrum-area tier / final fail-closed check.
                gui.error("Failed to light fire with branches on: " + t.ngob.name);
                continue;
            }
            Gob updated = Finder.findGob(t.id);
            if (updated != null && isLit(updated, config))
                remaining.remove(t);
        }
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

    private void extinguishAndReturnToBelt(NGameUI gui) throws InterruptedException {
        if (gui.vhand == null)
            return;
        NUtils.transferToBelt();
        NUtils.getUI().core.addTask(new WaitFreeHand());
    }

    // --- Shared helpers ---

    private NInventory getBeltInventory() {
        NEquipory equip = NUtils.getEquipment();
        if (equip == null)
            return null;
        WItem beltSlot = equip.quickslots[NEquipory.Slots.BELT.idx];
        if (beltSlot == null || !(beltSlot.item.contents instanceof NInventory))
            return null;
        return (NInventory) beltSlot.item.contents;
    }

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

    private boolean isTarget(long id) {
        for (Gob t : targets)
            if (t != null && t.id == id)
                return true;
        return false;
    }

    private Gob findLitFireSource() {
        ArrayList<Gob> sources = Finder.findGobs(FIRE_SOURCE_ALIAS);
        Gob closest = null;
        double closestDist = Double.MAX_VALUE;
        Coord2d playerPos = NUtils.player().rc;

        for (Gob gob : sources) {
            if (isTarget(gob.id))
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
