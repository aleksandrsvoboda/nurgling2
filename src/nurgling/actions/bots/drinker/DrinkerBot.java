package nurgling.actions.bots.drinker;

import haven.*;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.conf.NDrinkerProp;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitCheckable;
import nurgling.tasks.WaitFreeHand;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.bots.DrinkerWnd;

import java.util.*;
import java.util.function.Predicate;

/**
 * Takes a set number of sips of each chosen drink. Every cup the run needs is fetched from the
 * cups' TAKE areas in one trip, one cup per drink. Each drink is then filled from the first barrel
 * holding it in the drink's barrel TAKE area and sipped next to that barrel, and what is left is
 * poured back into it. At the end the cups go back to the containers they came from.
 */
public class DrinkerBot implements Action {

    /** Scenario settings carry a "sips:&lt;drink&gt;" number and a "cup:&lt;drink&gt;" name per drink. */
    public static final String SETTING_PREFIX = "sips:";
    public static final String CUP_PREFIX = "cup:";

    // NTask ticks; the client runs roughly 30 of them a second.
    private static final int HAND_TICKS = 90;
    private static final int SIP_TICKS = 600;
    private static final int SETTLE_TICKS = 15;

    /** Sip counts from scenario settings; null means ask with the dialog. */
    private final Map<String, Integer> presetSips;
    private final Map<String, String> presetCups;

    public DrinkerBot() {
        presetSips = null;
        presetCups = Map.of();
    }

    public DrinkerBot(Map<String, Object> settings) {
        Map<String, Integer> sips = null;
        Map<String, String> cups = new HashMap<>();
        if (settings != null) {
            for (Map.Entry<String, Object> e : settings.entrySet()) {
                if (e.getKey().startsWith(SETTING_PREFIX) && e.getValue() instanceof Number) {
                    if (sips == null)
                        sips = new HashMap<>();
                    sips.put(e.getKey().substring(SETTING_PREFIX.length()), ((Number) e.getValue()).intValue());
                } else if (e.getKey().startsWith(CUP_PREFIX) && e.getValue() instanceof String) {
                    cups.put(e.getKey().substring(CUP_PREFIX.length()), (String) e.getValue());
                }
            }
        }
        presetSips = sips;
        presetCups = cups;
    }

    /** One drink to take: how many sips, from which cup. */
    private static final class Order {
        final String drink;
        final String cup;
        final int sips;

        Order(String drink, String cup, int sips) {
            this.drink = drink;
            this.cup = cup;
            this.sips = sips;
        }
    }

    /** Cups taken out of one container, to be put back into it. */
    private static final class Taken {
        final Container container;
        final String cup;
        final int count;

        Taken(Container container, String cup, int count) {
            this.container = container;
            this.cup = cup;
            this.count = count;
        }
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        List<Order> orders;
        if (presetSips != null) {
            orders = active(presetSips, presetCups);
            if (orders.isEmpty())
                return Results.ERROR("Drinker: every drink is set to 0 sips");
            List<String> problems = findProblems(orders);
            if (!problems.isEmpty())
                return Results.ERROR("Drinker: " + String.join("; ", problems));
        } else {
            orders = askOrders(gui);
            if (orders == null)
                return Results.ERROR("No config");
        }

        NContext context = new NContext(gui);
        LinkedHashMap<String, List<String>> drinksByCup = new LinkedHashMap<>();
        for (Order o : orders)
            drinksByCup.computeIfAbsent(o.cup, k -> new ArrayList<>()).add(o.drink);

        // Cups already carried (an interrupted run, say) count towards what is needed.
        LinkedHashMap<String, Integer> needed = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, List<String>> e : drinksByCup.entrySet()) {
            int have = 0;
            for (WItem cup : cupsIn(gui.getInventory(), e.getKey())) {
                String content = drinkIn(cup);
                if (content == null || containsIgnoreCase(e.getValue(), content))
                    have++;
            }
            int need = Math.max(0, e.getValue().size() - have);
            needed.put(e.getKey(), need);
            total += need;
        }
        if (gui.getInventory().getNumberFreeCoord(new Coord(1, 1)) < total)
            return Results.ERROR("Drinker: no room in the inventory for " + total + " cups");

        List<Taken> taken = new ArrayList<>();
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            if (e.getValue() > 0)
                fetchCups(gui, context, e.getKey(), e.getValue(), taken);
        }

        List<String> report = new ArrayList<>();
        boolean complete = true;
        for (Order o : orders) {
            String line = drink(gui, o.drink, o.cup, o.sips);
            if (!line.startsWith("ok:"))
                complete = false;
            report.add(line.startsWith("ok:") ? line.substring(3) : line);
        }

        String unreturned = returnCups(gui, context, taken);
        if (unreturned != null) {
            complete = false;
            report.add(unreturned);
        }

        String summary = "Drinker: " + String.join(", ", report);
        System.out.println(summary);
        gui.msg(summary);
        return complete ? Results.SUCCESS() : Results.FAIL();
    }

    /**
     * Shows the dialog until Start is pressed with every chosen drink set up, or the window is
     * closed (null). Area lookups plan routes, so they run here on the bot thread, not in the window.
     */
    private List<Order> askOrders(NGameUI gui) throws InterruptedException {
        NDrinkerProp prop = NDrinkerProp.get(NUtils.getUI().sessInfo);
        Map<String, Integer> startSips = prop != null ? prop.sips : Map.of();
        Map<String, String> startCups = prop != null ? prop.cups : Map.of();
        List<String> problems = List.of();
        while (true) {
            DrinkerWnd w = null;
            try {
                NUtils.getUI().core.addTask(new WaitCheckable(gui.add((w = new DrinkerWnd(startSips, startCups, problems)), UI.scale(200, 200))));
            } finally {
                if (w != null)
                    w.destroy();
            }
            if (w.sips == null)
                return null;
            if (prop != null) {
                prop.sips.clear();
                prop.sips.putAll(w.sips);
                prop.cups.clear();
                prop.cups.putAll(w.cups);
                NDrinkerProp.set(prop);
            }
            List<Order> orders = active(w.sips, w.cups);
            problems = orders.isEmpty() ? List.of("Set at least one drink above 0 sips") : findProblems(orders);
            if (problems.isEmpty())
                return orders;
            startSips = w.sips;
            startCups = w.cups;
        }
    }

    /** The drinks with more than 0 sips, in the order of the drink list, each with its cup. */
    private static List<Order> active(Map<String, Integer> sips, Map<String, String> cups) {
        List<Order> res = new ArrayList<>();
        for (Vessels.Drink d : Vessels.DRINKS) {
            Integer n = sips.get(d.name);
            if (n != null && n > 0) {
                String cup = cups.get(d.name);
                res.add(new Order(d.name, cup != null && Vessels.isCup(cup) ? cup : d.cup, n));
            }
        }
        return res;
    }

    /** Every missing area for these drinks, so the user can fix them all at once. */
    private static List<String> findProblems(List<Order> orders) {
        LinkedHashSet<String> problems = new LinkedHashSet<>();
        for (Order o : orders) {
            NArea area = NContext.findInGlobal(o.drink);
            NArea.Ingredient in = area == null ? null : area.getInput(o.drink);
            if (in == null || in.type != NArea.Ingredient.Type.BARREL)
                problems.add("No TAKE area with " + o.drink + " barrels");
            if (NContext.findInGlobal(o.cup) == null)
                problems.add("No TAKE area for " + o.cup);
        }
        return new ArrayList<>(problems);
    }

    /** Takes up to {@code count} empty cups from the cup's TAKE area, one container open at a time. */
    private void fetchCups(NGameUI gui, NContext context, String cup, int count, List<Taken> taken) throws InterruptedException {
        context.addInItem(cup, null);
        int left = count;
        for (NContext.ObjectStorage storage : context.getInStorages(cup)) {
            if (left == 0)
                break;
            if (!(storage instanceof Container))
                continue;
            Container cont = (Container) storage;
            if (Container.pathTo(gui, cont) == null)
                continue;
            if (!new OpenTargetContainer(cont).run(gui).IsSuccess())
                continue;
            int got = 0;
            for (WItem item : cupsIn(gui.getInventory(cont.cap), cup)) {
                if (left == 0)
                    break;
                // A cup that already holds a drink belongs to someone; leave it be.
                if (drinkIn(item) != null)
                    continue;
                if (gui.getInventory().findFreeCoord(item) == null)
                    break;
                if (TransferToContainer.transfer(item, gui.getInventory(), 1) > 0) {
                    got++;
                    left--;
                }
            }
            new CloseTargetContainer(cont).run(gui);
            if (got > 0)
                taken.add(new Taken(cont, cup, got));
        }
        if (left > 0)
            System.out.println("Drinker: found only " + (count - left) + " of " + count + " empty " + cup);
    }

    /**
     * Fills, sips and pours back one drink. Returns "ok:Wine 5/5" when every sip was taken,
     * otherwise a line saying how far it got and why it stopped.
     */
    private String drink(NGameUI gui, String drink, String cup, int sips) throws InterruptedException {
        if (findCup(gui, cup, drink) == null)
            return drink + " 0/" + sips + " (no " + cup + ")";
        NArea area = NContext.findInGlobal(drink);
        if (area == null || !NUtils.navigateToArea(area, true))
            return drink + " 0/" + sips + " (can't reach the " + drink + " area)";
        ArrayList<Gob> barrels = Finder.findGobs(area, new NAlias("barrel"));
        barrels.sort(NUtils.d_comp);

        Gob barrel = null;
        int next = 0;
        int done = 0;
        String stop = null;
        while (done < sips) {
            WItem item = findCup(gui, cup, drink);
            if (item == null) {
                stop = "lost the " + cup;
                break;
            }
            if (drinkIn(item) == null) {
                boolean filled = false;
                while (!filled) {
                    if (barrel == null) {
                        while (next < barrels.size() && !NUtils.barrelHasContent(barrels.get(next)))
                            next++;
                        if (next >= barrels.size())
                            break;
                        barrel = barrels.get(next++);
                    }
                    filled = fill(gui, cup, drink, barrel);
                    if (!filled)
                        barrel = null;
                }
                if (!filled) {
                    stop = "out of " + drink;
                    break;
                }
                continue;
            }
            if (barrel == null) {
                // The cup came with this drink in it; sip it next to the first barrel.
                while (next < barrels.size() && !NUtils.barrelHasContent(barrels.get(next)))
                    next++;
                if (next < barrels.size()) {
                    barrel = barrels.get(next++);
                    new PathFinder(barrel).run(gui);
                }
            }
            NUtils.getUI().dropLastError();
            if (!new SelectFlowerAction("Sip", item).run(gui).IsSuccess()) {
                stop = "no Sip option on the " + cup;
                break;
            }
            WaitSip sip = new WaitSip(item);
            NUtils.addTask(sip);
            if (sip.error != null) {
                stop = sip.error;
                break;
            }
            if (sip.timedOut) {
                stop = "a sip never finished";
                break;
            }
            done++;
        }

        String line = drink + " " + done + "/" + sips;
        WItem item = findCup(gui, cup, drink);
        if (item != null && drinkIn(item) != null) {
            if (barrel == null || !pour(gui, cup, drink, barrel)) {
                WItem left = findCup(gui, cup, drink);
                line += " (" + (left != null ? contentName(left) : "some") + " left in the " + cup + ")";
            }
        }
        if (stop != null)
            return line + " (" + stop + ")";
        return line.contains(" left in the ") ? line : "ok:" + line;
    }

    /** Fills an empty cup of this kind at the barrel. False if the barrel gave nothing, or another drink. */
    private boolean fill(NGameUI gui, String cup, String drink, Gob barrel) throws InterruptedException {
        new PathFinder(barrel).run(gui);
        WItem item = emptyCup(gui, cup);
        if (item == null)
            return false;
        NUtils.takeItemToHand(item);
        NUtils.activateItem(barrel);
        boolean ok = waitHand(g -> !g.content().isEmpty());
        if (ok) {
            String got = drinkIn(gui.vhand);
            if (got == null || !got.equalsIgnoreCase(drink)) {
                System.out.println("Drinker: barrel " + barrel.id + " holds " + got + ", not " + drink + "; pouring it back");
                NUtils.activateItem(barrel, true);
                waitHand(g -> g.content().isEmpty());
                ok = false;
            }
        }
        NUtils.dropToInv();
        NUtils.addTask(new WaitFreeHand());
        return ok;
    }

    /** Pours the cup back into the barrel. False if something is still in the cup afterwards. */
    private boolean pour(NGameUI gui, String cup, String drink, Gob barrel) throws InterruptedException {
        new PathFinder(barrel).run(gui);
        WItem item = findCup(gui, cup, drink);
        if (item == null)
            return true;
        NUtils.takeItemToHand(item);
        NUtils.activateItem(barrel, true);
        boolean ok = waitHand(g -> g.content().isEmpty());
        NUtils.dropToInv();
        NUtils.addTask(new WaitFreeHand());
        return ok;
    }

    /**
     * Puts the taken cups back where they came from, emptiest first, opening each container once.
     * Cups that no longer fit there go into any container of the cup's TAKE area. Returns a line
     * for the report if some cups could not be put away, otherwise null.
     */
    private String returnCups(NGameUI gui, NContext context, List<Taken> taken) throws InterruptedException {
        LinkedHashMap<String, Integer> left = new LinkedHashMap<>();
        for (Taken t : taken) {
            int put = 0;
            if (Container.pathTo(gui, t.container) != null && new OpenTargetContainer(t.container).run(gui).IsSuccess()) {
                put = putCups(gui, gui.getInventory(t.container.cap), t.cup, t.count);
                new CloseTargetContainer(t.container).run(gui);
            }
            if (put < t.count)
                left.merge(t.cup, t.count - put, Integer::sum);
        }
        List<String> stranded = new ArrayList<>();
        for (Map.Entry<String, Integer> e : left.entrySet()) {
            int remaining = e.getValue();
            for (NContext.ObjectStorage storage : context.getInStorages(e.getKey())) {
                if (remaining == 0)
                    break;
                if (!(storage instanceof Container))
                    continue;
                Container cont = (Container) storage;
                if (Container.pathTo(gui, cont) == null || !new OpenTargetContainer(cont).run(gui).IsSuccess())
                    continue;
                remaining -= putCups(gui, gui.getInventory(cont.cap), e.getKey(), remaining);
                new CloseTargetContainer(cont).run(gui);
            }
            if (remaining > 0)
                stranded.add(remaining + " " + e.getKey());
        }
        return stranded.isEmpty() ? null : "still carrying " + String.join(", ", stranded);
    }

    /** Moves up to {@code count} cups of this kind into an open container, empty ones first. */
    private int putCups(NGameUI gui, NInventory target, String cup, int count) throws InterruptedException {
        int put = 0;
        while (put < count) {
            WItem item = emptyCup(gui, cup);
            if (item == null) {
                List<WItem> any = cupsIn(gui.getInventory(), cup);
                if (any.isEmpty())
                    break;
                item = any.get(0);
            }
            if (target.findFreeCoord(item) == null || TransferToContainer.transfer(item, target, 1) == 0)
                break;
            put++;
        }
        return put;
    }

    /** The cup of this kind holding this drink, or else an empty one; null if there is neither. */
    private static WItem findCup(NGameUI gui, String cup, String drink) throws InterruptedException {
        WItem empty = null;
        for (WItem item : cupsIn(gui.getInventory(), cup)) {
            String content = drinkIn(item);
            if (content == null) {
                if (empty == null)
                    empty = item;
            } else if (content.equalsIgnoreCase(drink)) {
                return item;
            }
        }
        return empty;
    }

    private static WItem emptyCup(NGameUI gui, String cup) throws InterruptedException {
        for (WItem item : cupsIn(gui.getInventory(), cup)) {
            if (drinkIn(item) == null)
                return item;
        }
        return null;
    }

    /** Cups by exact name: "Mug" must not pick up a Metal Mug, nor "Glass" a Glass Jug. */
    private static List<WItem> cupsIn(NInventory inv, String cup) throws InterruptedException {
        List<WItem> res = new ArrayList<>();
        for (WItem item : inv.getItems(new NAlias(cup))) {
            if (cup.equals(((NGItem) item.item).name()))
                res.add(item);
        }
        return res;
    }

    /** The full content line, e.g. "0.35 l of Wine", or null for an empty cup. */
    private static String contentName(WItem cup) {
        ArrayList<NGItem.NContent> content = ((NGItem) cup.item).content();
        return content.isEmpty() ? null : content.get(0).name();
    }

    /** What the cup holds ("Wine" out of "0.35 l of Wine"), or null when it is empty. */
    private static String drinkIn(WItem cup) {
        String name = contentName(cup);
        if (name == null)
            return null;
        int of = name.indexOf(" of ");
        return of < 0 ? name.trim() : name.substring(of + 4).trim();
    }

    private static boolean containsIgnoreCase(List<String> names, String name) {
        for (String n : names) {
            if (n.equalsIgnoreCase(name))
                return true;
        }
        return false;
    }

    /** Waits a few seconds for the item in hand to meet the condition; false if it never did. */
    private static boolean waitHand(Predicate<NGItem> cond) throws InterruptedException {
        boolean[] met = {false};
        NUtils.addTask(new NTask() {
            int ticks = 0;

            @Override
            public boolean check() {
                WItem hand = NUtils.getGameUI().vhand;
                if (hand != null && hand.item.spr != null && cond.test((NGItem) hand.item)) {
                    met[0] = true;
                    return true;
                }
                return ticks++ > HAND_TICKS;
            }
        });
        return met[0];
    }

    /**
     * Waits for one sip to end: the cup's content line changed and the drinking pose is over,
     * or the pose came and went. A game message in the meantime means the sip was refused.
     */
    private static class WaitSip extends NTask {
        private final WItem cup;
        private final String before;
        private int ticks = 0;
        private boolean drank = false;
        String error = null;
        boolean timedOut = false;

        WaitSip(WItem cup) {
            this.cup = cup;
            this.before = contentName(cup);
        }

        @Override
        public boolean check() {
            String err = NUtils.getUI().getLastError();
            if (err != null) {
                error = err;
                return true;
            }
            Gob player = NUtils.player();
            String pose = player == null ? null : player.pose();
            boolean drinking = pose != null && pose.contains("drinkan");
            if (drinking)
                drank = true;
            boolean changed = cup.parent == null || !Objects.equals(contentName(cup), before);
            // A content change seen before the pose starts gets a moment for the pose to show up,
            // so the next Sip is not sent while this one is still being drunk.
            if (!drinking && (drank || (changed && ticks > SETTLE_TICKS)))
                return true;
            if (ticks++ > SIP_TICKS) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }
}
