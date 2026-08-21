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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CoracleBot implements Action {

    private static final NAlias CORACLE_GOB_ALIAS = new NAlias("coracle");
    private static final double PICKUP_RANGE = 55.0;
    private static final double MOUNT_RANGE = 66.0;
    private static final Coord CORACLE_INV_SIZE = new Coord(4, 3);

    // Open water too deep to launch into or to pick the coracle back up from.
    // Matched exactly: substring matching on "deep" also hits the unrelated
    // land tiles gfx/tiles/deeptangle and gfx/tiles/deepcave.
    private static final Set<String> DEEP_WATER_TILES = new HashSet<>(Arrays.asList(
            "gfx/tiles/deep",      // Deep Water
            "gfx/tiles/odeep",     // Deep Ocean
            "gfx/tiles/odeeper"    // High Seas
    ));

    // Shallow open water the coracle can be launched into.
    private static final Set<String> SHALLOW_WATER_TILES = new HashSet<>(Arrays.asList(
            "gfx/tiles/water",     // Shallow Water
            "gfx/tiles/owater"     // Shallow Ocean
    ));

    // Wetland biomes the coracle can be launched into. Each biome ships as a
    // pair - the biome tile plus a "<biome>water" pool tile - so match by
    // prefix: bog/bogwater, fen/fenwater, swamp/swampwater, marsh/marshwater.
    private static final String[] WETLAND_TILE_PREFIXES = {
            "gfx/tiles/bog",
            "gfx/tiles/fen",
            "gfx/tiles/swamp",
            "gfx/tiles/marsh"
    };

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
            String tileName = tileNameAt(gui, playerTile());

            if (isDeepWaterTile(tileName))
                return Results.ERROR("Can't drop Coracle in Deep Water!");

            if (!isBoatableTile(tileName))
                return Results.ERROR("Must be in Shallow Water or a wetland to drop Coracle (tile: " + tileName + ").");

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

    private Coord playerTile() {
        return NUtils.player().rc.div(MCache.tilesz).floor();
    }

    private String tileNameAt(NGameUI gui, Coord tile) {
        MCache map = gui.ui.sess.glob.map;
        return map.tilesetname(map.gettile(tile));
    }

    private boolean isSurroundedByDeepWater(NGameUI gui) {
        Coord playerTile = playerTile();

        int[][] offsets = {
            {-1, -1}, {0, -1}, {1, -1},
            {-1,  0},          {1,  0},
            {-1,  1}, {0,  1}, {1,  1}
        };

        for (int[] offset : offsets) {
            Coord checkTile = playerTile.add(new Coord(offset[0], offset[1]));
            if (!isDeepWaterTile(tileNameAt(gui, checkTile)))
                return false;
        }
        return true;
    }

    private static boolean isDeepWaterTile(String tileName) {
        return tileName != null && DEEP_WATER_TILES.contains(tileName);
    }

    /**
     * Whether a coracle can be launched on this tile: shallow open water, or
     * any wetland biome. Deep water is excluded and reported separately.
     */
    private static boolean isBoatableTile(String tileName) {
        if (tileName == null || DEEP_WATER_TILES.contains(tileName))
            return false;
        if (SHALLOW_WATER_TILES.contains(tileName))
            return true;
        for (String prefix : WETLAND_TILE_PREFIXES) {
            if (tileName.startsWith(prefix))
                return true;
        }
        // Wetland biomes added later still ship their pools as "<biome>water",
        // so accept those even when the biome itself isn't listed above.
        return tileName.startsWith("gfx/tiles/") && tileName.endsWith("water");
    }
}
