package nurgling.actions.bots;

import haven.*;
import nurgling.NFlowerMenu;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.actions.TransferItems2;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.NFlowerMenuIsClosed;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitCollectState;
import nurgling.tasks.WaitPose;
import nurgling.tools.Finder;
import nurgling.tools.HarvestState;
import nurgling.tools.NAlias;
import nurgling.tools.VSpec;
import nurgling.widgets.Specialisation;

import java.util.*;

import static haven.OCache.posres;

/**
 * Leaf picker that needs no input, so it also works as a scenario step. Picks every tree and bush
 * in the nearest "Trees" area that shows leaves and carries them to their PUT areas, as many trips
 * as it takes.
 * <p>
 * Copied from the Leaf picker (CollectLeaf / CollectFromTreeBot), which asks for its areas and is
 * left as it is. The single pick is modelled on CollectFromGob; check fixes in one against the other.
 * Which trees still have leaves is read from the same live model state the tree harvest overlay
 * shows (HarvestState), so bare trees are never walked to or clicked.
 */
public class CollectLeafZone implements Action {
    private static final String PICK = "Pick leaf";
    private static final Coord LEAF_SIZE = new Coord(1, 1);
    private static final NAlias PLANTS = new NAlias("gfx/terobjs/trees", "gfx/terobjs/bushes");

    private enum Pick { BARE, FULL, SKIP }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);
        NArea area = context.findArea(Specialisation.SpecName.trees);
        if (area == null)
            return Results.ERROR("No Trees area found");

        // Trees that are bare, or that could not be picked (no path, no "Pick leaf"); never retried.
        Set<Long> done = new HashSet<>();
        Set<String> leaves = new HashSet<>();
        Set<String> noZone = new TreeSet<>();

        while (true) {
            Pair<Coord2d, Coord2d> rc = reach(area);
            if (rc == null)
                return Results.ERROR("Can't reach the Trees area");

            Map<Gob, String> trees = pickable(area, done);
            if (trees.isEmpty()) {
                // Trees far from the corner we arrived at may not be loaded yet.
                walkToCenter(gui, rc);
                trees = pickable(area, done);
            }
            for (Iterator<String> it = trees.values().iterator(); it.hasNext(); ) {
                if (!acceptLeaf(gui, context, it.next(), leaves, noZone))
                    it.remove();
            }
            if (trees.isEmpty())
                break;

            while (!trees.isEmpty()) {
                Gob tree = nearest(trees.keySet());
                trees.remove(tree);
                Pick result = pick(gui, tree);
                if (result == Pick.FULL) {
                    if (!deliver(gui, context, leaves))
                        return Results.ERROR("Inventory is still full after delivering the leaves. Is there room in the PUT areas for " + String.join(", ", leaves) + "?");
                    break;
                }
                done.add(tree.id);
            }
        }

        deliver(gui, context, leaves);
        if (leaves.isEmpty() && !noZone.isEmpty())
            return Results.ERROR("No PUT area for: " + String.join(", ", noZone));
        Set<String> left = carried(gui, leaves);
        if (!left.isEmpty())
            gui.msg("Leaf Picker (Zones): could not deliver " + String.join(", ", left));
        return Results.SUCCESS();
    }

    /**
     * Whether trees giving this leaf are worth picking, i.e. some PUT area takes it. The first
     * time a leaf has no area it is named once, and its trees are left alone from then on.
     */
    private boolean acceptLeaf(NGameUI gui, NContext context, String leaf, Set<String> leaves, Set<String> noZone)
            throws InterruptedException {
        if (leaves.contains(leaf))
            return true;
        if (noZone.contains(leaf))
            return false;
        // Any threshold will do here; each item is routed by its own quality on delivery.
        boolean zone = context.addOutItem(leaf, null, Double.MAX_VALUE);
        if (zone) {
            leaves.add(leaf);
        } else {
            noZone.add(leaf);
            gui.msg("Leaf Picker (Zones): no PUT area for " + leaf + ", skipping those trees");
        }
        return zone;
    }

    /**
     * Trees and bushes in the area that show leaves right now, with the leaf each one gives. Read
     * inside a task so a sprite that is still loading is retried instead of taken for bare.
     */
    private Map<Gob, String> pickable(NArea area, Set<Long> done) throws InterruptedException {
        ArrayList<Gob> gobs = Finder.findGobs(area, PLANTS);
        Map<Gob, String> res = new LinkedHashMap<>();
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                res.clear();
                for (Gob gob : gobs) {
                    if (done.contains(gob.id))
                        continue;
                    String leaf;
                    try {
                        leaf = leafShown(gob);
                    } catch (Loading l) {
                        return false;
                    }
                    if (leaf != null)
                        res.put(gob, leaf);
                }
                return true;
            }
        });
        return res;
    }

    /** The leaf a mature tree or bush is showing, or null when it shows none or gives none. */
    private static String leafShown(Gob gob) {
        String name = gob.ngob.name;
        if (!HarvestState.isTreeOrBushRes(name))
            return null;
        String leaf = leafProduct(name);
        if (leaf == null)
            return null;
        Drawable dr = gob.getattr(Drawable.class);
        if (!(dr instanceof ResDrawable))
            return null;
        ResDrawable d = (ResDrawable) dr;
        if (!HarvestState.isMatureTreeOrBush(gob, d))
            return null;
        return HarvestState.hasLeafBit(Sprite.decnum(d.sdt.clone())) ? leaf : null;
    }

    /** Same naming convention LpExplorer relies on: leaf products contain "Leaf" or "Leaves". */
    private static String leafProduct(String resName) {
        List<String> products = VSpec.object.get(resName);
        if (products == null)
            return null;
        for (String product : products) {
            if (product.contains("Leaf") || product.contains("Leaves"))
                return product;
        }
        return null;
    }

    /** Picks one tree until it is bare or the inventory is full. */
    private Pick pick(NGameUI gui, Gob tree) throws InterruptedException {
        if (gui.getInventory().getNumberFreeCoord(LEAF_SIZE) == 0)
            return Pick.FULL;
        if (!new PathFinder(tree).run(gui).IsSuccess())
            return Pick.SKIP;
        gui.map.wdgmsg("click", Coord.z, tree.rc.floor(posres), 3, 0, 1, (int) tree.id, tree.rc.floor(posres), 0, -1);
        NFlowerMenu fm = NUtils.findFlowerMenu();
        if (fm == null)
            return Pick.SKIP;
        if (!fm.hasOpt(PICK)) {
            fm.wdgmsg("cl", -1);
            NUtils.addTask(new NFlowerMenuIsClosed());
            return Pick.SKIP;
        }
        boolean chosen = fm.chooseOpt(PICK);
        NUtils.addTask(new NFlowerMenuIsClosed());
        if (!chosen)
            return Pick.SKIP;
        String pose = tree.ngob.name.startsWith("gfx/terobjs/bushes") ? "gfx/borka/bushpickan" : "gfx/borka/treepickan";
        NUtils.addTask(new WaitPose(NUtils.player(), pose));
        WaitCollectState wcs = new WaitCollectState(tree, LEAF_SIZE);
        NUtils.addTask(wcs);
        return (wcs.getState() == WaitCollectState.State.NOFREESPACE) ? Pick.FULL : Pick.BARE;
    }

    /**
     * Hands every carried leaf to its PUT area, each routed by its own quality.
     *
     * @return false when that left the inventory with no free cell, so another trip would not help.
     */
    private boolean deliver(NGameUI gui, NContext context, Set<String> leaves) throws InterruptedException {
        HashSet<String> targets = new HashSet<>();
        for (WItem item : gui.getInventory().getItems()) {
            NGItem ngi = (NGItem) item.item;
            String name = ngi.name();
            if (name == null || !leaves.contains(name))
                continue;
            if (context.addOutItem(name, null, ngi.quality != null ? ngi.quality : 1))
                targets.add(name);
        }
        if (!targets.isEmpty())
            new TransferItems2(context, targets).run(gui);
        return gui.getInventory().getNumberFreeCoord(LEAF_SIZE) > 0;
    }

    private static Set<String> carried(NGameUI gui, Set<String> leaves) throws InterruptedException {
        Set<String> res = new TreeSet<>();
        for (WItem item : gui.getInventory().getItems()) {
            String name = ((NGItem) item.item).name();
            if (name != null && leaves.contains(name))
                res.add(name);
        }
        return res;
    }

    /**
     * Walks onto the area so its trees are loaded, and returns its bounds, or null when it could not
     * be reached: an unloaded area must not be scanned, it looks exactly like a bare one.
     * Copied from SwillScan.reach.
     */
    private static Pair<Coord2d, Coord2d> reach(NArea area) throws InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (NUtils.navigateToArea(area, true)) {
                Pair<Coord2d, Coord2d> rc = area.getRCArea();
                if (rc != null)
                    return rc;
            }
        }
        return null;
    }

    private static void walkToCenter(NGameUI gui, Pair<Coord2d, Coord2d> rc) throws InterruptedException {
        Coord2d center = rc.a.add(rc.b).div(2);
        if (PathFinder.isAvailable(center))
            new PathFinder(center).run(gui);
    }

    private static Gob nearest(Collection<Gob> gobs) {
        Gob player = NUtils.player();
        Gob best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Gob gob : gobs) {
            double d = (player == null) ? 0 : gob.rc.dist(player.rc);
            if (best == null || d < bestDist) {
                best = gob;
                bestDist = d;
            }
        }
        return best;
    }
}
