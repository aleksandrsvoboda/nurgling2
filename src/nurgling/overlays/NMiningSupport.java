package nurgling.overlays;

import haven.*;
import haven.render.*;
import haven.res.lib.tree.*;
import nurgling.*;
import nurgling.overlays.map.*;
import nurgling.pf.Utils;

public class NMiningSupport extends Sprite implements RenderTree.Node
{
    /* A support's coverage, as one consistent snapshot: the mining
     * overlay builds each map section on its own worker thread, and
     * reading begin, end and the data separately could mix two
     * recalculations. end is exclusive. */
    public static final class Mask {
        public final Coord begin, end;
        public final boolean[][] data;

        Mask(Coord begin, Coord end, boolean[][] data) {
            this.begin = begin;
            this.end = end;
            this.data = data;
        }
    }

    /* How far a kind of support holds the ceiling up: a circle of a
     * radius, or for tunnels a rectangle of tiles along the gob's
     * facing. */
    public static final class Spec {
        public final Integer circleRadius;
        public final int widthTiles, lengthTiles;

        private Spec(Integer circleRadius, int widthTiles, int lengthTiles) {
            this.circleRadius = circleRadius;
            this.widthTiles = widthTiles;
            this.lengthTiles = lengthTiles;
        }

        public static Spec circle(int radius) {return(new Spec(radius, 0, 0));}
        public static Spec rect(int widthTiles, int lengthTiles) {return(new Spec(null, widthTiles, lengthTiles));}
        public boolean isRect() {return(circleRadius == null);}
    }

    public static Spec specFor(String name) {
        if (name == null)
            return null;
        switch (name) {
            case "gfx/terobjs/map/naturalminesupport":
                return Spec.circle(92);
            case "gfx/terobjs/ladder":
            case "gfx/terobjs/minesupport":
            case "gfx/terobjs/trees/towercap":
                return Spec.circle(100);
            case "gfx/terobjs/column":
                return Spec.circle(125);
            case "gfx/terobjs/minebeam":
                return Spec.circle(150);
            case "gfx/terobjs/monumentalcolumn":
                return Spec.circle(330);
            case "gfx/terobjs/timbertunnel":
                return Spec.rect(1, 4);
            case "gfx/terobjs/reinforcedtunnel":
                return Spec.rect(2, 8);
            case "gfx/terobjs/stonearchtunnel":
                return Spec.rect(3, 15);
            default:
                return null;
        }
    }

    /**
     * A tunnel's tiles. Forward is the gob's facing (a=0 is +X), snapped
     * to the nearest cardinal; positive directions start on the gob's
     * tile, negative ones a tile ahead. Even widths lean towards the
     * negative side of the lateral axis, as the game anchors them.
     * forwardShift moves the rectangle along the facing. The mask's end
     * is exclusive.
     */
    public static Mask computeRect(Coord2d rc, double angle, int widthTiles, int lengthTiles, int forwardShift) {
        Coord origin = rc.div(MCache.tilesz).floor();
        Coord fwd = snapCardinal(Coord2d.of(1, 0).rot(angle));
        Coord right = snapCardinal(Coord2d.of(0, 1).rot(angle));
        int i0 = ((fwd.x < 0 || fwd.y < 0) ? 1 : 0) + forwardShift;
        int i1 = i0 + lengthTiles - 1;
        int j0 = (right.x > 0 || right.y > 0) ? -Math.floorDiv(widthTiles, 2) : -Math.floorDiv(widthTiles - 1, 2);
        int j1 = j0 + widthTiles - 1;
        int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE, maxx = Integer.MIN_VALUE, maxy = Integer.MIN_VALUE;
        java.util.List<Coord> tiles = new java.util.ArrayList<>();
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) {
                Coord t = origin.add(fwd.mul(i)).add(right.mul(j));
                tiles.add(t);
                minx = Math.min(minx, t.x); miny = Math.min(miny, t.y);
                maxx = Math.max(maxx, t.x); maxy = Math.max(maxy, t.y);
            }
        }
        boolean[][] data = new boolean[maxx - minx + 1][maxy - miny + 1];
        for (Coord t : tiles)
            data[t.x - minx][t.y - miny] = true;
        return new Mask(new Coord(minx, miny), new Coord(maxx + 1, maxy + 1), data);
    }

    private static Coord snapCardinal(Coord2d v) {
        if (Math.abs(v.x) >= Math.abs(v.y))
            return new Coord(v.x >= 0 ? 1 : -1, 0);
        return new Coord(0, v.y >= 0 ? 1 : -1);
    }

    Gob gob;
    private volatile Mask mask;
    boolean rect = false;
    int widthTiles, lengthTiles;

    public synchronized Mask getMask()
    {
        if(isDynamic)
        {
            calcData();
        }
        return mask;
    }

    synchronized void calcData()
    {
        if (rect) {
            /* A built tunnel's gob sits a tile behind the ground it
             * holds up; a placement preview already starts on it. */
            mask = computeRect(gob.rc, gob.a, widthTiles, lengthTiles, (gob.id == -1) ? 0 : 1);
            return;
        }
        if(isTree)
        {
            TreeScale ts = gob.getattr(TreeScale.class);
            if(ts!=null)
            {
                this.r = (int) Math.round(baser * (ts.scale - 0.1) / 0.9);
            }
            else
            {
                this.r = baser;
                isTree = false;
                isDynamic = false;
            }
        }
        Coord a = gob.rc.sub(r, 0).div(MCache.tilesz).round();
        Coord b = gob.rc.sub(0, r).div(MCache.tilesz).round();
        Coord c = gob.rc.add(r, 0).div(MCache.tilesz).round();
        Coord d = gob.rc.add(0, r).div(MCache.tilesz).round();
        Coord begin = new Coord(a.x,b.y);
        Coord end = new Coord(c.x,d.y);

        boolean[][] data = new boolean[c.x-a.x+1][d.y-b.y+1];
        for(int i = 0; i<=c.x-a.x; i++)
        {
            for (int j = 0; j <= d.y-b.y; j++)
            {
                data[i][j] = (gob.rc.dist(new Coord2d(i+begin.x,j+begin.y).mul(MCache.tilesz).add(MCache.tilehsz))<r);
            }
        }
        mask = new Mask(begin, end, data);
    }

    public NMiningSupport(Owner owner, int r)
    {
        super(owner, null);
        this.gob = (Gob)owner;
        this.r = r;
        calcData();
        isDynamic = gob.id == -1;
        TreeScale ts = gob.getattr(TreeScale.class);
        if(ts!=null)
        {
            this.baser = r;
            isDynamic = true;
            isTree = true;
        }
    }
    public NMiningSupport(Owner owner, int widthTiles, int lengthTiles)
    {
        super(owner, null);
        this.gob = (Gob)owner;
        this.rect = true;
        this.widthTiles = widthTiles;
        this.lengthTiles = lengthTiles;
        calcData();
        isDynamic = gob.id == -1;
    }

    int r;
    int baser;
    boolean isTree = false;
    boolean isDynamic = false;
    NMiningOverlay mo = null;

    @Override
    public boolean tick(double dt)
    {
        if(mo == null)
        {
            mo = NMapView.getMiningOl();
            if(mo!=null)
                if(gob.id!=-1)
                {
                    mo.addMineSupp(gob.id);
                }
                else
                {
                    mo.addDummySupp(gob);
                }
        }
        return false;
    }

    @Override
    public void removed(RenderTree.Slot slot)
    {
        super.removed(slot);
        if(gob.id == -1)
        {
            mo.dummy = null;
        }
    }
}
