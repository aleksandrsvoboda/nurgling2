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
            // ensurePresence=true: the next step needs gobs actually streamed in, not just reachable.
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

        // Refill every empty Waterskin/Glass Jug the character carries; re-queried each iteration since a stale WItem reference can go invalid after a refill.
        while (true) {
            Pair<WItem, NInventory> found = findEmptyWaterContainer(gui);
            if (found == null) break;

            NUtils.takeItemToHand(found.a);

            boolean progressed = fillHeldWaterContainer(gui, target);

            // Belt isn't a regular grid inventory - dropToInv can't place into it reliably, so use transferToBelt() there instead.
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
                // Source is dry - every other under-filled container would hit the same wall.
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

    // A full Waterskin/Glass Jug holds 3l; a full Bucket holds 10l.
    private static final double WATERSKIN_FULL_LITERS = 3.0;
    private static final double BUCKET_FULL_LITERS = 10.0;
    private static final double LITERS_EPSILON = 0.01;

    private static final Pattern LITERS_PATTERN = Pattern.compile("([\\d.]+)\\s*l\\b", Pattern.CASE_INSENSITIVE);
    private static final long FILL_CLICK_TIMEOUT_MS = 3000;

    /** {@link #needsWaterRefill(NGItem, double)} for a Waterskin/Glass Jug specifically. */
    private boolean needsWaterRefill(NGItem ngItem) {
        return needsWaterRefill(ngItem, WATERSKIN_FULL_LITERS);
    }

    /** True if this container is empty or holds less than fullLiters of water; non-water contents are left alone. */
    private boolean needsWaterRefill(NGItem ngItem, double fullLiters) {
        return parseWaterLiters(ngItem) < fullLiters - LITERS_EPSILON;
    }

    /** How much water this container currently holds, in liters; 0 if empty or holding something else. */
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

    /** Clicks the held container against target until topped up; a partially-full container needs 2 clicks since itemact toggles it (dump, then fill). Returns false only once the source itself is dry. */
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
            // Bounded wait: a dry source never changes the held item's content, so this must time
            // out rather than block the bot thread forever - the progress check below already
            // handles a no-op click correctly.
            long deadline = System.currentTimeMillis() + FILL_CLICK_TIMEOUT_MS;
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    if (System.currentTimeMillis() > deadline) return true;
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

    /** Finds one empty Waterskin/Glass Jug anywhere the character carries one, with its NInventory; walks widget chains directly (not NInventory.getItems) so an item with unresolved name() is skipped, not blocked on. */
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
