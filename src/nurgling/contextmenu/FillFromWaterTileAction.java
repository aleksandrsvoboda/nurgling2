package nurgling.contextmenu;

import haven.Coord;
import haven.Coord2d;
import nurgling.actions.Action;
import nurgling.actions.FillWaterContainers;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

public class FillFromWaterTileAction implements TileContextAction {
    private static final NAlias WATER_TILE = new NAlias("gfx/tiles/deep", "gfx/tiles/owater");

    @Override
    public boolean appliesTo(Coord2d mapPos) {
        Coord tileCoord = new Coord2d(mapPos.x / 11, mapPos.y / 11).floor();
        return NParser.isIt(tileCoord, WATER_TILE);
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.fill_from_water_tile");
    }

    @Override
    public Action create(Coord2d mapPos) {
        return gui -> new FillWaterContainers(FillWaterContainers.fromWaterTile()).run(gui);
    }
}
