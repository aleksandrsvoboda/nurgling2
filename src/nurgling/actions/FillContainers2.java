package nurgling.actions;

import haven.Coord;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Container;

import nurgling.tools.NAlias;
import nurgling.tools.StackSupporter;
import nurgling.NInventory.QualityType;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.HashSet;

public class FillContainers2 implements Action
{
    ArrayList<Container> conts;
    String transferedItems;
    NContext context;
    Coord targetCoord = new Coord(1,1);
    QualityType qualityType = null;
    Specialisation.SpecName destinationSpec = null;
    String destinationSubSpec = null;

    public FillContainers2(ArrayList<Container> conts, String transferedItems, NContext context, QualityType qualityType,
                           Specialisation.SpecName destinationSpec, String destinationSubSpec) {
        this.conts = conts;
        this.context = context;
        this.transferedItems = transferedItems;
        this.qualityType = qualityType;
        this.destinationSpec = destinationSpec;
        this.destinationSubSpec = destinationSubSpec;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NArea area = NContext.findInGlobal(transferedItems);
        if (area == null)
            return Results.ERROR("NO area for: " + transferedItems);
        context.addInItem(transferedItems, null);
        /* Source storages already emptied of transferedItems, shared by every top-up below so
         * repeated trips do not walk back to ones with nothing left. Safe because the source
         * (findInGlobal above) is a different area from the containers being filled. */
        HashSet<String> depletedSources = new HashSet<>();

        /* QualityType.High means the BEST copies, and the best copies are not necessarily in
         * whichever container happens to be opened first - so rank the whole source area once and
         * take only from the top of that ranking. Everything clearing the cut-off belongs in the
         * result, which is why the fetch below can stay greedy and still end up globally correct.
         * Costs one pass over the source containers, once per fill. */
        Float minQuality = null;
        if (qualityType == QualityType.High) {
            FindQualityThreshold scan = new FindQualityThreshold(context, transferedItems, calculateTargetSize());
            scan.run(gui);
            minQuality = scan.getThreshold();
            if (minQuality != null) {
                // Containers holding nothing that good are not worth walking to at all.
                depletedSources.addAll(scan.getWithoutEligible());
                gui.msg("Taking " + transferedItems + " of q" + String.format("%.1f", minQuality) + " and above");
            }
        }

        for (Container cont : conts) {
            while(!isReady(cont)) {
                if (gui.getInventory().getItems(transferedItems).isEmpty()) {
                    int target_size = calculateTargetSize();
                    int optimalCapacity = StackSupporter.getOptimalItemCapacity(NUtils.getGameUI().getInventory(), transferedItems, targetCoord, target_size);
                    TakeItems2 take = new TakeItems2(context, transferedItems, optimalCapacity, qualityType);
                    take.depleted = depletedSources;
                    take.minQuality = minQuality;
                    take.run(gui);
                    if (gui.getInventory().getItems(transferedItems).isEmpty())
                        return Results.ERROR("NO ITEMS");
                }
                if (destinationSubSpec != null) {
                    context.goToArea(destinationSpec, destinationSubSpec);
                } else {
                    context.goToArea(destinationSpec);
                }
                TransferToContainer ttc = new TransferToContainer(cont, new NAlias(transferedItems));
                ttc.run(gui);
                new CloseTargetContainer(cont).run(gui);
            }

        }
        return Results.SUCCESS();
    }

    private int calculateTargetSize() throws InterruptedException {
        int target_size = 0;
        for (Container tcont : conts) {
            Container.Tetris tetris = tcont.getattr(Container.Tetris.class);
            if(tetris!=null) {
                if (!(Boolean) tetris.getRes().get(Container.Tetris.DONE)) {
                    ArrayList<Coord> coords = (ArrayList<Coord>) tetris.getRes().get(Container.Tetris.TARGET_COORD);
                    if (coords.size() != 1) {
                        NUtils.getGameUI().error("BAD LOGIC. TOO BIG COORDS ARRAY FOR TETRIS");
                        throw new InterruptedException();
                    }
                    Coord target_coord = coords.get(0);
                    target_size += tetris.calcNumberFreeCoord(Container.Tetris.SRC, target_coord);
                }
            }
            else
            {
                Container.Space space = tcont.getattr(Container.Space.class);
                Integer freeSpace = (Integer) space.getRes().get(Container.Space.FREESPACE);
                if (freeSpace != null) {
                    target_size += freeSpace;
                }
            }
        }
        return target_size;
    }

    boolean isReady(Container container) {
        Container.Tetris tetris = container.getattr(Container.Tetris.class);
        if(tetris!=null)
        {
            return (Boolean)tetris.getRes().get(Container.Tetris.DONE);
        }
        else
        {
            Container.Space space = container.getattr(Container.Space.class);
            return (Integer)space.getRes().get(Container.Space.FREESPACE) != null && (Integer)space.getRes().get(Container.Space.FREESPACE)==0;
        }
    };
}
