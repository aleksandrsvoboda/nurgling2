package nurgling.actions;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.tools.Finder;

import static nurgling.tools.Finder.findLiftedbyPlayer;

public class FindPlaceAndAction implements Action {
    boolean dynamicPf = false;
    public FindPlaceAndAction(Gob gob, Pair<Coord2d, Coord2d> rcArea) {
        this.placed = gob;
        this.area = rcArea;
    }

    @Override
    public Results run ( NGameUI gui )
            throws InterruptedException {
        if(placed == null)
            placed = findLiftedbyPlayer();
        if ( placed != null ) {
            // Stream in the whole target area before choosing a drop cell.
            // getFreePlace only sees loaded gobs, so a partially-visible area
            // could otherwise hand back a cell that is actually occupied by an
            // object that has not loaded yet. Only possible when we know the NArea.
            if (narea != null) {
                NUtils.navigateToArea(narea, true);
            }
            Coord2d pos = Finder.getFreePlace(area, placed);
            if(pos!=null) {

                new PlaceObject(placed, pos,0, dynamicPf).run(gui);
                return Results.SUCCESS();
            }
            else
                return Results.ERROR("No free place");

        }
        return Results.ERROR("No gob for place");
    }



    public FindPlaceAndAction(
            Gob gob,
            NArea area)
    {
        this.placed = gob;
        this.narea = area;
        this.area = area.getRCArea();
    }

    public FindPlaceAndAction(
            Gob gob,
            NArea area,
            boolean dynamicPf)
    {
        this.placed = gob;
        this.narea = area;
        this.area = area.getRCArea();
        this.dynamicPf = dynamicPf;
    }

    public Gob getPlaced() {
        return placed;
    }

    Gob placed = null;
    Pair<Coord2d, Coord2d> area = null;
    // Non-null when the area was supplied as an NArea; enables the "stream in
    // the whole area before placing" walk in run(). Null for raw-rectangle callers.
    NArea narea = null;
}