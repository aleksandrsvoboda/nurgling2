package nurgling.actions.bots;

import haven.*;
import haven.res.ui.tt.ingred.Ingredient;
import nurgling.NConfig;
import nurgling.NCore;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.FreeInventory2;
import nurgling.actions.Results;
import nurgling.actions.TakeFromStorageIndex;
import nurgling.areas.NContext;
import nurgling.cookbook.GenericTypes;
import nurgling.cookbook.Variations;
import nurgling.cookbook.connection.RecipeCatalogLoader;
import nurgling.i18n.L10n;
import nurgling.tasks.NTask;
import nurgling.tools.CraftCategories;
import nurgling.tools.ItemIcons;
import nurgling.widgets.NCookBook;
import nurgling.widgets.NMakewindow;
import nurgling.widgets.storage.VillageStorage;
import org.json.JSONObject;

import java.util.*;

/**
 * Crafts a food once for every untried option of one of its ingredient choices (the cookbook's
 * untried variations), so the cookbook records them. The other choices stay at the option the
 * village uses most. Only the village's storage and input areas are drawn from, and each craft
 * fetches its own ingredients: one fetch, one craft. A food made in stages (Meatpie from Unbaked
 * Meatpie) is crafted to its first stage and recorded once the player finishes it.
 */
public class TryUntriedVariations implements Action {
    private final String dish;
    private final String slotTitle;
    private final List<String> craftNames;
    private final VillageStorage village;
    private final Runnable done;

    /* One craft: the option tried, or null to go without the optional input. */
    private static final class Attempt {
        final Variations.Option option;
        final JSONObject ingredient;

        Attempt(Variations.Option option, JSONObject ingredient) {
            this.option = option;
            this.ingredient = ingredient;
        }

        String label() {
            return (option == null) ? "without it" : option.name;
        }
    }

    /**
     * @param craftNames the crafting actions that may make the food, from {@link Variations#craftNames}
     * @param village    read on the UI thread
     * @param done       run when the bot ends, whatever the outcome
     */
    public TryUntriedVariations(String dish, String slotTitle, List<String> craftNames, VillageStorage village, Runnable done) {
        this.dish = dish;
        this.slotTitle = slotTitle;
        this.craftNames = craftNames;
        this.village = village;
        this.done = done;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        try {
            return tryAll(gui);
        } finally {
            done.run();
        }
    }

    private Results tryAll(NGameUI gui) throws InterruptedException {
        if(!(Boolean)NConfig.get(NConfig.Key.ndbenable) || (NCore.databaseManager == null))
            return Results.ERROR("Try untried variations needs the database");
        /* Strictly the village: with no village found, VillageStorage would let every recorded chest in. */
        Set<Long> chunks = village.knowable() ? village.village() : Collections.emptySet();
        if(chunks.isEmpty())
            return Results.ERROR("The village can't be worked out yet (needs ChunkNav and areas)");

        /* Recorded variants as of now, so what was crafted since the cookbook loaded counts as tried. */
        RecipeCatalogLoader loader = new RecipeCatalogLoader(NCore.databaseManager, village);
        loader.run();
        if(loader.error() != null)
            return Results.ERROR("Could not read the cookbook: " + loader.error());
        Variations variations = loader.variations();
        if(variations == null)
            return Results.ERROR("The offline recipe data could not be read");
        Variations.Dish d = variations.of(dish);
        Variations.Slot slot = null;
        for(Variations.Slot s : d.slots) {
            if(slotTitle.equals(s.title))
                slot = s;
        }
        if(slot == null)
            return Results.ERROR(dish + " has no ingredient choice " + slotTitle);

        NMakewindow mw = openWindow(gui);
        if(mw == null)
            return Results.ERROR("You don't know a recipe that makes " + dish);
        String window = mw.rcpnm;
        boolean staged = !GenericTypes.norm(window).equals(GenericTypes.norm(dish));

        int target = specFor(variations, mw, slot);
        if(target < 0)
            return Results.ERROR(slotTitle + " is not chosen in the " + window + " window");
        String targetName = mw.inputs.get(target).name;

        /* The other choices: the option the village used most that it still has. */
        Map<Integer, JSONObject> baseline = new HashMap<>();
        Map<Integer, List<JSONObject>> candidates = new HashMap<>();
        Set<String> names = new HashSet<>();
        for(Variations.Slot s : d.slots) {
            if(s.wood() || (s == slot))
                continue;
            int i = specFor(variations, mw, s);
            if((i < 0) || (i == target) || baseline.containsKey(i) || candidates.containsKey(i))
                continue;
            List<JSONObject> c = new ArrayList<>();
            for(Variations.Option o : s.options) {
                JSONObject ing = (o.tried()) ? ingredient(variations, mw.inputs.get(i).name, o) : null;
                if(ing != null)
                    c.add(ing);
            }
            candidates.put(i, c);
            for(JSONObject ing : c)
                names.add(ing.getString("name"));
        }
        List<Attempt> attempts = new ArrayList<>();
        List<String> missing = new ArrayList<>(), unselectable = new ArrayList<>();
        Map<Variations.Option, JSONObject> untried = new LinkedHashMap<>();
        Set<String> picked = new HashSet<>();
        for(Variations.Option o : slot.options) {
            if(o.tried())
                continue;
            JSONObject ing = ingredient(variations, targetName, o);
            if(ing == null)
                unselectable.add(o.name);
            else if(picked.add(ing.getString("name"))) {
                untried.put(o, ing);
                names.add(ing.getString("name"));
            }
        }
        if(!unselectable.isEmpty())
            gui.msg(dish + ": can't select " + String.join(", ", unselectable) + " in the " + window + " window");
        TakeFromStorageIndex.Source source = TakeFromStorageIndex.Source.load(names, chunks);
        for(Map.Entry<Variations.Option, JSONObject> e : untried.entrySet()) {
            if(available(e.getValue().getString("name"), source))
                attempts.add(new Attempt(e.getKey(), e.getValue()));
            else
                missing.add(e.getKey().name);
        }
        if(slot.optional && !slot.noneTried && mw.inputs.get(target).opt())
            attempts.add(new Attempt(null, null));
        for(Map.Entry<Integer, List<JSONObject>> e : candidates.entrySet()) {
            for(JSONObject ing : e.getValue()) {
                if(available(ing.getString("name"), source)) {
                    baseline.put(e.getKey(), ing);
                    break;
                }
            }
        }

        if(attempts.isEmpty()) {
            gui.msg(dish + ": no untried " + slotTitle + " in the village"
                    + (missing.isEmpty() ? "" : " (not in storage: " + String.join(", ", missing) + ")"));
            return Results.SUCCESS();
        }
        List<String> labels = new ArrayList<>();
        for(Attempt a : attempts)
            labels.add(a.label());
        gui.msg(dish + ": trying " + attempts.size() + " " + slotTitle + " — " + String.join(", ", labels));
        System.out.println("[TryUntried] " + dish + " / " + slotTitle + ": " + labels
                + ", baselines " + baseline.keySet() + ", not in storage " + missing);

        List<String> tried = new ArrayList<>(), notListed = new ArrayList<>();
        for(Attempt a : attempts) {
            mw = currentWindow(gui, window);
            if(mw == null)
                return stopped(gui, tried, a, "the " + window + " window could not be opened again");
            Results r = craftOne(gui, mw, target, a, baseline);
            if(!r.IsSuccess())
                return stopped(gui, tried, a, null);
            if(!staged && (a.option != null) && !listed(gui, a.option.name))
                notListed.add(a.option.name);
            /* Into the food's PUT area when it has one; otherwise it stays in the inventory. */
            new FreeInventory2(new NContext(gui)).run(gui);
            tried.add(a.label());
        }

        StringBuilder sum = new StringBuilder(dish + ": tried " + tried.size() + " " + slotTitle);
        if(!missing.isEmpty())
            sum.append("; not in storage: ").append(String.join(", ", missing));
        if(!notListed.isEmpty())
            sum.append("; the game doesn't list ").append(String.join(", ", notListed)).append(", so they don't change its FEPs");
        gui.msg(sum.toString());
        if(staged)
            gui.msg("Finish these " + tried.size() + " " + window + " into " + dish + " to record them in the cookbook");
        return Results.SUCCESS();
    }

    private static Results stopped(NGameUI gui, List<String> tried, Attempt at, String why) {
        String head = "Tried " + tried.size() + (tried.isEmpty() ? "" : " (" + String.join(", ", tried) + ")")
                + ", stopped at " + at.label();
        return (why != null) ? Results.ERROR(head + ": " + why) : Results.ERROR(head);
    }

    /* ---- the crafting window ---- */

    private NMakewindow openWindow(NGameUI gui) throws InterruptedException {
        MenuGrid.Pagina pag = NCookBook.recipePagina(gui, craftNames);
        if(pag == null)
            return null;
        String name = pag.button().name();
        NMakewindow existing = (gui.craftwnd != null) ? gui.craftwnd.makeWidget : null;
        if((existing != null) && sameRecipe(existing, name)) {
            ready(gui, existing);
            return existing;
        }
        gui.menu.use(pag.button(), new MenuGrid.Interaction(), false);
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return (gui.craftwnd != null) && (gui.craftwnd.makeWidget != null) && (gui.craftwnd.makeWidget != existing)
                        && sameRecipe(gui.craftwnd.makeWidget, name);
            }
        });
        NMakewindow mw = gui.craftwnd.makeWidget;
        ready(gui, mw);
        return mw;
    }

    /* The open window when it still shows the recipe (lighting a fire can swap it), else the recipe reopened. */
    private NMakewindow currentWindow(NGameUI gui, String window) throws InterruptedException {
        NMakewindow mw = (gui.craftwnd != null) ? gui.craftwnd.makeWidget : null;
        if((mw != null) && sameRecipe(mw, window))
            return mw;
        mw = openWindow(gui);
        return ((mw != null) && sameRecipe(mw, window)) ? mw : null;
    }

    private static boolean sameRecipe(NMakewindow mw, String name) {
        return (mw.rcpnm != null) && GenericTypes.norm(mw.rcpnm).equals(GenericTypes.norm(name));
    }

    /* Autocraft on, and every input named and sorted into category or not, as AutocraftBot waits. */
    private static void ready(NGameUI gui, NMakewindow mw) throws InterruptedException {
        mw.setAutoMode(true);
        final boolean headless = nurgling.headless.Headless.isHeadless();
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                if((mw.inputs == null) || mw.inputs.isEmpty())
                    return false;
                for(NMakewindow.Spec s : mw.inputs) {
                    if((s.name == null) || (!headless && (s.spr == null)))
                        return false;
                }
                return true;
            }
        });
    }

    /* ---- matching choices to the window's inputs ---- */

    /* The category input of the window a choice's options go into, or -1. */
    private static int specFor(Variations variations, NMakewindow mw, Variations.Slot slot) {
        int best = -1, bestFit = 0;
        for(int i = 0; i < mw.inputs.size(); i++) {
            NMakewindow.Spec s = mw.inputs.get(i);
            if(!CraftCategories.has(s.name))
                continue;
            int fit = 0;
            for(Variations.Option o : slot.options) {
                if(variations.fits(s.name, o.name) || variations.fits(s.name, o.item))
                    fit++;
            }
            if(fit > bestFit) {
                best = i;
                bestFit = fit;
            }
        }
        return best;
    }

    /*
     * What to select in a category input for an option: the input's own entry for one of the names
     * its item is stored under, else one built from the item's icon, as the cookbook's craft button does.
     */
    private static JSONObject ingredient(Variations variations, String input, Variations.Option o) {
        Set<String> keys = new HashSet<>(o.stockKeys());
        List<String> names = new ArrayList<>(variations.itemNames(o.name));
        names.addAll(variations.itemNames(o.item));
        for(String n : names)
            keys.add(GenericTypes.stockKey(n));
        List<JSONObject> options = CraftCategories.options(input);
        if(options != null) {
            for(JSONObject item : options) {
                if(keys.contains(GenericTypes.stockKey(item.optString("name"))))
                    return item;
            }
        }
        if(!variations.fits(input, o.name) && !variations.fits(input, o.item))
            return null;
        JSONObject icon = ItemIcons.descriptor(null, names.get(0), false);
        if((icon != null) && (icon.has("static") || icon.has("layer")))
            return new JSONObject(icon.toString()).put("name", names.get(0));
        return null;
    }

    private static boolean available(String name, TakeFromStorageIndex.Source source) {
        return (NContext.findIn(name) != null) || (NContext.findInGlobal(name) != null) || source.has(name);
    }

    /* ---- one craft ---- */

    private Results craftOne(NGameUI gui, NMakewindow mw, int target, Attempt a, Map<Integer, JSONObject> baseline) throws InterruptedException {
        NMakewindow.Spec ts = mw.inputs.get(target);
        ts.ing = (a.option == null)
                ? mw.new Ingredient(Resource.loadsimg("nurgling/hud/autocraft/ignore"), L10n.get("craft.ignore_ingredient"), true)
                : mw.new Ingredient(a.ingredient);
        for(Map.Entry<Integer, JSONObject> e : baseline.entrySet())
            mw.inputs.get(e.getKey()).ing = mw.new Ingredient(e.getValue());

        /* The game takes any fitting item it finds in the inventory, so nothing else may be there. */
        String stray = stray(gui, mw);
        if(stray != null) {
            new FreeInventory2(new NContext(gui)).run(gui);
            stray = stray(gui, mw);
            if(stray != null)
                return Results.ERROR("The inventory holds " + stray + ", which would go into the craft; put it away");
        }

        /* The result is put away afterwards, and only where a PUT area exists. */
        boolean keep = mw.noTransfer.a;
        mw.noTransfer.a = true;
        try {
            Craft craft = new Craft(mw, 1);
            craft.useStorage = true;
            craft.village = village;
            return craft.run(gui);
        } finally {
            mw.noTransfer.a = keep;
            NMakewindow now = (gui.craftwnd != null) ? gui.craftwnd.makeWidget : null;
            if((now != null) && (now != mw))
                now.noTransfer.a = keep;
        }
    }

    /* An inventory item another option of a category input would take, or null. */
    private static String stray(NGameUI gui, NMakewindow mw) throws InterruptedException {
        for(NMakewindow.Spec s : mw.inputs) {
            if((s.ing == null) || s.ing.isIgnored || !CraftCategories.has(s.name))
                continue;
            String chosen = GenericTypes.stockKey(s.ing.name);
            Set<String> others = new HashSet<>();
            List<JSONObject> options = CraftCategories.options(s.name);
            if(options == null)
                continue;
            for(JSONObject o : options) {
                String k = GenericTypes.stockKey(o.optString("name"));
                if(!k.equals(chosen))
                    others.add(k);
            }
            for(WItem item : gui.getInventory().getItems()) {
                String n = ((NGItem)item.item).name();
                if((n != null) && others.contains(GenericTypes.stockKey(n)))
                    return n;
            }
        }
        return null;
    }

    /* Whether a crafted food in the inventory lists the option; true when there is nothing to tell by. */
    private boolean listed(NGameUI gui, String option) throws InterruptedException {
        String k = GenericTypes.key(option);
        boolean any = false;
        for(WItem item : gui.getInventory().getItems()) {
            NGItem ng = (NGItem)item.item;
            if((ng.name() == null) || !GenericTypes.norm(ng.name()).equals(GenericTypes.norm(dish)))
                continue;
            if(ng.info == null)
                continue;
            for(ItemInfo inf : ng.info) {
                if(inf instanceof Ingredient) {
                    any = true;
                    if(GenericTypes.key(((Ingredient)inf).name).equals(k))
                        return true;
                }
            }
        }
        return !any;
    }
}
