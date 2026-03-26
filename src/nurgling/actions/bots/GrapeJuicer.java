package nurgling.actions.bots;

import haven.*;
import haven.VMeter;
import haven.LayerMeter;
import nurgling.*;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.*;
import nurgling.tools.*;
import nurgling.tools.Container;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

import static haven.OCache.posres;

public class GrapeJuicer implements Action {

    private static final String PRESS_RES = "gfx/terobjs/winepress";
    private static final String PRESS_CAP = "Extraction Press";
    private static final String GRAPE_ITEM = "Grapes";
    private static final int PRESS_SLOTS = 25; // 5x5 grid
    private static final double JUICE_MAX = 10.0;
    private static final int PRESSING_MARKER = 7;
    private static final Coord GRAPE_SIZE = new Coord(1, 1);

    private NArea lastPressArea;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);

        // Validate required areas
        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(new NArea.Specialisation(Specialisation.SpecName.extractionPress.toString()));
        req.add(new NArea.Specialisation(Specialisation.SpecName.barrel.toString(), "Grape Juice"));

        if (!new Validator(req, new ArrayList<>()).run(gui).IsSuccess()) {
            return Results.ERROR("Required areas not found!");
        }

        // Check grape input area exists
        NArea grapeArea = NContext.findIn(GRAPE_ITEM);
        if (grapeArea == null) {
            grapeArea = NContext.findInGlobal(GRAPE_ITEM);
        }
        if (grapeArea == null) {
            return Results.ERROR("Input area for Grapes not found!");
        }

        // Verify press exists by navigating to its area
        context.getSpecArea(Specialisation.SpecName.extractionPress);
        context.addInItem(GRAPE_ITEM, null);
        Gob press = navigateToPress(context);
        if (press == null) {
            return Results.ERROR("Extraction Press not found in area!");
        }

        // Initialize press: empty leftover juice, remove seeds, count existing grapes
        int existingGrapes = initializePress(gui, context);
        if (existingGrapes < 0) {
            return Results.ERROR("Failed to initialize press!");
        }

        // Main loop
        boolean firstIteration = true;
        while (true) {
            // Phase 1: Fill the press with grapes
            int slotsToFill = firstIteration ? PRESS_SLOTS - existingGrapes : PRESS_SLOTS;
            firstIteration = false;
            int grapesLoaded = fillPress(gui, context, slotsToFill);
            if (grapesLoaded == 0 && slotsToFill > 0) {
                NUtils.getGameUI().msg("No more grapes available. Stopping.");
                return Results.SUCCESS();
            }

            // Phase 2: Press the grapes
            pressGrapes(gui);

            // Phase 3: Clean seeds and read juice level
            double juiceLevel = cleanSeedsAndReadJuice(gui, context);

            // Phase 4: If juice is full, empty into barrel
            if (juiceLevel >= JUICE_MAX) {
                if (!emptyJuiceIntoBarrel(gui, context)) {
                    NUtils.getGameUI().msg("No more empty barrels. Stopping.");
                    return Results.SUCCESS();
                }
            }
        }
    }

    /**
     * Navigate to the extraction press area and return a fresh gob reference.
     * Also caches the area in lastPressArea for reuse.
     */
    private Gob navigateToPress(NContext context) throws InterruptedException {
        lastPressArea = context.getSpecArea(Specialisation.SpecName.extractionPress);
        if (lastPressArea == null) return null;
        return Finder.findGob(lastPressArea, new NAlias(PRESS_RES));
    }

    /**
     * Check the press for leftover state and clean it up:
     * 1. If any juice exists, empty it into a barrel
     * 2. Remove any seeds (non-grape items)
     * 3. Count existing grapes
     * @return number of grapes already in the press, or -1 on error
     */
    private int initializePress(NGameUI gui, NContext context) throws InterruptedException {
        Gob press = Finder.findGob(lastPressArea, new NAlias(PRESS_RES));;
        if (press == null) return -1;

        new PathFinder(press).run(gui);
        openPressWindow(gui, press);

        // 1. If any juice, empty into barrel first
        double juiceLevel = readJuiceLevel(gui);
        if (juiceLevel > 0) {
            NUtils.getGameUI().msg("Press has " + juiceLevel + "L of juice. Emptying into barrel...");
            closePressWindow(gui);
            if (!emptyJuiceIntoBarrel(gui, context)) {
                NUtils.getGameUI().msg("No empty barrels to drain leftover juice. Stopping.");
                return -1;
            }
            // Re-navigate and reopen after barrel trip
            press = navigateToPress(context);
            if (press == null) return -1;
            new PathFinder(press).run(gui);
            openPressWindow(gui, press);
        }

        // 2. Remove seeds (non-grape items) from press
        // Snapshot inventory so we only drop seeds, not pre-existing items
        ArrayList<WItem> originalItems = gui.getInventory().getItems();
        removeSeedsFromPress(gui, context, originalItems);

        // Re-navigate and reopen in case removeSeedsFromPress closed the window
        if (gui.getWindow(PRESS_CAP) == null) {
            press = navigateToPress(context);
            if (press == null) return -1;
            new PathFinder(press).run(gui);
            openPressWindow(gui, press);
        }

        // 3. Count existing grapes
        int existingGrapes = 0;
        NInventory pressInv = gui.getInventory(PRESS_CAP);
        if (pressInv != null) {
            existingGrapes = pressInv.getItems(new NAlias(GRAPE_ITEM)).size();
        }
        if (existingGrapes > 0) {
            NUtils.getGameUI().msg("Press already has " + existingGrapes + " grapes.");
        }

        closePressWindow(gui);
        return existingGrapes;
    }

    /**
     * Remove all non-grape items (seeds) from the press inventory and drop them.
     * Handles multiple batches if player inventory is too small.
     * Only drops items that weren't in the player inventory before (preserves pre-existing items).
     */
    private void removeSeedsFromPress(NGameUI gui, NContext context, ArrayList<WItem> originalItems) throws InterruptedException {
        while (true) {
            NInventory pressInv = gui.getInventory(PRESS_CAP);
            if (pressInv == null) break;

            // Find non-grape items (seeds)
            ArrayList<WItem> allItems = pressInv.getItems();
            ArrayList<WItem> seeds = new ArrayList<>();
            for (WItem item : allItems) {
                String name = ((NGItem) item.item).name();
                if (name != null && !NParser.checkName(name, new NAlias(GRAPE_ITEM))) {
                    seeds.add(item);
                }
            }
            if (seeds.isEmpty()) break;

            // Check inventory space
            int freeSpace = gui.getInventory().getNumberFreeCoord(GRAPE_SIZE);
            if (freeSpace <= 0) {
                closePressWindow(gui);
                dropNonOriginalItems(gui, originalItems);
                // Reopen press
                Gob press = navigateToPress(context);
                if (press == null) break;
                openPressWindow(gui, press);
                continue;
            }

            // Transfer seeds to player inventory
            int toTransfer = Math.min(seeds.size(), freeSpace);
            for (int i = 0; i < toTransfer; i++) {
                seeds.get(i).item.wdgmsg("transfer", Coord.z);
                NUtils.addTask(new ISRemoved(seeds.get(i).item.wdgid()));
            }
        }

        // Drop any seeds we picked up
        closePressWindow(gui);
        dropNonOriginalItems(gui, originalItems);
    }

    /**
     * Fill the extraction press with grapes. Handles multiple trips if player
     * inventory is smaller than 25.
     * @return total number of grapes loaded into the press, 0 if no grapes available
     */
    private int fillPress(NGameUI gui, NContext context, int slotsToFill) throws InterruptedException {
        int totalLoaded = 0;
        int remaining = slotsToFill;

        while (remaining > 0) {
            // Check how much inventory space we have
            int freeSpace = gui.getInventory().getNumberFreeCoord(GRAPE_SIZE);
            if (freeSpace <= 0) {
                break;
            }

            int toTake = Math.min(freeSpace, remaining);

            // Navigate to grape area and take grapes (TakeItems2 handles navigation)
            new TakeItems2(context, GRAPE_ITEM, toTake).run(gui);

            // Check how many we actually got
            ArrayList<WItem> grapes = gui.getInventory().getItems(new NAlias(GRAPE_ITEM));
            if (grapes.isEmpty()) {
                break; // No more grapes available
            }
            int taken = grapes.size();

            // Navigate to press area (we might be far away at the grape area)
            Gob press = navigateToPress(context);
            if (press == null) {
                return 0;
            }

            // Use TransferToContainer to handle pathfinding, opening, and transferring
            Container pressCont = new Container(press, PRESS_CAP, lastPressArea);
            pressCont.initattr(Container.Space.class);
            new TransferToContainer(pressCont, new NAlias(GRAPE_ITEM)).run(gui);

            totalLoaded += taken;
            remaining -= taken;
        }

        return totalLoaded;
    }

    /**
     * Click the Press button and wait for pressing to complete.
     */
    private void pressGrapes(NGameUI gui) throws InterruptedException {
        Gob press = Finder.findGob(lastPressArea, new NAlias(PRESS_RES));

        // Open press window if not already open
        if (gui.getWindow(PRESS_CAP) == null) {
            new PathFinder(press).run(gui);
            openPressWindow(gui, press);
        }

        // Find and click the "Press" button
        clickPressButton(gui);

        // We are standing right at the press, so the gob stays loaded.
        // Cache the ID for the short-lived polling tasks.
        long pressId = press.id;

        // Wait for pressing to start (marker becomes PRESSING_MARKER)
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                Gob p = Finder.findGob(pressId);
                return p != null && p.ngob.getModelAttribute() == PRESSING_MARKER;
            }
        });

        // Wait for pressing to finish (marker is no longer PRESSING_MARKER)
        NUtils.addTask(new NTask() {
            {
                this.infinite = true;
            }
            @Override
            public boolean check() {
                Gob p = Finder.findGob(pressId);
                return p != null && p.ngob.getModelAttribute() != PRESSING_MARKER;
            }
        });
    }

    /**
     * Take all seeds from the press, drop them on the ground, and read juice level.
     * Handles multiple cycles if player inventory is smaller than seed count.
     * Only drops seed items, preserving any pre-existing inventory contents.
     * @return current juice level from the press meter
     */
    private double cleanSeedsAndReadJuice(NGameUI gui, NContext context) throws InterruptedException {
        // Navigate to press area (should still be there after pressing, but be safe)
        Gob press = navigateToPress(context);
        if (press == null) return 0;

        // Walk to press and open window
        new PathFinder(press).run(gui);
        openPressWindow(gui, press);

        // Read juice level first
        double juiceLevel = readJuiceLevel(gui);

        // Snapshot inventory before transferring seeds
        ArrayList<WItem> originalItems = gui.getInventory().getItems();

        // Take seeds out in batches (player inventory might be smaller than 25)
        NInventory pressInv = gui.getInventory(PRESS_CAP);
        while (pressInv != null) {
            ArrayList<WItem> seeds = pressInv.getItems();
            if (seeds.isEmpty()) {
                break; // All seeds removed
            }

            // Check how much space we have in player inventory
            int freeSpace = gui.getInventory().getNumberFreeCoord(GRAPE_SIZE);
            if (freeSpace <= 0) {
                // Close window, drop seeds we have, reopen
                closePressWindow(gui);
                dropNonOriginalItems(gui, originalItems);

                // We're still at the press area (just dropped seeds on the ground nearby)
                press = Finder.findGob(press.id);
                if (press == null) {
                    press = navigateToPress(context);
                }
                if (press == null) break;
                openPressWindow(gui, press);
                pressInv = gui.getInventory(PRESS_CAP);
                continue;
            }

            // Transfer seeds from press to player inventory one at a time
            int toTransfer = Math.min(seeds.size(), freeSpace);
            for (int i = 0; i < toTransfer; i++) {
                seeds.get(i).item.wdgmsg("transfer", Coord.z);
                NUtils.addTask(new ISRemoved(seeds.get(i).item.wdgid()));
            }

            pressInv = gui.getInventory(PRESS_CAP);
        }

        // Close window
        closePressWindow(gui);

        // Drop only seeds (items not in original snapshot)
        dropNonOriginalItems(gui, originalItems);

        return juiceLevel;
    }

    /**
     * Find a suitable barrel (prefer partially-filled grape juice barrels, then empty),
     * lift it, bring to press, transfer juice, return barrel.
     * @return true if successful, false if no suitable barrels available
     */
    private boolean emptyJuiceIntoBarrel(NGameUI gui, NContext context) throws InterruptedException {
        // Make sure press window is closed before barrel operations
        closePressWindow(gui);

        // Navigate to barrel area (may be far from press)
        NArea barrelArea = context.getSpecArea(Specialisation.SpecName.barrel, "Grape Juice");
        if (barrelArea == null) {
            return false;
        }

        // Find a suitable barrel
        Gob targetBarrel = findSuitableBarrel(gui, barrelArea);
        if (targetBarrel == null) {
            return false;
        }

        // Save barrel original position
        Coord2d originalPos = targetBarrel.rc;

        // Lift the barrel
        new LiftObject(targetBarrel).run(gui);

        // Navigate to press area (may be far from barrel area)
        Gob press = navigateToPress(context);
        if (press == null) {
            // Put barrel back if press not found
            Gob lifted = Finder.findLiftedbyPlayer();
            if (lifted != null) {
                NUtils.navigateToArea(barrelArea);
                new PlaceObject(lifted, originalPos, 0).run(gui);
            }
            return false;
        }

        // Walk to the press
        new PathFinder(press).run(gui);

        // Right-click the press while carrying barrel to transfer juice.
        // Game mechanic: if press is NOT full and barrel has grape juice,
        // the first right-click fills the press FROM the barrel (wrong direction).
        // If press IS full, right-click drains press INTO barrel.
        // So we may need two right-clicks: first fills press, second drains it.
        long pressId = press.id;
        NUtils.activateGob(press);

        // Wait for press marker to reach 0 or 4 (drained) - NOT infinite
        // If juice went wrong way (barrel->press), this will timeout
        NTask drainWait = new NTask() {
            {
                this.infinite = false;
            }
            @Override
            public boolean check() {
                Gob p = Finder.findGob(pressId);
                if (p == null) return true;
                long marker = p.ngob.getModelAttribute();
                return marker == 0 || marker == 4;
            }
        };
        NUtils.addTask(drainWait);

        // Check if press actually drained
        Gob pressAfter = Finder.findGob(pressId);
        if (pressAfter != null) {
            long marker = pressAfter.ngob.getModelAttribute();
            if (marker != 0 && marker != 4) {
                // Juice went wrong way (barrel filled press instead of draining).
                // Now press should be full, so right-clicking again will drain it.
                NUtils.activateGob(pressAfter);
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        Gob p = Finder.findGob(pressId);
                        if (p == null) return true;
                        long m = p.ngob.getModelAttribute();
                        return m == 0 || m == 4;
                    }
                });
            }
        }

        // Navigate back to barrel area (may be far from press) and place barrel
        NUtils.navigateToArea(barrelArea);
        Gob lifted = Finder.findLiftedbyPlayer();
        if (lifted != null) {
            new PlaceObject(lifted, originalPos, 0).run(gui);
        }

        return true;
    }

    /**
     * Find a suitable barrel for grape juice. Priority:
     * 1. Barrels that already contain grape juice and have room (<= 90L, so a full 10L press cycle fits)
     * 2. Empty barrels
     * Opens each candidate barrel to check content level.
     * @return suitable barrel gob, or null if none available
     */
    private Gob findSuitableBarrel(NGameUI gui, NArea barrelArea) throws InterruptedException {
        ArrayList<Gob> barrels = Finder.findGobs(barrelArea, new NAlias("barrel"));

        // First pass: check barrels that already have content (grape juice)
        for (Gob barrel : barrels) {
            if (NUtils.barrelHasContent(barrel)) {
                // Open barrel to check content level
                new PathFinder(barrel).run(gui);
                new OpenTargetContainer("Barrel", barrel).run(gui);

                // Wait for TipLabel info to load (FindBarrel only waits for RelCont)
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        return gui.getBarrelContent() >= 0;
                    }
                });
                double content = gui.getBarrelContent();

                Window bwnd = gui.getWindow("Barrel");
                if (bwnd != null) {
                    new CloseTargetWindow(bwnd).run(gui);
                }

                if (content <= 90) {
                    return barrel; // Has room for a full 10L press cycle
                }
                // Too full (> 90L), try next barrel
            }
        }

        // Second pass: find empty barrel
        for (Gob barrel : barrels) {
            if (!NUtils.barrelHasContent(barrel)) {
                return barrel;
            }
        }

        return null; // No suitable barrel found
    }

    /**
     * Open the extraction press window by right-clicking it.
     */
    private void openPressWindow(NGameUI gui, Gob press) throws InterruptedException {
        if (gui.getWindow(PRESS_CAP) != null) {
            return; // Already open
        }
        gui.map.wdgmsg("click", Coord.z, press.rc.floor(posres), 3, 0, 0,
                (int) press.id, press.rc.floor(posres), 0, -1);
        NUtils.addTask(new WaitWindow(PRESS_CAP));
    }

    /**
     * Close the extraction press window if it's open.
     */
    private void closePressWindow(NGameUI gui) throws InterruptedException {
        Window wnd = gui.getWindow(PRESS_CAP);
        if (wnd != null) {
            new CloseTargetWindow(wnd).run(gui);
        }
    }

    /**
     * Find and click the "Press" button in the extraction press window.
     */
    private void clickPressButton(NGameUI gui) {
        Window wnd = gui.getWindow(PRESS_CAP);
        if (wnd == null) return;

        for (Widget child = wnd.child; child != null; child = child.next) {
            if (child instanceof Button) {
                Button b = (Button) child;
                if (b.text.text.equals("Press")) {
                    b.click();
                    return;
                }
            }
        }
    }

    /**
     * Read juice level from the VMeter in the press window.
     * VMeter value is 0.0-1.0, multiply by JUICE_MAX to get liters.
     * Same pattern as NUtils.getFuelLvl() used for tanning tubs / smelters.
     * @return current juice level in liters, or 0 if can't read
     */
    private double readJuiceLevel(NGameUI gui) {
        Window wnd = gui.getWindow(PRESS_CAP);
        if (wnd == null) return 0;

        for (Widget w = wnd.lchild; w != null; w = w.prev) {
            if (w instanceof VMeter) {
                for (LayerMeter.Meter meter : ((VMeter) w).meters) {
                    return meter.a * JUICE_MAX;
                }
            }
        }
        return 0;
    }

    /**
     * Drop only items that were NOT in the original inventory snapshot.
     * This ensures we only drop seeds transferred from the press,
     * preserving any pre-existing player inventory contents.
     */
    private void dropNonOriginalItems(NGameUI gui, ArrayList<WItem> originalItems) throws InterruptedException {
        ArrayList<WItem> currentItems = gui.getInventory().getItems();
        for (WItem item : currentItems) {
            if (!originalItems.contains(item)) {
                NUtils.drop(item);
                NUtils.addTask(new ISRemoved(item.item.wdgid()));
            }
        }
    }
}
