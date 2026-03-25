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

import haven.res.ui.relcnt.RelCont;

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

        // Main loop
        while (true) {
            // Phase 1: Fill the press with grapes
            int grapesLoaded = fillPress(gui, context);
            if (grapesLoaded == 0) {
                NUtils.getGameUI().msg("No more grapes available. Stopping.");
                return Results.SUCCESS();
            }

            // Phase 2: Press the grapes
            pressGrapes(gui, context);

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
     * Must be called before any press interaction, especially after visiting other areas.
     */
    private Gob navigateToPress(NContext context) throws InterruptedException {
        NArea pressArea = context.getSpecArea(Specialisation.SpecName.extractionPress);
        if (pressArea == null) return null;
        return Finder.findGob(pressArea, new NAlias(PRESS_RES));
    }

    /**
     * Fill the extraction press with grapes. Handles multiple trips if player
     * inventory is smaller than 25.
     * @return total number of grapes loaded into the press, 0 if no grapes available
     */
    private int fillPress(NGameUI gui, NContext context) throws InterruptedException {
        int totalLoaded = 0;
        int remaining = PRESS_SLOTS;

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
            NArea pressArea = context.getSpecArea(Specialisation.SpecName.extractionPress);
            Container pressCont = new Container(press, PRESS_CAP, pressArea);
            pressCont.initattr(Container.Space.class);
            new TransferToContainer(pressCont, new NAlias(GRAPE_ITEM)).run(gui);

            totalLoaded += taken;
            remaining -= taken;
        }

        // Close press window after loading
        closePressWindow(gui);

        return totalLoaded;
    }

    /**
     * Click the Press button and wait for pressing to complete.
     */
    private void pressGrapes(NGameUI gui, NContext context) throws InterruptedException {
        // Navigate to press area (should already be there after fillPress, but be safe)
        Gob press = navigateToPress(context);
        if (press == null) return;

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
                // Close window, drop what we have, reopen
                closePressWindow(gui);
                dropAllInventoryItems(gui);

                // We're still at the press area (just dropped seeds on the ground nearby)
                // Re-find press gob (still in range, no area change)
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

        // Drop all seeds from player inventory
        dropAllInventoryItems(gui);

        return juiceLevel;
    }

    /**
     * Lift an empty barrel, bring it to the press, transfer juice, return barrel.
     * @return true if successful, false if no empty barrels available
     */
    private boolean emptyJuiceIntoBarrel(NGameUI gui, NContext context) throws InterruptedException {
        // Make sure press window is closed before barrel operations
        closePressWindow(gui);

        // Navigate to barrel area (may be far from press)
        NArea barrelArea = context.getSpecArea(Specialisation.SpecName.barrel, "Grape Juice");
        if (barrelArea == null) {
            return false;
        }

        ArrayList<Gob> barrels = Finder.findGobs(barrelArea, new NAlias("barrel"));
        Gob emptyBarrel = null;
        for (Gob barrel : barrels) {
            if (!NUtils.barrelHasContent(barrel)) {
                emptyBarrel = barrel;
                break;
            }
        }
        if (emptyBarrel == null) {
            return false; // All barrels are full
        }

        // Save barrel original position
        Coord2d originalPos = emptyBarrel.rc;

        // Lift the barrel
        new LiftObject(emptyBarrel).run(gui);

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

        // Right-click the press while carrying barrel to transfer juice
        NUtils.activateGob(press);

        // Wait for barrel to have content (juice transferred)
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                Gob lifted = Finder.findLiftedbyPlayer();
                if (lifted == null) return true;
                return NUtils.barrelHasContent(lifted);
            }
        });

        // Navigate back to barrel area (may be far from press) and place barrel
        NUtils.navigateToArea(barrelArea);
        Gob lifted = Finder.findLiftedbyPlayer();
        if (lifted != null) {
            new PlaceObject(lifted, originalPos, 0).run(gui);
        }

        return true;
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
            // Check inside RelCont containers
            if (child instanceof RelCont) {
                for (Widget child2 = child.child; child2 != null; child2 = child2.next) {
                    if (child2 instanceof Button) {
                        Button b = (Button) child2;
                        if (b.text.text.equals("Press")) {
                            b.click();
                            return;
                        }
                    }
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

        // Find the first VMeter in the window (juice level meter)
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
     * Drop all items currently in the player inventory onto the ground.
     */
    private void dropAllInventoryItems(NGameUI gui) throws InterruptedException {
        ArrayList<WItem> items = gui.getInventory().getItems();
        if (items.isEmpty()) return;

        for (WItem item : items) {
            NUtils.drop(item);
            NUtils.addTask(new ISRemoved(item.item.wdgid()));
        }
    }
}
