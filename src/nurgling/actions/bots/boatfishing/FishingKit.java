package nurgling.actions.bots.boatfishing;

import haven.*;
import haven.res.ui.stackinv.ItemStack;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.Equip;
import nurgling.actions.EquipFromInventory;
import nurgling.actions.Results;
import nurgling.tasks.GetCurs;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.tools.VSpec;
import org.json.JSONObject;

import java.util.*;

/**
 * Public fishing building blocks for plugins (Map Explorer's fish survey): put the rod in hand, read and swap its parts,
 * cast and read the bite window without catching. Thin wrappers over Boat Fisher's own package-private classes, so a fix
 * in one reaches both; Boat Fisher itself does not use this class.
 *
 * <p>Game facts these rely on (user-confirmed 2026-11-13): using a part on a rod that has one swaps it and puts the old
 * part in the hand; the game catches the best fish on its own no sooner than 5 s after the bite window opens, and moving
 * or recasting cancels that.
 */
public final class FishingKit {
    public static final String CASTING_ROD = "Primitive Casting-Rod";
    /** How far a cast reaches from the boat. */
    public static final double CAST_RANGE = CastGeometry.CAST_RANGE;

    private FishingKit() {}

    /** The rod parts a casting rod takes, with their item category. */
    public enum Kind {
        LINE("Fishline"), HOOK("Hooks"), LURE("Lures");

        public final String category;

        Kind(String category) {
            this.category = category;
        }

        /** Every item name of this kind the client knows. */
        public List<String> names() {
            List<String> res = new ArrayList<>();
            List<JSONObject> cat = VSpec.categories.get(category);
            if (cat != null)
                for (JSONObject o : cat) {
                    String n = o.optString("name", null);
                    if (n != null && !n.isEmpty())
                        res.add(n);
                }
            return res;
        }

        public boolean has(String name) {
            return name != null && names().contains(name);
        }
    }

    /** What is on the rod: any field may be null when that part is missing. */
    public static final class Gear {
        public final String rod, line, hook, lure;

        public Gear(String rod, String line, String hook, String lure) {
            this.rod = rod;
            this.line = line;
            this.hook = hook;
            this.lure = lure;
        }

        public String get(Kind k) {
            return k == Kind.LINE ? line : k == Kind.HOOK ? hook : lure;
        }

        public boolean complete() {
            return rod != null && line != null && hook != null && lure != null;
        }

        @Override
        public String toString() {
            return rod + " / " + line + " / " + hook + " / " + lure;
        }
    }

    /** One fish row of the bite window. */
    public static final class BiteRow {
        public final String fish;
        /** The row's percentages in window order (bite, land, overall); {@link #overall} is the last. */
        public final List<Double> percents;
        public final double overall;

        BiteRow(String fish, List<Double> percents, double overall) {
            this.fish = fish;
            this.percents = Collections.unmodifiableList(new ArrayList<>(percents));
            this.overall = overall;
        }

        @Override
        public String toString() {
            return fish + " " + percents;
        }
    }

    /** What one cast found: the bite window's rows, or why there are none. */
    public static final class Cast {
        /** Empty when no window opened. */
        public final List<BiteRow> rows;
        /** Null when the cast went out; otherwise why it couldn't (not in a boat, out of range, no Fish action). */
        public final String error;
        /** True when an item arrived in the creels or inventory during the cast: the cancel came too late. */
        public final boolean caught;

        Cast(List<BiteRow> rows, String error, boolean caught) {
            this.rows = Collections.unmodifiableList(rows);
            this.error = error;
            this.caught = caught;
        }

        static Cast failed(String error) {
            return new Cast(new ArrayList<>(), error, false);
        }
    }

    /** True when the player sits in a boat (any seat). */
    public static boolean aboard() {
        return Boat.aboard(NUtils.player()) != null;
    }

    /** True when the player sits where map clicks row the boat. */
    public static boolean steering() {
        return Boat.steering(NUtils.player());
    }

    /** The casting rod in hand, equipping it from the belt or the inventory when needed. */
    public static Results rodInHand(NGameUI gui) throws InterruptedException {
        if (RigRod.rodInHand(CASTING_ROD) != null)
            return Results.SUCCESS();
        Tackle.emptyHand(null);
        new Equip(new NAlias(CASTING_ROD)).run(gui);
        if (RigRod.rodInHand(CASTING_ROD) == null && gui.getInventory().getItem(new NAlias(CASTING_ROD)) != null)
            new EquipFromInventory(new NAlias(CASTING_ROD)).run(gui);
        return RigRod.rodInHand(CASTING_ROD) != null ? Results.SUCCESS() : Results.ERROR("No " + CASTING_ROD + " in hands, belt or inventory");
    }

    /** The parts on the rod in hand, read off the rod itself. UI-safe. */
    public static Gear gearOnRod() {
        WItem rod = RigRod.rodInHandNow(CASTING_ROD);
        if (rod == null)
            return new Gear(null, null, null, null);
        String line = null, hook = null, lure = null;
        for (NGItem.NContent c : ((NGItem) rod.item).content()) {
            String n = c.name();
            if (Kind.LINE.has(n))
                line = n;
            else if (Kind.HOOK.has(n))
                hook = n;
            else if (Kind.LURE.has(n))
                lure = n;
        }
        return new Gear(CASTING_ROD, line, hook, lure);
    }

    /** Distinct names of this kind in the creels and the inventory. UI-safe. */
    public static List<String> carried(Kind kind) {
        Set<String> known = new HashSet<>(kind.names());
        Set<String> res = new LinkedHashSet<>();
        for (NInventory inv : Tackle.all()) {
            for (Widget w = inv.child; w != null; w = w.next) {
                if (!(w instanceof WItem))
                    continue;
                GItem item = ((WItem) w).item;
                if (item.contents instanceof ItemStack) {
                    for (GItem g : ((ItemStack) item.contents).order)
                        addName(g, known, res);
                } else {
                    addName(item, known, res);
                }
            }
        }
        return new ArrayList<>(res);
    }

    private static void addName(GItem g, Set<String> known, Set<String> res) {
        if (g instanceof NGItem) {
            String n = ((NGItem) g).name();
            if (n != null && known.contains(n))
                res.add(n);
        }
    }

    /**
     * Puts {@code name} on the rod as its {@code kind} part, from the creels or the inventory; the part it replaces
     * goes back where the new one came from. Does nothing when it is already on.
     */
    public static Results swapPart(NGameUI gui, Kind kind, String name) throws InterruptedException {
        if (name.equals(gearOnRod().get(kind)))
            return Results.SUCCESS();
        Results r = rodInHand(gui);
        if (!r.IsSuccess())
            return r;
        if (!Tackle.emptyHand(null))
            return Results.ERROR("No room for the item in hand");
        Tackle.Found src = Tackle.find(new NAlias(name));
        if (src == null)
            return Results.ERROR("No " + name + " in creels or inventory");
        WItem rod = RigRod.rodInHand(CASTING_ROD);
        NUtils.takeItemToHand(src.item);
        WItem inHand = NUtils.getGameUI().vhand;
        NUtils.itemact(rod);
        Waits.until(() -> name.equals(gearOnRod().get(kind)) && NUtils.getGameUI().vhand != inHand, 10_000);
        if (NUtils.getGameUI().vhand != null) {
            // The part the rod gave back (or the rest of a stack): wait for it to load, then put it away.
            Waits.until(() -> NUtils.getGameUI().vhand == null || NUtils.getGameUI().vhand.item.spr != null, 5_000);
            if (!Tackle.emptyHand(src.from))
                return Results.ERROR("No room to put the old " + kind.category + " away");
        }
        return name.equals(gearOnRod().get(kind)) ? Results.SUCCESS() : Results.ERROR("Couldn't put " + name + " on the rod");
    }

    /**
     * Casts at {@code aim} (which must be within {@link #CAST_RANGE}) and returns the rows of the bite window that opens,
     * without answering it, then cancels the fishing at once so nothing is caught (see {@link #cancel}: steering seat
     * only). No rows and no error means no window opened within {@code timeoutMs} (no fish there).
     */
    public static Cast castAndRead(NGameUI gui, Coord2d aim, long timeoutMs) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null || Boat.aboard(player) == null)
            return Cast.failed("Not in a boat");
        if (!CastGeometry.inRange(player.rc, aim))
            return Cast.failed("Cast target out of range");
        MenuGrid.Pagina fishAct = null;
        for (MenuGrid.Pagina pag : gui.menu.paginae) {
            if (pag.button() != null && pag.button().name().equals("Fish")) {
                fishAct = pag;
                break;
            }
        }
        if (fishAct == null)
            return Cast.failed("No Fish action");

        int itemsBefore = Tackle.itemCount();
        Window before = BiteWindow.find();
        fishAct.button().use(new MenuGrid.Interaction(1, 0));
        GetCurs curs = new GetCurs("fish");
        if (Waits.until(curs::check, 5_000).timedOut)
            return Cast.failed("The fish cursor never came"); // a click without it would steer the boat
        NUtils.lclick(aim);

        Waits.until(() -> {
            Window w = BiteWindow.find();
            return w != null && w != before && !BiteWindow.rows(w).isEmpty();
        }, timeoutMs);
        Window w = BiteWindow.find();
        List<BiteRow> res = new ArrayList<>();
        if (w != null && w != before) {
            for (BiteWindow.Row row : BiteWindow.rows(w))
                res.add(new BiteRow(row.fish, row.percents, row.overall));
        }
        cancel(gui);
        return new Cast(res, null, Tackle.itemCount() > itemsBefore);
    }

    /** Stops fishing with a stop click where the player is (moving cancels a bite): the bite window closes and nothing is
     *  caught. Only from the steering seat, where a map click rows the boat; returns false (nothing done) from any other,
     *  or when the fish cursor won't go away. */
    public static boolean cancel(NGameUI gui) throws InterruptedException {
        if (!steering())
            return false;
        Window w = BiteWindow.find();
        // The stop click is a plain map click: with the fish cursor still up it would be another cast (at the boat), and
        // so would every rowing click after it. Drop the cursor first.
        if (!arrow(gui))
            return false;
        Boat.halt(gui);
        if (w != null)
            Waits.until(() -> BiteWindow.find() != w, 1_500);
        return true;
    }

    /** Puts the plain arrow cursor back (a right click drops any other); false if it didn't come within 2 s. */
    public static boolean arrow(NGameUI gui) throws InterruptedException {
        if (NParser.checkName(NUtils.getCursorName(), "arw"))
            return true;
        Gob player = NUtils.player();
        if (player == null || gui.map == null)
            return false;
        gui.map.wdgmsg("click", Coord.z, player.rc.floor(OCache.posres), 3, 0);
        GetCurs arw = new GetCurs("arw");
        return !Waits.until(arw::check, 2_000).timedOut;
    }

    /** Where to row so that {@code target} is in cast range with a margin: the point that far short of it. */
    public static Coord2d rowTo(Coord2d from, Coord2d target) {
        return CastGeometry.rowTo(from, target);
    }

    /** Whether {@code target} is within a cast from {@code from}. */
    public static boolean inRange(Coord2d from, Coord2d target) {
        return CastGeometry.inRange(from, target);
    }
}
