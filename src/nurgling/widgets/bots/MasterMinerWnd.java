package nurgling.widgets.bots;

import haven.Button;
import haven.Coord;
import haven.Gob;
import haven.Label;
import haven.Text;
import haven.TextEntry;
import haven.UI;
import haven.Window;
import haven.Widget;
import haven.WItem;
import nurgling.actions.bots.MasterMiner;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.sessions.ThreadLocalUI;
import nurgling.conf.NMasterMinerProp;
import nurgling.widgets.NEquipory;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;

/**
 * Live read-out for Master Miner: the quality of each stone as it drops, the wall quality
 * derived from it, and the best seen so far in each category.
 */
public class MasterMinerWnd extends Window {
    /** Enough to prop a decent stretch of tunnel without a trip back to a stockpile. */
    public static final int DEFAULT_KEEP_STONES = 30;

    private volatile boolean closed = false;

    private final Label masonryLbl;
    private final Label lastMinedLbl;   // most recent stone
    private final Label stoneLbl;       // best plain stone
    private final Label quarryartzLbl;  // best quarryartz
    private final Label catGoldLbl;     // best cat gold
    private final Label rakuhLbl;       // best shell
    private final Label counterLbl;     // stones mined this run
    private final TextEntry thresholdEntry;              // drop threshold, stone
    private final TextEntry shellCatGoldThresholdEntry;  // drop threshold, shell and cat gold
    private final TextEntry keepStonesEntry;             // stones reserved for supports

    private int totalStonesMined = 0;
    private final Text.Foundry boldFoundry;
    private final Color masonryColor = new Color(255, 215, 0); // gold
    private Coord savedWindowPos = null;
    private Coord lastPersistedPos = null;
    
    /** Best find so far in one category. */
    private static class BestStoneData {
        String stoneName;  // the actual rock
        double f3;         // quality of the stone in the pack
        double wallQ;      // quality derived for the wall
        Double bestAltQ;   // what the best other tool would have yielded
    }
    
    private BestStoneData bestStone = null;
    private BestStoneData bestQuarryartz = null;
    private BestStoneData bestCatGold = null;
    private BestStoneData bestRakuh = null;

    public MasterMinerWnd() {
        super(new Coord(UI.scale(550), UI.scale(410)), "Master Miner");

        // Masonry gets its own bold gold face so it reads as the reference value.
        Font boldFont = Text.std.font.deriveFont(Font.BOLD);
        boldFoundry = new Text.Foundry(boldFont, masonryColor);

        // Restore this character's saved thresholds.
        NMasterMinerProp prop = loadSettings();
        String savedDropThreshold = "";
        String savedShellCatGoldThreshold = "";
        String savedKeepStones = String.valueOf(DEFAULT_KEEP_STONES);
        if (prop != null) {
            if (!Float.isNaN(prop.dropThreshold)) {
                savedDropThreshold = String.valueOf((int)prop.dropThreshold == prop.dropThreshold ? 
                    (int)prop.dropThreshold : prop.dropThreshold);
            }
            if (!Float.isNaN(prop.shellCatGoldThreshold)) {
                savedShellCatGoldThreshold = String.valueOf((int)prop.shellCatGoldThreshold == prop.shellCatGoldThreshold ? 
                    (int)prop.shellCatGoldThreshold : prop.shellCatGoldThreshold);
            }
            savedKeepStones = String.valueOf(prop.keepStonesForSupport);
            if (prop.hasWindowPos()) {
                savedWindowPos = new Coord(prop.wndX, prop.wndY);
                lastPersistedPos = savedWindowPos;
            }
        }

        Coord pad = UI.scale(8, 6);
        Coord cur = pad;

        masonryLbl = add(new Label("Masonry: (waiting)", boldFoundry), cur);
        masonryLbl.setcolor(masonryColor);
        cur = masonryLbl.pos("bl").add(0, UI.scale(4));

        lastMinedLbl = add(new Label("Last mined: -"), cur);
        cur = lastMinedLbl.pos("bl").add(0, UI.scale(4));

        stoneLbl = add(new Label("Stone: -"), cur);
        cur = stoneLbl.pos("bl").add(0, UI.scale(4));

        quarryartzLbl = add(new Label("Quarryartz: -"), cur);
        cur = quarryartzLbl.pos("bl").add(0, UI.scale(4));

        catGoldLbl = add(new Label("Cat Gold: -"), cur);
        cur = catGoldLbl.pos("bl").add(0, UI.scale(4));

        rakuhLbl = add(new Label("Shell: -"), cur);
        cur = rakuhLbl.pos("bl").add(0, UI.scale(6));

        counterLbl = add(new Label("Mined: 0"), cur);
        cur = counterLbl.pos("bl").add(0, UI.scale(6));

        /* An empty box means "never drop", which is easy to leave set by accident and
         * gives no feedback, so the label says so. */
        add(new Label("Drop threshold (blank = never drop):"), cur);
        cur = cur.add(UI.scale(0, UI.scale(18)));
        thresholdEntry = add(new TextEntry(UI.scale(80), savedDropThreshold) {
            @Override
            public void changed() {
                super.changed();
                // Persist on every keystroke.
                saveSettings();
            }
        }, cur);
        Coord setBtn1Pos = thresholdEntry.pos("ur").add(UI.scale(5), -UI.scale(4));
        add(new Button(UI.scale(40), "Set") {
            @Override
            public void click() {
                super.click();
                saveSettings();
            }
        }, setBtn1Pos);
        cur = thresholdEntry.pos("bl").add(0, UI.scale(6));
        
        add(new Label("Drop threshold, Shell/Cat Gold (blank = never):"), cur);
        cur = cur.add(UI.scale(0, UI.scale(18)));
        shellCatGoldThresholdEntry = add(new TextEntry(UI.scale(80), savedShellCatGoldThreshold) {
            @Override
            public void changed() {
                super.changed();
                // Persist on every keystroke.
                saveSettings();
            }
        }, cur);
        Coord setBtn2Pos = shellCatGoldThresholdEntry.pos("ur").add(UI.scale(5), -UI.scale(4));
        add(new Button(UI.scale(40), "Set") {
            @Override
            public void click() {
                super.click();
                saveSettings();
            }
        }, setBtn2Pos);
        cur = shellCatGoldThresholdEntry.pos("bl").add(0, UI.scale(6));

        add(new Label("Keep stones (for support):"), cur);
        cur = cur.add(UI.scale(0, UI.scale(18)));
        keepStonesEntry = add(new TextEntry(UI.scale(50), savedKeepStones) {
            @Override
            public void changed() {
                super.changed();
                saveSettings();
            }
        }, cur);
        Coord setBtn3Pos = keepStonesEntry.pos("ur").add(UI.scale(5), -UI.scale(4));
        add(new Button(UI.scale(40), "Set") {
            @Override
            public void click() {
                super.click();
                saveSettings();
            }
        }, setBtn3Pos);
        cur = keepStonesEntry.pos("bl").add(0, UI.scale(6));

        // Swap the tool between hand and pack without leaving the window.
        add(new Button(UI.scale(160), "Switch") {
            @Override
            public void click() {
                super.click();
                switchMiningTool();
            }
        }, cur);
        cur = cur.add(0, UI.scale(26));

        add(new Button(UI.scale(160), "Reset All") {
            @Override
            public void click() {
                super.click();
                totalStonesMined = 0;
                counterLbl.settext("Mined: 0");
                bestStone = null;
                bestQuarryartz = null;
                bestCatGold = null;
                bestRakuh = null;
                stoneLbl.settext("Stone: -");
                quarryartzLbl.settext("Quarryartz: -");
                catGoldLbl.settext("Cat Gold: -");
                rakuhLbl.settext("Shell: -");
            }
        }, cur);

        pack();
    }

    public boolean isClosed() {
        return closed;
    }

    /** Saved screen position, or null to center on first open. */
    public Coord savedWindowPos() {
        return savedWindowPos;
    }

    @Override
    public void move(Coord c) {
        super.move(c);
        persistWindowPos();
    }

    @Override
    public boolean mouseup(Widget.MouseUpEvent ev) {
        boolean handled = super.mouseup(ev);
        persistWindowPos();
        return handled;
    }

    public void setMasonry(int masonry) {
        // Bracketed value is Masonry with the +25% bonus applied.
        int masonryWithBonus = (int) Math.round(masonry * 1.25);
        String newText = "Masonry: " + masonry + " [" + masonryWithBonus + "]";
        masonryLbl.text.dispose();
        masonryLbl.text = boldFoundry.render(newText, masonryColor);
        masonryLbl.texts = newText;
        masonryLbl.col = masonryColor;
        masonryLbl.f = boldFoundry;
        masonryLbl.resize(masonryLbl.text.sz());
    }

    public void setStoneInfo(String stoneType, String stoneName, double f3, double wallQ, Double bestAltQ, int masonry, MasterMiner.ToolSet toolSet, MasterMiner.ToolType currentToolType) {
        BestStoneData data = new BestStoneData();
        data.stoneName = stoneName;
        data.f3 = f3;
        data.wallQ = wallQ;
        data.bestAltQ = bestAltQ;
        
        // Keep the richest wall seen in this category.
        BestStoneData currentBest = null;
        boolean isNewBest = false;
        switch (stoneType) {
            case "Stone":
                if (bestStone == null || wallQ > bestStone.wallQ) {
                    bestStone = data;
                    isNewBest = true;
                }
                currentBest = bestStone;
                break;
            case "Quarryartz":
                /* Quarryartz has one wall quality per tile, the same whichever tool digs it,
                 * so the first reading stands and later tools only refine the rest of the row.
                 * A reading more than 2.0 away must be a different tile. */
                if (bestQuarryartz == null) {
                    bestQuarryartz = data;
                    isNewBest = true;
                } else {
                    double diff = Math.abs(wallQ - bestQuarryartz.wallQ);
                    if (diff > 2.0) {
                        // Different tile: take it if it is richer.
                        if (wallQ > bestQuarryartz.wallQ) {
                            bestQuarryartz = data;
                            isNewBest = true;
                        }
                    } else {
                        // Same tile: keep wallQ, refresh only what the tool changes.
                        bestQuarryartz.f3 = data.f3;
                        bestQuarryartz.stoneName = data.stoneName;
                    }
                }
                currentBest = bestQuarryartz;
                break;
            case "Cat Gold":
                if (bestCatGold == null || wallQ > bestCatGold.wallQ) {
                    bestCatGold = data;
                    isNewBest = true;
                }
                currentBest = bestCatGold;
                break;
            case "Shell":
                if (bestRakuh == null || wallQ > bestRakuh.wallQ) {
                    bestRakuh = data;
                    isNewBest = true;
                }
                currentBest = bestRakuh;
                break;
        }
        
        if (currentBest == null) return;
        
        // Quarryartz always recomputes, because its wallQ does not change with the tool.
        boolean shouldRecalculate = isNewBest;
        if ("Quarryartz".equals(stoneType)) {
            shouldRecalculate = true;
        }
        
        // What the tools you are not holding would have yielded from this wall.
        if (shouldRecalculate && toolSet != null) {
            Double recalculatedBestAltQ = null;
            if (currentToolType != MasterMiner.ToolType.STONE_AXE && toolSet.stoneAxeQ != null) {
                Double pred;
                if ("Quarryartz".equals(stoneType)) {
                    // Quarryartz inverted: f3 = (wallQ + f4) / 2
                    // f3 = (wallQ + f4) / 2
                    pred = (currentBest.wallQ + toolSet.stoneAxeQ) / 2.0;
                } else {
                    pred = MasterMiner.invDropQ(currentBest.wallQ, toolSet.stoneAxeQ, 0.8);
                }
                if (recalculatedBestAltQ == null || (pred != null && pred > recalculatedBestAltQ)) recalculatedBestAltQ = pred;
            }
            if (currentToolType != MasterMiner.ToolType.TINKER_AXE && toolSet.tinkerAxeQ != null) {
                Double pred;
                if ("Quarryartz".equals(stoneType)) {
                    // Quarryartz inverted: f3 = (wallQ + f4) / 2
                    // f3 = (wallQ + f4) / 2
                    pred = (currentBest.wallQ + toolSet.tinkerAxeQ) / 2.0;
                } else {
                    pred = MasterMiner.invDropQ(currentBest.wallQ, toolSet.tinkerAxeQ, 0.9);
                }
                if (recalculatedBestAltQ == null || (pred != null && pred > recalculatedBestAltQ)) recalculatedBestAltQ = pred;
            }
            if (currentToolType != MasterMiner.ToolType.PICKAXE && toolSet.pickaxeQ != null) {
                Double pred;
                if ("Quarryartz".equals(stoneType)) {
                    // Quarryartz inverted: f3 = (wallQ + f4) / 2
                    // f3 = (wallQ + f4) / 2
                    pred = (currentBest.wallQ + toolSet.pickaxeQ) / 2.0;
                } else {
                    pred = MasterMiner.invDropQ(currentBest.wallQ, toolSet.pickaxeQ, 1.0);
                }
                if (recalculatedBestAltQ == null || (pred != null && pred > recalculatedBestAltQ)) recalculatedBestAltQ = pred;
            }
            currentBest.bestAltQ = recalculatedBestAltQ;
        }
        
        // The generic "Stone" row names the actual rock instead.
        String displayName = stoneType;
        if ("Stone".equals(stoneType) && currentBest.stoneName != null && !currentBest.stoneName.isEmpty()) {
            displayName = currentBest.stoneName;
        }
        
        String text;
        if (currentBest.bestAltQ != null && !currentBest.bestAltQ.isNaN() && !currentBest.bestAltQ.isInfinite()) {
            text = String.format("%s: %.2f [%.2f] (%.2f)", displayName, currentBest.f3, currentBest.wallQ, currentBest.bestAltQ);
        } else {
            text = String.format("%s: %.2f [%.2f]", displayName, currentBest.f3, currentBest.wallQ);
        }

        Label targetLabel = null;
        switch (stoneType) {
            case "Stone":
                targetLabel = stoneLbl;
                break;
            case "Quarryartz":
                targetLabel = quarryartzLbl;
                break;
            case "Cat Gold":
                targetLabel = catGoldLbl;
                break;
            case "Shell":
                targetLabel = rakuhLbl;
                break;
        }
        
        if (targetLabel != null) {
            targetLabel.settext(text);
            // Shell has no Masonry relationship worth colouring.
            if ("Stone".equals(stoneType) || "Quarryartz".equals(stoneType) || "Cat Gold".equals(stoneType)) {
                updateWallQColor(targetLabel, currentBest.wallQ, masonry, stoneType);
            } else {
                targetLabel.setcolor(Color.WHITE);
            }
        }
    }
    
    private Color getWallQColor(double wallQ, int masonry, String stoneType) {
        // Quarryartz is compared against Masonry plus its 25% bonus.
        int comparisonValue = masonry;
        if ("Quarryartz".equals(stoneType)) {
            comparisonValue = (int) Math.round(masonry * 1.25);
        }
        
        // Red: the wall is capped by your Masonry, so a better wall would not help.
        if (wallQ >= comparisonValue - 1.0 && wallQ <= comparisonValue + 1.0) {
            return Color.RED;
        }
        // Orange: about 10 under the cap.
        double diff = comparisonValue - wallQ;
        if (diff >= 9.0 && diff <= 11.0) {
            return new Color(255, 165, 0);
        }
        return Color.WHITE;
    }
    
    private void updateWallQColor(Label lbl, double wallQ, int masonry, String stoneType) {
        Color color = getWallQColor(wallQ, masonry, stoneType);
        lbl.setcolor(color);
        lbl.col = color;
    }

    public void incrementCounter() {
        totalStonesMined++;
        counterLbl.settext("Mined: " + totalStonesMined);
    }

    /**
     * Update the "last mined" line.
     */
    public void setLastMined(String stoneName, double wallQ, int masonry) {
        if (stoneName == null || stoneName.isEmpty()) {
            lastMinedLbl.settext("Last mined: -");
            return;
        }
        
        // Wall quality, to match the rows above.
        String text = String.format("Last mined: %s q%.1f", stoneName, wallQ);
        lastMinedLbl.settext(text);
    }

    public double getDropThreshold() {
        return parseThreshold(thresholdEntry);
    }
    
    public double getShellCatGoldThreshold() {
        return parseThreshold(shellCatGoldThresholdEntry);
    }

    /** A blank or unparseable box means "no threshold", which reads as NaN. */
    private static double parseThreshold(TextEntry entry) {
        if (entry == null) {
            return Double.NaN;
        }
        String txt = entry.text().trim();
        if (txt.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(txt.replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** How many stones to always keep in the pack, for placing supports. */
    public int getKeepStonesForSupport() {
        if (keepStonesEntry == null) {
            return DEFAULT_KEEP_STONES;
        }
        String txt = keepStonesEntry.text().trim();
        if (txt.isEmpty()) {
            return DEFAULT_KEEP_STONES;
        }
        try {
            return Math.max(0, Integer.parseInt(txt));
        } catch (NumberFormatException e) {
            return DEFAULT_KEEP_STONES;
        }
    }
    
    
    /**
     * Load this character's saved settings.
     */
    private NMasterMinerProp loadSettings() {
        if (NUtils.getUI() == null || NUtils.getUI().sessInfo == null) {
            return null;
        }
        return NMasterMinerProp.get(NUtils.getUI().sessInfo);
    }

    private void persistWindowPos() {
        if (c == null) {
            return;
        }
        if (lastPersistedPos != null && lastPersistedPos.equals(c)) {
            return;
        }
        saveSettings(true);
    }
    
    /**
     * Persist the current settings.
     */
    private void saveSettings() {
        saveSettings(false);
    }

    private void saveSettings(boolean includeWindowPos) {
        if (NUtils.getUI() == null || NUtils.getUI().sessInfo == null) {
            return;
        }
        NMasterMinerProp prop = NMasterMinerProp.get(NUtils.getUI().sessInfo);
        if (prop == null) {
            if (NUtils.getGameUI() != null && NUtils.getGameUI().getCharInfo() != null) {
                prop = new NMasterMinerProp(NUtils.getUI().sessInfo.username,
                                            NUtils.getGameUI().getCharInfo().chrid);
            } else {
                System.err.println("[MasterMiner] Cannot save settings: no character info");
                return;
            }
        }

        if (thresholdEntry != null) {
            try {
                String dropText = thresholdEntry.text().trim();
                if (!dropText.isEmpty()) {
                    prop.dropThreshold = Float.parseFloat(dropText.replace(',', '.'));
                } else {
                    prop.dropThreshold = Float.NaN;
                }
            } catch (NumberFormatException e) {
                prop.dropThreshold = Float.NaN;
            }
        }

        if (shellCatGoldThresholdEntry != null) {
            try {
                String shellCatGoldText = shellCatGoldThresholdEntry.text().trim();
                if (!shellCatGoldText.isEmpty()) {
                    prop.shellCatGoldThreshold = Float.parseFloat(shellCatGoldText.replace(',', '.'));
                } else {
                    prop.shellCatGoldThreshold = Float.NaN;
                }
            } catch (NumberFormatException e) {
                prop.shellCatGoldThreshold = Float.NaN;
            }
        }

        if (keepStonesEntry != null) {
            prop.keepStonesForSupport = getKeepStonesForSupport();
        }

        if (includeWindowPos && c != null) {
            prop.wndX = c.x;
            prop.wndY = c.y;
            lastPersistedPos = new Coord(c.x, c.y);
        }

        NMasterMinerProp.set(prop);
    }

    /**
     * Swap the mining tool between hand and pack, on a bot thread.
     *
     * <p>Bound to this window's UI so it acts on the right session, and registered with the
     * bot-interrupt widget so the stop button can cancel it mid-swap.
     */
    private void switchMiningTool() {
        final NUI boundUI = NUtils.getUI();
        final NGameUI gui = (boundUI != null) ? boundUI.gui : null;
        if (gui == null) {
            return;
        }
        Thread switchThread = new Thread(() -> {
            ThreadLocalUI.set(boundUI);
            try {
                new SwitchMiningToolAction().run(gui);
            } catch (InterruptedException e) {
                // Stopped by the user mid-swap; nothing to clean up.
            } finally {
                ThreadLocalUI.clear();
            }
        }, "MasterMiner-ToolSwitch");
        switchThread.setDaemon(true);
        if (gui.biw != null) {
            gui.biw.addObserve(switchThread);
        }
        switchThread.start();
    }
    
    /**
     * Move the mining tool between hand and pack, swapping in another tool if one is stowed.
     */
    private static class SwitchMiningToolAction implements nurgling.actions.Action {
        @Override
        public nurgling.actions.Results run(NGameUI gui) throws InterruptedException {
            WItem lhand = NUtils.getEquipment().findItem(NEquipory.Slots.HAND_LEFT.idx);
            WItem rhand = NUtils.getEquipment().findItem(NEquipory.Slots.HAND_RIGHT.idx);
            WItem wbelt = NUtils.getEquipment().findItem(NEquipory.Slots.BELT.idx);
            
            // What, if anything, is in hand.
            WItem currentTool = null;
            int handSlot = -1;
            if (MasterMiner.isKnownMiningTool(lhand)) {
                currentTool = lhand;
                handSlot = NEquipory.Slots.HAND_LEFT.idx;
            } else if (MasterMiner.isKnownMiningTool(rhand)) {
                currentTool = rhand;
                handSlot = NEquipory.Slots.HAND_RIGHT.idx;
            }
            
            if (currentTool != null) {
                // Tool in hand: stow it, then equip whatever else is carried.
                NUtils.takeItemToHand(currentTool);
                
                // Pack first, belt only if the pack is full.
                Coord pos = gui.getInventory().getFreeCoord(NUtils.getGameUI().vhand);
                if (pos != null) {
                    gui.getInventory().dropOn(pos, ((NGItem) NUtils.getGameUI().vhand.item).name());
                } else {
                    if (wbelt != null && wbelt.item.contents instanceof NInventory) {
                        NInventory beltInv = (NInventory) wbelt.item.contents;
                        if (beltInv.getFreeSpace() > 0) {
                            NUtils.transferToBelt();
                        } else {
                            return nurgling.actions.Results.ERROR("No free space in inventory or belt");
                        }
                    } else {
                        return nurgling.actions.Results.ERROR("No free space in inventory");
                    }
                }
                
                NUtils.getEquipment().wdgmsg("drop", handSlot);
                // The hand has to be free before the next tool can be picked up.
                NUtils.getUI().core.addTask(new nurgling.tasks.WaitFreeHand());
                
                WItem toolInBelt = null;
                WItem toolInInv = null;
                
                // Pack first, then belt.
                ArrayList<WItem> invItems = gui.getInventory().getItems();
                for (WItem item : invItems) {
                    if (MasterMiner.isKnownMiningTool(item) && item != currentTool) {
                        toolInInv = item;
                        break;
                    }
                }
                
                if (toolInInv == null && wbelt != null && wbelt.item.contents instanceof NInventory) {
                    NInventory beltInv = (NInventory) wbelt.item.contents;
                    ArrayList<WItem> beltItems = beltInv.getItems();
                    for (WItem item : beltItems) {
                        if (MasterMiner.isKnownMiningTool(item) && item != currentTool) {
                            toolInBelt = item;
                            break;
                        }
                    }
                }
                
                WItem toolToEquip = toolInInv != null ? toolInInv : toolInBelt;
                if (toolToEquip != null) {
                    NUtils.takeItemToHand(toolToEquip);
                    
                    // Into the hand we just emptied.
                    NEquipory.Slots slot = (handSlot == NEquipory.Slots.HAND_LEFT.idx)
                            ? NEquipory.Slots.HAND_LEFT
                            : NEquipory.Slots.HAND_RIGHT;
                    
                    NUtils.getEquipment().wdgmsg("drop", handSlot);
                    NUtils.getUI().core.addTask(new nurgling.tasks.WaitItemInEquip(toolToEquip, new NEquipory.Slots[]{slot}));
                    
                    raiseMiningCursor();
                }
                
            } else {
                // Nothing in hand: equip a tool from the pack or belt.
                WItem toolInBelt = null;
                WItem toolInInv = null;
                
                // Pack first, then belt.
                ArrayList<WItem> invItems = gui.getInventory().getItems();
                for (WItem item : invItems) {
                    if (MasterMiner.isKnownMiningTool(item)) {
                        toolInInv = item;
                        break;
                    }
                }
                
                if (toolInInv == null && wbelt != null && wbelt.item.contents instanceof NInventory) {
                    NInventory beltInv = (NInventory) wbelt.item.contents;
                    ArrayList<WItem> beltItems = beltInv.getItems();
                    for (WItem item : beltItems) {
                        if (MasterMiner.isKnownMiningTool(item)) {
                            toolInBelt = item;
                            break;
                        }
                    }
                }
                
                WItem toolToEquip = toolInInv != null ? toolInInv : toolInBelt;
                if (toolToEquip == null) {
                    return nurgling.actions.Results.ERROR("No mining tool found in inventory or belt");
                }
                
                // Both hands full: empty one.
                if (lhand != null && rhand != null) {
                    WItem handToFree = lhand;
                    NUtils.takeItemToHand(handToFree);
                    
                    Coord freePos = gui.getInventory().getFreeCoord(NUtils.getGameUI().vhand);
                    if (freePos != null) {
                        gui.getInventory().dropOn(freePos, ((NGItem) NUtils.getGameUI().vhand.item).name());
                    } else {
                        if (wbelt != null && wbelt.item.contents instanceof NInventory) {
                            NInventory beltInv = (NInventory) wbelt.item.contents;
                            if (beltInv.getFreeSpace() > 0) {
                                NUtils.transferToBelt();
                            } else {
                                return nurgling.actions.Results.ERROR("No free space to free hand");
                            }
                        } else {
                            return nurgling.actions.Results.ERROR("No free space to free hand");
                        }
                    }
                    
                    NUtils.getEquipment().wdgmsg("drop", NEquipory.Slots.HAND_LEFT.idx);
                    NUtils.getUI().core.addTask(new nurgling.tasks.WaitFreeHand());
                }
                
                NUtils.takeItemToHand(toolToEquip);
                
                int targetSlot = (lhand == null) ? NEquipory.Slots.HAND_LEFT.idx : NEquipory.Slots.HAND_RIGHT.idx;
                NEquipory.Slots slot = (targetSlot == NEquipory.Slots.HAND_LEFT.idx)
                        ? NEquipory.Slots.HAND_LEFT
                        : NEquipory.Slots.HAND_RIGHT;
                
                NUtils.getEquipment().wdgmsg("drop", targetSlot);
                NUtils.getUI().core.addTask(new nurgling.tasks.WaitItemInEquip(toolToEquip, new NEquipory.Slots[]{slot}));
                
                raiseMiningCursor();
            }
            
            return nurgling.actions.Results.SUCCESS();
        }

        /** Put the mining cursor back up after a swap, so the player can keep swinging. */
        private static void raiseMiningCursor() throws InterruptedException {
            Gob player = NUtils.player();
            if (player == null) {
                return;
            }
            try {
                NUtils.mine(player.rc);
            } catch (NullPointerException e) {
                // Menu grid not up; the player can raise the cursor themselves.
            }
        }
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if ("close".equals(msg)) {
            closed = true;
            saveSettings(true);
            hide();
        }
        super.wdgmsg(msg, args);
    }
}

