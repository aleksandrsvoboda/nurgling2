package nurgling;

import haven.*;
import nurgling.pf.PolyRects;

import java.util.*;

public class NHitBox
{
    public Coord2d begin;
    public Coord2d end;

    /**
     * The sub-rectangles of a compound footprint, in the same object-local space as
     * {@link #begin}/{@link #end}, or {@code null} for a plain single-rectangle box.
     *
     * <p>Some objects - the timber tunnel, arches, anything with a doorway - block only part of
     * their bounding box and are walkable in between. {@link #begin}/{@link #end} stay the union of
     * every part, so any consumer that has not been taught about parts keeps working and simply
     * treats the object as solid, which is the conservative answer. Consumers that care go through
     * {@link nurgling.pf.NHitShapeD} (analytic) or {@link nurgling.pf.CellsArray} (raster).
     */
    private final NHitBox[] parts;

    public NHitBox(Coord begin, Coord end, boolean force)
    {
        this(new Coord2d(begin),new Coord2d(end), force);
    }

    public NHitBox(Coord begin, Coord end)
    {
        this(new Coord2d(begin),new Coord2d(end), false);
    }

    public NHitBox(Coord2d begin, Coord2d end)
    {
        this(begin, end, false);
    }

    public NHitBox(Coord2d begin, Coord2d end, boolean force)
    {
        this(begin, end, force, null);
    }

    private NHitBox(Coord2d begin, Coord2d end, boolean force, NHitBox[] parts)
    {
        if(force)
        {
            this.begin = new Coord2d(begin.x,begin.y);
            this.end = new Coord2d(end.x, end.y);
        }
        else {
            // A floor on the size of the object as a whole, not an inflation of each piece.
            // Parts are usually small enough to trip it, so applying it per part would grow each
            // one to 6x6 about the gob's origin and swallow the gap between them.
            this.begin = new Coord2d(Math.min(begin.x, -3), Math.min(begin.y, -3));
            this.end = new Coord2d(Math.max(end.x, 3), Math.max(end.y, 3));
        }
        this.parts = (parts != null && parts.length > 1) ? parts : null;
    }

    /** True when this footprint is made of several disjoint rectangles with gaps between them. */
    public boolean isCompound()
    {
        return parts != null;
    }

    /**
     * The sub-rectangles of a compound footprint, or {@code null} for a plain box. Callers that
     * only need "the whole thing" should keep using {@link #begin}/{@link #end}.
     */
    public NHitBox[] parts()
    {
        return parts;
    }

    /**
     * Build a footprint out of {@code {ul, br}} rectangle pairs. The union carries the usual
     * minimum-size clamp unless {@code force}; the parts are used as given.
     */
    public static NHitBox compound(List<Coord2d[]> rects, boolean force)
    {
        if (rects == null || rects.isEmpty())
            return null;
        NHitBox[] parts = new NHitBox[rects.size()];
        double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
        double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
        for (int i = 0; i < rects.size(); i++)
        {
            Coord2d[] r = rects.get(i);
            // Rounded outward, matching what the single-rectangle path has always done.
            Coord2d ul = new Coord2d(Math.floor(Math.min(r[0].x, r[1].x)), Math.floor(Math.min(r[0].y, r[1].y)));
            Coord2d br = new Coord2d(Math.ceil(Math.max(r[0].x, r[1].x)), Math.ceil(Math.max(r[0].y, r[1].y)));
            parts[i] = new NHitBox(ul, br, true);
            minx = Math.min(minx, ul.x);
            miny = Math.min(miny, ul.y);
            maxx = Math.max(maxx, br.x);
            maxy = Math.max(maxy, br.y);
        }
        if (parts.length == 1)
            return new NHitBox(parts[0].begin, parts[0].end, force);
        return new NHitBox(new Coord2d(minx, miny), new Coord2d(maxx, maxy), force, parts);
    }

    private final static HashMap<String, NHitBox> custom = new HashMap<String, NHitBox>()
    {
        {
            put("log", new NHitBox(new Coord2d(-10,-2.5),new Coord2d(10,2.5), true));
            put("gfx/terobjs/vehicle/dugout", new NHitBox(new Coord(-10,-2),new Coord(10,2)));
            put("gfx/terobjs/vehicle/coracle", new NHitBox(new Coord(-5,-3),new Coord(5,3)));
            put("gfx/terobjs/vehicle/skis-wilderness", new NHitBox(new Coord(-8,-2),new Coord(8,2)));
            put("gfx/terobjs/trough", new NHitBox(new Coord(-4,-13),new Coord(4,13)));
            put("gfx/terobjs/minehole", new NHitBox(new Coord(-15,-15),new Coord(15,15)));
            put("bumlings", new NHitBox(new Coord(-3,-3),new Coord(3,3)));
            put("gfx/terobjs/arch/stonemansion", new NHitBox(new Coord(-50,-50),new Coord(50,50)));
            put("gfx/terobjs/arch/logcabin", new NHitBox(new Coord(-23,-23),new Coord(23,23)));
            put("gfx/terobjs/arch/greathall", new NHitBox(new Coord(-80,-55),new Coord(80,55)));
            put("gfx/terobjs/arch/greathall-door", new NHitBox(new Coord2d(-3.3,-41.25),new Coord2d(3.3,41.25), true));
            put("gfx/terobjs/arch/timberhouse", new NHitBox(new Coord(-33,-33),new Coord(33,33)));
            put("gfx/terobjs/arch/stonetower", new NHitBox(new Coord(-39,-39),new Coord(39,39)));
            put("gfx/terobjs/arch/windmill", new NHitBox(new Coord(-28,-28),new Coord(28,28)));
            put("gfx/terobjs/wonders/tarpit", new NHitBox(new Coord(-28,-28),new Coord(28,28)));
            put("gfx/terobjs/coronationstone", new NHitBox(new Coord(-30,-35),new Coord(41,35)));
            put("gfx/terobjs/arch/stonestead", new NHitBox(new Coord(-45,-28),new Coord(45,28)));
            put("gfx/terobjs/villageidol", new NHitBox(new Coord(-11,-17),new Coord(11,17)));
            put("gfx/terobjs/pclaim", new NHitBox(new Coord(-3,-3),new Coord(3,3)));
            put("gfx/terobjs/iconsign", new NHitBox(new Coord(-2,-2),new Coord(2,2)));
            put("gfx/terobjs/candelabrum", new NHitBox(new Coord(-2,-2),new Coord(2,2)));
            put("gfx/terobjs/gardenpot", new NHitBox(new Coord2d(-2.5,-2.5), new Coord2d(2.5,2.5), true));
            put("gfx/terobjs/cupboard", new NHitBox(new Coord2d(-5.5,-5.5),new Coord2d(5.5,5.5)));
            put("gfx/terobjs/htable", new NHitBox(new Coord2d(-3.5,-7.0),new Coord2d(3.5,7.0), true));
            put("gfx/terobjs/lanternpost", new NHitBox(new Coord(-2,-2),new Coord(2,2)));
            put("gfx/terobjs/cistern", new NHitBox(new Coord(-9,-9),new Coord(9,9)));
            put("gfx/terobjs/oven", new NHitBox(new Coord(-9,-9),new Coord(9,9)));
            put("gfx/terobjs/kiln", new NHitBox(new Coord(-10,-10),new Coord(10,10)));
            put("gfx/terobjs/leanto", new NHitBox(new Coord(-9,-9),new Coord(9,9)));
            put("gfx/terobjs/stonepillar", new NHitBox(new Coord(-12,-12),new Coord(12,12)));
            put("gfx/terobjs/vehicle/plow", new NHitBox(new Coord(-7,-4),new Coord(3,4)));
//            put("gfx/terobjs/fineryforge", new NHitBox(new Coord(-9,-9),new Coord(9,9)));
            put("gfx/terobjs/smelter", new NHitBox(new Coord2d(-11.5,-20),new Coord2d(11.5,11)));
            put("gfx/terobjs/charterstone", new NHitBox(new Coord(-9,-9),new Coord(9,9)));
            put("gfx/terobjs/steelcrucible", new NHitBox(new Coord(-3,-4),new Coord(3,4)));
            put("gfx/terobjs/beehive", new NHitBox(new Coord(-5,-5),new Coord(5,5)));
            put("gfx/terobjs/dng/giantspool", new NHitBox(new Coord(-3,-3),new Coord(3,3)));
            put("gfx/terobjs/dng/rathole", new NHitBox(new Coord2d(0,0),new Coord2d(0,0), true));
            put("gfx/terobjs/dng/giantcheese", new NHitBox(new Coord2d(-7.0,-5.2),new Coord2d(5.5,5.2)));
            put("gfx/terobjs/column", new NHitBox(new Coord(-4,-4),new Coord(4,4)));
            put("gfx/terobjs/meatgrinder", new NHitBox(new Coord(-4,-4),new Coord(4,4)));
            put("gfx/terobjs/brazier", new NHitBox(new Coord(-4,-4),new Coord(4,4)));
            put("gfx/terobjs/granary", new NHitBox(new Coord(-16,-16),new Coord(16,16)));
            put("gfx/terobjs/pow", new NHitBox(new Coord(-4,-4),new Coord(4,4)));
            put("gfx/terobjs/stockpile-cloth", new NHitBox(new Coord(-5,-5),new Coord(5,5)));
            put( "gfx/terobjs/stockpile-soil", new NHitBox(new Coord2d(-5.5,-5.5),new Coord2d(5.5,5.5)));
            put("stockpile", new NHitBox(new Coord(-5,-5),new Coord(5,5)));
            put("gfx/terobjs/smokeshed", new NHitBox(new Coord2d(-7,-8),new Coord2d(7,8)));
            put("gfx/terobjs/vehicle/cart", new NHitBox(new Coord(-6,-6),new Coord(6,6)));
            put("gfx/terobjs/knarrdock", new NHitBox(new Coord(-62,-14),new Coord(60,14)));
            put("gfx/terobjs/furn/bed-sturdy", new NHitBox(new Coord(-9,-6),new Coord(9,6)));
            put("gfx/terobjs/vehicle/wreckingball-fold", new NHitBox(new Coord(-5,-11),new Coord(5,11)));
            put("gfx/terobjs/quern", new NHitBox(new Coord(-4,-4),new Coord(4,4)));
            put("gfx/terobjs/arch/palisadeseg", new NHitBox(new Coord2d(-5.5,-5.5),new Coord2d(5.5,5.5)));
            put("gfx/terobjs/arch/palisadecp", new NHitBox(new Coord2d(-5.5,-5.5),new Coord2d(5.5,5.5)));
            put("gfx/terobjs/arch/polecp", new NHitBox(new Coord(-5,-5),new Coord(5,5)));
            put("gfx/terobjs/arch/poleseg", new NHitBox(new Coord(-5,-5),new Coord(5,5)));
            put("gfx/terobjs/arch/drystonewallseg", new NHitBox(new Coord(-5,-5),new Coord(5,5)));
            put("gfx/terobjs/arch/drystonewallcp", new NHitBox(new Coord(-5,-5),new Coord(5,5)));
            put("gfx/terobjs/arch/polebiggate", new NHitBox(new Coord(-5,-16),new Coord(5,16)));
            put("gfx/terobjs/arch/drystonewallbiggate", new NHitBox(new Coord(-5,-16),new Coord(5,16)));
            put("gfx/terobjs/arch/palisadebiggate", new NHitBox(new Coord(-5,-16),new Coord(5,16)));
            put("gfx/terobjs/arch/polegate", new NHitBox(new Coord(-5,-11),new Coord(5,11)));
            put("gfx/terobjs/arch/drystonewallgate", new NHitBox(new Coord(-5,-11),new Coord(5,11)));
            put("gfx/terobjs/arch/palisadegate", new NHitBox(new Coord(-5,-11),new Coord(5,11)));
            put("gfx/terobjs/potterswheel", new NHitBox(new Coord(-2,-6),new Coord(2,6)));
            put("gfx/terobjs/stockpile-oddtuber", new NHitBox(new Coord(-5,-5),new Coord(5,5)));
            put("gfx/terobjs/stockpile-soil", new NHitBox(new Coord2d(-5.5,-5.5),new Coord2d(5.5,5.5)));
            put("gfx/terobjs/stockpile-lemon", new NHitBox(new Coord(-4,-4),new Coord(4,4)));
            put("gfx/terobjs/stockpile-nut", new NHitBox(new Coord(-4,-4),new Coord(4,4)));
            put("gfx/terobjs/stockpile-cavebulb", new NHitBox(new Coord(-4,-4),new Coord(4,4)));
            put("gfx/terobjs/stockpile-bark", new NHitBox(new Coord(-3,-3),new Coord(3,3)));
            put("gfx/terobjs/primsmelter", new NHitBox(new Coord(-8,-7),new Coord(11,7)));
            put("gfx/kritter/cattle/calf", new NHitBox(new Coord(-9,-4),new Coord(9,4)));
            put("gfx/kritter/cattle/cattle", new NHitBox(new Coord(-9,-4),new Coord(9,4)));
            put("gfx/kritter/horse/stallion", new NHitBox(new Coord2d(-8,-4),new Coord2d(8,4)));
            put("gfx/kritter/horse/mare", new NHitBox(new Coord2d(-8,-4),new Coord2d(8,4)));
            put("gfx/kritter/horse/foal", new NHitBox(new Coord2d(-8,-4),new Coord2d(8,4)));
            put("gfx/kritter/boar/boar", new NHitBox(new Coord(-10,-4),new Coord(7,4)));
            put("gfx/kritter/pig/piglet", new NHitBox(new Coord(-6,-4),new Coord(6,4)));
            put("gfx/kritter/pig/sow", new NHitBox(new Coord(-6,-4),new Coord(6,4)));
            put("gfx/kritter/pig/hog", new NHitBox(new Coord(-6,-4),new Coord(6,4)));
            put("gfx/kritter/sheep/lamb", new NHitBox(new Coord(-4,-2),new Coord(5,2)));
            put("gfx/kritter/sheep/sheep", new NHitBox(new Coord(-4,-2),new Coord(5,2)));
            put("gfx/kritter/goat/billy", new NHitBox(new Coord(-4,-2),new Coord(4,2)));
            put("gfx/kritter/goat/nanny", new NHitBox(new Coord(-4,-2),new Coord(4,2)));
            put("gfx/kritter/goat/kid", new NHitBox(new Coord(-4,-2),new Coord(4,2)));
            put("gfx/kritter/reindeer/teimdeercow", new NHitBox(new Coord(-12,-2),new Coord(6,2)));
            put("gfx/kritter/reddeer/reddeer", new NHitBox(new Coord(-10,-4),new Coord(7,4)));
            put("gfx/kritter/reindeer/teimdeerbull", new NHitBox(new Coord(-12,-2),new Coord(6,2)));
            put("gfx/kritter/reindeer/teimdeerkid", new NHitBox(new Coord(-12,-2),new Coord(6,2)));
            put("gfx/kritter/reindeer/reindeer", new NHitBox(new Coord(-12,-2),new Coord(6,2)));
            put("gfx/terobjs/trees/orangetree", new NHitBox(new Coord(-3,-3),new Coord(3,3)));
            put("gfx/terobjs/trees/orangetreestump", new NHitBox(new Coord(-3,-3),new Coord(3,3)));
            put("gfx/terobjs/trees/driftwood2", new NHitBox(new Coord(-10,-2),new Coord(10,2)));
            put("gfx/terobjs/stockpile-orange", new NHitBox(new Coord(-4,-4),new Coord(4,4)));
            put("gfx/terobjs/map/squirrelcache", new NHitBox(new Coord(-4,-4),new Coord(4,4)));
            put("gfx/terobjs/vehicle/wagon", new NHitBox(new Coord(-14,-8),new Coord(12,8)));
            put("gfx/terobjs/dovecote", new NHitBox(new Coord(-7,-7),new Coord(7,7)));
            put("gfx/terobjs/anvil", new NHitBox(new Coord(-7,-2),new Coord(5,2)));
            put("gfx/terobjs/moundbed", new NHitBox(new Coord2d(-15.4, -10.45),new Coord2d(15.4, 10.45)));
            put("gfx/terobjs/producesack-ter", new NHitBox(new Coord2d(-4,-4),new Coord2d(4,4)));
            put("gfx/terobjs/producesack-closed0", new NHitBox(new Coord2d(-4,-4),new Coord2d(4,4)));
            put("gfx/terobjs/producesack-closed1", new NHitBox(new Coord2d(-4,-4),new Coord2d(4,4)));
            put("gfx/terobjs/producesack-closed2", new NHitBox(new Coord2d(-4,-4),new Coord2d(4,4)));
            put("gfx/terobjs/producesack-closed3", new NHitBox(new Coord2d(-4,-4),new Coord2d(4,4)));
            put("gfx/terobjs/producesack-closed4", new NHitBox(new Coord2d(-4,-4),new Coord2d(4,4)));
            put("gfx/terobjs/arch/belltower", new NHitBox(new Coord2d(-15,-19),new Coord2d(15,19)));
            put("gfx/terobjs/still", new NHitBox(new Coord2d(-12,-6),new Coord2d(5,6)));
            put("gfx/terobjs/grandstudydesk", new NHitBox(new Coord2d(-6.2,-11.75),new Coord2d(7.45,11.75)));
        }
    };
    /**
     * Resource names that are allowed to keep the multi-rectangle footprint their obstacle data
     * actually describes, instead of being collapsed to one box.
     *
     * <p>This is deliberately a hardcoded opt-in list rather than a blanket rule. The obstacle
     * layer is multi-polygon for plenty of objects where the extra detail is noise, and letting
     * every one of them turn compound would change pathfinding across the whole world at once. An
     * object earns a place here only once its geometry has been checked in game - see
     * {@link nurgling.tools.HitBoxProbe}, which prints the real polygons and the resulting cell
     * raster for whatever is under the cursor.
     *
     * <p>Nothing is invented here: the geometry still comes from the resource itself. The name only
     * decides whether the gap survives. To override the geometry by hand instead, put a
     * {@link #compound(List, boolean)} box straight into {@link #custom} - that takes priority.
     */
    private final static Set<String> compoundNames = new HashSet<>(Arrays.asList(
            // Two legs with a walkable middle.
            "gfx/terobjs/timbertunnel"
    ));

    /** Whether {@code name} is on the hardcoded multi-part opt-in list. */
    public static boolean isCompoundName(String name)
    {
        return name != null && compoundNames.contains(name);
    }

    public static NHitBox fromObstacle(Coord2d[][] p)
    {
        return fromObstacle(p ,false);
    }

    public static NHitBox fromObstacle(Coord2d[][] p, boolean force)
    {
        if(p.length == 1 && p[0].length == 4)
        {
            return new NHitBox(p[0][0].floor(),p[0][2].ceil(), force);
        }
        return null;
    }

    /**
     * As {@link #fromObstacle(Coord2d[][], boolean)}, but resources on the {@link #compoundNames}
     * list keep their gaps. Every other name takes the unchanged single-rectangle path.
     */
    public static NHitBox fromObstacle(Coord2d[][] p, boolean force, String resName)
    {
        if(isCompoundName(resName))
        {
            List<Coord2d[]> rects = PolyRects.decompose(p);
            if(!rects.isEmpty())
                return compound(rects, force);
        }
        return fromObstacle(p, force);
    }

    public static NHitBox findCustom(String name)
    {
        NHitBox res = custom.get(name);
        if(res!=null)
            return res;
        if(name.endsWith("log") && name.startsWith("gfx/terobjs/trees"))
            return custom.get("log");
        if(name.startsWith("gfx/terobjs/bumlings"))
            return custom.get("bumlings");
        else if(name.endsWith("board"))
            return new NHitBox(new Coord(-8,-8),new Coord(8,8));
        else if(name.endsWith("block"))
            return new NHitBox(new Coord(-5,-5),new Coord(5,5));
        else if(name.toLowerCase().startsWith("bar of"))
            return new NHitBox(new Coord(-5,-7),new Coord(5,7));
        else if(name.toLowerCase().endsWith("leaf"))
            return new NHitBox(new Coord(-5,-5),new Coord(5,5));
        else if(name.toLowerCase().startsWith("flax") || name.toLowerCase().endsWith("stockpile-hempfibre"))
            return new NHitBox(new Coord(-3,-3),new Coord(3,3));
        return null;
    }

    public NHitBox rotate(){
        NHitBox[] rp = null;
        if(parts != null)
        {
            rp = new NHitBox[parts.length];
            for(int i = 0; i < parts.length; i++)
                rp[i] = parts[i].rotate();
        }
        return new NHitBox(new Coord2d((int) begin.y, (int) begin.x),new Coord2d((int) end.y, (int) end.x), true, rp);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("([").append(begin.x).append(",").append(begin.y).append("],[")
                .append(end.x).append(",").append(end.y).append("])");
        if(parts != null)
        {
            sb.append(" x").append(parts.length).append("{");
            for(int i = 0; i < parts.length; i++)
                sb.append(i > 0 ? " " : "").append(parts[i].toString());
            sb.append("}");
        }
        return sb.toString();
    }
}
