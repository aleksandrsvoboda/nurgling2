package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.Results;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.overlays.BuildGhostPreview;
import nurgling.overlays.NCustomBauble;
import nurgling.tasks.WaitCheckable;
import nurgling.tasks.WaitPlob;
import nurgling.widgets.bots.MultiAreaConfirm;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SelectAreaWithLiveGhosts extends SelectArea {
    private String buildingName;
    private int rotationCount = 0;
    private NHitBox customHitBox = null;
    public NArea ghostArea;
    NContext context;

    public SelectAreaWithLiveGhosts(NContext context, BufferedImage image, String buildingName) {
        super(image);
        this.buildingName = buildingName;
        this.context = context;
    }

    public SelectAreaWithLiveGhosts(NContext context, BufferedImage image, String buildingName, NHitBox customHitBox) {
        super(image);
        this.buildingName = buildingName;
        this.customHitBox = customHitBox;
        this.context = context;
    }

    public int getRotationCount() {
        return rotationCount;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        NMapView mapView = (NMapView) NUtils.getGameUI().map;

        if (mapView.isAreaSelectionMode.get())
        {
            return Results.ERROR("Area selection already in progress");
        }

        Gob player = NUtils.player();

        if (image != null && player != null)
        {
            player.addcustomol(new NCustomBauble(player, image, spr, mapView.isAreaSelectionMode));
        }

        // Activate build menu once to get hitbox/resource/sdt
        for (MenuGrid.Pagina pag : NUtils.getGameUI().menu.paginae)
        {
            if (pag.button() != null && pag.button().name().equals(buildingName))
            {
                pag.button().use(new MenuGrid.Interaction(1, 0));
                break;
            }
        }
        if (NUtils.getGameUI().map.placing == null)
        {
            for (MenuGrid.Pagina pag : NUtils.getGameUI().menu.paginae)
            {
                if (pag.button() != null && pag.button().name().equals(buildingName))
                {
                    pag.button().use(new MenuGrid.Interaction(1, 0));
                    break;
                }
            }
        }
        NUtils.addTask(new WaitPlob());
        MapView.Plob plob = NUtils.getGameUI().map.placing.get();

        // Clean up any existing ghost preview from previous run
        if (player != null)
        {
            BuildGhostPreview oldGhost = player.getattr(BuildGhostPreview.class);
            if (oldGhost != null)
            {
                oldGhost.dispose();
                player.delattr(BuildGhostPreview.class);
            }
        }

        NHitBox hitBox = plob.ngob.hitBox;
        if (hitBox == null && customHitBox != null)
        {
            hitBox = customHitBox;
        }

        Indir<Resource> resource = null;
        Message sdt = Message.nil;
        ResDrawable rd = plob.getattr(ResDrawable.class);
        if (rd != null && rd.res != null)
        {
            resource = rd.res;
            if (rd.sdt != null) sdt = rd.sdt.clone();
        }
        else if (plob.ngob.name != null)
        {
            resource = Resource.remote().load(plob.ngob.name);
        }

        try
        {
            if (NUtils.getGameUI().map.placing != null)
            {
                plob.delattr(ResDrawable.class);
                NUtils.getGameUI().map.placing.cancel();
                NUtils.getGameUI().map.placing = null;
            }
        } catch (Exception e)
        {
            // Ignore if already cancelled
        }

        // Multi-pass selection loop
        ArrayList<Gob> accumulatedGhosts = new ArrayList<>();
        NArea.Space combinedSpace = new NArea.Space();
        int areasSelected = 0;
        boolean userCancelled = false;

        while (true)
        {
            // Prepare selection state for this round
            mapView.areaSpace = null;
            mapView.currentSelectionCoords = null;
            mapView.rotationRequested = false;
            mapView.isAreaSelectionMode.set(true);

            if (areasSelected == 0)
            {
                NUtils.getGameUI().msg("Please, select build area");
            } else
            {
                NUtils.getGameUI().msg("Please, select another build area (" + (areasSelected + 1) + ")");
            }

            nurgling.tasks.SelectAreaWithLiveGhosts sa =
                new nurgling.tasks.SelectAreaWithLiveGhosts(hitBox, resource, sdt, rotationCount);
            NUtils.getUI().core.addTask(sa);

            if (sa.getResult() == null)
            {
                userCancelled = (areasSelected == 0);
                break;
            }

            // Track rotation chosen this round so the next round starts there
            rotationCount = sa.getRotationCount();

            // Snapshot this round's ghosts and take ownership so they remain visible
            // when the next round's task creates a fresh preview.
            BuildGhostPreview roundPreview = player != null ? player.getattr(BuildGhostPreview.class) : null;
            if (roundPreview != null)
            {
                accumulatedGhosts.addAll(roundPreview.takeGhosts());
            }

            mergeSpace(combinedSpace, sa.getResult());
            areasSelected++;

            // Ask whether to add another area
            int totalPositions = accumulatedGhosts.size();
            MultiAreaConfirm confirm = new MultiAreaConfirm(totalPositions, areasSelected);
            NUtils.getUI().core.addTask(new WaitCheckable(
                NUtils.getGameUI().add(confirm,
                    new Coord(NUtils.getGameUI().sz.x / 2 - UI.scale(130),
                              NUtils.getGameUI().sz.y / 2 - UI.scale(65)))
            ));
            MultiAreaConfirm.State state = confirm.getState();
            confirm.destroy();

            if (state == MultiAreaConfirm.State.BUILD || state == MultiAreaConfirm.State.CANCELLED)
            {
                break;
            }
            // Otherwise loop for another area
        }

        // Tear down any leftover empty preview that the inner task left attached
        if (player != null)
        {
            BuildGhostPreview leftover = player.getattr(BuildGhostPreview.class);
            if (leftover != null)
            {
                player.delattr(BuildGhostPreview.class);
            }
        }

        mapView.isAreaSelectionMode.set(false);

        if (areasSelected == 0 || userCancelled || accumulatedGhosts.isEmpty())
        {
            return Results.FAIL();
        }

        // Attach a fresh preview that owns all accumulated ghosts so Build can call
        // removeGhost(pos) and dispose() against the same set the user saw.
        if (player != null)
        {
            BuildGhostPreview master = new BuildGhostPreview(player, null, hitBox, resource, rotationCount, sdt);
            master.addExistingGhosts(accumulatedGhosts);
            player.setattr(master);
        }

        String id = context.createAreaFromSpace(combinedSpace);
        ghostArea = context.goToAreaById(id);

        return Results.SUCCESS();
    }

    /**
     * Merge another Space into `target`. Per grid, take the bounding box of both areas.
     */
    private static void mergeSpace(NArea.Space target, NArea.Space addition)
    {
        if (addition == null || addition.space == null) return;
        for (Map.Entry<Long, NArea.VArea> e : addition.space.entrySet())
        {
            NArea.VArea existing = target.space.get(e.getKey());
            if (existing == null)
            {
                target.space.put(e.getKey(),
                    new NArea.VArea(new Area(e.getValue().area.ul, e.getValue().area.br)));
            } else
            {
                Coord ul = new Coord(
                    Math.min(existing.area.ul.x, e.getValue().area.ul.x),
                    Math.min(existing.area.ul.y, e.getValue().area.ul.y));
                Coord br = new Coord(
                    Math.max(existing.area.br.x, e.getValue().area.br.x),
                    Math.max(existing.area.br.y, e.getValue().area.br.y));
                target.space.put(e.getKey(), new NArea.VArea(new Area(ul, br)));
            }
        }
    }
}
