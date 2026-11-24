package nurgling.actions.bots;

import haven.*;
import haven.res.lib.itemtex.ItemTex;
import haven.res.ui.tt.cn.CustomName;
import nurgling.*;
import nurgling.actions.*;
import nurgling.areas.*;
import nurgling.tasks.*;
import nurgling.tools.*;
import nurgling.widgets.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static haven.OCache.posres;
import nurgling.tools.StackSupporter;

public class Craft implements Action {


    public Craft(List<NMakewindow.Spec> in, List<NMakewindow.Spec> out, String station, int count) {

    }

    public Craft(List<NMakewindow.Spec> in, List<NMakewindow.Spec> out, String station) {
        this(in, out, station, 1);
    }

    public Craft(NMakewindow mwnd, int size) {
        this.mwnd = mwnd;
        this.count = size;
    }

    public Craft(NMakewindow mwnd) {
        this(mwnd, 1);
    }

    /**
     * Создает Craft в режиме prefilled - ингредиенты уже в инвентаре.
     * TakeItems2 будет пропущен, крафт начнётся сразу.
     * @param mwnd окно крафта
     * @param size количество крафтов
     * @param prefilled true если ингредиенты уже в инвентаре
     */
    public Craft(NMakewindow mwnd, int size, boolean prefilled) {
        this.mwnd = mwnd;
        this.count = size;
        this.prefilled = prefilled;
    }

    NMakewindow mwnd = null;
    String tools = null;
    int count = 0;

    boolean isGlobalMode = false;

    /**
     * Fetch inputs that have no input area from the Storage Items index. Set by the craft window's
     * "Use storage items" toggle only; preset bots leave it off.
     */
    public boolean useStorage = false;
    /**
     * Read on the UI thread by whoever sets useStorage; storage outside the village it describes is
     * never fetched from.
     */
    public nurgling.widgets.storage.VillageStorage village = null;
    private Set<Long> villageChunks = null;
    private TakeFromStorageIndex.Source storage = null;
    /** Exact names of the inputs this run takes from storage instead of an area. */
    private final Set<String> fromStorage = new HashSet<>();

    /**
     * Режим prefilled - ингредиенты уже загружены в инвентарь.
     * Пропускает TakeItems2 при крафте.
     */
    boolean prefilled = false;

    /**
     * Set when an input is a mix of kinds (NMakewindow.Spec.mix). The server chooses which held items
     * go into a craft, so a batch of several crafts' worth could put three grasshoppers into one Bug
     * Collection: with a mix every craft is fetched and made on its own.
     */
    private boolean oneAtATime = false;
    /** Set when an input runs short for another craft: what ran out, else null. */
    private String ranOut = null;
    /**
     * For each "Any" category input (NMakewindow.Ingredient.anyOf), the members some area holds,
     * worked out once per run.
     */
    private final Map<String, List<String>> anyKinds = new HashMap<>();

    /**
     * Original recipe identity captured before PrepareWorkStation runs. Lighting a
     * cold station (LightFire) opens the "Light fire" recipe in the craft window and
     * may leave it open if its own restore fails. We use this to re-select the
     * original recipe so mwnd never ends up pointing at the wrong makeWidget.
     */
    private String savedRecipeName = null;
    private boolean savedAutoMode = false;
    private boolean savedNoTransfer = false;
    private String savedCraftCount = null;
    private final Map<Integer, IngredientSel> savedInputIng = new HashMap<>();
    private final Map<Integer, IngredientSel> savedOutputIng = new HashMap<>();
    private final Map<Integer, List<NMakewindow.Ingredient>> savedInputMix = new HashMap<>();

    private static class IngredientSel {
        final java.awt.image.BufferedImage img;
        final String name;
        final boolean isIgnored;
        final String anyOf;
        IngredientSel(NMakewindow.Ingredient ing) {
            this.img = ing.img;
            this.name = ing.name;
            this.isIgnored = ing.isIgnored;
            this.anyOf = ing.anyOf;
        }
    }

    private int getActualItemCount(WItem item) {
        if (item.item.info != null) {
            for (ItemInfo inf : item.item.info) {
                if (inf instanceof CustomName) {
                    float count = ((CustomName) inf).count;
                    if (count > 0) {
                        return (int) (count * 100);
                    }
                }
            }
        }
        return 1;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if (mwnd != null) {
            return mwnd_run(gui);
        }
        return Results.SUCCESS();
    }

    private Results mwnd_run(NGameUI gui) throws InterruptedException {
        NContext ncontext = new NContext(gui);
        int size = 0;
        oneAtATime = false;
        ranOut = null;
        anyKinds.clear();
        // Pick category ingredients first, so the storage lookup below sees every input's name
        for (NMakewindow.Spec s : mwnd.inputs) {
            if (s.categories && s.ing == null) {
                selectIngredientFromCategory(s);
            }
        }
        if (useStorage) {
            resolveStorageInputs();
        }
        for (NMakewindow.Spec s : mwnd.inputs) {
            // Skip ignored optional ingredients
            if (s.ing != null && s.ing.isIgnored) {
                continue;
            }
            if (s.mixed()) {
                List<String> kinds = new ArrayList<>();
                for (NMakewindow.Ingredient ing : s.kinds()) {
                    kinds.add(ing.name);
                    if (!fromStorage.contains(ing.name)) {
                        ncontext.addInItem(ing.name, ing.img);
                    }
                }
                size += s.count;
                oneAtATime = true;
                System.out.println("[Craft] " + s.name + " slots " + kinds + ", one craft at a time");
                continue;
            }
            if (s.ing != null && s.ing.anyOf != null) {
                List<String> kinds = new ArrayList<>();
                for (String member : categoryMembers(s.name)) {
                    if ((NContext.findIn(member) != null || NContext.findInGlobal(member) != null) && !ncontext.isInBarrel(member)) {
                        kinds.add(member);
                        ncontext.addInItem(member, null);
                    }
                }
                if (kinds.isEmpty()) {
                    return Results.ERROR("No area holds any " + s.name);
                }
                anyKinds.put(s.name, kinds);
                size += s.count;
                System.out.println("[Craft] Any " + s.name + ": " + kinds.size() + " kinds have an area");
                continue;
            }

            String name;
            java.awt.image.BufferedImage img;
            if (!s.categories) {
                name = s.name;
                img = ItemTex.create(ItemTex.save(s.spr));
            } else if (s.ing != null) {
                name = s.ing.name;
                img = s.ing.img;
            } else {
                continue;
            }
            // A storage input has no area; addInItem would ask the player to draw one
            if (!fromStorage.contains(name)) {
                ncontext.addInItem(name, img);
            }
            if (!ncontext.isInBarrel(name)) {
                size += s.count;
            }
        }

        for (NMakewindow.Spec s : mwnd.outputs) {

            if (!mwnd.noTransfer.a) {
                if (!s.categories) {
                    if(!ncontext.isInBarrel(s.name))
                        size += s.count;
                    // Butter put away by kind needs no plain "Butter" area to be drawn
                    ncontext.addOutItem(s.name, hasKindOutArea(ncontext, s.name) ? null : ItemTex.create(ItemTex.save(s.spr)), 1);
                } else if (s.ing != null) {
                    if(!ncontext.isInBarrel(s.ing.name))
                        size += s.count;
                    ncontext.addOutItem(s.ing.name, s.ing.img, 1);
                }
            }
        }

        if (!mwnd.tools.isEmpty()) {
            ncontext.addTools(mwnd.tools);
        } else {
            if (mwnd.outputs.size() == 1) {
                String outName = mwnd.outputs.get(0).name;
                ncontext.addCustomTool(outName);
            }
        }

        if (ncontext.equip != null)
            new Equip(new NAlias(ncontext.equip)).run(gui);

        AtomicInteger left = new AtomicInteger(count);

        for (NMakewindow.Spec s : mwnd.inputs) {
            // Skip ignored optional ingredients
            if (s.ing != null && s.ing.isIgnored) {
                continue;
            }
            
            String item = s.ing == null ? s.name : s.ing.name;
            if (ncontext.isInBarrel(item)) {
                if (ncontext.workstation == null) {
                    NArea barrelwa = ncontext.goToArea(Specialisation.SpecName.barrelworkarea);
                    if (barrelwa == null)
                        return Results.ERROR("Not found area for work with barrels!");
                    else
                        ncontext.bwaused = true;
                }
                else
                {
                    ncontext.bwaused = true;
                }
            }
        }

        // Prepare workstation once before craft loop
        if (ncontext.workstation != null) {
            // Remember the recipe (and selected ingredients) while mwnd is still valid,
            // so we can restore it if PrepareWorkStation leaves a different recipe open.
            saveRecipeState();
            if (!new PrepareWorkStation(ncontext, ncontext.workstation.station).run(gui).IsSuccess()) {
                return Results.ERROR("Failed to prepare workstation");
            }
            if (ncontext.workstation.targetPoint != null) {
                new PathFinder(ncontext.workstation.targetPoint.getCurrentCoord()).run(gui);
            }
            // Re-select our recipe if PrepareWorkStation (LightFire) left a different one open,
            // then refresh mwnd to the live makeWidget.
            refreshMakeWidget(gui);
            // The re-opened recipe builds a fresh makeWidget whose Spec names load
            // asynchronously (Spec.tick resolves them from the sprite/resource). Until a
            // name is non-null, isInBarrel(name) is false, so barrel ingredients (e.g. a
            // 35-unit Wheat Flour / 25-unit Water) are wrongly counted as needing inventory
            // slots and collapse the batch size (for_craft) to 1. Wait for names to load.
            waitForSpecNames(gui);
        }

        Results craftResult = null;
        while (left.get() > 0) {
            craftResult = crafting(ncontext, gui, size, left);
            if (!craftResult.IsSuccess()) {
                return craftResult;
            }
        }

        for (NMakewindow.Spec s : mwnd.inputs) {
            // Skip ignored optional ingredients
            if (s.ing != null && s.ing.isIgnored) {
                continue;
            }
            
            String item = s.ing == null ? s.name : s.ing.name;
            if (ncontext.isInBarrel(item)) {
                new ReturnBarrelFromWorkArea(ncontext, item).run(gui);
            }
        }

        if (!mwnd.noTransfer.a) {
            new FreeInventory2(ncontext).run(gui);
        }


        return Results.SUCCESS();
    }

    Results crafting(NContext ncontext, NGameUI gui, int size, AtomicInteger left) throws InterruptedException {

        double currentEnergy = NUtils.getEnergy();

        if (currentEnergy < 0.25) {
            if (!new RestoreResources().run(gui).IsSuccess()) {
                return Results.ERROR("Energy too low and failed to restore resources");
            }
        }
        
        int freeSpace = NUtils.getGameUI().getInventory().getFreeSpace();

        int for_craft;
        if (size == 0) {
            for_craft = left.get();
        } else {
            // Use stack-aware calculation for better inventory utilization
            for_craft = calculateMaxCraftsWithStacking(ncontext, freeSpace, left.get());
        }


        if (oneAtATime) {
            for_craft = Math.min(for_craft, 1);
        }

        if (for_craft <= 0) {
            return Results.ERROR("Not enough inventory space");
        }

        // Auto-select ingredient from category if not already selected
        for (NMakewindow.Spec s : mwnd.inputs) {
            if (s.categories && s.ing == null) {
                selectIngredientFromCategory(s);
            }
        }
        /* An item can feed two inputs: Horrible Knot asks for Stinging Nettle itself and for String,
         * which Stinging Nettle may also be picked for. Plain inputs are fetched first; slot and Any
         * inputs after them, fetching on top of what the plain ones hold for their own use. */
        Map<String, Integer> plainDemand = new HashMap<>();
        List<NMakewindow.Spec> order = new ArrayList<>();
        List<NMakewindow.Spec> shared = new ArrayList<>();
        for (NMakewindow.Spec s : mwnd.inputs) {
            if (s.mixed() || (s.ing != null && s.ing.anyOf != null)) {
                shared.add(s);
                continue;
            }
            order.add(s);
            String name = inputName(s);
            if (name != null && !ncontext.isInBarrel(name)) {
                plainDemand.merge(name, s.count * for_craft, Integer::sum);
            }
        }
        order.addAll(shared);
        
        for (NMakewindow.Spec s : order) {
            // Skip ignored optional ingredients
            if (s.ing != null && s.ing.isIgnored) {
                continue;
            }

            if (s.mixed()) {
                if (!prefilled) {
                    Results took = takeMix(ncontext, gui, s, plainDemand);
                    if (!took.IsSuccess()) {
                        Results end = endOnRunOut(gui, left);
                        return (end != null) ? end : took;
                    }
                }
                continue;
            }

            if (s.ing != null && s.ing.anyOf != null) {
                if (!prefilled) {
                    int want = s.count * for_craft;
                    int have = takeAnyOf(ncontext, gui, s, want, plainDemand);
                    if (have < want) {
                        if (have >= s.count) {
                            // Craft what was found; the next round finds none and ends the run
                            for_craft = have / s.count;
                            System.out.println("[Craft] Any " + s.name + ": found " + have + " of " + want + ", making " + for_craft);
                        } else {
                            ranOut = "Not enough " + s.name + " in the areas: have " + have + " of " + s.count;
                            Results end = endOnRunOut(gui, left);
                            return (end != null) ? end : Results.ERROR(ranOut);
                        }
                    }
                }
                continue;
            }

            String item = s.ing == null ? s.name : s.ing.name;
            if (ncontext.isInBarrel(item)) {
                // Barrel ingredients are supplied by a barrel placed at the workstation /
                // work area; the station draws from it directly. Only transfer the barrel
                // if it isn't placed yet (e.g. first craft iteration) — a barrel item must
                // never fall through to TakeItems2, since it's not taken into inventory.
                // On later iterations the barrel is already placed and there is nothing to do.
                if (ncontext.getPlacedBarrelHash(item) == null) {
                    if(ncontext.workstation == null) {
                        new TransferBarrelInWorkArea(ncontext, item).run(gui);
                    }
                    else {
                        new TransferBarrelToWorkstation(ncontext, item).run(gui);
                    }
                }
            } else if (!prefilled) {
                // В режиме prefilled ингредиенты уже в инвентаре, пропускаем TakeItems2
                if (ItemVariants.isKey(item)) {
                    Results cleared = clearOtherKinds(ncontext, gui, item);
                    if (!cleared.IsSuccess()) {
                        return cleared;
                    }
                }
                if (fromStorage.contains(item)) {
                    if (!new TakeFromStorageIndex(storage, item, s.count * for_craft).run(gui).IsSuccess()) {
                        return Results.ERROR("Failed to take items from storage: " + item);
                    }
                } else if (!new TakeItems2(ncontext, s.ing == null ? s.name : s.ing.name, s.count * for_craft).run(gui).IsSuccess()) {
                    return Results.ERROR("Failed to take items: " + item);
                }
            }
        }



        if (ncontext.workstation != null) {
            if (!new UseWorkStation(ncontext).run(gui).IsSuccess()) {
                return Results.ERROR("Failed to use workstation");
            }
        }
        else if (ncontext.bwaused) {
            NArea barrelwa = ncontext.goToArea(Specialisation.SpecName.barrelworkarea);
            Pair<Coord2d, Coord2d> rcArea = barrelwa.getRCArea();
            Coord2d center = rcArea.b.sub(rcArea.a).div(2).add(rcArea.a);
            new PathFinder(center).run(gui);
        }

        openBarrelWindows(ncontext, gui);
        ArrayList<Window> windows = NUtils.getGameUI().getWindows("Barrel");
        
        boolean hasEnoughResources = true;
        String insufficientItem = null;
        double foundAmount = 0;
        double requiredAmount = 0;
        
        for (NMakewindow.Spec s : mwnd.inputs) {
            // Skip ignored optional ingredients
            if (s.ing != null && s.ing.isIgnored) {
                continue;
            }
            
            String item = s.ing == null ? s.name : s.ing.name;
            if (ncontext.isInBarrel(item)) {
                double val = barrelAmount(gui, windows, item);
                
                // Handle case when barrel content not found (-1 means not found)
                if (val < 0) {
                    hasEnoughResources = false;
                    insufficientItem = item;
                    foundAmount = 0; // Not found = 0
                    requiredAmount = s.count;
                    break;
                }
                
                double valInMilligrams = val * 100;
                if(valInMilligrams < s.count)
                {
                    hasEnoughResources = false;
                    insufficientItem = item;
                    foundAmount = valInMilligrams;
                    requiredAmount = s.count;
                    break;
                }
            }
        }
        
        if (!hasEnoughResources) {
            for (NMakewindow.Spec s : mwnd.inputs) {
                // Skip ignored optional ingredients
                if (s.ing != null && s.ing.isIgnored) {
                    continue;
                }
                
                String item = s.ing == null ? s.name : s.ing.name;
                if (ncontext.isInBarrel(item)) {
                    new ReturnBarrelFromWorkArea(ncontext, item).run(gui);
                }
            }
            return Results.ERROR("Not enough resources in barrels: '" + insufficientItem + 
                    "' found " + String.format("%.2f", foundAmount) + 
                    ", required " + String.format("%.2f", requiredAmount));
        }

        new Drink(0.9, false).run(gui);
        int resfc = for_craft;
        String targetName = null;
        for (NMakewindow.Spec s : mwnd.outputs) {
            String itemName = s.ing != null ? s.ing.name : s.name;
            int outputMultiplier = NContext.getOutputMultiplier(itemName);
            resfc = s.count * for_craft * outputMultiplier;
            ArrayList<WItem> currentItems;
            if (s.ing != null) {
                targetName = s.ing.name;
                currentItems = NUtils.getGameUI().getInventory().getItems(new NAlias(s.ing.name));
            } else {
                targetName = s.name;
                currentItems = NUtils.getGameUI().getInventory().getItems(new NAlias(s.name));
            }
            
            int actualCurrentCount = 0;
            for (WItem item : currentItems) {
                actualCurrentCount += getActualItemCount(item);
            }
            
            resfc += actualCurrentCount;
            
        }

        craftProc(ncontext, gui, resfc, targetName);

        boolean isCauldron = ncontext.workstation != null &&
                ncontext.workstation.station != null &&
                ncontext.workstation.station.contains("gfx/terobjs/cauldron");

        if (isCauldron)
        {

            Gob cauldron = Finder.findGob(ncontext.workstation.selected);
            PrepareCauldron pc = new PrepareCauldron(cauldron, ncontext);
            pc.run(gui);
            // Refresh mwnd reference after PrepareCauldron (may have changed due to LightFire)
            refreshMakeWidget(gui);
            if(pc.wasUpdate)
            {
                if (!new UseWorkStation(ncontext).run(gui).IsSuccess()) {
                    return Results.ERROR("Failed to use workstation");
                }
                openBarrelWindows(ncontext, gui);
                craftProc(ncontext, gui, resfc, targetName);
            }
        }
        for (NMakewindow.Spec s : mwnd.outputs) {
            if (s.ing != null) {
                NUtils.getUI().core.addTask(new WaitItems(NUtils.getGameUI().getInventory(), new NAlias(s.ing.name), resfc));
            } else {
                NUtils.getUI().core.addTask(new WaitItems(NUtils.getGameUI().getInventory(), new NAlias(s.name), resfc));
            }
        }
        HashSet<String> targets = new HashSet<>();
        for (NMakewindow.Spec s : mwnd.outputs) {
            GetItems gi;
            if (s.ing != null) {
                NUtils.getUI().core.addTask(gi = new GetItems(NUtils.getGameUI().getInventory(), new NAlias(s.ing.name)));
                targets.add(s.ing.name);
            } else {
                NUtils.getUI().core.addTask(gi = new GetItems(NUtils.getGameUI().getInventory(), new NAlias(s.name)));
                targets.add(s.name);
            }


        }
        if (!mwnd.noTransfer.a) {
            new FreeInventory2(ncontext).run(gui);
        }
        left.set(left.get() - for_craft);
        return Results.SUCCESS();
    }

    private void craftProc(NContext ncontext, NGameUI gui, int resfc, String targetName) throws InterruptedException
    {
        int finalResfc = resfc;
        String finalTargetName = targetName;
        int maxRetries = 3;
        
        for (int retry = 0; retry < maxRetries; retry++) {
            // Сбрасываем ошибку перед попыткой крафта
            NUtils.getUI().dropLastError();
            
            mwnd.wdgmsg("make", 1);
            
            // Ждём появления прогресс-бара или ошибки
            final boolean[] progAppeared = {false};
            NUtils.addTask(new NTask() {
                private int waitTicks = 0;
                private static final int MAX_WAIT_FOR_PROG = 100;
                
                @Override
                public boolean check() {
                    // Проверяем ошибку
                    String error = NUtils.getUI().getLastError();
                    if (error != null) {
                        return true;
                    }
                    
                    // Прогресс-бар появился
                    if (gui.prog != null && gui.prog.prog > 0) {
                        boolean wsReady = (ncontext.workstation == null) || 
                                          (ncontext.workstation.selected == -1) || 
                                          NUtils.isWorkStationReady(ncontext.workstation.station, Finder.findGob(ncontext.workstation.selected));
                        if (wsReady) {
                            progAppeared[0] = true;
                            return true;
                        }
                    }
                    
                    // Таймаут ожидания прогресс-бара
                    waitTicks++;
                    return waitTicks >= MAX_WAIT_FOR_PROG;
                }
            });
            
            // Если была ошибка при старте - проверяем нужен ли retry
            String startError = NUtils.getUI().getLastError();
            if (startError != null || !progAppeared[0]) {
                if (hasEnoughIngredientsForCraft(ncontext, gui)) {
                    continue; // Повторяем попытку
                } else {
                    // Недостаточно ингредиентов - выходим
                    break;
                }
            }
            
            // Ждём завершения крафта - предметы или ошибка
            final boolean[] craftSucceeded = {false};
            NUtils.addTask(new NTask() {
                private int ticksAfterProgGone = 0;
                private static final int MAX_TICKS_AFTER_PROG = 50;
                
                @Override
                public boolean check() {
                    // Проверяем ошибку
                    String error = NUtils.getUI().getLastError();
                    if (error != null) {
                        return true;
                    }
                    
                    // Проверяем количество предметов
                    GetItems gi = new GetItems(NUtils.getGameUI().getInventory(), new NAlias(finalTargetName));
                    gi.check();
                    if (gi.getResult().size() >= finalResfc) {
                        craftSucceeded[0] = true;
                        return true;
                    }
                    
                    // Прогресс-бар ещё видим - продолжаем ждать
                    if (gui.prog != null && gui.prog.visible) {
                        ticksAfterProgGone = 0;
                        return false;
                    }
                    
                    // Прогресс-бар исчез - даём немного времени на появление предметов
                    ticksAfterProgGone++;
                    return ticksAfterProgGone >= MAX_TICKS_AFTER_PROG;
                }
            });
            
            // Проверяем результат крафта
            if (craftSucceeded[0]) {
                // Успех! Делаем клики для сброса состояния и выходим
                NUtils.getGameUI().map.wdgmsg("click", Coord.z, NUtils.player().rc.floor(posres), 3, 0);
                NUtils.getGameUI().map.wdgmsg("click", Coord.z, NUtils.player().rc.floor(posres), 1, 0);
                return;
            }
            
            // Крафт не завершился успешно - проверяем нужен ли retry
            if (hasEnoughIngredientsForCraft(ncontext, gui)) {
                continue; // Повторяем попытку
            } else {
                // Недостаточно ингредиентов - делаем клики и выходим
                NUtils.getGameUI().map.wdgmsg("click", Coord.z, NUtils.player().rc.floor(posres), 3, 0);
                NUtils.getGameUI().map.wdgmsg("click", Coord.z, NUtils.player().rc.floor(posres), 1, 0);
                return;
            }
        }
        
        // Исчерпали все попытки
        NUtils.getGameUI().map.wdgmsg("click", Coord.z, NUtils.player().rc.floor(posres), 3, 0);
        NUtils.getGameUI().map.wdgmsg("click", Coord.z, NUtils.player().rc.floor(posres), 1, 0);
    }
    
    /**
     * Check if there are enough ingredients in inventory and open containers to continue crafting
     */
    private boolean hasEnoughIngredientsForCraft(NContext ncontext, NGameUI gui) {
        try {
            ArrayList<Window> barrelWindows = gui.getWindows("Barrel");
            
            for (NMakewindow.Spec s : mwnd.inputs) {
                // Skip ignored optional ingredients
                if (s.ing != null && s.ing.isIgnored) {
                    continue;
                }
                
                String itemName = s.ing == null ? s.name : s.ing.name;
                int required = s.count;

                if (s.mixed()) {
                    int available = 0;
                    for (NMakewindow.Ingredient ing : s.kinds()) {
                        available += heldExact(gui, ing.name);
                    }
                    if (available < required) {
                        return false;
                    }
                    continue;
                }
                if (s.ing != null && s.ing.anyOf != null) {
                    if (heldAny(gui, categoryMembers(s.name)) < required) {
                        return false;
                    }
                    continue;
                }
                
                if (ncontext.isInBarrel(itemName)) {
                    // Check barrel content
                    double val = barrelAmount(gui, barrelWindows, itemName);
                    if (val < 0 || val * 100 < required) {
                        return false;
                    }
                } else {
                    // Check inventory
                    ArrayList<WItem> items = gui.getInventory().getItems(new NAlias(ItemVariants.base(itemName)));
                    int available = 0;
                    for (WItem item : items) {
                        // Storage inputs are matched by exact name: "Flax" must not count "Flax Fibres"
                        if (fromStorage.contains(itemName) && !itemName.equals(((NGItem) item.item).name())) {
                            continue;
                        }
                        // One kind of butter counts only that kind
                        if (ItemVariants.isKey(itemName) && !ItemVariants.matches((NGItem) item.item, itemName)) {
                            continue;
                        }
                        available += getActualItemCount(item);
                    }
                    if (available < required) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openBarrelWindows(NContext ncontext, NGameUI gui) throws InterruptedException {
        ArrayList<Long> barrelIds = GetBarrelsIds(ncontext);
        int count = 0;
        for (Long barrelid : barrelIds) {
            Gob barrel = Finder.findGob(barrelid);
            if (barrel == null) {
                continue;
            }

            double distToBarrel = NUtils.player().rc.dist(barrel.rc);
            if (distToBarrel > 20) {
                new PathFinder(barrel).run(gui);
            }

            gui.map.wdgmsg("click", Coord.z, barrel.rc.floor(posres), 3, 0, 0, (int) barrel.id,
                    barrel.rc.floor(posres), 0, -1);
            count++;
            int finalCount = count;
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return NUtils.getGameUI().getWindowsNum("Barrel") >= finalCount;
                }
            });
        }
    }

    ArrayList<Long> GetBarrelsIds(NContext ncontext) throws InterruptedException
    {
        ArrayList<Long> ids = new ArrayList<>();
        NUtils.getGameUI().msg("GetBarrelsIds: Checking " + mwnd.inputs.size() + " inputs for barrel items");
        
        for (NMakewindow.Spec s : mwnd.inputs)
        {
            // Skip ignored optional ingredients
            if (s.ing != null && s.ing.isIgnored) {
                continue;
            }
            
            String item = s.ing == null ? s.name : s.ing.name;
            String storedHash = ncontext.getPlacedBarrelHash(item);
            NUtils.getGameUI().msg("GetBarrelsIds: Checking item '" + item + "', isInBarrel=" + ncontext.isInBarrel(item) + 
                    ", storedHash=" + (storedHash != null ? storedHash.substring(0, Math.min(16, storedHash.length())) + "..." : "null"));
            
            if (ncontext.isInBarrel(item))
            {
                Gob barrel = ncontext.getBarrelInWorkArea(item);
                NUtils.getGameUI().msg("GetBarrelsIds: getBarrelInWorkArea('" + item + "') returned " + 
                        (barrel != null ? "barrel id=" + barrel.id + " hash=" + barrel.ngob.hash.substring(0, Math.min(16, barrel.ngob.hash.length())) + "..." : "NULL"));
                if (barrel != null)
                    ids.add(barrel.id);
            }
        }
        return ids;
    }

    /**
     * Litres of an input in the open barrels. A kind of milk must be exactly that liquid: a plain
     * name match would let "Milk" stand for every kind.
     */
    private static double barrelAmount(NGameUI gui, ArrayList<Window> windows, String item) {
        String liquid = ItemVariants.liquidOf(item);
        if (liquid == null) {
            return gui.findBarrelContent(windows, new NAlias(item));
        }
        for (Window wnd : windows) {
            LiquidContent content = LiquidContent.read(wnd);
            if (content != null && liquid.equals(content.name)) {
                return content.amount;
            }
        }
        return -1;
    }

    /** Whether an output that comes in kinds has a PUT area for any of its kinds. */
    private static boolean hasKindOutArea(NContext ncontext, String name) throws InterruptedException {
        for (String key : ItemVariants.keys(name)) {
            if (ncontext.addOutItem(key, null, 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The server picks which butter in the inventory a recipe uses, so before one kind is fetched
     * every other kind is put away. Fails when one has no area to go to.
     */
    private Results clearOtherKinds(NContext ncontext, NGameUI gui, String key) throws InterruptedException {
        String name = ItemVariants.base(key);
        ArrayList<WItem> held = gui.getInventory().getItems(new NAlias(name));
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return !ItemVariants.anyUnread(held);
            }
        });
        HashSet<String> targets = new HashSet<>();
        for (WItem witem : held) {
            NGItem item = (NGItem) witem.item;
            if (name.equals(item.name()) && !ItemVariants.matches(item, key)) {
                double q = item.quality != null ? item.quality : 1;
                String route = ncontext.outKey(item, q);
                if (ncontext.addOutItem(route, null, q)) {
                    targets.add(route);
                }
            }
        }
        if (!targets.isEmpty()) {
            new TransferItems2(ncontext, targets).run(gui);
        }
        for (WItem witem : gui.getInventory().getItems(new NAlias(name))) {
            NGItem item = (NGItem) witem.item;
            if (name.equals(item.name()) && !ItemVariants.matches(item, key)) {
                String kind = ItemVariants.keyOf(item);
                return Results.ERROR("The inventory holds " + (kind != null ? ItemVariants.describe(kind) : name)
                        + " with no area to put it in; it would go into the craft instead of " + ItemVariants.describe(key));
            }
        }
        return Results.SUCCESS();
    }

    /** After an input ran out: end the run normally once a craft was made, as a "Craft all" ends; else null. */
    private Results endOnRunOut(NGameUI gui, AtomicInteger left) {
        int made = count - left.get();
        if (ranOut == null || made <= 0) {
            return null;
        }
        gui.msg(ranOut + "; stopped after " + made + (made == 1 ? " craft" : " crafts"));
        left.set(0);
        return Results.SUCCESS();
    }

    /** Every item name a category input offers. */
    private static Set<String> categoryMembers(String category) {
        Set<String> ret = new LinkedHashSet<>();
        ArrayList<org.json.JSONObject> options = CraftCategories.options(category);
        if (options != null) {
            for (org.json.JSONObject obj : options) {
                ret.add(obj.optString("name"));
            }
        }
        return ret;
    }

    /** Items in the inventory whose exact name is one of names. */
    private static int heldAny(NGameUI gui, Set<String> names) throws InterruptedException {
        int n = 0;
        for (WItem item : gui.getInventory().getItems()) {
            if (names.contains(((NGItem) item.item).name())) {
                n++;
            }
        }
        return n;
    }

    /**
     * Bring the inventory up to target items of an "Any" category input: whatever members the areas
     * hold, one area at a time, each container asked for every member in one visit. Members already
     * carried count too, since the server uses them as well, except those a plain input of the recipe
     * holds for itself (plainDemand). Returns how many are held for this input.
     */
    private int takeAnyOf(NContext ncontext, NGameUI gui, NMakewindow.Spec s, int wanted, Map<String, Integer> plainDemand) throws InterruptedException {
        List<String> kinds = anyKinds.get(s.name);
        Set<String> members = categoryMembers(s.name);
        // Copies a plain input of the recipe holds for itself are not this input's
        int reserved = 0;
        for (String member : members) {
            reserved += plainDemand.getOrDefault(member, 0);
        }
        int target = wanted + reserved;
        Set<String> withArea = new HashSet<>(kinds);
        Map<Integer, List<String>> byArea = new LinkedHashMap<>();
        for (String kind : kinds) {
            NArea area = NContext.findIn(kind);
            if (area == null) {
                area = NContext.findInGlobal(kind);
            }
            if (area != null) {
                byArea.computeIfAbsent(area.id, k -> new ArrayList<>()).add(kind);
            }
        }
        int have = heldAny(gui, members);
        for (List<String> group : byArea.values()) {
            if (have >= target) {
                break;
            }
            // takeAny looks in the first name's area; any member found there is taken
            List<String> keys = new ArrayList<>(group);
            for (String kind : kinds) {
                if (!keys.contains(kind)) {
                    keys.add(kind);
                }
            }
            // its count is absolute over the names it is given, which leave out members without an area
            int count = heldAny(gui, withArea) + (target - have);
            TakeItems2 take = new TakeItems2(ncontext, count, (Specialisation.SpecName) null, (NInventory.QualityType) null);
            take.exactMatch = true;
            take.takeAny(new NAlias(new ArrayList<>(keys), new ArrayList<>()), gui);
            have = heldAny(gui, members);
        }
        return Math.max(have - reserved, 0);
    }

    /**
     * Fetch one craft's worth of an input picked slot by slot: exactly what the slots name, so two
     * Grasshopper slots and four others give two grasshoppers and one of each other bug. The server
     * chooses which held items go into the craft, so nothing else it could use for this input may be
     * held: other members of the category are put away first. When a kind runs short the partial set
     * goes back and ranOut is set.
     */
    private Results takeMix(NContext ncontext, NGameUI gui, NMakewindow.Spec s, Map<String, Integer> plainDemand) throws InterruptedException {
        Map<String, Integer> want = new LinkedHashMap<>();
        for (NMakewindow.Ingredient ing : s.mix) {
            want.merge(ing.name, 1, Integer::sum);
        }
        List<String> kinds = new ArrayList<>(want.keySet());
        Results cleared = clearMixStrays(ncontext, gui, s, kinds);
        if (!cleared.IsSuccess()) {
            return cleared;
        }
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            // Copies a plain input of the recipe holds for itself are not this input's
            int reserved = plainDemand.getOrDefault(e.getKey(), 0);
            int got = fetchExact(ncontext, gui, e.getKey(), e.getValue() + reserved) - reserved;
            if (got < e.getValue()) {
                ranOut = "Not enough " + e.getKey() + " for another " + s.name + " set: have " + Math.max(got, 0) + " of " + e.getValue();
                // Put the partial set back; a kind with no area to return to stays in the inventory
                clearMixStrays(ncontext, gui, s, kinds);
                return Results.ERROR(ranOut);
            }
        }
        return Results.SUCCESS();
    }

    /**
     * Bring the inventory up to want copies of exactly this name; returns how many are held. Asks for
     * the shortfall only, since TakeItems2 takes its whole count from the first container whatever is
     * already held, and asks again while that still brings more.
     */
    private int fetchExact(NContext ncontext, NGameUI gui, String name, int want) throws InterruptedException {
        int have = heldExact(gui, name);
        while (have < want) {
            if (fromStorage.contains(name)) {
                new TakeFromStorageIndex(storage, name, want).run(gui);
            } else {
                TakeItems2 take = new TakeItems2(ncontext, name, want - have);
                take.exactMatch = true;
                take.run(gui);
            }
            int now = heldExact(gui, name);
            if (now <= have) {
                break;
            }
            have = now;
        }
        return have;
    }

    /** Items in the inventory named exactly name: "Silkmoth" does not count a "Male Silkmoth". */
    private static int heldExact(NGameUI gui, String name) throws InterruptedException {
        int n = 0;
        for (WItem item : gui.getInventory().getItems(new NAlias(name))) {
            if (name.equals(((NGItem) item.item).name())) {
                n++;
            }
        }
        return n;
    }

    /**
     * Before a mix is fetched, put away every held item the server could use for that input
     * instead: any member of the category, and leftovers of the picked kinds. What another input of
     * the recipe takes stays. Fails when a stray has no area to go to.
     */
    private Results clearMixStrays(NContext ncontext, NGameUI gui, NMakewindow.Spec s, List<String> kinds) throws InterruptedException {
        Set<String> members = new HashSet<>(kinds);
        ArrayList<org.json.JSONObject> options = CraftCategories.options(s.name);
        if (options != null) {
            for (org.json.JSONObject obj : options) {
                members.add(obj.optString("name"));
            }
        }
        for (NMakewindow.Spec other : mwnd.inputs) {
            if (other != s && !other.mixed()) {
                members.remove(inputName(other));
            }
        }
        HashSet<String> targets = new HashSet<>();
        for (WItem witem : gui.getInventory().getItems()) {
            NGItem item = (NGItem) witem.item;
            if (members.contains(item.name())) {
                double q = item.quality != null ? item.quality : 1;
                String route = ncontext.outKey(item, q);
                if (ncontext.addOutItem(route, null, q)) {
                    targets.add(route);
                }
            }
        }
        if (!targets.isEmpty()) {
            new TransferItems2(ncontext, targets).run(gui);
        }
        for (WItem witem : gui.getInventory().getItems()) {
            String name = ((NGItem) witem.item).name();
            if (members.contains(name)) {
                return Results.ERROR("The inventory holds " + name + " with no area to put it in; it could go into the craft instead of the picked " + s.name + " mix");
            }
        }
        return Results.SUCCESS();
    }

    private void selectIngredientFromCategory(NMakewindow.Spec spec) {
        if (!spec.categories || spec.ing != null) {
            return;
        }

        ArrayList<org.json.JSONObject> categoryItems = CraftCategories.options(spec.name);
        if (categoryItems == null || categoryItems.isEmpty()) {
            NUtils.getGameUI().msg("Category '" + spec.name + "' has no known items");
            return;
        }

        NUtils.getGameUI().msg("Searching ingredient for category: " + spec.name + " (" + categoryItems.size() + " options)");

        // First try to find in nearby areas
        for (org.json.JSONObject obj : categoryItems) {
            String itemName = (String) obj.get("name");
            if (NContext.findIn(itemName) != null) {
                NUtils.getGameUI().msg("Found nearby: " + itemName + " for category " + spec.name);
                spec.ing = mwnd.new Ingredient(obj);
                return;
            }
        }

        // If not found nearby, try global search
        for (org.json.JSONObject obj : categoryItems) {
            String itemName = (String) obj.get("name");
            if (NContext.findInGlobal(itemName) != null) {
                NUtils.getGameUI().msg("Found globally: " + itemName + " for category " + spec.name);
                spec.ing = mwnd.new Ingredient(obj);
                return;
            }
        }

        // Last, the Storage Items index (exact names only)
        if (useStorage) {
            List<String> names = new ArrayList<>();
            for (org.json.JSONObject obj : categoryItems) {
                names.add((String) obj.get("name"));
            }
            TakeFromStorageIndex.Source src = TakeFromStorageIndex.Source.load(names, villageChunks());
            for (org.json.JSONObject obj : categoryItems) {
                String itemName = (String) obj.get("name");
                if (src.has(itemName)) {
                    NUtils.getGameUI().msg("Found in storage: " + itemName + " for category " + spec.name);
                    spec.ing = mwnd.new Ingredient(obj);
                    return;
                }
            }
        }

        NUtils.getGameUI().msg("No available ingredients found for category: " + spec.name);
    }

    /** The village's chunks, worked out once per run. */
    private Set<Long> villageChunks() {
        if (villageChunks == null) {
            villageChunks = (village != null) ? village.village() : Collections.emptySet();
        }
        return villageChunks;
    }

    private static String inputName(NMakewindow.Spec s) {
        if (s.ing != null) {
            return (s.ing.isIgnored || s.ing.anyOf != null) ? null : s.ing.name;
        }
        return s.categories ? null : s.name;
    }

    /**
     * Decide which inputs come from the Storage Items index: those with no input area anywhere
     * that the index lists under exactly their name. All names go in one query.
     */
    private void resolveStorageInputs() {
        Set<String> candidates = new LinkedHashSet<>();
        for (NMakewindow.Spec s : mwnd.inputs) {
            List<String> names = new ArrayList<>();
            if (s.mixed()) {
                for (NMakewindow.Ingredient ing : s.kinds()) {
                    names.add(ing.name);
                }
            } else if (inputName(s) != null) {
                names.add(inputName(s));
            }
            for (String name : names) {
                if (NContext.findIn(name) == null && NContext.findInGlobal(name) == null) {
                    candidates.add(name);
                }
            }
        }
        storage = TakeFromStorageIndex.Source.load(candidates, villageChunks());
        fromStorage.clear();
        for (String name : candidates) {
            if (storage.has(name)) {
                fromStorage.add(name);
                System.out.println("[Craft] " + name + ": no input area, taking it from storage");
            }
        }
    }

    /**
     * Capture the current recipe name and selected ingredients from mwnd while it is
     * still valid, so they can be restored after the craft window is hijacked by
     * fire-lighting (LightFire opens the "Light fire" recipe).
     */
    /**
     * Wait until every (non-ignored) input/output spec of the current makeWidget has
     * resolved its name. Needed after a recipe re-open, since names load asynchronously
     * (Spec.tick) and the slot estimator depends on isInBarrel(name) — a null name reads
     * as "not in a barrel" and inflates the estimate. Mirrors AutocraftBot's guard.
     */
    private void waitForSpecNames(NGameUI gui) throws InterruptedException {
        final boolean isHeadless = nurgling.headless.Headless.isHeadless();
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                if (mwnd == null || mwnd.inputs == null || mwnd.inputs.isEmpty()) {
                    return false;
                }
                for (NMakewindow.Spec spec : mwnd.inputs) {
                    if (spec.ing != null && spec.ing.isIgnored) {
                        continue;
                    }
                    if (spec.name == null) {
                        return false;
                    }
                    if (!isHeadless && spec.spr == null) {
                        return false;
                    }
                }
                for (NMakewindow.Spec spec : mwnd.outputs) {
                    if (spec.name == null) {
                        return false;
                    }
                }
                return true;
            }
        });
    }

    private void saveRecipeState() {
        savedRecipeName = null;
        savedAutoMode = false;
        savedNoTransfer = false;
        savedInputIng.clear();
        savedOutputIng.clear();
        savedInputMix.clear();
        if (mwnd == null) {
            return;
        }
        savedRecipeName = mwnd.rcpnm;
        savedAutoMode = mwnd.autoMode;
        savedNoTransfer = mwnd.noTransfer != null && mwnd.noTransfer.a;
        savedCraftCount = mwnd.getCraftCount();
        for (int i = 0; i < mwnd.inputs.size(); i++) {
            NMakewindow.Spec spec = mwnd.inputs.get(i);
            if (spec.ing != null) {
                savedInputIng.put(i, new IngredientSel(spec.ing));
            }
            if (spec.mixed()) {
                savedInputMix.put(i, spec.mix);
            }
        }
        for (int i = 0; i < mwnd.outputs.size(); i++) {
            NMakewindow.Spec spec = mwnd.outputs.get(i);
            if (spec.ing != null) {
                savedOutputIng.put(i, new IngredientSel(spec.ing));
            }
        }
    }

    /**
     * Refresh the mwnd reference from the current craft window.
     * This is needed after operations that may change the craft widget (like LightFire).
     *
     * LightFire opens the "Light fire" recipe and is supposed to restore ours afterward,
     * but if that restore fails the craft window is left on the wrong recipe. Grabbing it
     * blindly would point mwnd at a recipe whose Specs have null names. So if the live
     * recipe doesn't match the one we started with, re-open ours and re-apply the saved
     * ingredient selections before adopting the new makeWidget.
     */
    private void refreshMakeWidget(NGameUI gui) throws InterruptedException {
        if (savedRecipeName != null
                && (gui.craftwnd == null || gui.craftwnd.makeWidget == null
                    || !savedRecipeName.equals(gui.craftwnd.makeWidget.rcpnm))) {
            MenuGrid.Pagina target = null;
            for (MenuGrid.Pagina pag : gui.menu.paginae) {
                try {
                    if (pag.button() != null && savedRecipeName.equals(pag.button().name())) {
                        target = pag;
                        break;
                    }
                } catch (Loading l) {
                    // Skip paginae that aren't loaded yet.
                }
            }
            if (target != null) {
                target.button().use(new MenuGrid.Interaction(1, 0));
                final String want = savedRecipeName;
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        return gui.craftwnd != null && gui.craftwnd.makeWidget != null
                                && want.equals(gui.craftwnd.makeWidget.rcpnm);
                    }
                });
            }
        }

        if (gui.craftwnd != null && gui.craftwnd.makeWidget != null) {
            mwnd = gui.craftwnd.makeWidget;
            if (savedRecipeName != null) {
                // Restore autocraft mode and transfer settings. A freshly re-opened
                // recipe defaults to autoMode=false ("regular crafting"), which disables
                // category/ingredient selection and the barrel logistics the bot relies on.
                // Set this before re-applying ingredients, since Spec.categories is
                // recomputed from autoMode on tick.
                mwnd.setAutoMode(savedAutoMode);
                if (mwnd.noTransfer != null) {
                    mwnd.noTransfer.a = savedNoTransfer;
                }
                // Restore the requested target quantity. A freshly re-opened recipe has an
                // empty quantity field; if the bot is later relaunched it would read that
                // empty field and fall back to 1, silently shrinking the order.
                if (savedCraftCount != null && !savedCraftCount.isEmpty()) {
                    mwnd.setCraftCount(savedCraftCount);
                }
            }
            // Re-apply ingredient selections onto the fresh widget (a re-opened recipe
            // starts with categories unselected).
            for (Map.Entry<Integer, IngredientSel> e : savedInputIng.entrySet()) {
                if (e.getKey() < mwnd.inputs.size()) {
                    IngredientSel sel = e.getValue();
                    NMakewindow.Ingredient ing = mwnd.new Ingredient(sel.img, sel.name, sel.isIgnored);
                    ing.anyOf = sel.anyOf;
                    mwnd.inputs.get(e.getKey()).ing = ing;
                    mwnd.inputs.get(e.getKey()).mix = savedInputMix.get(e.getKey());
                }
            }
            for (Map.Entry<Integer, IngredientSel> e : savedOutputIng.entrySet()) {
                if (e.getKey() < mwnd.outputs.size()) {
                    IngredientSel sel = e.getValue();
                    mwnd.outputs.get(e.getKey()).ing = mwnd.new Ingredient(sel.img, sel.name, sel.isIgnored);
                }
            }
        }
    }

    /**
     * Calculate the number of slots needed for a given number of crafts, considering stacking.
     * @param ncontext The crafting context
     * @param numCrafts Number of crafts to calculate for
     * @return Total number of inventory slots needed
     */
    private int calculateSlotsNeeded(NContext ncontext, int numCrafts) {
        int totalSlots = 0;
        
        // Calculate slots for inputs
        for (NMakewindow.Spec s : mwnd.inputs) {
            // Skip ignored optional ingredients
            if (s.ing != null && s.ing.isIgnored) {
                continue;
            }
            
            // A mix may be of kinds that do not stack together: one cell per item
            if (s.mixed()) {
                totalSlots += s.count * numCrafts;
                continue;
            }
            if (s.ing != null && s.ing.anyOf != null) {
                List<String> kinds = anyKinds.get(s.name);
                int stackSize = (kinds == null || kinds.isEmpty()) ? 1 : StackSupporter.getFullStackSize(kinds.get(0));
                totalSlots += (s.count * numCrafts + stackSize - 1) / stackSize;
                continue;
            }

            String itemName = s.ing != null ? s.ing.name : s.name;
            
            // Skip items stored in barrels
            if (ncontext.isInBarrel(itemName)) {
                continue;
            }
            
            int itemsNeeded = s.count * numCrafts;
            int stackSize = StackSupporter.getFullStackSize(ItemVariants.base(itemName));
            
            // Calculate slots needed: ceil(itemsNeeded / stackSize)
            int slotsForItem = (itemsNeeded + stackSize - 1) / stackSize;
            totalSlots += slotsForItem;
        }
        
        // Calculate slots for outputs (if noTransfer is not enabled)
        if (!mwnd.noTransfer.a) {
            for (NMakewindow.Spec s : mwnd.outputs) {
                String itemName = s.ing != null ? s.ing.name : s.name;
                
                // Skip items stored in barrels
                if (ncontext.isInBarrel(itemName)) {
                    continue;
                }
                
                int outputMultiplier = NContext.getOutputMultiplier(itemName);
                int itemsProduced = s.count * numCrafts * outputMultiplier;
                int stackSize = StackSupporter.getFullStackSize(itemName);
                
                // Calculate slots needed: ceil(itemsProduced / stackSize)
                int slotsForItem = (itemsProduced + stackSize - 1) / stackSize;
                totalSlots += slotsForItem;
            }
        }
        
        return totalSlots;
    }

    /**
     * Calculate the maximum number of crafts that can fit in the inventory, considering stacking.
     * Uses binary search to find the optimal number.
     * @param ncontext The crafting context
     * @param freeSpace Available inventory slots
     * @param maxCrafts Maximum number of crafts desired
     * @return Maximum number of crafts that can fit
     */
    private int calculateMaxCraftsWithStacking(NContext ncontext, int freeSpace, int maxCrafts) {
        if (freeSpace <= 0) {
            return 0;
        }
        
        // Binary search for the maximum number of crafts
        int low = 0;
        int high = maxCrafts;
        int result = 0;
        
        while (low <= high) {
            int mid = (low + high) / 2;
            int slotsNeeded = calculateSlotsNeeded(ncontext, mid);
            
            if (slotsNeeded <= freeSpace) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        
        return result;
    }

}
