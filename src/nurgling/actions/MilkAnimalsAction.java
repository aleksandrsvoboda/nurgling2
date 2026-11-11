package nurgling.actions;

import haven.Coord2d;
import haven.Gob;
import haven.res.gfx.hud.rosters.cow.Ochs;
import haven.res.gfx.hud.rosters.goat.Goat;
import haven.res.gfx.hud.rosters.sheep.Sheep;
import haven.res.ui.croster.CattleId;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitSound;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.areas.NArea;
import nurgling.areas.NContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public class MilkAnimalsAction implements Action {
    
    private final NAlias animalAlias;
    private String animalAreaName;
    private String milkAreaName;
    private Predicate<Gob> lactatingPredicate;
    private final NAlias milkContent = new NAlias("Milk");

    /** Frames to wait for a pour to empty the barrel; past this the cistern counts as full. */
    private static final int POUR_TICKS = 300;

    private final ArrayList<Long> cisternIds = new ArrayList<>();
    private final Set<Long> fullCisterns = new HashSet<>();
    private long currentCistern = -1;

    public MilkAnimalsAction(NAlias animalAlias) {
        this.animalAlias = animalAlias;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        initBot(animalAlias);

        // Get areas from context
        NArea animalArea = NContext.findSpec(animalAreaName);
        if (animalArea == null) {
            return Results.ERROR("Animal area '" + animalAreaName + "' not found in specializations");
        }
        
        NArea milkArea = NContext.findSpec("cistern", milkAreaName);
        if (milkArea == null) {
            return Results.ERROR("Milk area '" + milkAreaName + "' not found in specializations");
        }
        
        // Find required containers
        Gob barrel = Finder.findGob(milkArea, new NAlias("barrel"));
        if (barrel == null) {
            return Results.ERROR("Barrel not found in milk area");
        }
        
        for (Gob cistern : Finder.findGobs(milkArea, new NAlias("cistern"))) {
            cisternIds.add(cistern.id);
        }
        if (cisternIds.isEmpty()) {
            return Results.ERROR("Cistern not found in milk area");
        }
        
        // Find lactating animals
        ArrayList<Gob> lactatingAnimals = findLactatingAnimals(animalArea);
        if (lactatingAnimals.isEmpty()) {
            return Results.SUCCESS(); // No animals to milk, but not an error
        }
        
        // Lift barrel; if it already holds milk, empty it before milking
        Coord2d barrelOriginalPos = barrel.rc;
        new LiftObject(barrel).run(gui);
        boolean cisternsFull = !unloadBarrel(gui, barrel);
        
        // Milk animals in batches
        int milkCount = 0;
        while (!cisternsFull && !lactatingAnimals.isEmpty()) {
            Gob targetAnimal = findClosestAnimal(lactatingAnimals);
            lactatingAnimals.remove(targetAnimal);
            
            // Navigate to and milk the animal
            new DynamicPf(targetAnimal).run(gui);
            NUtils.activateGob(targetAnimal);

            NUtils.getUI().core.addTask(new WaitSound("sfx/fx/water"));

            milkCount++;
            
            // Empty barrel every 4 milking
            int BATCH_SIZE = 4;
            if (milkCount >= BATCH_SIZE) {
                cisternsFull = !unloadBarrel(gui, barrel);
                milkCount = 0;
            }
        }
        
        // Final check - if barrel has milk, empty it into a cistern before placing back
        if (!cisternsFull) {
            cisternsFull = !unloadBarrel(gui, barrel);
        }
        
        // Place barrel back (still holding the leftover milk if every cistern is full)
        new PlaceObject(barrel, barrelOriginalPos, 0).run(gui);
        
        if (cisternsFull) {
            gui.error("All cisterns in '" + milkAreaName + "' are full");
            return Results.FAIL();
        }
        return Results.SUCCESS();
    }
    
    /**
     * Pours the carried barrel into the milk area's cisterns until it is empty. A pour that leaves
     * milk in the barrel means that cistern is full (it took part of the barrel or none), so it is
     * remembered as full and the rest goes to the next one.
     * @return false when every cistern is full and the barrel still holds milk
     */
    private boolean unloadBarrel(NGameUI gui, Gob barrel) throws InterruptedException {
        while (NUtils.isOverlay(barrel, milkContent)) {
            Gob cistern = pickCistern();
            if (cistern == null) {
                return false;
            }
            new DynamicPf(cistern).run(gui);
            NUtils.activateGob(cistern);
            
            WaitBarrelEmpty wait = new WaitBarrelEmpty(barrel, milkContent);
            NUtils.getUI().core.addTask(wait);
            if (!wait.emptied) {
                fullCisterns.add(cistern.id);
                currentCistern = -1;
            }
        }
        return true;
    }
    
    /**
     * Keeps pouring into the same cistern until it fills, so cisterns fill one at a time;
     * after that, the nearest cistern not known to be full.
     */
    private Gob pickCistern() {
        if (currentCistern != -1) {
            Gob current = Finder.findGob(currentCistern);
            if (current != null) {
                return current;
            }
        }
        Gob player = NUtils.player();
        Gob best = null;
        for (long id : cisternIds) {
            if (fullCisterns.contains(id)) {
                continue;
            }
            Gob cistern = Finder.findGob(id);
            if (cistern == null) {
                continue;
            }
            if (best == null || (player != null && player.rc.dist(cistern.rc) < player.rc.dist(best.rc))) {
                best = cistern;
            }
        }
        currentCistern = best == null ? -1 : best.id;
        return best;
    }
    
    private ArrayList<Gob> findLactatingAnimals(NArea animalArea) throws InterruptedException {
        ArrayList<Gob> animals = Finder.findGobs(animalArea, animalAlias);
        ArrayList<Gob> lactatingAnimals = new ArrayList<>();
        
        // Only include animals that are in the roster (have CattleId) and match our predicate
        for (Gob animal : animals) {
            if (animal.getattr(CattleId.class) != null && lactatingPredicate.test(animal)) {
                lactatingAnimals.add(animal);
            }
        }
        
        // Sort by distance for efficiency
        lactatingAnimals.sort(NUtils.d_comp);
        
        return lactatingAnimals;
    }
    
    private Gob findClosestAnimal(ArrayList<Gob> animals) {
        if (animals.isEmpty()) return null;
        
        Gob closest = animals.get(0);
        double closestDist = NUtils.getGameUI().map.player().rc.dist(closest.rc);
        
        for (Gob animal : animals) {
            double dist = NUtils.getGameUI().map.player().rc.dist(animal.rc);
            if (dist < closestDist) {
                closest = animal;
                closestDist = dist;
            }
        }
        
        return closest;
    }

    public void initBot(NAlias animalAlias) {
        if(!animalAlias.getKeys().isEmpty())
        {
            if (Objects.equals(animalAlias.getKeys().get(0), "cattle"))
            {
                this.animalAreaName = "cows";
                this.milkAreaName = "Cow Milk";
                this.lactatingPredicate = createCowLactatingPredicate();
            } else if (Objects.equals(animalAlias.getKeys().get(0), "goat"))
            {
                this.animalAreaName = "goats";
                this.milkAreaName = "Goat Milk";
                this.lactatingPredicate = createGoatLactatingPredicate();
            } else if (Objects.equals(animalAlias.getKeys().get(0), "sheep"))
            {
                this.animalAreaName = "sheeps";
                this.milkAreaName = "Sheep Milk";
                this.lactatingPredicate = createSheepLactatingPredicate();
            }
        }
    }

    // Static helper methods for creating common animal milking predicates
    public static Predicate<Gob> createCowLactatingPredicate() {
        return gob -> {
            Ochs cow = (Ochs) NUtils.getAnimalEntity(gob, Ochs.class);
            return cow != null && !cow.bull && !cow.dead && cow.lactate;
        };
    }
    
    public static Predicate<Gob> createSheepLactatingPredicate() {
        return gob -> {
            Sheep sheep = (Sheep) NUtils.getAnimalEntity(gob, Sheep.class);
            return sheep != null && !sheep.ram && !sheep.dead && sheep.lactate;
        };
    }
    
    public static Predicate<Gob> createGoatLactatingPredicate() {
        return gob -> {
            Goat goat = (Goat) NUtils.getAnimalEntity(gob, Goat.class);
            return goat != null && !goat.billy && !goat.dead && goat.lactate;
        };
    }

    /** Waits for the barrel's content overlay to go, giving up after POUR_TICKS frames. */
    private static class WaitBarrelEmpty extends NTask {
        private final Gob barrel;
        private final NAlias content;
        boolean emptied = false;
        private int ticks = 0;

        WaitBarrelEmpty(Gob barrel, NAlias content) {
            this.barrel = barrel;
            this.content = content;
        }

        @Override
        public boolean check() {
            if (!NUtils.isOverlay(barrel, content)) {
                emptied = true;
                return true;
            }
            return ++ticks >= POUR_TICKS;
        }
    }
}
