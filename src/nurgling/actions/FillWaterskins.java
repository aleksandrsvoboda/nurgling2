package nurgling.actions;

import haven.*;
import nurgling.*;
import nurgling.actions.bots.SelectArea;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.NTask;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.NEquipory;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills waterskins from a water source (barrel, cistern, well).
 * Two modes:
 * - useGlobalZone=false (default): prompts user to select a water zone
 * - useGlobalZone=true: uses NContext water specialisation area (local then global), errors if not found
 */
public class FillWaterskins implements Action {

    protected final boolean useGlobalZone;

    public FillWaterskins() { this.useGlobalZone = false; }
    public FillWaterskins(boolean useGlobalZone) { this.useGlobalZone = useGlobalZone; }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Pair<Coord2d, Coord2d> area = null;

        if (useGlobalZone) {
            NContext context = new NContext(gui);
            NArea nArea = context.findArea(Specialisation.SpecName.water);
            if (nArea == null) {
                return Results.ERROR("No water area found! Please create an area with 'water' specialization.");
            }
            // context.goToArea() would navigate with ensurePresence=false, which skips the
            // actual walk whenever the area's grid happens to already be loaded/reachable by
            // local PF - that's fine for callers that are about to walk there anyway for some
            // other reason, but here the very next step (Finder.findGobs below) needs the
            // barrel/cistern/well gobs to actually be streamed in, or it finds nothing and
            // fails with "No containers with water" despite a perfectly valid area existing.
            // ensurePresence=true forces the real walk - same fix HoneyAndWaxCollector,
            // ReturnBarrelFromWorkArea, and FindPlaceAndAction already apply for the same
            // "must physically be there before searching for gobs" reason.
            if (!NUtils.navigateToArea(nArea, true)) {
                return Results.ERROR("Failed to reach water area");
            }
            area = nArea.getRCArea();
        } else {
            SelectArea insa;
            NUtils.getGameUI().msg("Please, select area with cistern or barrel");
            (insa = new SelectArea(Resource.loadsimg("baubles/waterRefiller"))).run(gui);
            area = insa.getRCArea();
        }

        Gob target = null;
        if(area!=null)
        {
            ArrayList<Gob> targets = Finder.findGobs(area,new NAlias("barrel", "cistern", "well"));
            for(Gob cand: targets)
            {
                if(NParser.isIt(cand,new NAlias("barrel")))
                {
                    if(NUtils.barrelHasContent(cand) && NParser.checkName(NUtils.getContentsOfBarrel(cand), "water")) {
                        target = cand;
                        break;
                    }
                }
                else {
                    target = cand;
                    break;
                }
            }
            if(target==null)
                return Results.ERROR("No containers with water");
        }
        else
        {
            return Results.ERROR("no water area");
        }
        new PathFinder(target).run(gui);

        // Refill every empty Waterskin/Glass Jug the character is carrying, wherever it is -
        // main inventory, belt, or any other equipped item with its own storage (pants
        // pockets, coat, etc.), not just the belt.
        //
        // Re-resolves the target container fresh on every iteration instead of processing a
        // pre-snapshotted list of WItems. Taking an item out of a container and dropping a
        // (possibly server-recreated) widget back into it can invalidate other WItem
        // references still held from that same container's earlier snapshot - iterating a
        // stale list after the first refill was silently breaking every waterskin after the
        // first one in the same run (the "take" message lands on a widget that no longer
        // exists, so the hand never actually fills and the bot stalls). Re-querying avoids
        // ever touching a reference across that mutation boundary.
        while (true) {
            Pair<WItem, NInventory> found = findEmptyWaterContainer(gui);
            if (found == null) break;

            NUtils.takeItemToHand(found.a);

            boolean progressed = fillHeldWaterContainer(gui, target);

            // Return it to whichever container it came from. The belt is NOT a regular
            // rectangular grid inventory the way the main pack or a pocket is - dropToInv's
            // coordinate-based drop (findFreeCoord + dropOn) doesn't reliably place an item
            // back into it, which is why waterskins pulled from the belt were getting stuck
            // in hand. transferToBelt() (itemact on the belt's own equipped widget) is the
            // mechanism the original, belt-only version of this bot used successfully - reuse
            // it specifically for the belt, and keep the generic drop for every other
            // container (main inventory, pockets, etc.), which are regular grids.
            //
            // NOT HandIsFree(sourceInv) either way: that task's "destination full" escape
            // condition is wrong here - a container already packed with waterskins reads 0
            // free space before the transfer even starts, so the wait would resolve
            // instantly, before the item actually left the hand.
            NInventory beltInv = null;
            WItem beltEquip = NUtils.getEquipment().findItem(NEquipory.Slots.BELT.idx);
            if (beltEquip != null && beltEquip.item.contents instanceof NInventory) {
                beltInv = (NInventory) beltEquip.item.contents;
            }
            if (found.b == beltInv) {
                NUtils.transferToBelt();
            } else {
                NUtils.dropToInv(found.b);
            }
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return NUtils.getGameUI().vhand == null;
                }
            });

            if (!progressed) {
                // The water source gave us nothing this time (e.g. a barrel that just ran
                // dry) - every other under-filled container out there would hit the exact
                // same wall, since they all draw from this same target. Stop here instead of
                // re-finding and re-attempting them forever; whatever's already been topped
                // up (even partially) stays as-is.
                break;
            }
        }

        refillItemInEquip(gui,NUtils.getEquipment().findItem(NEquipory.Slots.LFOOT.idx),target);
        refillItemInEquip(gui,NUtils.getEquipment().findItem(NEquipory.Slots.RFOOT.idx),target);
        // Refill buckets in hands
        refillBucketInHand(gui,NUtils.getEquipment().findItem(NEquipory.Slots.HAND_LEFT.idx),target);
        refillBucketInHand(gui,NUtils.getEquipment().findItem(NEquipory.Slots.HAND_RIGHT.idx),target);
        return Results.SUCCESS();
    }

    // A full Waterskin/Glass Jug holds 3l (confirmed via live logging: a freshly-filled
    // Waterskin reports "3.00 l of Water"); a full Bucket holds 10l. Anything measurably
    // below a container's own full amount - not just completely empty - should get topped up.
    private static final double WATERSKIN_FULL_LITERS = 3.0;
    private static final double BUCKET_FULL_LITERS = 10.0;
    private static final double LITERS_EPSILON = 0.01;

    private static final Pattern LITERS_PATTERN = Pattern.compile("([\\d.]+)\\s*l\\b", Pattern.CASE_INSENSITIVE);

    /** {@link #needsWaterRefill(NGItem, double)} for a Waterskin/Glass Jug specifically. */
    private boolean needsWaterRefill(NGItem ngItem) {
        return needsWaterRefill(ngItem, WATERSKIN_FULL_LITERS);
    }

    /**
     * True if this container is empty or holds less than {@code fullLiters} of water.
     * Non-water contents (or a content string that doesn't parse) are left alone - this bot
     * fills water containers, it doesn't dump out whatever else might be in one.
     */
    private boolean needsWaterRefill(NGItem ngItem, double fullLiters) {
        return parseWaterLiters(ngItem) < fullLiters - LITERS_EPSILON;
    }

    /**
     * How much water this container currently holds, in liters. 0 for an empty container or
     * one holding something other than water (or a content string that doesn't parse) - so
     * "0" here doesn't necessarily mean literally empty, just "not usefully measurable as
     * water," which is all the progress-tracking in {@link #fillHeldWaterContainer} needs.
     */
    private double parseWaterLiters(NGItem ngItem) {
        if (ngItem.content().isEmpty()) return 0;
        String contentName = ngItem.content().get(0).name();
        if (contentName == null || !contentName.contains("Water")) return 0;
        Matcher m = LITERS_PATTERN.matcher(contentName);
        if (!m.find()) return 0;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Clicks the currently-held water container against target until it reads as topped up.
     * A container that already holds SOME water needs two clicks: the game treats an itemact
     * against the water source as a toggle for a partially-full container - the first click
     * dumps its existing water back into the source (leaving it empty), the second actually
     * fills it. A fully empty container fills in a single click. Confirmed live (2026-08-19)
     * by the user - this is why the single-click version left partially-full waterskins
     * un-filled (and, worse, the old WaitItemContent-based wait would have hung forever on
     * one, since dumping leaves the content list empty, which that wait never treats as done).
     * <p>
     * Bails out after 2 attempts regardless of outcome, so a container holding something
     * other than water (which {@link #needsWaterRefill} won't recognize as "full" no matter
     * how many times it's clicked) can't loop forever either.
     *
     * @return true if the container ended up full, OR at least gained some water this call.
     *         False means the source had nothing left to give at all - e.g. a barrel with
     *         only 1l left tops a waterskin up to 1l and returns true (real progress, even
     *         though the skin still reads as "needs refill" at 1l/3l); the NEXT waterskin,
     *         with the barrel now actually empty, gains nothing and returns false. Callers
     *         use this to stop retrying once the source is confirmed dry, instead of
     *         re-finding the same under-filled container forever (its "needs refill" state
     *         never changes once the source can't add any more).
     */
    private boolean fillHeldWaterContainer(NGameUI gui, Gob target) throws InterruptedException {
        return fillHeldWaterContainer(gui, target, WATERSKIN_FULL_LITERS);
    }

    private boolean fillHeldWaterContainer(NGameUI gui, Gob target, double fullLiters) throws InterruptedException {
        WItem startHeld = NUtils.getGameUI().vhand;
        if (startHeld == null || !(startHeld.item instanceof NGItem)) return false;
        double startLiters = parseWaterLiters((NGItem) startHeld.item);

        for (int attempt = 1; attempt <= 2; attempt++) {
            WItem held = NUtils.getGameUI().vhand;
            if (held == null || !(held.item instanceof NGItem)) break;
            NGItem ngItem = (NGItem) held.item;
            String before = ngItem.content().isEmpty() ? null : ngItem.content().get(0).name();

            NUtils.activateItem(target);
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    WItem h = NUtils.getGameUI().vhand;
                    if (h == null || !(h.item instanceof NGItem)) return true;
                    NGItem ng = (NGItem) h.item;
                    String now = ng.content().isEmpty() ? null : ng.content().get(0).name();
                    return !java.util.Objects.equals(before, now);
                }
            });

            WItem afterHeld = NUtils.getGameUI().vhand;
            if (afterHeld == null || !(afterHeld.item instanceof NGItem)) break;
            if (!needsWaterRefill((NGItem) afterHeld.item, fullLiters)) break;
        }

        WItem endHeld = NUtils.getGameUI().vhand;
        if (endHeld == null || !(endHeld.item instanceof NGItem)) return true;
        NGItem endItem = (NGItem) endHeld.item;
        double endLiters = parseWaterLiters(endItem);
        return endLiters > startLiters + LITERS_EPSILON || !needsWaterRefill(endItem, fullLiters);
    }

    /**
     * Finds one empty Waterskin/Glass Jug wherever the character happens to be carrying it -
     * main inventory, belt, or any other equipped item with its own storage (pants pockets,
     * coat, etc.) - along with the NInventory it lives in, so it can be dropped back into the
     * same place after filling. Returns null if none are found. Deliberately re-run fresh
     * each iteration by the caller rather than cached - see the loop's comment in run().
     * <p>
     * Walks each container's widget chain directly instead of using {@code NInventory.getItems}
     * (the {@code GetItems} task it runs). That task's {@code check()} refuses to return a
     * result at all - blocking the caller indefinitely - while ANY item in the container has a
     * still-null {@code name()}, which happens for items sitting in a container the client
     * hasn't had a reason to request tooltip data for yet (e.g. a pants-pocket or belt pouch
     * that hasn't been opened this session). That's exactly the class of container this method
     * was widened to search, so it silently hung the very first scan instead of ever reaching
     * the barrel. Skipping an unresolved item here (instead of blocking on it) means the loop
     * just doesn't count it as a candidate yet; it'll be picked up on a later pass once its
     * name resolves, and a single permanently-unresolved item can no longer freeze the bot.
     */
    private Pair<WItem, NInventory> findEmptyWaterContainer(NGameUI gui) throws InterruptedException {
        NAlias alias = new NAlias("Waterskin", "Glass Jug");

        Pair<WItem, NInventory> found = findEmptyWaterContainerIn(gui.getInventory(), alias);
        if (found != null) return found;

        NEquipory equip = NUtils.getEquipment();
        if (equip != null) {
            for (NEquipory.Slots slot : NEquipory.Slots.values()) {
                WItem equipped = equip.quickslots[slot.idx];
                if (equipped != null && equipped.item.contents instanceof NInventory) {
                    found = findEmptyWaterContainerIn((NInventory) equipped.item.contents, alias);
                    if (found != null) return found;
                }
            }
        }

        return null;
    }

    private Pair<WItem, NInventory> findEmptyWaterContainerIn(NInventory inv, NAlias alias) {
        if (inv == null) return null;
        synchronized (inv.ui) {
            for (Widget w = inv.child; w != null; w = w.next) {
                if (!(w instanceof WItem)) continue;
                WItem witem = (WItem) w;
                if (!(witem.item instanceof NGItem)) continue;
                NGItem ngItem = (NGItem) witem.item;
                String name = ngItem.name();
                if (name != null && NParser.checkName(name, alias) && needsWaterRefill(ngItem)) {
                    return new Pair<>(witem, inv);
                }
            }
        }
        return null;
    }

    void refillItemInEquip(NGameUI gui, WItem item, Gob target) throws InterruptedException
    {
        if(NParser.isIt(target,new NAlias("barrel")))
        {
            if(!NUtils.barrelHasContent(target) || !NParser.checkName(NUtils.getContentsOfBarrel(target), "water")) {
                return;
            }
        }
        if(item!=null && item.item instanceof NGItem && NParser.checkName(((NGItem)item.item).name(), new NAlias("Waterskin", "Glass Jug"))) {
            NGItem ngItem = ((NGItem) item.item);
            if (needsWaterRefill(ngItem)) {
                NUtils.takeItemToHand(item);
                fillHeldWaterContainer(gui, target);
                NUtils.getEquipment().wdgmsg("drop", -1);
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        return NUtils.getGameUI().vhand == null;
                    }
                });
            }
        }
    }

    void refillBucketInHand(NGameUI gui, WItem item, Gob target) throws InterruptedException
    {
        if(target == null) return;
        if(NParser.isIt(target,new NAlias("barrel")))
        {
            if(!NUtils.barrelHasContent(target) || !NParser.checkName(NUtils.getContentsOfBarrel(target), "water")) {
                return;
            }
        }
        if(item!=null && item.item instanceof NGItem && NParser.checkName(((NGItem)item.item).name(), "Bucket")) {
            NGItem ngItem = ((NGItem) item.item);
            if (needsWaterRefill(ngItem, BUCKET_FULL_LITERS)) {
                NUtils.takeItemToHand(item);
                fillHeldWaterContainer(gui, target, BUCKET_FULL_LITERS);
                NUtils.getEquipment().wdgmsg("drop", -1);
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        return NUtils.getGameUI().vhand == null;
                    }
                });
            }
        }
    }


    public static boolean checkIfNeed() throws InterruptedException {
        boolean hasWaterskin = false;
        boolean hasWaterInWaterskin = false;
        
        WItem wbelt = NUtils.getEquipment().findItem(NEquipory.Slots.BELT.idx);
        if (wbelt != null) {
            if (wbelt.item.contents instanceof NInventory) {
                ArrayList<WItem> witems = ((NInventory) wbelt.item.contents).getItems(new NAlias("Waterskin"));
                if (!witems.isEmpty()) {
                    hasWaterskin = true;
                    for (WItem item : witems) {
                        NGItem ngItem = ((NGItem) item.item);
                        if (!ngItem.content().isEmpty()) {
                            if (ngItem.content().get(0).name().contains("Water")) {
                                hasWaterInWaterskin = true;
                                break;
                            }
                        }
                    }
                }
            }
        }
        
        // Check buckets in hands
        boolean hasBucket = false;
        boolean hasWaterInBucket = false;
        WItem bucket = NUtils.getEquipment().findBucket("Water");
        if (bucket != null) {
            hasBucket = true;
            NGItem ngItem = ((NGItem) bucket.item);
            if (!ngItem.content().isEmpty() && ngItem.content().get(0).name().contains("Water")) {
                hasWaterInBucket = true;
            }
        }
        
        // Need refill if we have containers but none of them have water
        if (hasWaterskin || hasBucket) {
            return !hasWaterInWaterskin && !hasWaterInBucket;
        }
        return false;
    }
}
