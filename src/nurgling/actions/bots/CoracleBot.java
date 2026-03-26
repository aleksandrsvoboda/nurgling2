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

public class CoracleBot implements Action {

    private static final NAlias CORACLE_GOB_ALIAS = new NAlias("coracle");
    private static final double PICKUP_RANGE = 55.0;
    private static final double MOUNT_RANGE = 66.0;
    private static final Coord CORACLE_INV_SIZE = new Coord(4, 3);

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null)
            return Results.ERROR("Player not found.");

        if (isPlayerInCoracle(gui))
            return dismount(gui);
        else
            return mount(gui);
    }

    private boolean isPlayerInCoracle(NGameUI gui) {
        Gob player = NUtils.player();
        if (player == null) return false;

        Following following = player.getattr(Following.class);
        if (following != null) {
            Gob mount = gui.ui.sess.glob.oc.getgob(following.tgt);
            if (mount != null && mount.ngob != null && mount.ngob.name != null) {
                return NParser.checkName(mount.ngob.name, CORACLE_GOB_ALIAS);
            }
        }
        return false;
    }

    private Results dismount(NGameUI gui) throws InterruptedException {
        Gob coracleGob = Finder.findGob(NUtils.player().rc, CORACLE_GOB_ALIAS, null, PICKUP_RANGE);
        if (coracleGob == null)
            return Results.ERROR("No Coracle found nearby.");

        if (isSurroundedByDeepWater(gui))
            return Results.ERROR("Surrounded by Deep Water! Get closer to Shallow Water or Land.");

        Results flowerResult = new SelectFlowerAction("Pick up", coracleGob).run(gui);
        if (!flowerResult.IsSuccess())
            return Results.ERROR("Failed to pick up Coracle.");

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
                            if (name != null && name.endsWith("Coracle"))
                                return true;
                        }
                    }
                }
                return false;
            }
        };
        NUtils.addTask(waitPickup);
        if (waitPickup.criticalExit)
            return Results.ERROR("Timed out picking up Coracle.");

        // If item went to equipment, done
        if (gui.vhand == null)
            return Results.SUCCESS();

        // Item in hand — drop to inventory
        int freeSlots = gui.getInventory().getNumberFreeCoord(CORACLE_INV_SIZE);
        if (freeSlots <= 0)
            return Results.ERROR("No inventory space for Coracle (needs 4x3).");
        NUtils.dropToInv();
        NUtils.addTask(new WaitFreeHand());

        return Results.SUCCESS();
    }

    private Results mount(NGameUI gui) throws InterruptedException {
        WItem coracleItem = findCoracleItem(gui);

        if (coracleItem != null) {
            if (!isOnValidWaterTile(gui))
                return Results.ERROR("Must be in Shallow Water or Bog to drop Coracle.");

            if (isOnDeepWater(gui))
                return Results.ERROR("Can't drop Coracle in Deep Water!");

            NUtils.drop(coracleItem);

            NTask waitGob = new NTask() {
                { maxCounter = 100; infinite = false; }
                @Override
                public boolean check() {
                    synchronized (NUtils.getGameUI().ui.sess.glob.oc) {
                        for (Gob gob : NUtils.getGameUI().ui.sess.glob.oc) {
                            if (gob.ngob != null && gob.ngob.name != null
                                && NParser.checkName(gob.ngob.name, CORACLE_GOB_ALIAS)
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
                return Results.ERROR("Could not find dropped Coracle in world.");
        }

        Gob coracleGob = Finder.findGob(NUtils.player().rc, CORACLE_GOB_ALIAS, null, MOUNT_RANGE);

        if (coracleGob == null) {
            if (coracleItem == null)
                return Results.ERROR("No Coracle in inventory and no mountable Coracle nearby.");
            return Results.ERROR("Could not find dropped Coracle in world.");
        }

        ResDrawable rd = coracleGob.getattr(ResDrawable.class);
        if (rd != null && rd.sdt != null && rd.sdt.rbuf.length > 0 && rd.sdt.rbuf[0] != 22)
            return Results.ERROR("Coracle is not mountable (state: " + rd.sdt.rbuf[0] + ").");

        Results flowerResult = new SelectFlowerAction("Into the blue yonder!", coracleGob).run(gui);
        if (!flowerResult.IsSuccess())
            return Results.ERROR("Failed to board Coracle.");

        return Results.SUCCESS();
    }

    private WItem findCoracleItem(NGameUI gui) throws InterruptedException {
        ArrayList<WItem> invCoracles = gui.getInventory().getItems("Coracle");
        if (!invCoracles.isEmpty())
            return invCoracles.get(0);

        NEquipory eq = NUtils.getEquipment();
        if (eq != null) {
            WItem equipped = eq.findItem("Coracle");
            if (equipped != null)
                return equipped;
        }

        return null;
    }

    private boolean isSurroundedByDeepWater(NGameUI gui) {
        MCache map = gui.ui.sess.glob.map;
        Coord playerTile = NUtils.player().rc.div(MCache.tilesz).floor();

        int[][] offsets = {
            {-1, -1}, {0, -1}, {1, -1},
            {-1,  0},          {1,  0},
            {-1,  1}, {0,  1}, {1,  1}
        };

        for (int[] offset : offsets) {
            Coord checkTile = playerTile.add(new Coord(offset[0], offset[1]));
            String tileName = map.tilesetname(map.gettile(checkTile));
            if (tileName == null || !tileName.contains("deep"))
                return false;
        }
        return true;
    }

    private boolean isOnDeepWater(NGameUI gui) {
        MCache map = gui.ui.sess.glob.map;
        Coord playerTile = NUtils.player().rc.div(MCache.tilesz).floor();
        String tileName = map.tilesetname(map.gettile(playerTile));
        return tileName != null && tileName.contains("deep");
    }

    private boolean isOnValidWaterTile(NGameUI gui) {
        MCache map = gui.ui.sess.glob.map;
        Coord playerTile = NUtils.player().rc.div(MCache.tilesz).floor();
        String tileName = map.tilesetname(map.gettile(playerTile));
        if (tileName == null) return false;

        return tileName.contains("water") ||
               tileName.contains("bog") ||
               tileName.contains("fen") ||
               tileName.contains("swamp");
    }
}
