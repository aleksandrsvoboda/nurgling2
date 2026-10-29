package nurgling.actions;

import haven.MenuGrid;
import haven.WItem;
import nurgling.*;
import nurgling.tasks.*;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.NEquipory;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutoDrink implements Action
{

    public final AtomicBoolean stop = new AtomicBoolean(false);

    public AutoDrink()
    {
        stop.set(false);
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        while(!stop.get())
        {
            final double threshold = getThreshold();
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    if(NUtils.getGameUI()==null)
                        return false;
                    double stamina = NUtils.getStamina();
                    if(stamina < 0)
                        return false;
                    NGameUI g = NUtils.getGameUI();
                    boolean botRunning = g != null && g.biw != null && g.biw.waitBot.get();
                    return (!botRunning && stamina < threshold) || stop.get();
                }
            });
            if(stop.get()) {
                return Results.SUCCESS();
            }

            if(checkWater()) {
                NUtils.getUI().dropLastError();
                NGameUI gameUI = NUtils.getGameUI();
                if (gameUI == null || gameUI.menu == null) {
                    continue;
                }
                for (MenuGrid.Pagina pag : gameUI.menu.paginae) {
                    if (pag.button() != null && pag.button().name().equals("Drink")) {

                        pag.button().use(new MenuGrid.Interaction(1, 0));
                        WaitPoseOrMsg wops = new WaitPoseOrMsg(NUtils.player(), "gfx/borka/drinkan", new NAlias("You have nothing on your hotbelt to drink."));
                        NUtils.getUI().core.addTask(wops);
                        NUtils.addTask(new NTask() {
                            @Override
                            public boolean check() {
                                return NUtils.player() == null || !NParser.checkName(NUtils.player().pose(), "gfx/borka/drinkan");
                            }
                        });
                    }
                }
            }
            else
            {
                NUtils.addTask(new NTask(){
                    int count = 0;
                    @Override
                    public boolean check()
                    {
                        return count++>60;
                    }
                });
            }
        }
        return Results.SUCCESS();
    }

    /** Stamina fraction (0..1) at or below which auto-drink triggers, from the QoL slider (percent). */
    static double getThreshold()
    {
        Object val = NConfig.get(NConfig.Key.autoDrinkThreshold);
        int percent = (val instanceof Number) ? ((Number) val).intValue() : 50;
        return percent / 100.0;
    }

    boolean checkWater() throws InterruptedException
    {
        NEquipory equipment = NUtils.getEquipment();
        if (equipment == null) {
            return false;
        }
        // Check waterskins in belt
        WItem wbelt = equipment.findItem (NEquipory.Slots.BELT.idx);
        if(wbelt!=null && wbelt.item.contents!=null) {
            ArrayList<WItem> witems = ((NInventory) wbelt.item.contents).getItems(new NAlias("Waterskin"));
            if (!witems.isEmpty()) {
                for (WItem item : witems) {
                    NGItem ngItem = ((NGItem) item.item);
                    if (!ngItem.content().isEmpty()) {
                        if (ngItem.content().get(0).name().contains("Water")) {
                            return true;
                        }
                    }
                }
            }
        }
        // Check bucket in hands
        WItem bucket = equipment.findBucket("Water");
        if (bucket != null) {
            NGItem ngItem = ((NGItem) bucket.item);
            if (!ngItem.content().isEmpty()) {
                if (ngItem.content().get(0).name().contains("Water")) {
                    return true;
                }
            }
        }
        return false;
    }
}
