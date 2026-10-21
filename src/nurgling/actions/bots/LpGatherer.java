package nurgling.actions.bots;

import haven.*;
import nurgling.NFlowerMenu;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.tasks.GetItems;
import nurgling.tasks.NFlowerMenuIsClosed;
import nurgling.tasks.NTask;
import nurgling.tools.Finder;
import nurgling.tools.HarvestState;
import nurgling.tools.LpExplorer;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.tools.VSpec;
import nurgling.widgets.NEquipory;

import java.util.*;

import static haven.OCache.posres;

/**
 * Collects one of every product the LP helper (LpExplorer) still marks undiscovered on the gobs
 * in view: walks to the nearest reachable one, does a single pick / saw / chop / chip, stops the
 * action as soon as the helper has recorded the discovery, and looks around again until nothing
 * in view has anything left to find. Each gathered item is dropped on the ground straight away.
 */
public class LpGatherer implements Action {
    private static final NAlias SOURCES = new NAlias("gfx/terobjs/trees", "gfx/terobjs/bushes", "gfx/terobjs/bumlings");

    private static final NAlias SAWS = new NAlias("Bonesaw", "Bone Saw", "Metal Saw");
    private static final NAlias AXES = new NAlias("Woodsman's Axe", "Stone Axe", "Metal Axe", "Butcher's cleaver",
            "Tinker's Throwing Axe", "Battle Axe of the Twelfth Bay");
    private static final NAlias CHIPPERS = new NAlias("Woodsman's Axe", "Stone Axe", "Metal Axe", "Butcher's cleaver",
            "Tinker's Throwing Axe", "Battle Axe of the Twelfth Bay", "Pickaxe");

    /** A gob that never got its product credited is given up on, and a product after this many such gobs. */
    private static final int MAX_FAILS_PER_PRODUCT = 2;
    /** The player standing idle this long after the petal was chosen means the action ended (or never began). */
    private static final long IDLE_MS = 2500;
    /** An item that arrived but was not credited within this long was not the product we were after. */
    private static final long CREDIT_GRACE_MS = 3000;
    private static final long ACTION_CAP_MS = 60_000;
    private static final long MENU_WAIT_MS = 5000;
    private static final int MENU_ATTEMPTS = 2;
    /** A walk ends beside the gob, not at its centre, so it can come in this much under the straight line. */
    private static final double REACH_SLACK = 2 * MCache.tilesz.x;

    private enum Kind {
        LEAF("leaf", null, new Coord(1, 1)),
        SEED("seed or fruit", null, new Coord(1, 1)),
        BOUGH("bough", null, new Coord(2, 1)),
        BARK("bark", null, new Coord(1, 1)),
        BOARD("board", SAWS, new Coord(4, 1)),
        BLOCK("block", AXES, new Coord(1, 2)),
        STONE("stone", CHIPPERS, new Coord(1, 1));

        final String label;
        final NAlias tool;
        final Coord size;

        Kind(String label, NAlias tool, Coord size) {
            this.label = label;
            this.tool = tool;
            this.size = size;
        }

        static Kind of(String gobResName, String product) {
            if (gobResName.startsWith("gfx/terobjs/bumlings"))
                return STONE;
            if (product.startsWith("Board of"))
                return BOARD;
            if (product.startsWith("Block of"))
                return BLOCK;
            if (LpExplorer.isLeafProduct(product))
                return LEAF;
            if (LpExplorer.isBoughProduct(product))
                return BOUGH;
            if (HarvestState.isBarkProductName(product))
                return BARK;
            return SEED;
        }

        /** The petal that yields this product, out of the ones the gob offers, or null when none does. */
        String petal(NFlowerMenu.NPetal[] petals, String product) {
            String known = knownPetal(petals);
            return known != null ? known : namedPetal(petals, product);
        }

        private String knownPetal(NFlowerMenu.NPetal[] petals) {
            for (NFlowerMenu.NPetal p : petals) {
                switch (this) {
                    case LEAF: if (p.name.equals("Pick leaf")) return p.name; break;
                    case BOUGH: if (p.name.equals("Take bough")) return p.name; break;
                    case BARK: if (p.name.equals("Take bark")) return p.name; break;
                    case BOARD: if (p.name.equals("Make boards")) return p.name; break;
                    case BLOCK: if (p.name.equals("Chop into blocks")) return p.name; break;
                    case STONE: if (p.name.equals("Chip stone")) return p.name; break;
                    // No bot picks tree fruit yet, so its exact petal text is unconfirmed.
                    case SEED: if (p.name.startsWith("Pick ") && !p.name.equals("Pick leaf")) return p.name; break;
                }
            }
            return null;
        }

        /**
         * A gathering petal that names the product, for products whose petal is not one of the
         * known ones (e.g. "Cork" from a "Take cork"). Matched on the product's last word without
         * its plural ending; only Pick/Take petals, so nothing destructive like "Chop" is chosen.
         */
        private static String namedPetal(NFlowerMenu.NPetal[] petals, String product) {
            String[] words = product.toLowerCase(Locale.ROOT).split("\\s+");
            String key = words[words.length - 1].replaceAll("(es|s)$", "");
            if (key.length() < 3)
                return null;
            for (NFlowerMenu.NPetal p : petals) {
                String name = p.name.toLowerCase(Locale.ROOT);
                if ((name.startsWith("pick ") || name.startsWith("take ")) && name.contains(key))
                    return p.name;
            }
            return null;
        }
    }

    private enum Outcome { DISCOVERED, GONE, NO_MENU, NO_PETAL, IDLE, NOT_CREDITED, TIMEOUT }

    private static class Target {
        final Gob gob;
        final String product;
        final Kind kind;

        Target(Gob gob, String product, Kind kind) {
            this.gob = gob;
            this.product = product;
            this.kind = kind;
        }
    }

    private final Set<Long> unreachable = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private final Map<String, Integer> failsPerProduct = new HashMap<>();
    private final Set<String> skipped = new TreeSet<>();
    private final List<String> found = new ArrayList<>();
    /** Why each product that was tried and not credited failed, for the closing summary. */
    private final Map<String, String> problems = new TreeMap<>();
    /** The petals the last NO_PETAL menu offered. */
    private List<String> offered = Collections.emptyList();

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // The bot menu reuses one instance, so every run starts from a clean slate.
        unreachable.clear();
        failed.clear();
        failsPerProduct.clear();
        found.clear();
        problems.clear();

        while (true) {
            skipped.clear();
            Target target = nextTarget(gui);
            if (target == null)
                break;

            if (!new RestoreResources().run(gui).IsSuccess())
                return Results.ERROR("Failed to restore resources");
            if (!new PathFinder(target.gob).run(gui).IsSuccess()) {
                unreachable.add(target.gob.id);
                continue;
            }
            if (target.kind.tool != null) {
                new Equip(target.kind.tool).run(gui);
                if (!inHands(target.kind.tool)) {
                    fail(target, "could not equip a tool for it");
                    continue;
                }
            }

            Outcome outcome = harvest(gui, target);
            switch (outcome) {
                case DISCOVERED: found.add(target.product); problems.remove(target.product); break;
                case GONE: break;
                case NO_MENU: fail(target, "no menu opened on " + target.gob.ngob.name); break;
                case NO_PETAL: fail(target, "no matching menu option on " + target.gob.ngob.name + ", it offered " + offered); break;
                case IDLE: fail(target, "the action stopped without giving it"); break;
                case NOT_CREDITED: fail(target, "an item came but the LP helper did not count it"); break;
                case TIMEOUT: fail(target, "timed out"); break;
            }
        }

        String summary = found.isEmpty() ? "nothing new found" : "found " + String.join(", ", found);
        if (!skipped.isEmpty())
            summary += ". Skipped: " + String.join("; ", skipped);
        if (!problems.isEmpty()) {
            List<String> lines = new ArrayList<>();
            problems.forEach((product, why) -> lines.add(product + ": " + why));
            summary += ". Failed: " + String.join("; ", lines);
        }
        gui.msg("LP Gatherer: " + summary);
        return Results.SUCCESS();
    }

    /**
     * The gob in view with an undiscovered product we can still collect that is the shortest walk
     * away. Products we lack a tool or inventory room for are named in {@link #skipped}.
     * <p>
     * Gobs are taken in straight-line order and each one's walk is planned; a walk is never
     * shorter than the straight line (less the reach to the gob's edge), so the search ends at the
     * first gob whose straight line is already longer than the best walk, and only the nearest few
     * get a path planned.
     */
    private Target nextTarget(NGameUI gui) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null)
            return null;
        ArrayList<Gob> gobs = Finder.findGobs(player.rc, SOURCES, null, Double.MAX_VALUE);
        gobs.removeIf(g -> g.ngob == null || !VSpec.object.containsKey(g.ngob.name) || unreachable.contains(g.id));
        Map<Gob, List<String>> products = undiscovered(gobs);
        gobs.removeIf(g -> !products.containsKey(g));
        gobs.sort(NUtils.d_comp);

        Map<Kind, Boolean> doable = new EnumMap<>(Kind.class);
        Target best = null;
        double bestWalk = Double.POSITIVE_INFINITY;
        for (Gob gob : gobs) {
            if (gob.rc.dist(player.rc) - REACH_SLACK >= bestWalk)
                break;
            Target target = null;
            for (String product : products.get(gob)) {
                if (failed.contains(failKey(gob, product)) || failsPerProduct.getOrDefault(product, 0) >= MAX_FAILS_PER_PRODUCT)
                    continue;
                Kind kind = Kind.of(gob.ngob.name, product);
                Boolean ok = doable.get(kind);
                if (ok == null)
                    doable.put(kind, ok = canDo(gui, kind));
                if (ok) {
                    target = new Target(gob, product, kind);
                    break;
                }
            }
            if (target == null)
                continue;
            double walk = PathFinder.walkLength(gob);
            if (walk == Double.POSITIVE_INFINITY) {
                unreachable.add(gob.id);
            } else if (walk < bestWalk) {
                best = target;
                bestWalk = walk;
            }
        }
        return best;
    }

    /** Each gob's undiscovered products, read inside a task so a sprite still loading is retried. */
    private static Map<Gob, List<String>> undiscovered(List<Gob> gobs) throws InterruptedException {
        Map<Gob, List<String>> res = new HashMap<>();
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                res.clear();
                for (Gob gob : gobs) {
                    List<String> products;
                    try {
                        products = LpExplorer.allUndiscoveredProducts(gob);
                    } catch (Loading l) {
                        return false;
                    }
                    if (!products.isEmpty())
                        res.put(gob, products);
                }
                return true;
            }
        });
        return res;
    }

    private boolean canDo(NGameUI gui, Kind kind) throws InterruptedException {
        if (kind.tool != null && !hasTool(kind.tool)) {
            skipped.add(kind.label + " (no tool in hands or on the belt)");
            return false;
        }
        if (gui.getInventory().getNumberFreeCoord(kind.size) == 0) {
            skipped.add(kind.label + " (no room in the inventory)");
            return false;
        }
        return true;
    }

    /**
     * Right-clicks the gob, chooses the petal for its product, waits for the product to be
     * credited, then drops whatever the action put in the inventory.
     */
    private Outcome harvest(NGameUI gui, Target target) throws InterruptedException {
        Gob gob = target.gob;
        Set<WItem> before = new HashSet<>(gui.getInventory().getItems());
        NFlowerMenu fm = openMenu(gui, gob);
        if (fm == null)
            return Outcome.NO_MENU;
        String petal = target.kind.petal(fm.nopts, target.product);
        if (petal == null) {
            offered = new ArrayList<>();
            for (NFlowerMenu.NPetal p : fm.nopts)
                offered.add(p.name);
            fm.wdgmsg("cl", -1);
            NUtils.addTask(new NFlowerMenuIsClosed());
            return Outcome.NO_PETAL;
        }
        fm.chooseOpt(petal);
        NUtils.addTask(new NFlowerMenuIsClosed());

        WaitCredit wait = new WaitCredit(gui, gob, target.product);
        NUtils.addTask(wait);
        stopAction(gui);
        dropNew(gui, before);
        return wait.outcome;
    }

    /**
     * Right-clicks the gob and waits for its flower menu, clicking once more if none comes. A
     * menu still open from an earlier click is closed first: one that arrives late would
     * otherwise be taken for this gob's, and choosing from it walks back to the earlier gob.
     */
    private static NFlowerMenu openMenu(NGameUI gui, Gob gob) throws InterruptedException {
        for (int attempt = 0; attempt < MENU_ATTEMPTS; attempt++) {
            closeMenu();
            gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 3, 0, 1, (int) gob.id, gob.rc.floor(posres), 0, -1);
            NFlowerMenu fm = awaitMenu();
            if (fm != null)
                return fm;
        }
        return null;
    }

    /** The flower menu once it shows, or null after MENU_WAIT_MS; timed by the clock, not by ticks, so lag can't cut it short. */
    private static NFlowerMenu awaitMenu() throws InterruptedException {
        long since = System.currentTimeMillis();
        NFlowerMenu[] res = {null};
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                NFlowerMenu fm = (NFlowerMenu) NUtils.getUI().findInRoot(NFlowerMenu.class);
                if (fm != null && fm.nopts != null && fm.nopts.length > 0) {
                    res[0] = fm;
                    return true;
                }
                return System.currentTimeMillis() - since > MENU_WAIT_MS;
            }
        });
        return res[0];
    }

    private static void closeMenu() throws InterruptedException {
        NFlowerMenu fm = (NFlowerMenu) NUtils.getUI().findInRoot(NFlowerMenu.class);
        if (fm == null)
            return;
        fm.wdgmsg("cl", -1);
        NUtils.addTask(new NFlowerMenuIsClosed());
    }

    /** Drops every inventory item that was not there before, one at a time, waiting for each to leave. */
    private static void dropNew(NGameUI gui, Set<WItem> before) throws InterruptedException {
        for (WItem item : gui.getInventory().getItems()) {
            if (before.contains(item))
                continue;
            NUtils.drop(item);
            long since = System.currentTimeMillis();
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    if (System.currentTimeMillis() - since > IDLE_MS)
                        return true;
                    GetItems gi = new GetItems(gui.getInventory());
                    return gi.check() && !gi.getResult().contains(item);
                }
            });
        }
    }

    /** Ends whatever the player is still doing (one item is all we wanted) by stepping onto its own tile. */
    private static void stopAction(NGameUI gui) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null || isIdle(player))
            return;
        gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 1, 0);
        long since = System.currentTimeMillis();
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return isIdle(player) || System.currentTimeMillis() - since > IDLE_MS;
            }
        });
    }

    private static class WaitCredit extends NTask {
        private final NGameUI gui;
        private final Gob gob;
        private final Gob player = NUtils.player();
        private final String product;
        private final long start = System.currentTimeMillis();
        private int freeAtStart = -1;
        private long idleSince = -1;
        private long itemAt = -1;
        Outcome outcome = Outcome.TIMEOUT;

        WaitCredit(NGameUI gui, Gob gob, String product) {
            this.gui = gui;
            this.gob = gob;
            this.product = product;
        }

        @Override
        public boolean check() {
            long now = System.currentTimeMillis();
            if (!LpExplorer.isProductUndiscovered(gob.ngob.name, product))
                return done(Outcome.DISCOVERED);
            if (Finder.findGob(gob.id) == null)
                return done(Outcome.GONE);

            // An item stacking onto one already carried takes no new cell; the idle and cap checks cover that.
            int free = gui.getInventory().calcFreeSpace();
            if (freeAtStart < 0)
                freeAtStart = free;
            else if (free < freeAtStart && itemAt < 0)
                itemAt = now;
            if (itemAt >= 0 && now - itemAt > CREDIT_GRACE_MS)
                return done(Outcome.NOT_CREDITED);

            if (player != null && isIdle(player)) {
                if (idleSince < 0)
                    idleSince = now;
                else if (now - idleSince > IDLE_MS)
                    return done(Outcome.IDLE);
            } else {
                idleSince = -1;
            }
            return now - start > ACTION_CAP_MS;
        }

        private boolean done(Outcome outcome) {
            this.outcome = outcome;
            return true;
        }
    }

    private static boolean isIdle(Gob player) {
        String pose = player.pose();
        return pose != null && pose.contains("gfx/borka/idle");
    }

    private void fail(Target target, String why) {
        failed.add(failKey(target.gob, target.product));
        failsPerProduct.merge(target.product, 1, Integer::sum);
        problems.put(target.product, why);
        System.out.println("LpGatherer: " + target.product + " from " + target.gob.ngob.name + " failed: " + why);
    }

    private static String failKey(Gob gob, String product) {
        return gob.id + "|" + product;
    }

    private static boolean inHands(NAlias tool) throws InterruptedException {
        for (NEquipory.Slots slot : new NEquipory.Slots[]{NEquipory.Slots.HAND_LEFT, NEquipory.Slots.HAND_RIGHT}) {
            WItem item = NUtils.getEquipment().findItem(slot.idx);
            if (item != null && NParser.checkName(((NGItem) item.item).name(), tool))
                return true;
        }
        return false;
    }

    /** In hand or on the belt, which is where Equip takes tools from. */
    private static boolean hasTool(NAlias tool) throws InterruptedException {
        if (inHands(tool))
            return true;
        WItem belt = NUtils.getEquipment().findItem(NEquipory.Slots.BELT.idx);
        return belt != null && belt.item.contents instanceof NInventory
                && ((NInventory) belt.item.contents).getItem(tool) != null;
    }
}
