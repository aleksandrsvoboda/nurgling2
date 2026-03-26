package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.tasks.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.NEquipory;

import java.util.ArrayList;

public class SkisBot implements Action {

    private static final NAlias SKIS_GOB_ALIAS = new NAlias("skis-wilderness");
    private static final double PICKUP_RANGE = 55.0;
    private static final double MOUNT_RANGE = 66.0;
    private static final Coord SKIS_INV_SIZE = new Coord(2, 3);

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null)
            return Results.ERROR("Player not found.");

        if (isPlayerOnSkis(gui))
            return dismount(gui);
        else
            return mount(gui);
    }

    private boolean isPlayerOnSkis(NGameUI gui) {
        Gob player = NUtils.player();
        if (player == null) return false;

        Following following = player.getattr(Following.class);
        if (following != null) {
            Gob mount = gui.ui.sess.glob.oc.getgob(following.tgt);
            if (mount != null && mount.ngob != null && mount.ngob.name != null) {
                return NParser.checkName(mount.ngob.name, SKIS_GOB_ALIAS);
            }
        }
        return false;
    }

    private Results dismount(NGameUI gui) throws InterruptedException {
        Gob skisGob = Finder.findGob(NUtils.player().rc, SKIS_GOB_ALIAS, null, PICKUP_RANGE);
        if (skisGob == null)
            return Results.ERROR("No Skis found nearby.");

        Results flowerResult = new SelectFlowerAction("Pick up", skisGob).run(gui);
        if (!flowerResult.IsSuccess())
            return Results.ERROR("Failed to pick up Skis.");

        // Wait for item to arrive with sprite loaded (hand or equipment)
        NTask waitPickup = new NTask() {
            { maxCounter = 200; infinite = false; }
            @Override
            public boolean check() {
                if (NUtils.getGameUI().vhand != null && NUtils.getGameUI().vhand.item.spr != null)
                    return true;
                NEquipory eq = NUtils.getEquipment();
                if (eq != null) {
                    for (WItem slot : eq.quickslots) {
                        if (slot != null) {
                            String name = ((NGItem) slot.item).name();
                            if (name != null && name.endsWith("Wilderness Skis"))
                                return true;
                        }
                    }
                }
                return false;
            }
        };
        NUtils.addTask(waitPickup);
        if (waitPickup.criticalExit)
            return Results.ERROR("Timed out picking up Skis.");

        // If item went to equipment, done
        if (gui.vhand == null)
            return Results.SUCCESS();

        // Item in hand — drop to inventory
        int freeSlots = gui.getInventory().getNumberFreeCoord(SKIS_INV_SIZE);
        if (freeSlots <= 0)
            return Results.ERROR("No inventory space for Skis (needs 2x3).");
        NUtils.dropToInv();
        NUtils.addTask(new WaitFreeHand());

        return Results.SUCCESS();
    }

    private Results mount(NGameUI gui) throws InterruptedException {
        WItem skisItem = findSkisItem(gui);

        if (skisItem != null) {
            NUtils.drop(skisItem);

            NTask waitGob = new NTask() {
                { maxCounter = 100; infinite = false; }
                @Override
                public boolean check() {
                    synchronized (NUtils.getGameUI().ui.sess.glob.oc) {
                        for (Gob gob : NUtils.getGameUI().ui.sess.glob.oc) {
                            if (gob.ngob != null && gob.ngob.name != null
                                && NParser.checkName(gob.ngob.name, SKIS_GOB_ALIAS)
                                && gob.rc.dist(NUtils.player().rc) < MOUNT_RANGE) {
                                return true;
                            }
                        }
                    }
                    return false;
                }
            };
            NUtils.addTask(waitGob);
            if (waitGob.criticalExit)
                return Results.ERROR("Could not find dropped Skis in world.");
        }

        Gob skisGob = Finder.findGob(NUtils.player().rc, SKIS_GOB_ALIAS, null, MOUNT_RANGE);

        if (skisGob == null) {
            if (skisItem == null)
                return Results.ERROR("No Skis in inventory and no mountable Skis nearby.");
            return Results.ERROR("Could not find dropped Skis in world.");
        }

        ResDrawable rd = skisGob.getattr(ResDrawable.class);
        if (rd != null && rd.sdt != null && rd.sdt.rbuf.length > 0 && rd.sdt.rbuf[0] != 0)
            return Results.ERROR("Skis are not mountable (state: " + rd.sdt.rbuf[0] + ").");

        Results flowerResult = new SelectFlowerAction("Ski off", skisGob).run(gui);
        if (!flowerResult.IsSuccess())
            return Results.ERROR("Failed to mount Skis.");

        return Results.SUCCESS();
    }

    private WItem findSkisItem(NGameUI gui) throws InterruptedException {
        ArrayList<WItem> invSkis = gui.getInventory().getItems("Wilderness Skis");
        if (!invSkis.isEmpty())
            return invSkis.get(0);

        NEquipory eq = NUtils.getEquipment();
        if (eq != null) {
            WItem equipped = eq.findItem("Wilderness Skis");
            if (equipped != null)
                return equipped;
        }

        return null;
    }
}
