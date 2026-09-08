package nurgling.actions.bots;

import haven.*;
import haven.MCache;
import haven.Resource;
import haven.res.lib.itemtex.ItemTex;
import haven.res.ui.stackinv.ItemStack;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.ActionWithFinal;
import nurgling.actions.Results;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitTicks;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.tools.VSpec;
import nurgling.NInventory;
import nurgling.widgets.LabeledMinimapMark;
import nurgling.widgets.NEquipory;
import nurgling.widgets.bots.MasterMinerWnd;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Master Miner: a helper, not an automator. You swing the pick; it watches the pack for
 * what drops, but only while the mining cursor is up.
 * From each stone it back-computes the real quality of the wall and reports the best seen.
 *
 * Formula:
 * ((F3−F4)*2 + (F4−10)/F5) + 10
 * F3 — quality of the stone that dropped,
 * F4 — quality of the tool,
 * F5 — the tool's debuff:
 *   stone axe 0.8, tinker's axe 0.9, pickaxe 1.0
 */
public class MasterMiner extends ActionWithFinal {

    private static final NAlias MINED_ITEMS;
    /** Ores that get a map mark, as opposed to plain stones. */
    private static final NAlias ORE_ITEMS;
    static {
        // Reuse Chipper's stone list rather than keeping a second copy in step with it.
        MINED_ITEMS = Chipper.stones;
        
        // Ores worth marking on the map.
        ORE_ITEMS = new NAlias(new ArrayList<>(List.of(
            "Black Ore", "Bloodstone", "Cassiterite", "Chalcopyrite", "Cinnabar",
            "Direvein", "Galena", "Heavy Earth", "Horn Silver", "Iron Ochre",
            "Lead Glance", "Leaf Ore", "Malachite", "Meteorite", "Peacock Ore",
            "Schrifterz", "Silvershine", "Wine Glance"
        )));
    }

    private volatile boolean stop = false;
    private MasterMinerWnd wnd = null;
    /* Keyed by GItem, not WItem: the inventory destroys and rebuilds the WItem widget when
     * an item changes slot, so widget identity is not stable enough to remember a stone by
     * -- every stone would look new again on the next pass. */
    private final Set<GItem> known = new HashSet<>();
    




    /** Drop icons cached from the previous session's resource set. */
    public static void clearIconCache() {
        oreIconCache.clear();
    }
    
    // Resolved icons, so a repeat find of the same ore costs no resource load.
    private static final ConcurrentHashMap<String, BufferedImage> oreIconCache = new ConcurrentHashMap<>();
    
    // Finds are batched so a burst off one wall collapses into a single mark.
    private static class MarkerBatch {
        final String oreName;
        final NGItem item;
        final double wallQ;
        final Coord tileCoords;
        final long segmentId;
        final String markerType; // "ore", "gem", "quarryartz"
        
        MarkerBatch(String oreName, NGItem item, double wallQ, Coord tileCoords, long segmentId, String markerType) {
            this.oreName = oreName;
            this.item = item;
            this.wallQ = wallQ;
            this.tileCoords = tileCoords;
            this.segmentId = segmentId;
            this.markerType = markerType;
        }
        
        /** Finds sharing this key are the same discovery; only the best quality survives. */
        String getGroupKey() {
            return markerType + ":" + oreName + ":" + segmentId + ":" + tileCoords.x + "," + tileCoords.y;
        }
    }
    
    // Pending finds, drained by the run loop.
    private final List<MarkerBatch> markerBatchQueue = new ArrayList<>();
    private volatile long lastBatchProcessTime = 0;
    /** Ticks to wait for a dropped stone to actually leave the pack. */
    private static final int DROP_CONFIRM_TICKS = 30;
    /** How many 2-tick waits to spend looking for a free drop slot before giving up. */
    private static final int DROP_SLOT_ATTEMPTS = 15;

    /** How far apart two finds of one ore count as different spots. */
    private static final int ORE_SPOT_RADIUS = 40;
    /** How long the batch is allowed to keep filling before it is drained. */
    private static final long BATCH_DELAY_MS = 1000;
    private final Object batchLock = new Object();

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // Reset, so a second run does not inherit the first one's state.
        stop = false;
        known.clear();
        MasterMinerWnd created = new MasterMinerWnd();
        Coord savedPos = created.savedWindowPos();
        if (savedPos != null) {
            gui.add(created, savedPos);
            gui.fitwdg(created);
            wnd = created;
        } else {
            wnd = NUtils.addCentered(gui, created);
        }

        if (gui.map instanceof nurgling.NMapView) {
            ((nurgling.NMapView) gui.map).restoreMinesweeperOverlay();
        }

        // Put the mining cursor up so the player can start swinging straight away.
        Gob player = NUtils.player();
        if (player != null) {
            try {
                NUtils.mine(player.rc);
            } catch (NullPointerException e) {
                // Menu grid not up yet; the player can raise the cursor themselves.
            }
        }

        try {
            /* `known` starts empty on purpose, so the first pass judges what is already
             * carried as well as what is mined from now on. The threshold is the player's
             * statement of what is worth keeping; stone that fails it is no more worth
             * carrying because it was mined a minute ago. */
            ArrayList<WItem> allItems;

            while (!stop && wnd != null && !wnd.isClosed()) {
                flushMarkerBatchIfDue(gui);

                wnd.setMasonry(masonry());

                String curs = NUtils.getCursorName();
                boolean mining = (curs != null) && NParser.checkName(curs, "mine");

                if (!mining) {
                    NUtils.addTask(new WaitTicks(10));
                    continue;
                }

                /* Walk the widget tree rather than getItems(): that only looks at
                 * child/next and misses items sitting in stack slots. */
                allItems = collectAllWItemsFromWidget(gui.getInventory());
                ArrayList<WItem> cur = filterMinedItems(allItems);

                /* Forget stones that have left the pack, so `known` tracks what is carried
                 * rather than everything ever seen. */
                Set<GItem> carried = new HashSet<>();
                for (WItem it : cur) {
                    carried.add(it.item);
                }
                known.retainAll(carried);

                ArrayList<WItem> newItems = new ArrayList<>();
                for (WItem it : cur) {
                    if (!known.contains(it.item)) {
                        newItems.add(it);
                    }
                }
                
                /* Re-check every stack each pass: a stack can grow, and change its average
                 * quality, without any new item appearing. */
                ArrayList<WItem> stacksToCheck = new ArrayList<>();
                for (WItem it : cur) {
                    if (!(it.item instanceof NGItem)) {
                        continue;
                    }
                    GItem.Amount amount = ((NGItem) it.item).getInfo(GItem.Amount.class);
                    if (amount != null && amount.itemnum() > 1) {
                        stacksToCheck.add(it);
                    }
                }

                // Droppable = everything carried, hand included, beyond the support reserve.
                int totalStones = countTotalStones(cur);
                WItem vhandItem = gui.vhand;
                if (vhandItem != null && vhandItem.item instanceof NGItem) {
                    NGItem vhandNGItem = (NGItem) vhandItem.item;
                    String vhandName = vhandNGItem.name();
                    if (vhandName != null && !isGemstone(vhandNGItem) && !isGemstone(vhandName) &&
                        (NParser.checkName(vhandName, MINED_ITEMS) || NParser.checkName(vhandName, ORE_ITEMS))) {
                        String vhandStoneType = classifyStoneType(vhandName);
                        if (!"Shell".equals(vhandStoneType) && !"Cat Gold".equals(vhandStoneType)) {
                            haven.GItem.Amount vhAm = vhandNGItem.getInfo(haven.GItem.Amount.class);
                            totalStones += (vhAm != null && vhAm.itemnum() > 0) ? vhAm.itemnum() : 1;
                        }
                    }
                }
                int keepStones = wnd.getKeepStonesForSupport();
                int[] needToDropRef = new int[] { Math.max(0, totalStones - keepStones) };

                // A stone lands in the hand instead of the pack when the pack is full.
                if (vhandItem != null && vhandItem.item instanceof NGItem) {
                    NGItem vhandNGItem = (NGItem) vhandItem.item;
                    String vhandName = vhandNGItem.name();
                    if (vhandName != null) {
                        boolean isMinedItem = NParser.checkName(vhandName, MINED_ITEMS) || 
                                             NParser.checkName(vhandName, ORE_ITEMS) ||
                                             isGemstone(vhandNGItem) || 
                                             isGemstone(vhandName);
                        if (isMinedItem && !known.contains(vhandItem.item)
                            && processNewStone(gui, vhandItem, wnd, needToDropRef)) {
                            known.add(vhandItem.item);
                        }
                    }
                }
                
                if (newItems.isEmpty() && stacksToCheck.isEmpty()) {
                    NUtils.addTask(new WaitTicks(5));
                    continue;
                }
                /* Stacks first: otherwise the loose stones use up the drop budget and the
                 * stacks are never touched. */
                for (WItem stackItem : stacksToCheck) {
                    checkAndDropStack(gui, stackItem, wnd, needToDropRef);
                }
                /* Only a stone that was actually judged is remembered. One whose quality had
                 * not arrived yet, or that was mined mid tool-swap, comes round again next
                 * pass instead of being written off unexamined. */
                for (WItem newItem : newItems) {
                    if (processNewStone(gui, newItem, wnd, needToDropRef)) {
                        known.add(newItem.item);
                    }
                }

                NUtils.addTask(new WaitTicks(2));
            }
        } finally {
            // Anything found in the last second still deserves its mark, stop or no stop.
            processMarkerBatch(gui);
            if (wnd != null) {
                wnd.destroy();
            }
            wnd = null;
        }
        return Results.SUCCESS();
    }
    
    /**
     * The quality of an item, reading through a stack where there is one.
     * Stacks report an average; single items carry their own.
     */
    private double getItemQuality(NGItem item, WItem wItem) {
        if (item == null) return -1;
        
        // Is it a stack?
        try {
            haven.GItem.Amount amount = item.getInfo(haven.GItem.Amount.class);
            if (amount != null && amount.itemnum() > 1) {
                // A stack: the Stack info carries the average quality.
                haven.res.ui.tt.stackn.Stack stackInfo = item.getInfo(haven.res.ui.tt.stackn.Stack.class);
                if (stackInfo != null && stackInfo.quality > 0) {
                    return stackInfo.quality;
                }
                // Stack info not populated yet; try the Quality info instead.
                List<ItemInfo> infoList = item.info();
                if (infoList != null) {
                    haven.res.ui.tt.q.quality.Quality qualityInfo = haven.ItemInfo.find(haven.res.ui.tt.q.quality.Quality.class, infoList);
                    if (qualityInfo != null && qualityInfo.q > 0) {
                        return qualityInfo.q;
                    }
                }
                // Last resort: average the individual items, for the window between a
                // stack forming and its summary info arriving.
                if (wItem != null && wItem.parent instanceof haven.res.ui.stackinv.ItemStack) {
                    haven.res.ui.stackinv.ItemStack itemStack = (haven.res.ui.stackinv.ItemStack) wItem.parent;
                    double sumQuality = 0;
                    int count = 0;
                    for (WItem w : itemStack.wmap.values()) {
                        if (w.item instanceof NGItem) {
                            NGItem ngItem = (NGItem) w.item;
                            if (ngItem.quality != null) {
                                sumQuality += ngItem.quality;
                                count++;
                            }
                        }
                    }
                    if (count > 0) {
                        return sumQuality / count;
                    }
                }
            }
        } catch (Loading e) {
            // Item info still resolving; fall through to the plain quality field.
        } catch (ConcurrentModificationException e) {
            // wmap is a plain HashMap owned by the UI thread; retry next pass.
        }

        // Single items carry their own quality.
        if (item.quality != null) {
            return item.quality;
        }
        
        return -1; // Quality has not arrived yet.
    }
    
    /**
     * The BEST quality in a stack, which is what decides whether to drop it.
     * A stack is either an ItemStack widget holding several GItems, or one slot with
     */
    private double getMaxStackQuality(NGItem item, WItem wItem) {
        if (item == null) return -1;
        
        try {
            haven.GItem.Amount amount = item.getInfo(haven.GItem.Amount.class);
            if (amount != null && amount.itemnum() > 1) {
                // ItemStack widget: several GItems, each with its own quality.
                if (wItem != null && wItem.parent instanceof haven.res.ui.stackinv.ItemStack) {
                    haven.res.ui.stackinv.ItemStack itemStack = (haven.res.ui.stackinv.ItemStack) wItem.parent;
                    double maxQuality = -1;
                    for (WItem w : itemStack.wmap.values()) {
                        if (w.item instanceof NGItem) {
                            NGItem ngItem = (NGItem) w.item;
                            if (ngItem.quality != null && ngItem.quality > maxQuality) {
                                maxQuality = ngItem.quality;
                            }
                        }
                    }
                    if (maxQuality > 0) return maxQuality;
                }
                
                // Single slot with Amount > 1: fall back to the stack summary.
                haven.res.ui.tt.stackn.Stack stackInfo = item.getInfo(haven.res.ui.tt.stackn.Stack.class);
                if (stackInfo != null && stackInfo.quality > 0) {
                    return stackInfo.quality;
                }
                if (item.quality != null) {
                    return item.quality;
                }
            }
        } catch (ConcurrentModificationException e) {
            // wmap is a plain HashMap owned by the UI thread; retry next pass.
        }

        if (item.quality != null) {
            return item.quality;
        }
        return -1;
    }
    
    /**
     * Whether the item is carried, counting stones held inside a stack.
     *
     * <p>A stack's widget is parented to a {@link haven.GItem.ContentsWindow} at the UI root,
     * not to the inventory, so walking straight up from a stacked stone never reaches the
     * inventory and it looks dropped already. The walk hops from that window back to the item
     * holding the stack, which is in the inventory.
     */
    private boolean isInMainInventory(NGameUI gui, WItem witem) {
        if (witem == null || gui == null) return false;
        if (witem == gui.vhand) return true;
        Widget inv = gui.getInventory();
        for (Widget w = witem; w != null; ) {
            if (w == inv) return true;
            if (w instanceof GItem.ContentsWindow) {
                w = ((GItem.ContentsWindow) w).cont;
            } else {
                w = w.parent;
            }
        }
        return false;
    }

    /**
     * Collect every WItem under a widget. getItems() only walks child/next, and NInventory
     * nests its slots, so without recursion the stones are simply not seen.
     */
    private ArrayList<WItem> collectAllWItemsFromWidget(Widget w) {
        ArrayList<WItem> out = new ArrayList<>();
        collectAllWItemsRecur(w, out);
        return out;
    }

    private void collectAllWItemsRecur(Widget w, ArrayList<WItem> out) {
        if (w == null) return;
        if (w instanceof WItem) {
            WItem wi = (WItem) w;
            if (wi.item != null && !out.contains(wi)) {
                out.add(wi);
                collectStackMembers(wi, out);
            }
        }
        for (Widget ch = w.child; ch != null; ch = ch.next) {
            collectAllWItemsRecur(ch, out);
        }
    }

    /**
     * Add the stones held inside a stack.
     *
     * <p>They cannot be reached by walking the inventory's children: {@link haven.GItem#addchild}
     * stores the stack in the {@code contents} field and parents the ItemStack widget to a
     * ContentsWindow off the UI root, so nothing under the inventory ever points at it. Reading
     * the field is the only way in -- without this the bot sees only loose stones, and a pack
     * of stacked ones looks empty.
     */
    private void collectStackMembers(WItem holder, ArrayList<WItem> out) {
        if (!(holder.item.contents instanceof ItemStack)) {
            return;
        }
        ItemStack stack = (ItemStack) holder.item.contents;
        // Snapshot: the UI thread owns these collections.
        for (GItem gi : new ArrayList<>(stack.order)) {
            WItem wi = stack.wmap.get(gi);
            if (wi != null && wi.item != null && !out.contains(wi)) {
                out.add(wi);
            }
        }
    }



    /**
     * Drop one stone and make sure it actually left.
     *
     * <p>The server throttles "drop" messages: send them faster than
     * {@link NUtils#dropSlotReady()} allows and the extras are silently discarded, which is
     * why a burst of finds used to leave most of its stones in the pack. So this waits for a
     * slot, sends the drop, and then waits for the item to disappear rather than assuming it
     * did.
     *
     * @return true if the stone is gone; false if it is still held, so the caller can keep
     *         its drop budget and try again on the next pass
     */
    private boolean dropStone(NGameUI gui, WItem item) throws InterruptedException {
        if (item == null || item.item == null) {
            return false;
        }
        // Wait for a drop slot rather than firing into the throttle and losing the message.
        for (int i = 0; i < DROP_SLOT_ATTEMPTS && !NUtils.dropSlotReady(); i++) {
            NUtils.addTask(new WaitTicks(2));
        }
        if (!isInMainInventory(gui, item) && item != gui.vhand) {
            return false;
        }
        if (item.parent instanceof ItemStack) {
            /* The message the client sends for ctrl-click on one stone in a stack. The
             * whole-item drop would take the stack with it. */
            item.item.wdgmsg("drop", Coord.z, 1);
        } else {
            NUtils.drop(item);
        }
        NUtils.addTask(new NTask() {
            int ticks = 0;

            @Override
            public boolean check() {
                return hasLeftInventory(gui, item) || ++ticks >= DROP_CONFIRM_TICKS;
            }
        });
        return hasLeftInventory(gui, item);
    }

    /**
     * Whether the item is no longer carried. WItem.item is final, so a dropped stone is not
     * recognised by its GItem going null; the widget is unlinked from the inventory instead.
     */
    private boolean hasLeftInventory(NGameUI gui, WItem item) {
        return item.parent == null || (!isInMainInventory(gui, item) && item != gui.vhand);
    }

    /**
     * Drop from a stack one stone at a time while its best quality is under the threshold.
     * Never the whole stack at once, and never past the support reserve in needToDropRef.
     */
    private void checkAndDropStack(NGameUI gui, WItem stackItem, MasterMinerWnd wnd, int[] needToDropRef) throws InterruptedException {
        if (stackItem == null || stackItem.item == null || !(stackItem.item instanceof NGItem) ||
            needToDropRef == null || needToDropRef[0] <= 0) {
            return;
        }
        
        NGItem ngItem = (NGItem) stackItem.item;
        String itemName = ngItem.name();
        if (itemName == null) return;
        
        boolean isMinedItem = NParser.checkName(itemName, MINED_ITEMS) || NParser.checkName(itemName, ORE_ITEMS);
        if (isGemstone(ngItem) || isGemstone(itemName) || !isMinedItem) return;
        
        double maxQ = -1;
        for (int attempt = 0; attempt < 5; attempt++) {
            maxQ = getMaxStackQuality(ngItem, stackItem);
            if (maxQ >= 0) break;
            if (attempt < 4) NUtils.addTask(new WaitTicks(2));
        }
        // Quality known: drop only what is under the threshold.
        if (maxQ >= 0) {
            String stoneType = classifyStoneType(itemName);
            double threshold = "Shell".equals(stoneType) || "Cat Gold".equals(stoneType)
                ? wnd.getShellCatGoldThreshold() : wnd.getDropThreshold();
            if (!Double.isNaN(threshold) && maxQ >= threshold) return;
        }
        // maxQ < 0: a stack often will not report quality, so fall back to dropping
        
        // The item may live inside a stack widget rather than directly in the pack.
        boolean isInInventory = isInMainInventory(gui, stackItem);
        boolean isInHand = (stackItem == gui.vhand);
        if (!isInInventory && !isInHand) return;
        String itemNameLower = itemName.toLowerCase();
        if (itemNameLower.contains("axe") || itemNameLower.contains("pickaxe") || 
            itemNameLower.contains("axe")) return;
        
        haven.GItem.Amount amount = ngItem.getInfo(haven.GItem.Amount.class);
        int stackSize = (amount != null && amount.itemnum() > 0) ? amount.itemnum() : 1;
        int toDrop = Math.min(stackSize, needToDropRef[0]);
        for (int i = 0; i < toDrop; i++) {
            if (needToDropRef[0] <= 0 || hasLeftInventory(gui, stackItem)) {
                break;
            }
            if (!dropStone(gui, stackItem)) {
                // Throttled or refused; the next pass picks the stack up again.
                break;
            }
            needToDropRef[0]--;
        }
    }
    
    /**
     * Total stones carried, stacks included: plain stone and ore, but not gemstones.
     * Used to honour the "keep N stones for supports" reserve.
     */
    private int countTotalStones(ArrayList<WItem> items) {
        if (items == null) return 0;
        int total = 0;
        for (WItem w : items) {
            if (w == null || w.item == null || !(w.item instanceof NGItem)) continue;
            NGItem ng = (NGItem) w.item;
            String name = ng.name();
            if (name == null) continue;
            if (isGemstone(ng) || isGemstone(name)) continue;
            if (!NParser.checkName(name, MINED_ITEMS) && !NParser.checkName(name, ORE_ITEMS)) continue;
            String stoneType = classifyStoneType(name);
            if ("Shell".equals(stoneType) || "Cat Gold".equals(stoneType)) continue;
            haven.GItem.Amount amount = ng.getInfo(haven.GItem.Amount.class);
            total += (amount != null && amount.itemnum() > 0) ? amount.itemnum() : 1;
        }
        return total;
    }

    /**
     * Narrow an inventory listing to mined stone, ore and gemstones.
     */
    private ArrayList<WItem> filterMinedItems(ArrayList<WItem> allItems) {
        ArrayList<WItem> result = new ArrayList<>();
        if (allItems == null) return result;
        
        for (WItem item : allItems) {
            if (item == null || item.item == null) continue;
            
            if (!(item.item instanceof NGItem)) {
                continue;
            }
            NGItem ngItem = (NGItem) item.item;
            /* Skip the stack container: it carries no quality of its own, so judging it
             * would stall waiting for one, and dropping it would throw away the whole stack.
             * collectStackMembers lists its stones individually, so nothing is missed. */
            if (ngItem.contents instanceof ItemStack) {
                continue;
            }
            String itemName = ngItem.name();
            if (itemName == null) {
                continue;
            }
            if (NParser.checkName(itemName, MINED_ITEMS) || isGemstone(ngItem) || isGemstone(itemName)) {
                result.add(item);
            }
        }
        
        return result;
    }
    
    /**
     * Handle one newly dropped stone.
     * needToDropRef[0] is the remaining drop budget after the support reserve.
     *
     * @return true once the stone has been judged, false if nothing could be decided yet —
     *         its quality has not arrived, or there is no tool to compare it against. The
     *         caller must not remember a stone it gets false for, or that stone is never
     *         looked at again and sits in the pack forever.
     */
    private boolean processNewStone(NGameUI gui, WItem newItem, MasterMinerWnd wnd, int[] needToDropRef) throws InterruptedException {
        NGItem dropped = (NGItem) newItem.item;
        
        // Stacks report their quality through the stack summary.
        double f3 = getItemQuality(dropped, newItem);
        
        if (f3 < 0) {
            // Quality has not arrived yet; wait for it.
            WItem finalNewItem = newItem;
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    /* Stop waiting if the stone is gone: the caller reads the quality again
                     * and gives up cleanly, whereas an unbounded wait would never return. */
                    if (!(finalNewItem.item instanceof NGItem)) {
                        return true;
                    }
                    NGItem gi = (NGItem) finalNewItem.item;
                    if (gi.name() == null) {
                        return false;
                    }
                    return getItemQuality(gi, finalNewItem) >= 0;
                }
            });
            f3 = getItemQuality(dropped, newItem);
            if (f3 < 0) {
                NUtils.addTask(new WaitTicks(2));
                return false;
            }
        }
        String stoneName = dropped.name();
        String stoneType = classifyStoneType(stoneName);

        // Gemstone?
        boolean isGem = isGemstone(dropped);
        if (!isGem) {
            isGem = isGemstone(stoneName);
        }
        
        // Gemstones do not feed the wall-quality read-out and are never dropped,
        // but they do get a map mark.
        if (isGem) {
            // Show it on the "last mined" line.
            // A gemstone's wall quality is just its own quality; no tool formula applies.
            wnd.setLastMined(stoneName, f3, masonry());
            
            // Mark it only if this gem is enabled in the settings.
            nurgling.conf.NMasterMinerMarkingConfig markingConfig = nurgling.conf.NMasterMinerMarkingConfig.get();
            if (markingConfig != null) {
                String configKey = extractGemstoneBaseName(stoneName);
                
                // Settings keys have been written in mixed case; try the variants.
                Boolean enabled = markingConfig.isEnabled(configKey);
                if (enabled == null && !configKey.equals(configKey.toLowerCase())) {
                    // lower case
                    enabled = markingConfig.isEnabled(configKey.toLowerCase());
                    if (enabled != null) {
                        configKey = configKey.toLowerCase();
                    }
                }
                if (enabled == null && !configKey.equals(configKey.substring(0, 1).toUpperCase() + configKey.substring(1).toLowerCase())) {
                    // capitalised
                    String properCase = configKey.substring(0, 1).toUpperCase() + configKey.substring(1).toLowerCase();
                    enabled = markingConfig.isEnabled(properCase);
                    if (enabled != null) {
                        configKey = properCase;
                    }
                }
                
                Double threshold = markingConfig.getThreshold(configKey);
                
                // No explicit setting means on: gemstones are worth marking by default.
                boolean shouldMark = false;
                if (enabled == null) {
                    shouldMark = true;
                } else {
                    // Explicit setting wins.
                    shouldMark = enabled;
                }
                
                if (shouldMark) {
                    double itemThreshold = (threshold != null && !threshold.isNaN()) ? threshold : 10.0;
                    if (f3 >= itemThreshold) {
                        // Gems are marked at their own quality, with no tool formula.
                        // Mark under the base name, so every cut of a gem shares one type.
                        String baseGemName = extractGemstoneBaseName(stoneName);
                        enqueueMark(gui, baseGemName, dropped, f3, "gem");
                    }
                }
            }
            // Gemstones are never dropped and never counted.
            return true;
        }

        WItem tool = findMiningTool();
        if (tool == null) {
            NUtils.addTask(new WaitTicks(10));
            return false;
        }

        // Wait for the tool's name and quality.
        final WItem ftool = tool;
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                if (!(ftool.item instanceof NGItem)) {
                    return true;
                }
                NGItem ti = (NGItem) ftool.item;
                return ti.name() != null && ti.quality != null;
            }
        });
        if (!(ftool.item instanceof NGItem)) {
            return false;
        }

        String toolName = ((NGItem) ftool.item).name();
        Double f4 = ((NGItem) ftool.item).quality != null ? (double) ((NGItem) ftool.item).quality : null;
        double f5 = toolCoef(toolName);
        ToolType currentToolType = classifyTool(toolName);

        if (f4 != null) {
            // Quarryartz follows its own formula; everything else uses the tool debuff.
            double wallQ;
            if ("Quarryartz".equals(stoneType)) {
                // A wall poorer than the tool yields its own quality unchanged.
                if (f3 < f4) {
                    wallQ = f3;
                } else {
                    // Quarryartz: wallQ = 2*f3 - f4
                    // and is the same whichever tool is used.
                    wallQ = (2.0 * f3) - f4;
                }
            } else {
                // Everything else goes through the tool-debuff formula.
                wallQ = calcWallQ(f3, f4, f5);
            }

            // What the other tools would have yielded from this same wall.
            ToolSet set = scanTools(gui, ftool);
            Double bestAltQ = null;
            if (currentToolType != ToolType.STONE_AXE && set.stoneAxeQ != null) {
                Double pred;
                if ("Quarryartz".equals(stoneType)) {
                    // A wall poorer than the tool would drop at its own quality.
                    if (wallQ < set.stoneAxeQ) {
                        pred = wallQ;
                    } else {
                        // Quarryartz inverted: from wallQ = 2*f3 - f4 it follows that
                        // f3 = (wallQ + f4) / 2, and wallQ is the same for every tool.
                        pred = (wallQ + set.stoneAxeQ) / 2.0;
                    }
                } else {
                    pred = invDropQ(wallQ, set.stoneAxeQ, 0.8);
                }
                if (bestAltQ == null || (pred != null && pred > bestAltQ)) bestAltQ = pred;
            }
            if (currentToolType != ToolType.TINKER_AXE && set.tinkerAxeQ != null) {
                Double pred;
                if ("Quarryartz".equals(stoneType)) {
                    // A wall poorer than the tool would drop at its own quality.
                    if (wallQ < set.tinkerAxeQ) {
                        pred = wallQ;
                    } else {
                        // Quarryartz inverted: from wallQ = 2*f3 - f4 it follows that
                        // f3 = (wallQ + f4) / 2, and wallQ is the same for every tool.
                        pred = (wallQ + set.tinkerAxeQ) / 2.0;
                    }
                } else {
                    pred = invDropQ(wallQ, set.tinkerAxeQ, 0.9);
                }
                if (bestAltQ == null || (pred != null && pred > bestAltQ)) bestAltQ = pred;
            }
            if (currentToolType != ToolType.PICKAXE && set.pickaxeQ != null) {
                Double pred;
                if ("Quarryartz".equals(stoneType)) {
                    // A wall poorer than the tool would drop at its own quality.
                    if (wallQ < set.pickaxeQ) {
                        pred = wallQ;
                    } else {
                        // Quarryartz inverted: from wallQ = 2*f3 - f4 it follows that
                        // f3 = (wallQ + f4) / 2, and wallQ is the same for every tool.
                        pred = (wallQ + set.pickaxeQ) / 2.0;
                    }
                } else {
                    pred = invDropQ(wallQ, set.pickaxeQ, 1.0);
                }
                if (bestAltQ == null || (pred != null && pred > bestAltQ)) bestAltQ = pred;
            }

            // Update the row for this category.
            if (stoneType != null) {
                int masonryForUI = masonry();
                wnd.setStoneInfo(stoneType, stoneName, f3, wallQ, bestAltQ, masonryForUI, set, currentToolType);
                wnd.setLastMined(stoneName, wallQ, masonryForUI);
                wnd.incrementCounter();
                
                // Mark it on the map if the settings say so.
                nurgling.conf.NMasterMinerMarkingConfig markingConfig = nurgling.conf.NMasterMinerMarkingConfig.get();
                if (markingConfig != null) {
                    // Non-gems are keyed by their full name.
                    String configKey = stoneName;
                    
                    Boolean enabled = markingConfig.isEnabled(configKey);
                    Double threshold = markingConfig.getThreshold(configKey);
                    
                    /* Everything mined is marked unless the settings say otherwise: the map
                     * window's ore/gem/stone buttons are what hide a category day to day, and
                     * a layer that is never populated cannot be toggled back on. */
                    boolean shouldMark = (enabled == null) || enabled;
                    
                    // Enabled, and the wall is rich enough to be worth remembering.
                    if (shouldMark) {
                        double itemThreshold = (threshold != null && !threshold.isNaN()) ? threshold : 10.0;
                        
                        if (wallQ >= itemThreshold) {
                            if ("Quarryartz".equals(stoneType)) {
                                // Quarryartz is marked exactly where it was dug.
                                enqueueMark(gui, stoneName, null, wallQ, "quarryartz");
                            } else {
                                // Stone and ore share one mark per spot.
                                enqueueMark(gui, stoneName, dropped, wallQ, "ore");
                            }
                        }
                    }
                }
            }

            // Drop check. Uses the stone's own quality (f3), not the wall quality,
            // because what you carry is the stone, not the wall.
            // Shell and Cat Gold have their own threshold.
            double threshold;
            if ("Shell".equals(stoneType) || "Cat Gold".equals(stoneType)) {
                threshold = wnd.getShellCatGoldThreshold();
            } else {
                threshold = wnd.getDropThreshold();
            }
            
            // Only within the drop budget, and only below the threshold.
            int budget = (needToDropRef == null) ? 0 : needToDropRef[0];
            boolean inPack = (newItem != null)
                    && (isInMainInventory(gui, newItem) || newItem == gui.vhand);
            String lower = stoneName != null ? stoneName.toLowerCase() : "";
            boolean isTool = lower.contains("axe");
            /* A blank threshold reads as NaN, which means "never drop" -- the window's label
             * says so, because it is otherwise an invisible off switch. */
            boolean wantDrop = budget > 0 && !Double.isNaN(threshold) && f3 < threshold
                    && inPack && !isTool;

            if (wantDrop) {
                if (dropStone(gui, newItem)) {
                    needToDropRef[0]--;
                } else {
                    /* Still held: leave it unjudged so the next pass retries it,
                     * rather than writing it off as dealt with. */
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void endAction() {
        stop = true;
        if (wnd != null) {
            wnd.destroy();
        }
    }

    /** Current Masonry, or 0 while the character sheet is still on its way. */
    private static int masonry() {
        try {
            return NUtils.getUI().sess.glob.getcattr("masonry").comp;
        } catch (NullPointerException e) {
            return 0;
        }
    }

    private static double calcWallQ(double f3, double f4, double f5) {
        // A wall richer than the tool needs the formula; a poorer one yields its own
        // quality unchanged.
        if (f3 < f4) {
            return f3;
        }
        if (f5 <= 0) f5 = 1.0;
        return ((f3 - f4) * 2.0 + (f4 - 10.0) / f5) + 10.0;
    }


    /** Whether a name is something that comes out of a wall at all: stone, ore or quarryartz. */
    public static boolean isMinedStone(String stoneName) {
        return NParser.checkName(stoneName, MINED_ITEMS);
    }

    /**
     * Whether a stone name is one of the ores that get marked.
     */
    public static boolean isOre(String stoneName) {
        if (stoneName == null) return false;
        String lowerName = stoneName.toLowerCase().trim();
        for (String oreKey : ORE_ITEMS.keys) {
            if (oreKey != null) {
                String lowerOreKey = oreKey.toLowerCase().trim();
                // Exact match, or contained, in case the name carries extra words.
                if (lowerName.equals(lowerOreKey) || lowerName.contains(lowerOreKey)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Whether a name is a gemstone, judged by its last word: the words before it
     * describe cut and size ("Fair Cabochon Onyx"), and the gem itself comes last.
     */
    public static boolean isGemstone(String stoneName) {
        if (stoneName == null || stoneName.trim().isEmpty()) return false;
        
        // The gem is the last word.
        String[] words = stoneName.trim().split("\\s+");
        if (words.length == 0) return false;
        
        String lastWord = words[words.length - 1].toLowerCase();
        
        // Two-word gem names have to be matched as a pair.
        if (words.length > 1) {
            String secondLastWord = words[words.length - 2].toLowerCase();
            
            if (lastWord.equals("jewel") && secondLastWord.equals("dust")) {
                return true;
            }
            
            if (lastWord.equals("shard") && secondLastWord.equals("star")) {
                return true;
            }
            
            if (lastWord.equals("diamond") && secondLastWord.equals("sugar")) {
                return true;
            }
            
            if (lastWord.equals("coral") && secondLastWord.equals("red")) {
                return true;
            }
            
            if (lastWord.equals("pearl") && secondLastWord.equals("oyster")) {
                return true;
            }
            
            if (lastWord.equals("pearl") && secondLastWord.equals("river")) {
                return true;
            }
        }
        
        // Single-word gems.
        // Moonstone, Onyx, Opal, Ruby, Sapphire, Topaz, Turquoise
        String[] simpleGemstoneNames = {
            "amber", "amethyst", "diamond", "emerald", "jade",
            "moonstone", "onyx", "opal", "ruby",
            "sapphire", "topaz", "turquoise"
        };
        
        // "diamond" alone is a plain diamond; "Sugar Diamond" was handled above.
        for (String gemName : simpleGemstoneNames) {
            if (lastWord.equals(gemName)) {
                if (gemName.equals("diamond") && words.length > 1 && 
                    words[words.length - 2].toLowerCase().equals("sugar")) {
                    continue; // "Sugar Diamond", already handled above.
                }
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * The gem's base name, so every cut of it shares one map layer.
     * "Fair Cabochon Onyx" and "Small Rough Onyx" both give "Onyx".
     * "Dust Jewel" -> "Dust Jewel", "Sugar Diamond" -> "Sugar Diamond"
     */
    private static String extractGemstoneBaseName(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) return fullName;
        
        String[] words = fullName.trim().split("\\s+");
        if (words.length == 0) return fullName;
        
        String lastWord = words[words.length - 1];
        if (lastWord == null || lastWord.isEmpty()) return fullName;
        
        // Two-word names keep both words.
        if (words.length > 1) {
            String secondLastWord = words[words.length - 2];
            if (secondLastWord != null && !secondLastWord.isEmpty()) {
                String secondLastWordLower = secondLastWord.toLowerCase();
                String lastWordLower = lastWord.toLowerCase();
                
                if ((lastWordLower.equals("jewel") && secondLastWordLower.equals("dust")) ||
                    (lastWordLower.equals("shard") && secondLastWordLower.equals("star")) ||
                    (lastWordLower.equals("diamond") && secondLastWordLower.equals("sugar")) ||
                    (lastWordLower.equals("coral") && secondLastWordLower.equals("red")) ||
                    (lastWordLower.equals("pearl") && (secondLastWordLower.equals("oyster") || secondLastWordLower.equals("river")))) {
                    return secondLastWord.substring(0, 1).toUpperCase() + secondLastWord.substring(1).toLowerCase() + " " + 
                           lastWord.substring(0, 1).toUpperCase() + lastWord.substring(1).toLowerCase();
                }
            }
        }
        
        // Otherwise the last word, capitalised.
        if (lastWord.length() > 1) {
            return lastWord.substring(0, 1).toUpperCase() + lastWord.substring(1).toLowerCase();
        } else {
            return lastWord.toUpperCase();
        }
    }
    
    /**
     * Whether an item is a gemstone, by resource path rather than name. Gem names vary with
     * cut and size ("Small Smooth Moonstone"), but they all live under a gems resource path.
     */
    public static boolean isGemstone(NGItem item) {
        if (item == null) {
            return false;
        }
        if (isGemstone(item.name())) {
            return true;
        }
        try {
            if (item.res != null && item.res.isReady() && isGemstoneResource(item.res.get())) {
                return true;
            }
            return isGemstoneResource(item.getres());
        } catch (Loading e) {
            // Still resolving; the name check above is all we have for now.
            return false;
        }
    }

    private static boolean isGemstoneResource(Resource res) {
        if (res == null || res.name == null) {
            return false;
        }
        String path = res.name.toLowerCase();
        return path.contains("gemstone")
                || path.contains("/gems/")
                || path.endsWith("/gems")
                || path.contains("invobjs/gems");
    }
    
    /**
     * Which read-out row a stone belongs to.
     */
    private static String classifyStoneType(String stoneName) {
        if (stoneName == null) return null;
        String name = stoneName.toLowerCase();
        if (name.contains("quarryartz")) return "Quarryartz";
        if (name.contains("cat gold")) return "Cat Gold";
        if (name.contains("rakuh") || 
            name.contains("shard of conch") || name.contains("parifai") || 
            name.contains("seashell") || name.contains("petrifiedshell") || 
            name.contains("petrified seashell")) {
            return "Shell";
        }
        // Anything that is not quarryartz, cat gold or shell is plain stone.
        if (!name.contains("quarryartz") && !name.contains("cat gold") &&
            !name.contains("rakuh") && 
            !name.contains("shard of conch") && !name.contains("parifai") && 
            !name.contains("seashell") && !name.contains("petrifiedshell") && 
            !name.contains("petrified seashell")) {
            return "Stone";
        }
        return null;
    }

    private static double toolCoef(String toolName) {
        if (toolName == null) return 1.0;
        String n = toolName.toLowerCase();
        // Pickaxe first, so it does not fall through to the "axe" tests.
        if (n.contains("pickaxe")) return 1.0;
        // Tinker's axe
        if (n.contains("tinker") && n.contains("axe")) return 0.9;
        // Stone axe
        if (n.contains("stone") && n.contains("axe")) return 0.8;
        return 1.0;
    }

    public enum ToolType { STONE_AXE, TINKER_AXE, PICKAXE, OTHER }

    public static boolean isKnownMiningTool(WItem w) {
        if (w == null) return false;
        String name = ((NGItem) w.item).name();
        if (name == null) return false;
        return classifyTool(name) != ToolType.OTHER || name.toLowerCase().contains("axe");
    }

    private static WItem findMiningTool() throws InterruptedException {
        if (NUtils.getEquipment() == null) return null;
        WItem l = NUtils.getEquipment().findItem(NEquipory.Slots.HAND_LEFT.idx);
        WItem r = NUtils.getEquipment().findItem(NEquipory.Slots.HAND_RIGHT.idx);
        if (isKnownMiningTool(l)) return l;
        if (isKnownMiningTool(r)) return r;
        return (l != null) ? l : r;
    }

    public static class ToolSet {
        public Double stoneAxeQ;
        public Double tinkerAxeQ;
        public Double pickaxeQ;
    }
    
    static ToolType classifyTool(String name) {
        if (name == null) return ToolType.OTHER;
        String n = name.toLowerCase();
        if (n.contains("pickaxe")) return ToolType.PICKAXE;
        if (n.contains("tinker") && n.contains("axe")) return ToolType.TINKER_AXE;
        if (n.contains("stone") && n.contains("axe")) return ToolType.STONE_AXE;
        return ToolType.OTHER;
    }
    
    public static double invDropQ(double wallQ, double f4, double f5) {
        // A wall poorer than the tool drops at its own quality.
        if (wallQ < f4) {
            return wallQ;
        }
        if (f5 <= 0) f5 = 1.0;
        // F3 = F4 + 0.5 * ((W-10) - (F4-10)/F5)
        return f4 + 0.5 * ((wallQ - 10.0) - (f4 - 10.0) / f5);
    }

    /**
     * Find mining tools in hand, on the belt and in the pack, keeping the best quality
     * of each type. Only the qualities matter here.
     */
    private static ToolSet scanTools(NGameUI gui, WItem currentTool) throws InterruptedException {
        ToolSet set = new ToolSet();

        // 1) whatever is in hand right now
        if (currentTool != null && currentTool.item instanceof NGItem) {
            NGItem ci = (NGItem) currentTool.item;
            if (ci.name() != null && ci.quality != null) {
                putBest(set, classifyTool(ci.name()), (double) ci.quality);
            }
        }

        // 2) the belt's own inventory
        WItem belt = NUtils.getEquipment().findItem(NEquipory.Slots.BELT.idx);
        if (belt != null && belt.item != null && belt.item.contents instanceof NInventory) {
            scanInventoryForTools(set, (NInventory) belt.item.contents);
        }

        // 3) the pack
        scanInventoryForTools(set, gui.getInventory());

        return set;
    }

    private static void scanInventoryForTools(ToolSet set, NInventory inv) throws InterruptedException {
        if (inv == null) return;
        ArrayList<WItem> all = inv.getItems();
        for (WItem wi : all) {
            if (wi == null || !(wi.item instanceof NGItem)) continue;
            NGItem gi = (NGItem) wi.item;
            if (gi.name() == null) continue;
            ToolType tp = classifyTool(gi.name());
            if (tp == ToolType.OTHER) continue;

            /* A tool whose quality has not resolved yet is simply skipped. scanTools runs
             * again on the next stone, so waiting here would only stall the read-out. */
            if (gi.quality != null) {
                putBest(set, tp, (double) gi.quality);
            }
        }
    }

    private static void putBest(ToolSet set, ToolType tp, double q) {
        switch (tp) {
            case STONE_AXE:
                if (set.stoneAxeQ == null || q > set.stoneAxeQ) set.stoneAxeQ = q;
                break;
            case TINKER_AXE:
                if (set.tinkerAxeQ == null || q > set.tinkerAxeQ) set.tinkerAxeQ = q;
                break;
            case PICKAXE:
                if (set.pickaxeQ == null || q > set.pickaxeQ) set.pickaxeQ = q;
                break;
            case OTHER:
            default:
                break;
        }
    }

    /**
     * Queue a find for marking. The tile is taken one step along the player's facing, which
     * is the wall being mined; the batch is drained later by the run loop.
     *
     * @param markerType one of "quarryartz", "ore" or "gem" — decides the dedup rule
     */
    private void enqueueMark(NGameUI gui, String resourceName, NGItem item, double quality, String markerType) {
        Gob player = NUtils.player();
        if (player == null || gui.mmap == null || gui.mmap.sessloc == null) {
            return;
        }
        Coord2d minedTile = new Coord2d(
                player.rc.x + (Math.cos(player.a) * MCache.tilesz.x),
                player.rc.y + (Math.sin(player.a) * MCache.tilesz.y));
        long segmentId;
        Coord tileCoords;
        try {
            segmentId = gui.mmap.sessloc.seg.id;
            tileCoords = minedTile.floor(MCache.tilesz).add(gui.mmap.sessloc.tc);
        } catch (NullPointerException e) {
            // Session location dropped out from under us mid-read; skip this find.
            return;
        }
        synchronized (batchLock) {
            markerBatchQueue.add(new MarkerBatch(resourceName, item, quality, tileCoords, segmentId, markerType));
            scheduleBatchProcessing();
        }
    }
    
    
    /** Chime on a quarryartz find, using whichever of these the resource set actually has. */
    private void playQuarryartzSound(NGameUI gui) {
        if (gui == null || gui.ui == null) {
            return;
        }
        for (String soundPath : new String[]{"sfx/msg", "sfx/fx/ore", "sfx/fx/stone", "sfx/fx/water"}) {
            try {
                Resource soundRes = Resource.local().loadwait(soundPath);
                if (soundRes != null) {
                    gui.ui.sfx(soundRes);
                    return;
                }
            } catch (Resource.LoadFailedException e) {
                // Not in this resource set; try the next one.
            }
        }
    }
    
    
    /**
     * Note that a find is waiting. The run loop drains the queue once the batch has had
     * {@link #BATCH_DELAY_MS} to fill up, so several stones off one wall become one mark.
     */
    private void scheduleBatchProcessing() {
        if (lastBatchProcessTime == 0) {
            lastBatchProcessTime = System.currentTimeMillis();
        }
    }

    /** Drain the batch from the bot thread once it has stopped filling. */
    private void flushMarkerBatchIfDue(NGameUI gui) {
        boolean due;
        synchronized (batchLock) {
            due = !markerBatchQueue.isEmpty()
                    && System.currentTimeMillis() - lastBatchProcessTime >= BATCH_DELAY_MS;
        }
        if (due) {
            processMarkerBatch(gui);
            lastBatchProcessTime = 0;
        }
    }
    
    /**
     * Turn the queued finds into map marks. Several stones from the same wall arrive in one
     * batch, so each (type, tile) group keeps only its best quality.
     */
    private void processMarkerBatch(NGameUI gui) {
        List<MarkerBatch> batch;
        synchronized (batchLock) {
            if (markerBatchQueue.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(markerBatchQueue);
            markerBatchQueue.clear();
        }

        if (gui.labeledMarkService == null) {
            return;
        }

        Map<String, MarkerBatch> bestMarkers = new HashMap<>();
        for (MarkerBatch item : batch) {
            String key = item.getGroupKey();
            MarkerBatch existing = bestMarkers.get(key);
            if (existing == null || item.wallQ > existing.wallQ) {
                bestMarkers.put(key, item);
            }
        }

        for (MarkerBatch best : bestMarkers.values()) {
            String label = String.format("q%.0f", best.wallQ);
            if ("quarryartz".equals(best.markerType)) {
                // Quarryartz quality varies wall to wall, so every find keeps its own mark.
                createMarkerDirect(gui, label, best.oreName, best.segmentId, best.tileCoords,
                        best.markerType, best.item, 0);
                playQuarryartzSound(gui);
            } else if ("gem".equals(best.markerType)) {
                createMarkerDirect(gui, label, best.oreName, best.segmentId, best.tileCoords,
                        best.markerType, best.item, 2);
            } else {
                updateOreSpot(gui, best, label);
            }
        }
    }

    /**
     * One ore spot carries one mark, which follows the richest wall found in it so far.
     * A find within {@link #ORE_SPOT_RADIUS} of an existing mark of the same ore moves that
     * mark here if it beats it, and is otherwise ignored.
     */
    private void updateOreSpot(NGameUI gui, MarkerBatch best, String label) {
        String bestLocationId = null;
        double bestExistingQ = 0;
        for (LabeledMinimapMark mark : gui.labeledMarkService.getMarksByResourceType(best.oreName)) {
            if (!mark.isNear(best.segmentId, best.tileCoords, ORE_SPOT_RADIUS)) {
                continue;
            }
            if (mark.quality > bestExistingQ) {
                bestExistingQ = mark.quality;
                bestLocationId = mark.getLocationId();
            }
        }

        if (bestLocationId == null) {
            createMarkerDirect(gui, label, best.oreName, best.segmentId, best.tileCoords,
                    best.markerType, best.item, ORE_SPOT_RADIUS);
        } else if (best.wallQ > bestExistingQ) {
            gui.labeledMarkService.updateMarkPosition(bestLocationId, label, best.wallQ, best.tileCoords);
        }
    }
    
    /**
     * Place a mark, resolving its icon from the item that dropped or from the resource
     * catalogue. Icons are shared per resource type, so a mark whose icon will not resolve
     * still renders its label and picks the icon up as soon as any find of that type
     * registers one.
     */
    private void createMarkerDirect(NGameUI gui, String label, String oreName, long segmentId,
                                    Coord tileCoords, String markerType, NGItem item, int radiusTiles) {
        BufferedImage icon;
        if ("quarryartz".equals(markerType)) {
            icon = getQuarryartzIcon();
        } else if (item != null) {
            icon = getOreIconFromItem(item, oreName);
        } else {
            icon = getOreIcon(oreName);
        }
        if (icon == null) {
            icon = LabeledMinimapMark.icon(oreName);
        }
        gui.labeledMarkService.addMinedMark(label, oreName, parseLabelQuality(label),
                segmentId, tileCoords, icon, radiusTiles);
    }

    private static double parseLabelQuality(String label) {
        if (label == null || !label.startsWith("q")) {
            return 0;
        }
        try {
            return Double.parseDouble(label.substring(1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    
    /**
     * The icon path for an ore, from the resource catalogue.
     * Rewrites the world-object path (gfx/terobjs/bumlings/...) to the item icon path.
     * 
     * @param resourceType resource name, e.g. "Wine Glance"
     * @return icon path, e.g. "gfx/invobjs/cuprite", or null if there is none
     */
    private static String getIconPathFromVSpec(String resourceType) {
        if (resourceType == null || VSpec.object == null) return null;
        
        String lower = resourceType.toLowerCase().trim();
        String normalized = lower.replaceAll("\\s+", "");
        
        for (String iconPath : VSpec.object.keySet()) {
            ArrayList<String> oreNames = VSpec.object.get(iconPath);
            if (oreNames != null) {
                for (String oreName : oreNames) {
                    String lowerOreName = oreName.toLowerCase().trim();
                    String normalizedOreName = lowerOreName.replaceAll("\\s+", "");
                    
                    // Match the name as given or with spaces stripped.
                    if (lowerOreName.equals(lower) || normalizedOreName.equals(normalized) ||
                        lowerOreName.equals(normalized) || normalizedOreName.equals(lower)) {
                        if (iconPath.startsWith("gfx/terobjs/bumlings/")) {
                            String oreType = iconPath.substring("gfx/terobjs/bumlings/".length());
                            return "gfx/invobjs/" + oreType;
                        }
                        // Already an item path.
                        return iconPath;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * The icon for a stone or ore. Prefers the catalogue path, which needs no loaded item,
     * and falls back to the item's own sprite. Resolved icons are cached per name; a miss is
     * not cached, so the next find gets another chance once the resource has loaded.
     */
    public static BufferedImage getOreIconFromItem(NGItem oreItem, String oreName) {
        if (oreName != null) {
            BufferedImage cached = oreIconCache.get(oreName);
            if (cached != null) {
                return cached;
            }
        }

        BufferedImage icon = getOreIcon(oreName);
        if (icon == null && oreItem != null && oreItem.res != null && oreItem.res.isReady()) {
            try {
                icon = imageFromSprite(oreItem.spr());
            } catch (Loading e) {
                // Still loading; leave it uncached so a later find can retry.
            }
        }
        if (icon != null && oreName != null) {
            oreIconCache.put(oreName, icon);
        }
        return icon;
    }
    
    /**
     * The icon for an ore by name, preferring the catalogue path over guesswork.
     */
    public static BufferedImage getOreIcon(String oreName) {
        if (oreName == null) return null;
        
        // The catalogue knows the odd cases, so try it before guessing paths.
        String vSpecPath = getIconPathFromVSpec(oreName);
        if (vSpecPath != null) {
            BufferedImage img = loadIcon(vSpecPath);
            if (img != null) {
                return img;
            }
        }
        
        // Wine Glance is drawn as cuprite in resource sets that lack its own icon.
        if (oreName.equalsIgnoreCase("Wine Glance")) {
            BufferedImage img = loadIcon("gfx/invobjs/wineglance");
            if (img == null) {
                img = loadIcon("gfx/invobjs/cuprite");
            }
            if (img != null) {
                return img;
            }
        }
        
        String lower = oreName.toLowerCase().trim();
        
        String resourceName = lower;
        if (lower.equals("rock salt") || lower.equals("rocksalt")) {
            resourceName = "halite"; // Rock Salt is drawn as halite.
        }
        
        // Icon paths carry no spaces: "lead glance" -> "leadglance".
        String normalized = resourceName.replaceAll("\\s+", "");
        
        // Most likely paths first.
        String[] possiblePaths = {
            "gfx/invobjs/" + normalized,
            "gfx/invobjs/" + resourceName,
            "gfx/invobjs/ore-" + normalized,
            "gfx/invobjs/ore-" + resourceName,
            "gfx/invobjs/stone-" + normalized,
            "gfx/invobjs/stone-" + resourceName
        };
        
        for (String path : possiblePaths) {
            BufferedImage img = loadIcon(path);
            if (img != null) {
                return img;
            }
        }
        
        // Nothing matched; the caller falls back to the shared icon for this type.
        return null;
    }
    
    
    
    /**
     * The icon for a gemstone, taken from the item itself so cut and colour survive.
     * Returns null if nothing has loaded yet; the mark then renders label-only and picks up
     * the shared icon as soon as any find of the same type resolves one.
     */
    public static BufferedImage getGemstoneIconFromItem(NGItem gemItem) {
        if (gemItem == null) {
            return null;
        }
        try {
            BufferedImage img = imageFromSprite(gemItem.spr());
            if (img != null) {
                return img;
            }
            if (gemItem.res != null && gemItem.res.isReady() && gemItem.sdt != null) {
                Resource res = gemItem.res.get();
                if (res != null) {
                    img = imageFromSprite(GSprite.create(gemItem, res, gemItem.sdt.clone()));
                    if (img != null) {
                        return img;
                    }
                    img = imageFromResource(res);
                    if (img != null) {
                        return img;
                    }
                }
            }
            return imageFromResource(gemItem.getres());
        } catch (Loading e) {
            return null;
        }
    }

    private static BufferedImage imageFromSprite(GSprite spr) {
        if (spr == null) {
            return null;
        }
        BufferedImage img = ItemTex.sprimg(spr);
        if (img != null) {
            return img;
        }
        return (spr instanceof GSprite.ImageSprite) ? ((GSprite.ImageSprite) spr).image() : null;
    }

    private static BufferedImage imageFromResource(Resource res) {
        if (res == null) {
            return null;
        }
        Resource.Image layer = res.layer(Resource.imgc);
        return (layer == null) ? null : layer.img;
    }

    /** Load an icon by resource path, or null when this resource set has no such path. */
    private static BufferedImage loadIcon(String path) {
        try {
            return imageFromResource(Resource.remote().loadwait(path));
        } catch (Resource.LoadFailedException e) {
            return null;
        }
    }
    
    
    public static BufferedImage getQuarryartzIcon() {
        /* The resource is spelled with two q's; the other paths are here because older
         * resource sets differ, and plain stone is a readable last resort. */
        for (String path : new String[]{"gfx/invobjs/quarryquartz", "gfx/invobjs/quarryartz", "gfx/invobjs/stone"}) {
            BufferedImage img = loadIcon(path);
            if (img != null) {
                return img;
            }
        }
        return null;
    }
    
    
}


