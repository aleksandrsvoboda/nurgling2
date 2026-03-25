package nurgling.actions;

import haven.*;
import haven.res.ui.invsq.InvSquare;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitWindow;
import nurgling.tools.Finder;

import static haven.OCache.posres;

public class TakeFromVehicleSlot implements Action {
    private final Gob vehicle;
    private final int slotIndex;

    public TakeFromVehicleSlot(Gob vehicle, int slotIndex) {
        this.vehicle = vehicle;
        this.slotIndex = slotIndex;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        new PathFinder(vehicle).run(gui);

        if (vehicle.ngob.name.contains("cart")) {
            return takeFromCartSlot(gui);
        } else if (vehicle.ngob.name.contains("snekkja")) {
            return takeFromWindowSlot(gui, "Cargo", "Snekkja", 16);
        } else if (vehicle.ngob.name.contains("wagon")) {
            return takeFromWindowSlot(gui, "Open", "Wagon", 20);
        }
        return Results.FAIL();
    }

    private Results takeFromCartSlot(NGameUI gui) throws InterruptedException {
        int mul = 4 << slotIndex;
        if ((vehicle.ngob.getModelAttribute() & mul) != mul) {
            return Results.FAIL();
        }
        gui.map.wdgmsg("click", Coord.z, vehicle.rc.floor(posres), 3, 0, 0,
                (int) vehicle.id, vehicle.rc.floor(posres), 0, slotIndex + 2);
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return Finder.findLiftedbyPlayer() != null;
            }
        });
        return Results.SUCCESS();
    }

    private Results takeFromWindowSlot(NGameUI gui, String flowerAction, String windowName, int minChildren) throws InterruptedException {
        Window existing = NUtils.getGameUI().getWindow(windowName);
        if (existing != null) {
            existing.wdgmsg("close");
        }
        new SelectFlowerAction(flowerAction, vehicle).run(gui);
        NUtils.addTask(new WaitWindow(windowName));
        Window window = NUtils.getGameUI().getWindow(windowName);
        if (window == null) {
            return Results.FAIL();
        }

        for (Widget container : window.children()) {
            if (container.children().size() >= minChildren) {
                int itemIndex = 0;
                for (Widget child : container.children()) {
                    if (!(child instanceof InvSquare)) {
                        if (itemIndex == slotIndex) {
                            child.wdgmsg("click", UI.scale(15, 15), 1, 0);
                            NUtils.addTask(new NTask() {
                                @Override
                                public boolean check() {
                                    return Finder.findLiftedbyPlayer() != null;
                                }
                            });
                            return Results.SUCCESS();
                        }
                        itemIndex++;
                    }
                }
                return Results.FAIL();
            }
        }
        return Results.FAIL();
    }

    public static int countCargoItems(NGameUI gui, Gob vehicle) throws InterruptedException {
        if (vehicle.ngob.name.contains("cart")) {
            int count = 0;
            int mul = 4;
            for (int i = 0; i < 6; i++) {
                if ((vehicle.ngob.getModelAttribute() & mul) == mul) count++;
                mul *= 2;
            }
            return count;
        }

        boolean isSnekkja = vehicle.ngob.name.contains("snekkja");
        String flowerAction = isSnekkja ? "Cargo" : "Open";
        String windowName = isSnekkja ? "Snekkja" : "Wagon";
        int minChildren = isSnekkja ? 16 : 20;

        new PathFinder(vehicle).run(gui);
        Window existing = NUtils.getGameUI().getWindow(windowName);
        if (existing != null) {
            existing.wdgmsg("close");
        }
        new SelectFlowerAction(flowerAction, vehicle).run(gui);
        NUtils.addTask(new WaitWindow(windowName));
        Window window = NUtils.getGameUI().getWindow(windowName);
        if (window == null) return 0;

        for (Widget container : window.children()) {
            if (container.children().size() >= minChildren) {
                int count = 0;
                for (Widget child : container.children()) {
                    if (!(child instanceof InvSquare)) count++;
                }
                return count;
            }
        }
        return 0;
    }
}
