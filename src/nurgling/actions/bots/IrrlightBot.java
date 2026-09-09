package nurgling.actions.bots;

import haven.Coord2d;
import haven.Gob;
import haven.Loading;
import haven.MenuGrid;
import haven.Resource;
import haven.Session;
import haven.Widget;
import haven.WItem;
import haven.res.ui.stackinv.ItemStack;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Drink;
import nurgling.actions.PathFinder;
import nurgling.actions.LightGob;
import nurgling.actions.Results;
import nurgling.actions.TakeItems2;
import nurgling.actions.TransferItems2;
import nurgling.areas.NContext;
import nurgling.areas.NGlobalCoord;
import nurgling.tasks.IsMoving;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitDuration;
import nurgling.tasks.WaitPose;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.NMakewindow;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Farms Irrlights at a crucible.
 * <p>
 * Smelting can call an Irrlight ({@code gfx/kritter/irrbloss}, "Irrlight" in the inventory), so the
 * bot rolls for one by cycling a single lump of metal back and forth: "Smelt Metal Nuggets"
 * ({@code paginae/craft/nuggify}) turns a bar into nuggets, "Smelt Metal Bar"
 * ({@code paginae/craft/denuggify}) turns them back into the bar, forever. The Irrlight flies fast
 * and does not wait, so every wait this bot performs also watches for it: the moment one shows up
 * the craft is abandoned, the bot chases the critter down, and only then walks back to the crucible
 * and picks the cycle back up.
 * <p>
 * Each pass right-clicks the crucible, which smelting needs and which running off after an Irrlight
 * drops. If the crucible has gone cold it is refuelled with branches from the Fuel/Branch zone and
 * relit, so the cycle survives a burnout unattended.
 * <p>
 * Catching fills the inventory, so once fewer than {@link #MIN_FREE_SLOTS} cells are left the
 * Irrlights - and only the Irrlights, never the metal the cycle runs on - are delivered to their
 * output zone, and the bot walks back to the exact spot it started from.
 * <p>
 * Preconditions: the crucible is lit, the player stands next to it with either one metal bar or a
 * set of nuggets (any metal - the recipes are generic), a zone somewhere has "Irrlight" marked
 * as an output, and - only needed once the crucible burns out - a Fuel zone with the "Branch"
 * subtype.
 */
public class IrrlightBot implements Action {
    /** Bar -> nuggets. */
    private static final String NUGGIFY = "paginae/craft/nuggify";
    /** Nuggets -> bar. */
    private static final String DENUGGIFY = "paginae/craft/denuggify";

    /** Every metal bar is gfx/invobjs/bar-<metal>, every nugget gfx/invobjs/nugget-<metal>. */
    private static final String BAR = "gfx/invobjs/bar-";
    private static final String NUGGET = "gfx/invobjs/nugget-";
    private static final String IRRLIGHT_ITEM = "gfx/invobjs/irrbloss";
    /** Tooltip of gfx/invobjs/irrbloss, and therefore the key the area system files it under. */
    private static final String IRRLIGHT_NAME = "Irrlight";

    private static final NAlias IRRLIGHT = new NAlias("gfx/kritter/irrbloss");
    private static final String CRUCIBLE_RES = "gfx/terobjs/crucible";
    /** "gfx/terobjs/steelcrucible" does not contain this, so no exception is needed. */
    private static final NAlias CRUCIBLE = new NAlias(CRUCIBLE_RES);

    private static final double CRUCIBLE_RANGE = 200;
    private static final long RECIPE_TIMEOUT = 10000;
    private static final long CRAFT_TIMEOUT = 15000;
    private static final long CHASE_TIMEOUT = 30000;
    private static final long PICKUP_TIMEOUT = 2000;
    /** Re-click the Irrlight this often: each click re-aims the chase at where it has moved to. */
    private static final long CHASE_CLICK_PERIOD = 200;
    /** The crucible burns branches - what players call sticks - not coal. */
    private static final String BRANCH = "Branch";
    /** Branches carried back per refuel trip. */
    private static final int FUEL_BATCH = 8;
    /** Give up if the crucible still reads as empty after this many branches. */
    private static final int MAX_FUEL_ITEMS = 8;
    /**
     * How long a branch may sit in the hand before we read it as "the crucible will take no more".
     * Only ever paid once per refuel, at the point the station fills up.
     */
    private static final long FEED_TIMEOUT = 2000;
    /**
     * Crucible model bits, taken from the one place that defines them. Fuel and fire are separate
     * and not interchangeable: 4 is the flame, while the low bits say what is loaded - 0 empty,
     * 1 branches, 2 coal.
     */
    private static final int FUEL_MASK = LightObject.getConfig(CRUCIBLE_RES).fuelFlag;
    private static final int FIRE_BIT = LightObject.getConfig(CRUCIBLE_RES).fireFlag;
    private static final int MAX_CRAFT_FAILS = 5;
    private static final int MAX_PREP_ATTEMPTS = 3;
    /** Deliver the catch once the inventory is down to fewer free cells than this. */
    private static final int MIN_FREE_SLOTS = 5;
    /** Close enough to the starting spot that walking back would be a no-op. */
    private static final double HOME_TOLERANCE = 3;

    private NMakewindow mwnd = null;
    private String openError = null;
    /** Irrlights we chased and could not catch; touched from the UI thread too. */
    private final java.util.Set<Long> ignored = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Gob crucible = Finder.findGob(NUtils.player().rc, CRUCIBLE, null, CRUCIBLE_RANGE);
        if (crucible == null)
            return Results.ERROR("No crucible nearby: stand next to a lit crucible before starting");
        long crucibleId = crucible.id;
        NGlobalCoord home = NUtils.bookmarkHere();

        NContext context = new NContext(gui);

        // Checked up front rather than at the first delivery: the bot fills its inventory over
        // hours, and finding out only then that the catch has nowhere to go would waste the run.
        // findOutsGlobal is a pure configuration lookup - unlike addOutItem it does not also
        // require the zone to be routable right now, so a cold chunk-nav cannot fail the start.
        if (NContext.findOutsGlobal(IRRLIGHT_NAME).isEmpty())
            return Results.ERROR("No output zone for Irrlights: mark a zone with \"" + IRRLIGHT_NAME
                    + "\" as an output so the catch has somewhere to go");

        int total = 0;
        int fails = 0;
        int preps = 0;
        String lastCraftError = null;

        Results delivery = maybeDeliver(gui, context, home, crucibleId);
        if (!delivery.IsSuccess())
            return delivery;

        while (true) {
            // An Irrlight outranks whatever else we were about to do.
            if (catchable() != null) {
                int got = chase(gui);
                if (got > 0) {
                    total += got;
                    gui.msg("Irrlight caught (" + total + " this run)");
                }
                delivery = maybeDeliver(gui, context, home, crucibleId);
                if (!delivery.IsSuccess())
                    return delivery;
                goHome(gui, home, crucibleId);
                continue;
            }

            if (Finder.findGob(crucibleId) == null)
                return Results.ERROR("The crucible is gone");
            if (!burning(crucibleId)) {
                if (++preps > MAX_PREP_ATTEMPTS)
                    return Results.ERROR("The crucible will not stay lit after " + MAX_PREP_ATTEMPTS
                            + " attempts");
                gui.msg("Crucible is not burning, refuelling and lighting it");
                Results prep = prepareCrucible(gui, context, crucibleId, home);
                goHome(gui, home, crucibleId);
                if (!prep.IsSuccess())
                    return prep;
                continue;
            }

            int bars = count(gui, BAR);
            int nuggets = count(gui, NUGGET);
            String recipe;
            String output;
            int before;
            /* Whichever half of the cycle we are holding. Nuggets win, so a leftover bar is
             * spent before a fresh batch of nuggets is made and the two never pile up. How many
             * nuggets make a bar is the server's business - it says so plainly enough if the
             * inventory is short. */
            if (nuggets > 0) {
                recipe = DENUGGIFY;
                output = BAR;
                before = bars;
            } else if (bars > 0) {
                recipe = NUGGIFY;
                output = NUGGET;
                before = nuggets;
            } else {
                return Results.ERROR("Nothing to smelt: carry a metal bar or its nuggets");
            }

            new Drink(0.9, false).run(gui);

            if (!useCrucible(gui, crucibleId))
                return Results.ERROR("The crucible is gone");

            Wake wake = openRecipe(gui, recipe, output);
            if (wake == Wake.IRRLIGHT)
                continue;
            if (wake != Wake.DONE)
                return Results.ERROR(openError != null ? openError
                        : "Could not open recipe " + recipe + " (does this character know it?)");

            NUtils.getUI().dropLastError();
            mwnd.wdgmsg("make", 0);
            final int target = before;
            final String grows = output;
            Watch watch = watch(() -> countRes(gui.getInventory(), grows) > target, CRAFT_TIMEOUT);
            switch (watch.wake) {
                case DONE:
                    fails = 0;
                    preps = 0;
                    break;
                case IRRLIGHT:
                    break;
                default:
                    if (watch.error != null)
                        lastCraftError = watch.error;
                    System.out.println("IrrlightBot: craft attempt failed (" + watch.wake + ", "
                            + watch.error + "), retry " + (fails + 1) + "/" + MAX_CRAFT_FAILS);
                    if (++fails >= MAX_CRAFT_FAILS) {
                        if (++preps > MAX_PREP_ATTEMPTS)
                            return Results.ERROR("Smelting keeps failing"
                                    + (lastCraftError != null ? ": " + lastCraftError : ""));
                        // Last net under the fire-bit check at the top of the loop: whatever else
                        // stalls a craft, a crucible that quietly went out looks the same from
                        // here, so try a refuel and relight before giving up on it.
                        gui.msg("Smelting keeps failing, refuelling and relighting the crucible");
                        Results prep = prepareCrucible(gui, context, crucibleId, home);
                        goHome(gui, home, crucibleId);
                        if (!prep.IsSuccess())
                            return prep;
                        fails = 0;
                    }
                    break;
            }
        }
    }

    /**
     * Refuels the crucible with branches and lights it again.
     * <p>
     * {@link nurgling.actions.PrepareWorkStation} is deliberately not used: its crucible branch is
     * hardwired to Coal, and it would additionally demand a zone carrying the "Crucible"
     * specialisation when we already know exactly which crucible we are standing at. The shape is
     * otherwise the one every fuel action here uses - fetch the fuel, then feed the gob one item at
     * a time with takeItemToHand + activateItem.
     */
    private Results prepareCrucible(NGameUI gui, NContext context, long crucibleId, NGlobalCoord home)
            throws InterruptedException {
        if (fuelled(crucibleId))
            return lightCrucible(gui, crucibleId);
        if (gui.getInventory().getItems(new NAlias(BRANCH)).isEmpty()) {
            new TakeItems2(context, BRANCH, FUEL_BATCH, Specialisation.SpecName.fuel, BRANCH).run(gui);
            goHome(gui, home, crucibleId);
            if (gui.getInventory().getItems(new NAlias(BRANCH)).isEmpty())
                return Results.ERROR("No branches to refuel the crucible: they come from a Fuel zone"
                        + " with the \"" + BRANCH + "\" subtype");
        }

        /* Straight take -> activate -> hand free per branch, the same tight loop FuelToContainers
         * and fillCrucible use. Nothing is polled in between: the fuel bit and the emptied hand are
         * two results of the same server action, so it has already arrived by the time the hand
         * clears, and waiting on it separately added a dead second to every single branch.
         *
         * It keeps feeding past the point the fuel marker appears, because that marker only says
         * "branches are in there", not how many - a full load burns longer, and burning longer is
         * the whole point of refuelling. The station itself says when it has had enough: it stops
         * taking what we hold, and the branch is simply put back. */
        for (int fed = 0; fed < MAX_FUEL_ITEMS; fed++) {
            Gob station = Finder.findGob(crucibleId);
            if (station == null)
                return Results.ERROR("The crucible is gone");
            ArrayList<WItem> branches = gui.getInventory().getItems(new NAlias(BRANCH));
            if (branches.isEmpty())
                break;
            NUtils.takeItemToHand(branches.get(0));
            NUtils.activateItem(station);
            // Bounded, unlike WaitFreeHand, which kills the bot on its counter instead of failing:
            // if the station will not take what we hold, the item simply stays there.
            if (!waitFor(() -> gui.vhand == null, FEED_TIMEOUT)) {
                NUtils.dropToInv();
                break;
            }
        }

        if (!fuelled(crucibleId))
            return Results.ERROR("The crucible would not take any branches (model marker "
                    + modelAttr(crucibleId) + ")");
        return lightCrucible(gui, crucibleId);
    }

    private Results lightCrucible(NGameUI gui, long crucibleId) throws InterruptedException {
        Gob station = Finder.findGob(crucibleId);
        if (station == null)
            return Results.ERROR("The crucible is gone");
        return new LightGob(new ArrayList<>(Collections.singletonList(station.ngob.hash)), FIRE_BIT).run(gui);
    }

    /** Holds fuel of any kind - branches or coal - as opposed to standing empty. */
    private static boolean fuelled(long crucibleId) {
        return (modelAttr(crucibleId) & FUEL_MASK) != 0;
    }

    /** Actually alight, which is what smelting needs - fuel alone is not enough. */
    private static boolean burning(long crucibleId) {
        return (modelAttr(crucibleId) & FIRE_BIT) != 0;
    }

    private static long modelAttr(long crucibleId) {
        Gob station = Finder.findGob(crucibleId);
        return (station == null || station.ngob == null) ? 0 : station.ngob.getModelAttribute();
    }

    /**
     * Delivers the caught Irrlights to their output zone once the inventory is nearly full, then
     * comes back. Only Irrlights are handed over - the bar or nuggets the cycle runs on are named
     * nowhere in the transfer, so they stay in the inventory.
     */
    private Results maybeDeliver(NGameUI gui, NContext context, NGlobalCoord home, long crucibleId)
            throws InterruptedException {
        int freeBefore = gui.getInventory().getFreeSpace();
        if (freeBefore >= MIN_FREE_SLOTS)
            return Results.SUCCESS();

        HashSet<String> targets = new HashSet<>();
        for (WItem item : collect(gui, IRRLIGHT_ITEM)) {
            NGItem ngi = (NGItem) item.item;
            String name = ngi.name();
            if (name == null)
                continue;
            // Resolved per item: output zones can be split by quality.
            if (context.addOutItem(name, null, ngi.quality != null ? ngi.quality : 1))
                targets.add(name);
        }
        if (targets.isEmpty())
            return Results.ERROR("Inventory is full and no output zone accepts the Irrlights");

        gui.msg("Inventory nearly full, delivering the Irrlights");
        new TransferItems2(context, targets).run(gui);
        goHome(gui, home, crucibleId);

        if (gui.getInventory().getFreeSpace() <= freeBefore)
            return Results.ERROR("Delivering the Irrlights freed no space: is their storage full?");
        return Results.SUCCESS();
    }

    /**
     * Right-clicks the crucible to put it in use, which smelting requires. Sent before the recipe
     * is opened, so the server has already ordered the two by the time we press craft.
     */
    private boolean useCrucible(NGameUI gui, long crucibleId) throws InterruptedException {
        Gob crucible = Finder.findGob(crucibleId);
        if (crucible == null)
            return false;
        NUtils.rclickGob(crucible);
        // Both return at once when we are already standing at it, which is the normal case.
        NUtils.addTask(new IsMoving(crucible.rc, 50));
        NUtils.addTask(new WaitPose(NUtils.player(), "gfx/borka/idle"));
        return true;
    }

    /**
     * Chases every Irrlight in sight until it is caught or gone.
     *
     * @return how many were caught this pass.
     */
    private int chase(NGameUI gui) throws InterruptedException {
        int got = 0;
        Gob target;
        while ((target = catchable()) != null) {
            long id = target.id;
            final int before = count(gui, IRRLIGHT_ITEM);
            gui.msg("Irrlight! Chasing...");
            long deadline = System.currentTimeMillis() + CHASE_TIMEOUT;
            for (Gob g = target; g != null; g = Finder.findGob(id)) {
                if (System.currentTimeMillis() >= deadline)
                    break;
                NUtils.rclickGob(g);
                NUtils.addTask(new WaitDuration(CHASE_CLICK_PERIOD));
            }
            // The gob is gone (or we gave up); the item shows up a beat later.
            waitFor(() -> countRes(gui.getInventory(), IRRLIGHT_ITEM) > before, PICKUP_TIMEOUT);
            int now = count(gui, IRRLIGHT_ITEM);
            if (now > before) {
                got += now - before;
            } else {
                // Either out of reach or gone. Either way stop counting it as a target, or a
                // stubborn one would keep the bot away from the crucible forever.
                ignored.add(id);
                gui.msg(Finder.findGob(id) != null
                        ? "Could not reach the Irrlight, giving up on it"
                        : "The Irrlight got away");
            }
        }
        NUtils.getUI().dropLastError();
        return got;
    }

    /** Nearest Irrlight we have not already given up on, or null. Safe on either thread. */
    private Gob catchable() {
        Gob player = NUtils.player();
        Gob best = null;
        double bestd = Double.MAX_VALUE;
        for (Gob g : Finder.findGobs(IRRLIGHT)) {
            if (ignored.contains(g.id))
                continue;
            double d = (player == null) ? 0 : g.rc.dist(player.rc);
            if (best == null || d < bestd) {
                bestd = d;
                best = g;
            }
        }
        return best;
    }

    /**
     * Walks back to where the player stood when the bot started. The bookmark is grid-relative, so
     * it survives the chunk-nav hops a delivery to a distant storage takes.
     */
    private void goHome(NGameUI gui, NGlobalCoord home, long crucibleId) throws InterruptedException {
        Gob player = NUtils.player();
        Coord2d spot = (home != null) ? home.getCurrentCoord() : null;
        if (player != null && spot != null && player.rc.dist(spot) < HOME_TOLERANCE)
            return;
        if (NUtils.navigateTo(home))
            return;
        Gob crucible = Finder.findGob(crucibleId);
        if (crucible != null)
            new PathFinder(crucible).run(gui);
    }

    /**
     * Activates a craft recipe and waits for its window. Stores the window in {@link #mwnd}.
     *
     * @param output resource prefix the recipe must produce, so we never craft into the wrong window.
     */
    private Wake openRecipe(NGameUI gui, String recipe, String output) throws InterruptedException {
        MenuGrid.PagButton button = recipeButton(gui, recipe);
        if (button == null)
            return Wake.TIMEOUT;
        final NMakewindow old = (gui.craftwnd != null) ? gui.craftwnd.makeWidget : null;
        openError = null;
        NUtils.getUI().dropLastError();
        gui.menu.use(button, new MenuGrid.Interaction(), false);
        Watch watch = watch(() -> {
            NMakewindow m = (gui.craftwnd != null) ? gui.craftwnd.makeWidget : null;
            return m != null && m != old && !m.inputs.isEmpty() && specHas(m.outputs, output);
        }, RECIPE_TIMEOUT);
        if (watch.wake == Wake.DONE) {
            mwnd = gui.craftwnd.makeWidget;
            return Wake.DONE;
        }
        if (watch.wake == Wake.TIMEOUT) {
            NMakewindow m = (gui.craftwnd != null) ? gui.craftwnd.makeWidget : null;
            openError = (m == null || m == old)
                    ? "Recipe " + recipe + " did not open (does this character know it?)"
                    : "Recipe " + recipe + " opened as \"" + m.rcpnm + "\" producing "
                            + specNames(m.outputs) + ", expected " + output + "*";
        } else {
            openError = watch.error;
        }
        return watch.wake;
    }

    private static String specNames(List<NMakewindow.Spec> specs) {
        StringBuilder sb = new StringBuilder();
        for (NMakewindow.Spec s : specs) {
            if (sb.length() > 0)
                sb.append(", ");
            try {
                sb.append(s.res == null ? "?" : s.res.get().name);
            } catch (Loading l) {
                sb.append("<loading>");
            }
        }
        return sb.toString();
    }

    /**
     * Finds the recipe among the actions this character knows. Reads the cached resource name so a
     * pagina whose resource has not been fetched yet does not have to be loaded just to be skipped.
     */
    private MenuGrid.PagButton recipeButton(NGameUI gui, String recipe) throws InterruptedException {
        long deadline = System.currentTimeMillis() + RECIPE_TIMEOUT;
        do {
            MenuGrid.Pagina found = null;
            synchronized (gui.menu.paginae) {
                for (MenuGrid.Pagina pag : gui.menu.paginae) {
                    if (recipe.equals(resnm(pag))) {
                        found = pag;
                        break;
                    }
                }
            }
            if (found != null) {
                try {
                    return found.button();
                } catch (Loading l) {
                    // Resource still on its way; fall through and retry.
                }
            }
            NUtils.addTask(new WaitDuration(100));
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private static String resnm(MenuGrid.Pagina pag) {
        if (pag.res instanceof Session.CachedRes.Ref)
            return ((Session.CachedRes.Ref) pag.res).resnm();
        try {
            return pag.res().name;
        } catch (Loading l) {
            return null;
        }
    }

    private static boolean specHas(List<NMakewindow.Spec> specs, String prefix) {
        for (NMakewindow.Spec s : specs) {
            if (prefix.equals(specPrefix(s, prefix)))
                return true;
        }
        return false;
    }

    private static String specPrefix(NMakewindow.Spec s, String prefix) {
        try {
            if (s.res != null && s.res.get().name.startsWith(prefix))
                return prefix;
        } catch (Loading l) {
            // Not loaded yet: the caller polls, so it will match on a later tick.
        }
        return null;
    }

    private enum Wake {DONE, IRRLIGHT, ERROR, TIMEOUT}

    /** Evaluated on the UI thread, so it may walk widget trees directly. */
    private interface Cond {
        boolean done();
    }

    /**
     * Waits for {@code cond}, but wakes up early for an Irrlight or a server error. Every wait the
     * bot performs goes through here, which is what keeps the catch responsive: the check runs once
     * per frame, so the critter is spotted the frame it spawns.
     */
    private static class Watch extends NTask {
        private final IrrlightBot bot;
        private final Cond cond;
        private final long deadline;
        Wake wake = Wake.TIMEOUT;
        String error = null;

        Watch(IrrlightBot bot, Cond cond, long timeoutMs) {
            this.bot = bot;
            this.cond = cond;
            this.deadline = System.currentTimeMillis() + timeoutMs;
            this.infinite = true;
        }

        @Override
        public boolean check() {
            if (bot.catchable() != null) {
                wake = Wake.IRRLIGHT;
                return true;
            }
            String err = NUtils.getUI().getLastError();
            if (err != null) {
                error = err;
                wake = Wake.ERROR;
                return true;
            }
            if (cond.done()) {
                wake = Wake.DONE;
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                wake = Wake.TIMEOUT;
                return true;
            }
            return false;
        }
    }

    private Watch watch(Cond cond, long timeoutMs) throws InterruptedException {
        Watch w = new Watch(this, cond, timeoutMs);
        NUtils.addTask(w);
        return w;
    }

    /** Plain wait, without the Irrlight short circuit: used while one is already being chased. */
    private static boolean waitFor(Cond cond, long timeoutMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        final boolean[] met = {false};
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                if (cond.done()) {
                    met[0] = true;
                    return true;
                }
                return System.currentTimeMillis() >= deadline;
            }
        });
        return met[0];
    }

    private static int count(NGameUI gui, String prefix) throws InterruptedException {
        final NInventory inv = gui.getInventory();
        final int[] result = {0};
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                result[0] = countRes(inv, prefix);
                return true;
            }
        });
        return result[0];
    }

    private static ArrayList<WItem> collect(NGameUI gui, String prefix) throws InterruptedException {
        final NInventory inv = gui.getInventory();
        final ArrayList<WItem> result = new ArrayList<>();
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                result.clear();
                collectRes(inv, prefix, result);
                return true;
            }
        });
        return result;
    }

    private static int countRes(NInventory inv, String prefix) {
        return collectRes(inv, prefix, null);
    }

    /**
     * Counts inventory items by resource path, which is the only stable handle here: the display
     * names are not uniform ("Bar of Wrought Iron" but "Copper Nugget"). Must run on the UI thread.
     * <p>
     * A bundled stack is a container widget holding the real items and carries no quality of its
     * own, so it is descended into and never counted itself - which is what lets the bot run with
     * bundling left on, where ten nuggets sit in a single cell.
     */
    private static int collectRes(NInventory inv, String prefix, List<WItem> out) {
        if (inv == null)
            return 0;
        synchronized (inv.ui) {
            return collectRes(inv.child, prefix, out);
        }
    }

    private static int collectRes(Widget first, String prefix, List<WItem> out) {
        int n = 0;
        for (Widget w = first; w != null; w = w.next) {
            if (!(w instanceof WItem))
                continue;
            WItem item = (WItem) w;
            if (item.item.contents != null) {
                if (item.item.contents instanceof ItemStack)
                    n += collectRes(item.item.contents.child, prefix, out);
                continue;
            }
            try {
                Resource res = item.item.getres();
                if (res != null && res.name.startsWith(prefix)) {
                    n++;
                    if (out != null)
                        out.add(item);
                }
            } catch (Loading l) {
                // Item not resolved yet; it will be counted on a later poll.
            }
        }
        return n;
    }
}
