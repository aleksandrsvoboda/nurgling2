package nurgling.actions.bots;

import haven.Button;
import haven.Composite;
import haven.Composited;
import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Indir;
import haven.ItemInfo;
import haven.Loading;
import haven.MessageBuf;
import haven.Pair;
import haven.Resource;
import haven.Session;
import haven.Sprite;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.res.ui.relcnt.RelCont;
import haven.res.ui.stackinv.ItemStack;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.CloseTargetContainer;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.PathFinder;
import nurgling.actions.RestoreResources;
import nurgling.actions.Results;
import nurgling.actions.TransferItems2;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.areas.NGlobalCoord;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitDuration;
import nurgling.tasks.WaitFreeHand;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.NEquipory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smokes Pipestuff from the equipped Bull Pipe and catches the Smokerings and Smokeships it blows.
 * <p>
 * Puffing a pipe now and then blows a Smokering ({@code gfx/invobjs/smokering} once caught), and more
 * rarely a Smokeship ({@code gfx/invobjs/smokeship}). Both fly off fast, so the bot stands on the
 * spot it was started from and every wait it performs also watches for one: the moment one shows
 * up it is chased down with right-clicks, and the bot walks back to its spot.
 * <p>
 * The pipe is kept burning without relighting where possible: Pipestuff can be added to a lit pipe,
 * so it is topped up from the inventory once it is down to {@link #TOPUP_KG}. The inventory is
 * restocked from a barrel in the zone that takes Pipestuff as soon as it runs dry - while the pipe is
 * still nearly full - so a trip rarely lets it go out. When it does go out, it is lit again through
 * {@link LightObject}'s torch and fire sources.
 * <p>
 * Below half hard HP the bot stops smoking: the pipe comes off into the inventory and the run ends.
 * <p>
 * Preconditions: a Bull Pipe is equipped, the player stands where it should smoke (an open field -
 * rings pass through walls, the player does not), a zone takes Pipestuff from its barrels unless
 * the pipe and inventory already hold some, and zones have Smokering and Smokeship as outputs.
 * Something to light the pipe must be at hand when it is not lit: a torch, a torchpost or a fire.
 * <p>
 * The watch, chase and wait helpers are copied from {@link IrrlightBot}, which has users and is left
 * as it is - a fix in one belongs in the other too.
 */
public class SmokeringBot implements Action {
    private static final String PIPE_ITEM = "gfx/invobjs/bullpipe";
    /** The pipe as drawn on the player; its sprite data says what the pipe holds and whether it burns. */
    private static final String PIPE_SPRITE = "gfx/terobjs/items/bullpipe";
    /** Sprite flag of the meshes drawn with pipe-embers and pipe-smoke (mesh id 1). */
    private static final int LIT_BIT = 2;

    private static final String PIPESTUFF = "Pipestuff";
    private static final NAlias PIPESTUFF_ALIAS = new NAlias(PIPESTUFF);
    /** Barrel overlay gfx/terobjs/barrel-pipestuff, and the barrel window's "x kg of Pipestuff" label. */
    private static final String PIPESTUFF_KEY = "pipestuff";

    private static final String RING_ITEM = "gfx/invobjs/smokering";
    private static final String SHIP_ITEM = "gfx/invobjs/smokeship";
    private static final String RING_NAME = "Smokering";
    private static final String SHIP_NAME = "Smokeship";
    /** The flying ones. Their gob resource is not confirmed yet, so match anything named after them. */
    private static final NAlias CATCHABLE = new NAlias("smokering", "smokeship");

    private static final double HP_STOP = 0.5;
    /** Top the pipe up once it is down to this, while it still burns, so it never needs relighting. */
    private static final double TOPUP_KG = 0.05;
    /** A take from the barrel is one 0.50 kg portion; four of them carry 2 kg per trip. */
    private static final int CARRY_TAKES = 4;
    private static final double KG_PER_TAKE = 0.5;
    private static final Coord PORTION_SIZE = new Coord(1, 1);

    private static final long CHASE_TIMEOUT = 20000;
    /** Re-click this often: each click re-aims the chase at where the ring has moved to. */
    private static final long CHASE_CLICK_PERIOD = 200;
    private static final long PICKUP_TIMEOUT = 2000;
    private static final long FILL_TIMEOUT = 3000;
    private static final long PIPE_INFO_TIMEOUT = 5000;
    private static final long BARREL_LOAD_TIMEOUT = 10000;
    private static final long BARREL_LABEL_TIMEOUT = 5000;
    private static final long TAKE_TIMEOUT = 5000;
    /** Eat, drink and deliver at most this often; the watch wakes up for it. */
    private static final long CHORES_PERIOD = 60000;
    private static final int LIGHT_ATTEMPTS = 3;
    private static final int FILL_ATTEMPTS = 3;
    /** Deliver the catch once the inventory is down to fewer free cells than this. */
    private static final int MIN_FREE_SLOTS = 3;
    /** Close enough to the starting spot that walking back would be a no-op. */
    private static final double HOME_TOLERANCE = 3;

    /** "0.40 kg of Pipestuff": amount, unit, substance. */
    private static final Pattern AMOUNT_OF = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*(kg|g)\\s+of\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);

    private NGlobalCoord home;
    private NContext context;
    private int rings = 0;
    private int ships = 0;
    private int fillFails = 0;
    /** Set once a trip finds every Pipestuff barrel empty: the pipe is smoked out, then the run ends. */
    private boolean barrelsEmpty = false;
    /** Rings we chased and could not catch; touched from the UI thread too. */
    private final Set<Long> ignored = ConcurrentHashMap.newKeySet();
    /** Catchable gobs already logged, so each one's resource is printed once. */
    private final Set<Long> logged = ConcurrentHashMap.newKeySet();

    /** What LightObject lights: the worn pipe, lit when its embers are drawn. */
    private final LightObject.ItemTarget pipeTarget = new LightObject.ItemTarget() {
        @Override
        public WItem find() {
            return pipe();
        }

        @Override
        public boolean lit() {
            return SmokeringBot.lit();
        }
    };

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // The bot menu builds this object once and runs the same instance on every click, so
        // whatever the last run learned - "the barrels are empty" above all - must not carry over.
        rings = 0;
        ships = 0;
        fillFails = 0;
        barrelsEmpty = false;
        ignored.clear();
        logged.clear();

        if (hpLow())
            return Results.ERROR("HP is below 50%: not smoking");
        if (pipe() == null)
            return Results.ERROR("Equip a Bull Pipe before starting");
        if (gui.vhand != null) {
            NUtils.dropToInv();
            NUtils.addTask(new WaitFreeHand());
        }
        home = NUtils.bookmarkHere();
        if (home == null)
            return Results.ERROR("Could not bookmark the starting spot");

        // Configuration lookups only: findOutsGlobal does not need the zone to be routable right now.
        for (String name : new String[]{RING_NAME, SHIP_NAME}) {
            if (NContext.findOutsGlobal(name).isEmpty())
                return Results.ERROR("No output zone for " + name + "s: mark a zone with \"" + name
                        + "\" as an output so the catch has somewhere to go");
        }
        Results known = awaitPipeInfo();
        if (!known.IsSuccess())
            return known;
        if (!onUi(SmokeringBot::filled) && countPipestuff(gui) == 0 && !hasPipestuffInput())
            return Results.ERROR("No Pipestuff: mark the zone with its barrel as taking \"" + PIPESTUFF + "\"");

        context = new NContext(gui);
        return loop(gui);
    }

    private Results loop(NGameUI gui) throws InterruptedException {
        int lightFails = 0;
        long nextChores = System.currentTimeMillis() + CHORES_PERIOD;

        while (true) {
            if (hpLow())
                return stopSmoking(gui);

            // A ring outranks whatever else we were about to do.
            if (catchable() != null) {
                chase(gui);
                if (hpLow())
                    return stopSmoking(gui);
                Results delivery = maybeDeliver(gui);
                if (!delivery.IsSuccess())
                    return delivery;
                Results back = goHome();
                if (!back.IsSuccess())
                    return back;
                continue;
            }

            Results fuel = ensureFuel(gui);
            if (!fuel.IsSuccess())
                return fuel;

            if (!onUi(SmokeringBot::lit)) {
                if (++lightFails > LIGHT_ATTEMPTS)
                    return Results.ERROR("Could not light the pipe after " + LIGHT_ATTEMPTS + " attempts:"
                            + " equip a lit torch, or have a torch, torchpost or fire nearby");
                gui.msg("Lighting the pipe");
                new LightObject(pipeTarget).run(gui);
                if (!onUi(SmokeringBot::lit))
                    System.out.println("SmokeringBot: pipe still not lit, player equipment sprites: " + equipSprites());
                Results back = goHome();
                if (!back.IsSuccess())
                    return back;
                continue;
            }
            lightFails = 0;

            if (System.currentTimeMillis() >= nextChores) {
                /* Drinks below half stamina, eats below a third energy, and refills the waterskins
                 * from the water zone when the drink finds them empty - same call as IrrlightBot. */
                if (!new RestoreResources().run(gui).IsSuccess())
                    return Results.ERROR("Out of food or water");
                Results delivery = maybeDeliver(gui);
                if (!delivery.IsSuccess())
                    return delivery;
                Results back = goHome();
                if (!back.IsSuccess())
                    return back;
                nextChores = System.currentTimeMillis() + CHORES_PERIOD;
                continue;
            }

            // Stand still until something needs doing. The loop above re-reads what woke us.
            watch(countPipestuff(gui) > 0, nextChores - System.currentTimeMillis());
        }
    }

    /**
     * Keeps the pipe fed: tops it up from the inventory when it runs low, and restocks the inventory
     * from the Pipestuff barrel as soon as it runs dry. Fails only when there is nothing left to smoke.
     */
    private Results ensureFuel(NGameUI gui) throws InterruptedException {
        Results known = awaitPipeInfo();
        if (!known.IsSuccess())
            return known;

        if (onUi(SmokeringBot::needsFuel) && countPipestuff(gui) > 0) {
            Results fill = fillPipe(gui);
            if (!fill.IsSuccess())
                return fill;
        }

        if (!barrelsEmpty && countPipestuff(gui) == 0) {
            gui.msg("Out of spare Pipestuff, fetching more");
            String failed = fetchPipestuff(gui);
            Results back = goHome();
            if (!back.IsSuccess())
                return back;
            if (failed != null || countPipestuff(gui) == 0) {
                // Nothing to restock from: smoke out what the pipe holds, then the run ends below.
                String why = (failed != null) ? failed : "The Pipestuff barrels are empty";
                if (!onUi(SmokeringBot::filled))
                    return Results.ERROR("Out of Pipestuff: " + why);
                barrelsEmpty = true;
                gui.msg(why + ": smoking what is left in the pipe");
            } else if (onUi(SmokeringBot::needsFuel)) {
                Results fill = fillPipe(gui);
                if (!fill.IsSuccess())
                    return fill;
            }
        }

        if (!onUi(SmokeringBot::filled))
            return Results.ERROR("Out of Pipestuff: the pipe is empty and so are the barrels");
        return Results.SUCCESS();
    }

    /**
     * Uses one portion of Pipestuff on the worn pipe - the same take, itemact, put-back sequence
     * RepairFishingRot uses to load an equipped rod. A lit pipe stays lit while it is topped up.
     */
    private Results fillPipe(NGameUI gui) throws InterruptedException {
        WItem pipe = pipe();
        if (pipe == null)
            return Results.ERROR("The Bull Pipe is no longer equipped");
        ArrayList<WItem> stuff = gui.getInventory().getItems(PIPESTUFF_ALIAS);
        if (!stuff.isEmpty()) {
            final double before = onUi(SmokeringBot::pipeKg);
            final Cond grew = () -> pipeKg() > before;
            NUtils.takeItemToHand(stuff.get(0));
            final WItem held = gui.vhand;
            NUtils.itemact(pipe);
            waitFor(() -> gui.vhand != held || grew.done(), FILL_TIMEOUT);
            if (gui.vhand != null) {
                // Whatever did not fit goes back into the inventory for the next top-up.
                NUtils.dropToInv();
                NUtils.addTask(new WaitFreeHand());
            }
            // The hand empties first; the pipe's new contents arrive in their own update.
            if (waitFor(grew, FILL_TIMEOUT)) {
                fillFails = 0;
                return Results.SUCCESS();
            }
        }
        if (++fillFails >= FILL_ATTEMPTS)
            return Results.ERROR("The pipe will not take any Pipestuff");
        return Results.SUCCESS();
    }

    /**
     * Walks to the zone that takes Pipestuff and carries {@link #CARRY_TAKES} portions back from its
     * barrels, nearest first. Does not walk home: the caller does.
     *
     * @return why the trip could not be made, or null - also when the barrels turned out empty.
     */
    private String fetchPipestuff(NGameUI gui) throws InterruptedException {
        // Local first: the global lookup drops areas chunk-nav cannot route to at this moment.
        NArea area = NContext.findIn(PIPESTUFF);
        if (area == null)
            area = NContext.findInGlobal(PIPESTUFF);
        if (area == null)
            return "No reachable zone takes \"" + PIPESTUFF + "\"";
        // ensurePresence: from nearby, navigateToArea would not walk, and the unloaded barrels would
        // read as "none here".
        if (!NUtils.navigateToArea(area, true))
            return "Could not reach the Pipestuff zone";

        ArrayList<Gob> barrels = awaitBarrels(area);
        if (barrels.isEmpty()) {
            // A large zone may not have loaded from its corner: look again from the middle.
            Pair<Coord2d, Coord2d> rc = area.getRCArea();
            if (rc != null) {
                new PathFinder(rc.a.add(rc.b).div(2)).run(gui);
                barrels = awaitBarrels(area);
            }
        }
        if (barrels.isEmpty())
            return null;

        Coord2d me = NUtils.player().rc;
        barrels.sort((a, b) -> Double.compare(a.rc.dist(me), b.rc.dist(me)));
        for (Gob barrel : barrels) {
            int want = CARRY_TAKES - countPipestuff(gui);
            int free = gui.getInventory().getNumberFreeCoord(PORTION_SIZE);
            if (want <= 0 || free <= 0)
                break;
            takeFromBarrel(gui, barrel, Math.min(want, free));
        }
        return null;
    }

    /**
     * One open/close per barrel: open it, read how much it holds, press Take that many times (at most
     * {@code takes}), close it. Anything left in the hand goes back into the barrel.
     */
    private void takeFromBarrel(NGameUI gui, Gob barrel, int takes) throws InterruptedException {
        new PathFinder(barrel).run(gui);
        if (!new OpenTargetContainer("Barrel", barrel).run(gui).IsSuccess())
            return;
        final NAlias key = new NAlias(PIPESTUFF_KEY);
        waitFor(() -> gui.getBarrelContent(key) >= 0, BARREL_LABEL_TIMEOUT);
        double kg = gui.getBarrelContent(key);
        Button take = takeButton(gui);
        if (kg > 0 && take != null) {
            int n = Math.min(takes, (int) Math.ceil(kg / KG_PER_TAKE - 1e-9));
            final int target = countPipestuff(gui) + n;
            for (int i = 0; i < n; i++)
                take.click();
            final NInventory inv = gui.getInventory();
            waitFor(() -> countName(inv, PIPESTUFF) >= target, TAKE_TIMEOUT);
        }
        if (gui.vhand != null) {
            NUtils.activateItem(barrel, false);
            NUtils.addTask(new WaitFreeHand());
        }
        new CloseTargetContainer("Barrel").run(gui);
    }

    private static Button takeButton(NGameUI gui) {
        Window wnd = gui.getWindow("Barrel");
        if (wnd == null)
            return null;
        for (Widget child : wnd.children()) {
            if (!(child instanceof RelCont))
                continue;
            for (Widget w : child.children()) {
                if (w instanceof Button && "Take".equals(((Button) w).text.text))
                    return (Button) w;
            }
        }
        return null;
    }

    /**
     * The zone's Pipestuff barrels, giving them time to stream in after the walk. Polled from this
     * thread: the area scan resolves gob names through a task of its own.
     */
    private ArrayList<Gob> awaitBarrels(NArea area) throws InterruptedException {
        long deadline = System.currentTimeMillis() + BARREL_LOAD_TIMEOUT;
        while (true) {
            ArrayList<Gob> found = new ArrayList<>();
            ArrayList<String> seen = new ArrayList<>();
            if (area.getRCArea() != null) {
                for (Gob g : Finder.findGobs(area, new NAlias("barrel"))) {
                    String content = NUtils.getContentsOfBarrel(g);
                    seen.add(g.ngob.name + "=" + content);
                    if (content != null && content.contains(PIPESTUFF_KEY))
                        found.add(g);
                }
            }
            if (!found.isEmpty() || System.currentTimeMillis() >= deadline) {
                System.out.println("SmokeringBot: zone " + area.name + (area.getRCArea() == null ? " (not loaded)" : "")
                        + ", barrels seen: " + seen + ", with Pipestuff: " + found.size());
                return found;
            }
            NUtils.addTask(new WaitDuration(250));
        }
    }

    /** Below half HP: take the pipe off so it stops burning, and end the run. */
    private Results stopSmoking(NGameUI gui) throws InterruptedException {
        WItem pipe = pipe();
        if (pipe != null && gui.getInventory().getNumberFreeCoord(pipe) > 0) {
            if (gui.vhand != null) {
                NUtils.dropToInv();
                NUtils.addTask(new WaitFreeHand());
            }
            NUtils.takeItemToHand(pipe);
            NUtils.dropToInv();
            NUtils.addTask(new WaitFreeHand());
            gui.msg("HP below 50%: stopped smoking, the pipe is in the inventory");
        } else {
            gui.msg("HP below 50%: stopped smoking");
        }
        return Results.SUCCESS();
    }

    /**
     * Delivers the caught rings and ships to their output zones once the inventory is nearly full.
     * Pipestuff is named nowhere in the transfer, so it stays.
     */
    private Results maybeDeliver(NGameUI gui) throws InterruptedException {
        int freeBefore = gui.getInventory().getFreeSpace();
        if (freeBefore >= MIN_FREE_SLOTS)
            return Results.SUCCESS();

        HashSet<String> targets = new HashSet<>();
        ArrayList<WItem> catches = collect(gui, w -> resStarts(w, RING_ITEM) || resStarts(w, SHIP_ITEM));
        if (catches.isEmpty())
            return Results.ERROR("The inventory is nearly full of other things: make room for the catch");
        for (WItem item : catches) {
            NGItem ngi = (NGItem) item.item;
            String name = ngi.name();
            if (name == null)
                continue;
            // Resolved per item: output zones can be split by quality.
            if (context.addOutItem(name, null, ngi.quality != null ? ngi.quality : 1))
                targets.add(name);
        }
        if (targets.isEmpty())
            return Results.ERROR("Inventory is full and no output zone accepts the Smokerings or Smokeships");

        gui.msg("Inventory nearly full, delivering the catch");
        new TransferItems2(context, targets).run(gui);
        Results back = goHome();
        if (!back.IsSuccess())
            return back;
        if (gui.getInventory().getFreeSpace() <= freeBefore)
            return Results.ERROR("Delivering the catch freed no space: is its storage full?");
        return Results.SUCCESS();
    }

    /** Chases every ring and ship in sight until each is caught or gone. */
    private void chase(NGameUI gui) throws InterruptedException {
        final NInventory inv = gui.getInventory();
        Gob target;
        while ((target = catchable()) != null) {
            if (hpLow())
                return;
            long id = target.id;
            final int ringsBefore = count(gui, RING_ITEM);
            final int shipsBefore = count(gui, SHIP_ITEM);
            long deadline = System.currentTimeMillis() + CHASE_TIMEOUT;
            for (Gob g = target; g != null; g = Finder.findGob(id)) {
                if (System.currentTimeMillis() >= deadline || hpLow())
                    break;
                NUtils.rclickGob(g);
                NUtils.addTask(new WaitDuration(CHASE_CLICK_PERIOD));
            }
            // The gob is gone (or we gave up); the item shows up a beat later.
            waitFor(() -> countRes(inv, RING_ITEM) > ringsBefore || countRes(inv, SHIP_ITEM) > shipsBefore,
                    PICKUP_TIMEOUT);
            int gotRings = count(gui, RING_ITEM) - ringsBefore;
            int gotShips = count(gui, SHIP_ITEM) - shipsBefore;
            if (gotRings > 0 || gotShips > 0) {
                rings += Math.max(gotRings, 0);
                ships += Math.max(gotShips, 0);
                gui.msg((gotShips > 0 ? SHIP_NAME : RING_NAME) + " caught (" + rings + " rings, "
                        + ships + " ships this run)");
            } else {
                // Out of reach or gone. Stop counting it as a target, or a stubborn one would keep
                // the bot away from its spot forever.
                ignored.add(id);
                gui.msg(Finder.findGob(id) != null ? "Could not reach it, giving up on it" : "It got away");
            }
        }
        NUtils.getUI().dropLastError();
    }

    /** Nearest ring or ship we have not already given up on, or null. Safe on either thread. */
    private Gob catchable() {
        Gob player = NUtils.player();
        Gob best = null;
        double bestd = Double.MAX_VALUE;
        for (Gob g : Finder.findGobs(CATCHABLE)) {
            if ((player != null && g.id == player.id) || ignored.contains(g.id))
                continue;
            if (logged.add(g.id))
                System.out.println("SmokeringBot: catchable gob " + g.id + " " + g.ngob.name);
            double d = (player == null) ? 0 : g.rc.dist(player.rc);
            if (best == null || d < bestd) {
                bestd = d;
                best = g;
            }
        }
        return best;
    }

    /**
     * Walks back to where the player stood when the bot started. The bookmark is grid-relative, so it
     * survives the chunk-nav hops of a trip to a distant barrel. One retry, then the run stops: a bot
     * smoking somewhere it was not put is worse than one that stops.
     */
    private Results goHome() throws InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Gob player = NUtils.player();
            Coord2d spot = home.getCurrentCoord();
            if (player != null && spot != null && player.rc.dist(spot) < HOME_TOLERANCE)
                return Results.SUCCESS();
            if (NUtils.navigateTo(home))
                return Results.SUCCESS();
        }
        return Results.ERROR("Could not walk back to the starting spot");
    }

    // --- The pipe ---

    /*
     * Pipe state. These read UI-owned fields and run on the UI thread: inside an NTask, or through
     * onUi() from the bot thread.
     */

    /** The worn Bull Pipe, or null. */
    private static WItem pipe() {
        NEquipory equip = NUtils.getEquipment();
        if (equip == null)
            return null;
        for (WItem item : equip.quickslots) {
            if (item == null)
                continue;
            try {
                Resource res = item.item.getres();
                if (res != null && res.name.equals(PIPE_ITEM))
                    return item;
            } catch (Loading l) {
                // Not resolved yet; the next call will see it.
            }
        }
        return null;
    }

    /**
     * Sprite flags of the pipe as drawn on the player, or -1 when it is not drawn (yet). Only
     * {@link #LIT_BIT} is used: it switches on the embers and the smoke. What the pipe holds comes
     * from its tooltip instead.
     */
    private static int pipeSprite() {
        Gob player = NUtils.player();
        if (player == null)
            return -1;
        Composite c = player.getattr(Composite.class);
        if (c == null || c.comp == null)
            return -1;
        for (Composited.ED ed : c.comp.cequ) {
            if (ed.res != null && PIPE_SPRITE.equals(resnm(ed.res.res)))
                return Sprite.decnum(new MessageBuf(ed.res.sdt));
        }
        return -1;
    }

    private static boolean lit() {
        int fl = pipeSprite();
        return fl >= 0 && (fl & LIT_BIT) != 0;
    }

    private static boolean filled() {
        return pipeKg() > 0;
    }

    /** Empty, or down to the top-up mark. False while the amount is unknown. */
    private static boolean needsFuel() {
        double kg = pipeKg();
        return kg >= 0 && kg <= TOPUP_KG;
    }

    /**
     * Kilograms in the pipe, read from its tooltip's Contents line ("0.40 kg of Pipestuff"); 0 when
     * the tooltip lists no contents, -1 when it cannot be told yet - no pipe, or its tooltip has not
     * arrived. Read from the built item info rather than NGItem.content(), which drops any contents
     * line that carries no quality.
     */
    private static double pipeKg() {
        WItem pipe = pipe();
        // infoseq stays 0 until the first tooltip arrives; info() cannot be built before that.
        if (pipe == null || pipe.item.infoseq == 0)
            return -1;
        List<ItemInfo> info;
        try {
            info = pipe.item.info();
        } catch (Loading l) {
            return -1;
        }
        double kg = 0;
        for (ItemInfo inf : info) {
            if (!(inf instanceof ItemInfo.Contents))
                continue;
            for (ItemInfo sub : ((ItemInfo.Contents) inf).sub) {
                if (!(sub instanceof ItemInfo.Name))
                    continue;
                Matcher m = AMOUNT_OF.matcher(((ItemInfo.Name) sub).str.text.trim());
                if (!m.matches())
                    continue;
                double amount = Double.parseDouble(m.group(1));
                kg += m.group(2).equalsIgnoreCase("g") ? amount / 1000 : amount;
            }
        }
        return kg;
    }

    /**
     * Waits for the pipe's tooltip, so "how much is in it" is answered from the tooltip, never
     * guessed. Fails when there is no pipe or its tooltip does not come.
     */
    private static Results awaitPipeInfo() throws InterruptedException {
        if (!waitFor(() -> pipeKg() >= 0, PIPE_INFO_TIMEOUT))
            return Results.ERROR(pipe() == null ? "The Bull Pipe is no longer equipped"
                    : "Could not read the pipe's tooltip");
        return Results.SUCCESS();
    }

    /** What the player's composite wears, for the log when the lit signal cannot be found. */
    private static String equipSprites() {
        Gob player = NUtils.player();
        Composite c = (player == null) ? null : player.getattr(Composite.class);
        if (c == null || c.comp == null)
            return "<no composite>";
        StringBuilder sb = new StringBuilder();
        for (Composited.ED ed : c.comp.cequ) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(ed.res == null ? "?" : resnm(ed.res.res));
            if (ed.res != null)
                sb.append("#").append(Sprite.decnum(new MessageBuf(ed.res.sdt)));
        }
        return sb.toString();
    }

    private static String resnm(Indir<Resource> res) {
        if (res instanceof Session.CachedRes.Ref)
            return ((Session.CachedRes.Ref) res).resnm();
        try {
            return res.get().name;
        } catch (Loading l) {
            return null;
        }
    }

    private static boolean hpLow() {
        double hp = NUtils.getHPFraction();
        return hp >= 0 && hp < HP_STOP;
    }

    /**
     * A "take" zone for Pipestuff exists anywhere. A configuration lookup, like findOutsGlobal: it
     * does not ask whether the zone can be routed to right now.
     */
    private static boolean hasPipestuffInput() {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || gui.map == null)
            return false;
        for (Integer id : gui.map.nols.keySet()) {
            if (id <= 0)
                continue;
            NArea area = gui.map.glob.map.areas.get(id);
            if (area != null && !area.isDisabled() && area.getInput(PIPESTUFF) != null)
                return true;
        }
        return false;
    }

    // --- Waiting ---

    /** Evaluated on the UI thread, so it may walk widget trees directly. */
    private interface Cond {
        boolean done();
    }

    /**
     * Stands still until HP drops, a ring shows up, the pipe goes out or needs a top-up (only worth
     * waking for with Pipestuff to spare), or {@code timeoutMs} passes. Checked once per frame, so a
     * ring is spotted the frame it spawns.
     */
    private void watch(boolean spare, long timeoutMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0);
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return hpLow() || catchable() != null || !lit() || (spare && needsFuel())
                        || System.currentTimeMillis() >= deadline;
            }
        });
    }

    /**
     * Reads pipe state on the UI thread. The pipe's contents list is cleared and refilled there on
     * every tooltip update, so iterating it from the bot thread can throw or catch it empty.
     */
    private static <T> T onUi(Supplier<T> read) throws InterruptedException {
        final List<T> out = new ArrayList<>(1);
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                out.clear();
                out.add(read.get());
                return true;
            }
        });
        return out.get(0);
    }

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

    // --- Inventory, walked on the UI thread (NInventory queries would block on it) ---

    private static int count(NGameUI gui, String prefix) throws InterruptedException {
        return collect(gui, w -> resStarts(w, prefix)).size();
    }

    private static int countPipestuff(NGameUI gui) throws InterruptedException {
        return collect(gui, w -> nameIs(w, PIPESTUFF)).size();
    }

    private static ArrayList<WItem> collect(NGameUI gui, Predicate<WItem> match) throws InterruptedException {
        final NInventory inv = gui.getInventory();
        final ArrayList<WItem> result = new ArrayList<>();
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                result.clear();
                walk(inv, match, result);
                return true;
            }
        });
        return result;
    }

    private static int countRes(NInventory inv, String prefix) {
        return walk(inv, w -> resStarts(w, prefix), null);
    }

    private static int countName(NInventory inv, String name) {
        return walk(inv, w -> nameIs(w, name), null);
    }

    private static boolean resStarts(WItem item, String prefix) {
        try {
            Resource res = item.item.getres();
            return res != null && res.name.startsWith(prefix);
        } catch (Loading l) {
            // Not resolved yet; it will be counted on a later poll.
            return false;
        }
    }

    private static boolean nameIs(WItem item, String name) {
        return name.equals(((NGItem) item.item).name());
    }

    private static int walk(NInventory inv, Predicate<WItem> match, List<WItem> out) {
        if (inv == null)
            return 0;
        synchronized (inv.ui) {
            return walk(inv.child, match, out);
        }
    }

    /** Descends into bundled stacks, which hold the real items and are never counted themselves. */
    private static int walk(Widget first, Predicate<WItem> match, List<WItem> out) {
        int n = 0;
        for (Widget w = first; w != null; w = w.next) {
            if (!(w instanceof WItem))
                continue;
            WItem item = (WItem) w;
            if (item.item.contents != null) {
                if (item.item.contents instanceof ItemStack)
                    n += walk(item.item.contents.child, match, out);
                continue;
            }
            if (match.test(item)) {
                n++;
                if (out != null)
                    out.add(item);
            }
        }
        return n;
    }
}
