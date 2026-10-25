package nurgling.actions;

import haven.Coord2d;
import haven.Gob;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.bots.SwillItemRegistry;
import nurgling.areas.NArea;
import nurgling.tasks.ChangeModelAtrib;
import nurgling.tasks.FilledTrough;
import nurgling.tasks.WaitFreeHand;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;
import java.util.List;

public class TransferToTrough implements Action {
    private static final String TROUGH = "gfx/terobjs/trough";

    @Override
    public Results run ( NGameUI gui )
            throws InterruptedException {
            if(troughs.isEmpty())
                return Results.ERROR("NO THROUGH");
            ArrayList<WItem> witems;

            while(!(witems = gui.getInventory ().getItems( items )).isEmpty()) {
                // A full trough hands over to the nearest one with room; the cistern is only
                // used once every trough is full, since emptying one means carrying it there.
                Gob trough = nearest(true);
                if(trough == null) {
                    if(cistern == null) {
                        // Every trough is full and there is nowhere to empty them - nothing more
                        // can be deposited, so leave the rest in the inventory instead of looping
                        // on a drop that the server will keep refusing.
                        break;
                    }
                    trough = nearest(false);
                }
                new PathFinder(trough).run(gui);
                trough = live(trough);
                if(trough.ngob.getModelAttribute()==7 )
                {
                    if(nearest(true) != null) {
                        // The cached state was stale; another trough has room after all.
                        continue;
                    }
                    if(cistern!=null) {
                        if (NUtils.getGameUI().vhand != null) {
                            gui.getInventory().dropOn(gui.getInventory().findFreeCoord(NUtils.getGameUI().vhand));
                        }
                        Coord2d pos = trough.rc;
                        double a = trough.a;
                        new LiftObject(trough).run(gui);
                        new PathFinder ( cistern ).run(gui);
                        NUtils.activateGob ( cistern );
                        NUtils.getUI().core.addTask(new ChangeModelAtrib(trough, 7));
                        new PlaceObject(trough, pos, a).run(gui);
                    }
                    else {
                        break;
                    }
                }
                boolean carriesOtherSwill = carriesOtherSwill(gui);
                NUtils.takeItemToHand(witems.get(0));
                if(carriesOtherSwill) {
                    NUtils.activateItem(trough, false);
                    NUtils.getUI().core.addTask(new WaitFreeHand());
                } else {
                    NUtils.dropsame(trough);
                    NUtils.getUI().core.addTask(new FilledTrough(trough, items));
                }
            }
        return Results.SUCCESS();
        }

    /**
     * Every trough in the area, for the multi-trough constructors. Exact name match: the alias
     * is a substring match. Empty when the area is unset or not loaded.
     */
    public static ArrayList<Gob> findTroughs(NArea area) throws InterruptedException {
        ArrayList<Gob> result = new ArrayList<>();
        if (area == null)
            return result;
        for (Gob gob : Finder.findGobs(area, new NAlias(TROUGH))) {
            if (TROUGH.equals(gob.ngob.name))
                result.add(gob);
        }
        return result;
    }

    /** Whether any of the troughs can still take items (attribute 7 = full). */
    public static boolean hasRoom(List<Gob> troughs) {
        for (Gob trough : troughs) {
            if (live(trough).ngob.getModelAttribute() != 7)
                return true;
        }
        return false;
    }

    /**
     * The trough closest to the player; with {@code withRoom}, only the ones that are not full.
     * Null when there is none.
     */
    private Gob nearest(boolean withRoom) {
        Gob player = NUtils.player();
        Gob best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Gob cached : troughs) {
            Gob trough = live(cached);
            if (withRoom && trough.ngob.getModelAttribute() == 7)
                continue;
            double d = (player == null) ? 0 : trough.rc.dist(player.rc);
            if (best == null || d < bestDist) {
                best = trough;
                bestDist = d;
            }
        }
        return best;
    }

    /**
     * The current gob for a trough. Troughs drain as animals eat and a trough that left view
     * comes back as a new Gob, so the fill state is read from the live object; out of view,
     * the cached one still gives a position to walk to.
     */
    private static Gob live(Gob cached) {
        Gob gob = Finder.findGob(cached.id);
        return (gob != null) ? gob : cached;
    }

    /**
     * Whether the inventory holds swill-compatible items other than the ones being transferred.
     *
     * dropsame() is a shift+ctrl itemact, which makes the server empty everything the trough
     * accepts out of the inventory - not just the item in hand. A pumpkin harvest reaches the
     * trough holding both Pumpkin Seeds and Pumpkin Flesh, and the flesh went in with the seeds
     * instead of to its own stockpile. When anything else edible is carried, deposit one item at
     * a time so only the requested items move.
     */
    private boolean carriesOtherSwill(NGameUI gui) throws InterruptedException {
        for (WItem item : gui.getInventory().getItems()) {
            String name = ((NGItem) item.item).name();
            if (name == null || NParser.checkName(name, items))
                continue;
            if (SwillItemRegistry.isSwillItem(name))
                return true;
        }
        return false;
    }



    public TransferToTrough(
            Gob gob,
            NAlias items
    )
    {
        this((gob == null) ? List.of() : List.of(gob), items);
    }

    public TransferToTrough(
            Gob gob,
            NAlias items,
            Gob cistern
    )
    {
        this(gob,items);
        this.cistern = cistern;
    }

    /** Fills the troughs one after another, moving on whenever the one in use is full. */
    public TransferToTrough(
            List<Gob> troughs,
            NAlias items
    )
    {
        this.troughs = troughs;
        this.items = items;
    }

    public TransferToTrough(
            List<Gob> troughs,
            NAlias items,
            Gob cistern
    )
    {
        this(troughs,items);
        this.cistern = cistern;
    }

    List<Gob> troughs;
    Gob cistern = null;
    NAlias items;
}
