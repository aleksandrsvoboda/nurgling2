package nurgling.tasks;

import haven.Area;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

public class WaitGobsInField extends NTask
{
    Area area;
    int count;
    // Substring of the tiles that count: "field" for tilled crops, "woodchip" for champignons.
    final String soil;
    public WaitGobsInField(Area area , int count)
    {
        this(area, count, "field");
    }

    public WaitGobsInField(Area area , int count, String soil)
    {
        this.area = area;
        this.count = count;
        this.soil = soil;
    }

    @Override
    public boolean check()
    {
        Area.Tile[][] tiles =  area.getTiles(area, new NAlias("gfx/terobjs/moundbed"));
        int num = 0;
        for (int i = 0; i <= area.br.x - area.ul.x; i++) {
            for (int j = 0; j <= area.br.y - area.ul.y; j++)
            {
                if(!tiles[i][j].isFree && NParser.checkName(tiles[i][j].name, soil))
                {
                    num++;
                }
            }
        }
        return num==count;
    }

}
