package nurgling.actions.bots.boatfishing;

import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Equip;
import nurgling.actions.EquipFromInventory;
import nurgling.actions.Results;
import nurgling.conf.NBoatFishingSettings;
import nurgling.tools.NAlias;
import nurgling.widgets.NEquipory;

/** Puts the rod in hand and attaches whatever it lacks: line, hook, and the lure (casting rod) or bait (pole).
 *  Parts come from the creels or the inventory; bait only from the inventory, where the pole re-baits from. */
final class RigRod implements Action {
    private final NBoatFishingSettings prop;

    RigRod(NBoatFishingSettings prop) {
        this.prop = prop;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Tackle.emptyHand(null);
        if (NUtils.getGameUI().vhand != null)
            return Results.ERROR("Boat Fisher: no room for the item in hand");
        WItem rod = rodInHand(prop.tool);
        if (rod == null) {
            new Equip(new NAlias(prop.tool)).run(gui);
            rod = rodInHand(prop.tool);
        }
        if (rod == null && gui.getInventory().getItem(new NAlias(prop.tool)) != null) {
            new EquipFromInventory(new NAlias(prop.tool)).run(gui);
            rod = rodInHand(prop.tool);
        }
        if (rod == null)
            return Results.ERROR("Boat Fisher: no " + prop.tool + " in hands, belt or inventory");

        for (Part part : Part.of(prop)) {
            if (part.onRod((NGItem) rod.item))
                continue;
            Results r = attach(gui, rod, part);
            if (!r.IsSuccess())
                return r;
            rod = rodInHand(prop.tool);
            if (rod == null)
                return Results.ERROR("Boat Fisher: the rod left the hand while rigging");
        }
        return Results.SUCCESS();
    }

    /** The first part the rod lacks, or null when fully rigged. UI-safe. */
    static String missingPart(NBoatFishingSettings prop) {
        WItem rod = rodInHandNow(prop.tool);
        if (rod == null)
            return prop.tool;
        for (Part part : Part.of(prop))
            if (!part.onRod((NGItem) rod.item))
                return part.label;
        return null;
    }

    private Results attach(NGameUI gui, WItem rod, Part part) throws InterruptedException {
        if (part.names.isEmpty() || part.names.get(0) == null)
            return Results.ERROR("Boat Fisher: no item list for " + part.label);
        Tackle.Found src;
        if (part.inventoryOnly) {
            WItem w = gui.getInventory().getItem(part.alias());
            src = w == null ? null : new Tackle.Found(w, gui.getInventory());
        } else {
            src = Tackle.find(part.alias());
        }
        if (src == null)
            return Results.ERROR("Boat Fisher: no " + part.label + (part.inventoryOnly ? " in the inventory" : " in creels or inventory"));
        System.out.println("[BoatFisher] attaching " + ((NGItem) src.item.item).name() + " (" + part.label + ")");
        NUtils.takeItemToHand(src.item);
        WItem inHand = NUtils.getGameUI().vhand;
        NUtils.itemact(rod);
        Waits.until(() -> NUtils.getGameUI().vhand != inHand || part.onRod((NGItem) rod.item), 10_000);
        if (NUtils.getGameUI().vhand != null) {
            // The rest of a stack, or whatever the rod gave back.
            Waits.until(() -> NUtils.getGameUI().vhand == null || NUtils.getGameUI().vhand.item.spr != null, 5_000);
            if (!Tackle.emptyHand(src.from))
                return Results.ERROR("Boat Fisher: no room to put the " + part.label + " back");
        }
        WItem now = rodInHand(prop.tool);
        if (now == null || !part.onRod((NGItem) now.item))
            return Results.ERROR("Boat Fisher: couldn't attach " + part.label + " to the rod");
        return Results.SUCCESS();
    }

    private static WItem rodInHand(String tool) throws InterruptedException {
        NEquipory eq = NUtils.getEquipment();
        for (NEquipory.Slots s : new NEquipory.Slots[]{NEquipory.Slots.HAND_LEFT, NEquipory.Slots.HAND_RIGHT}) {
            WItem w = eq.findItem(s.idx);
            if (w != null && tool.equals(((NGItem) w.item).name()))
                return w;
        }
        return null;
    }

    private static WItem rodInHandNow(String tool) {
        NEquipory eq = NUtils.getEquipment();
        if (eq == null)
            return null;
        for (NEquipory.Slots s : new NEquipory.Slots[]{NEquipory.Slots.HAND_LEFT, NEquipory.Slots.HAND_RIGHT}) {
            WItem w = eq.quickslots[s.idx];
            if (w != null && tool.equals(((NGItem) w.item).name()))
                return w;
        }
        return null;
    }
}
